@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app's queue shown as the session's timeline (8.4): what Android Auto's queue screen lists and jumps in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueTimelineTest {
    /** The single-item engine: whatever title it plays, and what it was asked to do. */
    private class Engine : SimpleBasePlayer(Looper.getMainLooper()) {
        var item: MediaItem? = MediaItem.Builder().setMediaId("c").setUri("file:///c.mp3").build()
        val seeks = mutableListOf<Long>()
        var shuffle = false
        var repeat = MediaPlayer.REPEAT_MODE_OFF

        fun change(next: MediaItem?) { item = next; invalidateState() }

        override fun getState(): State {
            val builder = State.Builder().setAvailableCommands(MediaPlayer.Commands.Builder().addAllCommands().build())
                .setShuffleModeEnabled(shuffle).setRepeatMode(repeat)
            val current = item ?: return builder.build()
            return builder.setPlaylist(listOf(MediaItemData.Builder(current.mediaId).setMediaItem(current).setDurationUs(200_000_000L).build()))
                .setCurrentMediaItemIndex(0).build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks += positionMs
            return Futures.immediateVoidFuture()
        }

        override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
            shuffle = shuffleModeEnabled; return Futures.immediateVoidFuture()
        }

        override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
            repeat = repeatMode; return Futures.immediateVoidFuture()
        }
    }

    private class Host(var state: PlayerUiState) : QueueHost {
        var outside = false
        val jumps = mutableListOf<Int>()
        val shuffles = mutableListOf<Boolean>()
        val repeats = mutableListOf<Int>()
        override fun state() = state
        override fun artwork(track: Track): Uri? = track.coverUrl?.let { Uri.parse("content://covers/${track.id}") }
        override fun queueTitle() = "File d'attente"
        override fun jumpTo(index: Int) { jumps += index }
        override fun setShuffle(on: Boolean) { shuffles += on }
        override fun setRepeat(mode: Int) { repeats += mode }
        override fun fromOutside() = outside
    }

    private fun track(id: String) = Track(id, "Title $id", "Artist $id", "Album", durationMs = 180_000, coverUrl = "https://img/$id.jpg")
    private fun queue(vararg ids: String, index: Int) =
        PlayerUiState(queue = ids.map(::track), queueEntryIds = ids.map { "e$it" }, index = index)

    private val engine = Engine()
    private val host = Host(queue("a", "b", "c", "d", index = 2))
    private val bridge = QueueBridgePlayer(engine, {}, {}, host) { false }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun theWholeQueueIsTheTimelineWithTheEnginesTitleInIt() {
        val timeline = bridge.currentTimeline
        assertEquals(4, timeline.windowCount)
        assertEquals(4, bridge.mediaItemCount)
        assertEquals(2, bridge.currentMediaItemIndex)
        assertEquals(2, bridge.currentPeriodIndex)
        assertEquals(listOf("a", "b", "c", "d"), (0 until 4).map { bridge.getMediaItemAt(it).mediaId })
        assertEquals("Title a", bridge.getMediaItemAt(0).mediaMetadata.title.toString())
        assertEquals("content://covers/a", bridge.getMediaItemAt(0).mediaMetadata.artworkUri.toString())
        // The title playing is the engine's own item, with the engine's timing.
        assertSame(engine.currentMediaItem, bridge.getMediaItemAt(2))
        assertEquals(200_000_000L, timeline.getWindow(2, Timeline.Window()).durationUs)
        assertEquals(180_000_000L, timeline.getWindow(0, Timeline.Window()).durationUs)
        assertEquals(3, bridge.nextMediaItemIndex)
        assertEquals(1, bridge.previousMediaItemIndex)
        assertTrue(bridge.isCommandAvailable(MediaPlayer.COMMAND_SEEK_TO_MEDIA_ITEM))
        assertEquals("File d'attente", bridge.playlistMetadata.title.toString())
        // The app's own controller sees only the title playing (Media3 cuts the timeline for it): the engine's item.
        val single = timeline.copyWithSingleWindow(bridge.currentMediaItemIndex)
        assertEquals(1, single.windowCount)
        assertSame(engine.currentMediaItem, single.getWindow(0, Timeline.Window()).mediaItem)
    }

    @Test fun aTitleChosenInTheCarsQueueJumpsThere() {
        bridge.seekToDefaultPosition(0)
        bridge.seekTo(3, 0L)
        assertEquals(listOf(0, 3), host.jumps)
        assertTrue(engine.seeks.isEmpty())
        // The title playing is only sought.
        bridge.seekTo(2, 42_000L)
        bridge.seekToDefaultPosition(2)
        assertEquals(listOf(0, 3), host.jumps)
        assertEquals(listOf(42_000L, C.TIME_UNSET), engine.seeks)
        bridge.seekToDefaultPosition(9)
        assertEquals(listOf(0, 3), host.jumps)
    }

    @Test fun aLongQueueIsShownAroundTheTitlePlaying() {
        val ids = (0 until 1_000).map { "t$it" }
        host.state = PlayerUiState(queue = ids.map(::track), queueEntryIds = ids.map { "e$it" }, index = 300)
        engine.change(MediaItem.Builder().setMediaId("t300").setUri("file:///t300.mp3").build())
        assertEquals(QueueWindows.MAX_WINDOWS, bridge.mediaItemCount)
        assertEquals(QueueWindows.BEHIND, bridge.currentMediaItemIndex)
        assertEquals("t280", bridge.getMediaItemAt(0).mediaId)
        bridge.seekToDefaultPosition(0)
        assertEquals(listOf(280), host.jumps)
        // At the end of the queue, the last titles.
        host.state = host.state.copy(index = 999)
        engine.change(MediaItem.Builder().setMediaId("t999").setUri("file:///t999.mp3").build())
        assertEquals(QueueWindows.MAX_WINDOWS - 1, bridge.currentMediaItemIndex)
        assertEquals(C.INDEX_UNSET, bridge.nextMediaItemIndex)
    }

    @Test fun anEngineTitleOutsideTheQueueKeepsTheEnginesOwnTimeline() {
        engine.change(MediaItem.Builder().setMediaId("elsewhere").setUri("file:///x.mp3").build())
        assertEquals(1, bridge.mediaItemCount)
        assertEquals(0, bridge.currentMediaItemIndex)
        engine.change(null)
        assertEquals(0, bridge.mediaItemCount)
        // The queue moved ahead while the engine still plays the last title: that title stays the one shown.
        host.state = host.state.copy(index = 3)
        engine.change(MediaItem.Builder().setMediaId("c").setUri("file:///c.mp3").build())
        assertEquals(2, bridge.currentMediaItemIndex)
    }

    @Test fun shuffleAndRepeatAreTheAppsAndFromOutsideGoToItsQueue() {
        host.state = host.state.copy(shuffle = true, repeatAll = true)
        assertTrue(bridge.shuffleModeEnabled)
        assertEquals(MediaPlayer.REPEAT_MODE_ALL, bridge.repeatMode)
        host.state = host.state.copy(repeatAll = false, repeatOne = true)
        assertEquals(MediaPlayer.REPEAT_MODE_ONE, bridge.repeatMode)
        // The app's own controller sets the engine's flags, as before.
        bridge.shuffleModeEnabled = false
        bridge.repeatMode = MediaPlayer.REPEAT_MODE_ONE
        assertTrue(host.shuffles.isEmpty() && host.repeats.isEmpty())
        assertEquals(MediaPlayer.REPEAT_MODE_ONE, engine.repeat)
        // A car or an assistant: the app's queue.
        host.outside = true
        bridge.shuffleModeEnabled = true
        bridge.repeatMode = MediaPlayer.REPEAT_MODE_ALL
        assertEquals(listOf(true), host.shuffles)
        assertEquals(listOf(MediaPlayer.REPEAT_MODE_ALL), host.repeats)
        assertEquals(MediaPlayer.REPEAT_MODE_ONE, engine.repeat)
    }

    @Test fun listenersHearTheQueueOnceAndTheEnginesNewsInTheQueuesTerms() {
        val timelines = mutableListOf<Timeline>()
        val repeats = mutableListOf<Int>()
        val listener = object : MediaPlayer.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { timelines += timeline }
            override fun onRepeatModeChanged(repeatMode: Int) { repeats += repeatMode }
        }
        bridge.addListener(listener)
        bridge.queueChanged()
        assertEquals(4, timelines.single().windowCount)
        bridge.queueChanged()
        assertEquals("An unchanged queue is not sent again", 1, timelines.size)
        host.state = host.state.copy(queue = host.state.queue + track("e"), queueEntryIds = host.state.queueEntryIds + "ee")
        bridge.queueChanged()
        assertEquals(5, timelines.last().windowCount)
        // The engine's own timeline change reaches the session as the queue's timeline.
        engine.change(MediaItem.Builder().setMediaId("d").setUri("file:///d.mp3").build())
        idle()
        assertEquals(5, timelines.last().windowCount)
        assertEquals(3, bridge.currentMediaItemIndex)
        // The engine's repeat flag (off while the app repeats its whole queue) is told as the app's mode.
        host.state = host.state.copy(repeatAll = true)
        engine.repeatMode = MediaPlayer.REPEAT_MODE_ONE
        idle()
        assertEquals(MediaPlayer.REPEAT_MODE_ALL, repeats.last())
    }

    @Test fun withoutAHostTheBridgeIsTheSingleItemItWas() {
        val plain = QueueBridgePlayer(engine, {}, {}) { false }
        host.state = host.state.copy(repeatAll = true)
        assertEquals(1, plain.mediaItemCount)
        assertEquals(MediaPlayer.REPEAT_MODE_OFF, plain.repeatMode)
    }
}
