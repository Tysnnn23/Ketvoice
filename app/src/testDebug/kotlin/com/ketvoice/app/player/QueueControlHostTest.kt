package com.ketvoice.app.player

import androidx.media3.common.FlagSet
import androidx.media3.common.Player as MediaPlayer
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Queue rules of the player object and the session bridge, on the host: no audio is decoded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueControlHostTest {
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>
    private fun field(name: String) = Player::class.java.getDeclaredField(name).apply { isAccessible = true }
    private lateinit var original: PlayerUiState
    private var oldController: Any? = null

    private fun track(id: String) = Track("fixture:$id", "Title $id", "Artist", "Album")
    private val queue = listOf("a", "b", "c", "d", "e").map(::track)
    private val keys = listOf("k0", "k1", "k2", "k3", "k4")

    @Before fun setup() {
        original = state.value
        oldController = field("controller").get(Player)
        field("controller").set(Player, null)
        state.value = PlayerUiState(queue = queue, queueEntryIds = keys, index = 2)
    }

    @After fun cleanup() {
        state.value = original
        field("controller").set(Player, oldController)
    }

    @Test fun shuffleIsVisibleInTheQueueAndTurningItOffRestoresTheOrder() {
        Player.setShuffle(true)
        val shuffled = state.value
        assertTrue(shuffled.shuffle)
        assertEquals("The current title keeps playing and leads the queue", "fixture:c", shuffled.current?.id)
        assertEquals(0, shuffled.index)
        assertEquals(keys, shuffled.linearOrder)
        assertEquals(keys.toSet(), shuffled.queueEntryIds.toSet())

        // A second request changes nothing: the order on screen is the order that plays.
        Player.setShuffle(true)
        assertEquals(shuffled.queueEntryIds, state.value.queueEntryIds)

        Player.setShuffle(false)
        val restored = state.value
        assertFalse(restored.shuffle)
        assertEquals(keys, restored.queueEntryIds)
        assertEquals(queue.map { it.id }, restored.queue.map { it.id })
        assertEquals(2, restored.index)
        assertTrue(restored.linearOrder.isEmpty())
    }

    @Test fun titlesAddedWhileShuffledKeepASensiblePlaceOnceShuffleIsOff() {
        Player.setShuffle(true)
        val current = state.value.queueEntryIds[state.value.index]
        Player.enqueue(listOf(track("z")))
        val added = state.value.queueEntryIds.last()
        assertEquals(keys + added, state.value.linearOrder)
        Player.removeAt(state.value.queue.indexOfFirst { it.id == "fixture:a" })
        assertFalse("k0" in state.value.linearOrder)
        Player.setShuffle(false)
        assertEquals(listOf("k1", "k2", "k3", "k4", added), state.value.queueEntryIds)
        assertEquals(current, state.value.queueEntryIds[state.value.index])
    }

    @Test fun repeatCyclesThroughQueueThenTitleThenOff() {
        assertFalse(state.value.repeatAll || state.value.repeatOne)
        Player.cycleRepeat()
        assertTrue(state.value.repeatAll); assertFalse(state.value.repeatOne)
        Player.cycleRepeat()
        assertTrue(state.value.repeatOne); assertFalse(state.value.repeatAll)
        Player.cycleRepeat()
        assertFalse(state.value.repeatAll || state.value.repeatOne)
        Player.setRepeatOne(true)
        assertTrue(state.value.repeatOne)
    }

    @Test fun clearingTheQueueKeepsTheCurrentTitleAndCanBeUndone() {
        val before = state.value
        Player.clearUpcoming()
        assertEquals(listOf("fixture:c"), state.value.queue.map { it.id })
        assertEquals(0, state.value.index)
        assertEquals(listOf("k2"), state.value.queueEntryIds)
        assertTrue(Player.restoreQueue(before.queue, before.queueEntryIds, before.index, before.linearOrder))
        assertEquals(keys, state.value.queueEntryIds)
        assertEquals(2, state.value.index)
    }

    @Test fun undoOfAClearedQueueIsRefusedOnceAnotherTitlePlays() {
        val before = state.value
        Player.clearUpcoming()
        state.value = state.value.copy(queue = listOf(track("other")), queueEntryIds = listOf("new"), index = 0)
        assertFalse(Player.restoreQueue(before.queue, before.queueEntryIds, before.index, before.linearOrder))
        assertEquals(listOf("new"), state.value.queueEntryIds)
    }

    @Test fun aRemovedTitleReturnsToItsPlaceWithoutMovingTheCurrentOne() {
        Player.removeAt(4)
        assertEquals(4, state.value.queue.size)
        val key = Player.insertAt(4, track("e"))
        assertNotNull(key)
        assertEquals("fixture:e", state.value.queue[4].id)
        assertEquals(2, state.value.index)
        // Put back before the current title: the current title is still the same entry.
        Player.insertAt(0, track("first"))
        assertEquals(3, state.value.index)
        assertEquals("fixture:c", state.value.current?.id)
    }

    @Test fun aReceivedSessionKeepsItsOrder() {
        Player.adoptModes(shuffle = true, repeatOne = false, autoplay = false)
        assertTrue(state.value.shuffle)
        assertFalse(state.value.autoplay)
        assertEquals(keys, state.value.queueEntryIds)
        assertTrue(state.value.linearOrder.isEmpty())
    }

    // ------------------------------------------------------------ session bridge
    private class Engine {
        val listeners = mutableListOf<MediaPlayer.Listener>()
        var seeks = 0
        val player: MediaPlayer = Proxy.newProxyInstance(MediaPlayer::class.java.classLoader, arrayOf(MediaPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "addListener" -> { listeners += args[0] as MediaPlayer.Listener; null }
                "removeListener" -> { listeners.remove(args[0]); null }
                "getAvailableCommands" -> MediaPlayer.Commands.EMPTY
                "isCommandAvailable", "hasNextMediaItem", "hasPreviousMediaItem" -> false
                "seekToNext", "seekToNextMediaItem", "seekToPrevious", "seekToPreviousMediaItem" -> { seeks++; null }
                "hashCode" -> 1
                "equals" -> proxy === args[0]
                "toString" -> "Engine"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as MediaPlayer
    }

    @Test fun theSessionSeesNextAndPreviousAndHandsThemToTheAppQueue() {
        val engine = Engine()
        var next = 0
        var previous = 0
        val bridge = QueueBridgePlayer(engine.player, { next++ }, { previous++ })
        QueueBridgePlayer.QUEUE_COMMANDS.forEach { command ->
            assertTrue(bridge.availableCommands.contains(command))
            assertTrue(bridge.isCommandAvailable(command))
        }
        assertFalse(bridge.isCommandAvailable(MediaPlayer.COMMAND_SET_SHUFFLE_MODE))
        assertTrue(bridge.hasNextMediaItem() && bridge.hasPreviousMediaItem())
        bridge.seekToNext(); bridge.seekToNextMediaItem()
        bridge.seekToPrevious(); bridge.seekToPreviousMediaItem()
        assertEquals(2, next); assertEquals(2, previous)
        assertEquals("The single-item engine is never asked to change item", 0, engine.seeks)
    }

    @Test fun commandUpdatesFromTheEngineKeepTheQueueButtons() {
        val engine = Engine()
        val bridge = QueueBridgePlayer(engine.player, {}, {})
        val seen = mutableListOf<MediaPlayer.Commands>()
        var playing = false
        var eventsFrom: MediaPlayer? = null
        val listener = object : MediaPlayer.Listener {
            override fun onAvailableCommandsChanged(availableCommands: MediaPlayer.Commands) { seen += availableCommands }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onEvents(player: MediaPlayer, events: MediaPlayer.Events) { eventsFrom = player }
        }
        bridge.addListener(listener)
        val registered = engine.listeners.single()
        registered.onAvailableCommandsChanged(MediaPlayer.Commands.EMPTY)
        assertTrue(seen.single().contains(MediaPlayer.COMMAND_SEEK_TO_NEXT))
        assertTrue(seen.single().contains(MediaPlayer.COMMAND_SEEK_TO_PREVIOUS))
        // Every other callback reaches the session untouched.
        registered.onIsPlayingChanged(true)
        assertTrue(playing)
        registered.onEvents(engine.player, MediaPlayer.Events(FlagSet.Builder().build()))
        assertSame(bridge, eventsFrom)
        bridge.removeListener(listener)
        assertTrue(engine.listeners.isEmpty())
    }

    private class Equal(val id: Int) : MediaPlayer.Listener {
        override fun equals(other: Any?) = other is Equal && other.id == id
        override fun hashCode() = id
    }

    @Test fun aListenerIsRemovedThroughAnEqualWrapperLikeTheSessionDoes() {
        val engine = Engine()
        val bridge = QueueBridgePlayer(engine.player, {}, {})
        bridge.addListener(Equal(1))
        bridge.addListener(Equal(2))
        assertEquals(2, engine.listeners.size)
        bridge.removeListener(Equal(1))
        assertEquals(1, engine.listeners.size)
        bridge.removeListener(Equal(1))
        bridge.removeListener(Equal(2))
        assertTrue(engine.listeners.isEmpty())
    }
}
