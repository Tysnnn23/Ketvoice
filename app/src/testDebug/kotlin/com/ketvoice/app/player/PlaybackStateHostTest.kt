package com.ketvoice.app.player

import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.PlaybackException
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises controller listener callbacks on the host, not actual audio decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackStateHostTest {
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>
    private val type = Player::class.java
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val listener get() = field("listener").get(Player) as MediaPlayer.Listener
    private lateinit var original: PlayerUiState
    private var oldResolving = false
    private var oldDesired = false
    private var oldHistory = 0
    private var oldController: Any? = null
    private var oldAttempts = 0
    private var oldStream: Any? = null
    private var oldSource: Any? = null
    private var oldPendingPlay: Any? = null
    private var oldRestored = false
    private var oldConnected = false
    private var oldPosition = 0L

    @Before fun setup() {
        original = state.value
        oldResolving = field("resolving").getBoolean(Player)
        oldDesired = field("desiredPlay").getBoolean(Player)
        oldHistory = field("historyGeneration").getInt(Player)
        oldController = field("controller").get(Player)
        oldAttempts = field("fallbackAttempts").getInt(Player)
        oldStream = field("activeStream").get(Player)
        oldSource = field("activeSourceId").get(Player)
        oldPendingPlay = field("pendingPlay").get(Player)
        oldRestored = field("restoredSessionPending").getBoolean(Player)
        oldConnected = field("connected").getBoolean(Player)
        oldPosition = Player.positionMs.value
        field("controller").set(Player, null)
        state.value = PlayerUiState(playWhenReady = true, buffering = true)
        field("desiredPlay").setBoolean(Player, true)
        field("resolving").setBoolean(Player, true)
    }

    @After fun cleanup() {
        com.ketvoice.app.data.ActiveListenerTracker.onPlaybackStateChanged(null, false)
        state.value = original
        field("resolving").setBoolean(Player, oldResolving)
        field("desiredPlay").setBoolean(Player, oldDesired)
        field("historyGeneration").setInt(Player, oldHistory)
        field("controller").set(Player, oldController)
        field("fallbackAttempts").setInt(Player, oldAttempts)
        field("activeStream").set(Player, oldStream)
        field("activeSourceId").set(Player, oldSource)
        field("pendingPlay").set(Player, oldPendingPlay)
        field("restoredSessionPending").setBoolean(Player, oldRestored)
        field("connected").setBoolean(Player, oldConnected)
        (Player.positionMs as MutableStateFlow<Long>).value = oldPosition
    }

    @Test fun playPauseDuringResolutionOnlyChangesIntentWithoutRestartingResolution() {
        val generation = field("generation").getInt(Player)
        Player.toggle()
        assertFalse(state.value.playWhenReady)
        listener.onPlayWhenReadyChanged(false, MediaPlayer.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        Player.toggle()
        assertTrue(state.value.playWhenReady)
        assertTrue(field("desiredPlay").getBoolean(Player))
        assertEquals(generation, field("generation").getInt(Player))
    }

    @Test fun bufferingReadyPlayingAndPauseUpdateTheVisibleState() {
        field("resolving").setBoolean(Player, false)
        listener.onPlaybackStateChanged(MediaPlayer.STATE_BUFFERING)
        assertTrue(state.value.buffering)
        listener.onPlaybackStateChanged(MediaPlayer.STATE_READY)
        assertFalse(state.value.buffering)
        listener.onIsPlayingChanged(true)
        assertTrue(state.value.isPlaying)
        Player.pause()
        listener.onIsPlayingChanged(false)
        assertFalse(state.value.isPlaying)
        assertFalse(state.value.playWhenReady)
        listener.onPlayWhenReadyChanged(true, MediaPlayer.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener.onIsPlayingChanged(true)
        assertTrue(state.value.playWhenReady)
        assertTrue(state.value.isPlaying)
    }

    @Test fun exhaustedPlaybackErrorStopsBufferingAndIsVisible() {
        state.value = PlayerUiState(queue = listOf(Track("fixture", "Title", "Artist", "Album")), index = 0,
            playWhenReady = true, buffering = true)
        field("activeStream").set(Player, null)
        field("activeSourceId").set(Player, null)
        field("fallbackAttempts").setInt(Player, Int.MAX_VALUE - 1)
        listener.onPlayerError(PlaybackException("fixture", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertTrue(state.value.failed)
        assertFalse(state.value.buffering)
        assertFalse(state.value.playWhenReady)
        assertEquals("Erreur de lecture · réessayez", state.value.quality)
        listener.onPlayWhenReadyChanged(true, MediaPlayer.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertFalse("A late controller callback must not hide the terminal error", state.value.playWhenReady)
    }

    @Test fun restoredQueueWaitsForPlayAndResolvesAtTheSavedPosition() {
        val track = Track("fixture", "Title", "Artist", "Album")
        state.value = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("entry"), index = 0,
            positionMs = 42_000, playWhenReady = false)
        (Player.positionMs as MutableStateFlow<Long>).value = 42_000
        field("resolving").setBoolean(Player, false)
        field("restoredSessionPending").setBoolean(Player, true)
        field("connected").setBoolean(Player, true)
        field("pendingPlay").set(Player, null)
        assertFalse(state.value.playWhenReady)
        Player.toggle()
        val pending = requireNotNull(field("pendingPlay").get(Player))
        val position = pending.javaClass.getDeclaredField("position").apply { isAccessible = true }.getLong(pending)
        val playing = pending.javaClass.getDeclaredField("playing").apply { isAccessible = true }.getBoolean(pending)
        assertEquals(42_000L, position)
        assertTrue(playing)
        assertFalse(field("restoredSessionPending").getBoolean(Player))
    }
}
