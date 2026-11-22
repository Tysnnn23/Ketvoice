package com.ketvoice.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.ketvoice.app.data.Track

/**
 * What [QueueBridgePlayer] needs of the app to show its queue (8.4): the queue itself, and where a car's
 * requests about it go. Everything goes through [Player], never onto the engine.
 */
internal interface QueueHost {
    fun state(): PlayerUiState
    /** The cover of a queued title as a car can read it. */
    fun artwork(track: Track): Uri?
    /** The queue's name on the car's queue screen. */
    fun queueTitle(): String
    fun jumpTo(index: Int)
    fun setShuffle(on: Boolean)
    fun setRepeat(mode: Int)
    /** Whether the session request being carried out comes from outside the app (a car, an assistant, Bluetooth). */
    fun fromOutside(): Boolean
}

/**
 * The app's queue as a Media3 timeline: one window per queued title, the playing one carrying the engine's own
 * item and timing. A long queue is shown around the title playing, [MAX_WINDOWS] titles at most.
 */
@UnstableApi
internal class QueueWindows {
    companion object {
        const val MAX_WINDOWS = 150
        /** Titles kept before the one playing when the queue is longer than [MAX_WINDOWS]. */
        const val BEHIND = 20
        private const val ITEM_CACHE = 1_000
    }

    class View(val timeline: QueueTimeline, val current: Int, private val start: Int, val signature: Any) {
        fun item(window: Int): MediaItem = timeline.getWindow(window, Timeline.Window()).mediaItem
        fun uid(window: Int): Any = timeline.getUidOfPeriod(window)
        fun next(): Int = if (current + 1 < timeline.windowCount) current + 1 else C.INDEX_UNSET
        fun previous(): Int = if (current > 0) current - 1 else C.INDEX_UNSET
        /** The app queue's index of [window], or null outside the timeline. */
        fun queueIndex(window: Int): Int? = (start + window).takeIf { window in 0 until timeline.windowCount }
    }

    private class Key(val queue: List<Track>, val ids: List<String>, val index: Int, val engine: Timeline, val engineIndex: Int,
                      val item: MediaItem) {
        fun same(other: Key?) = other != null && other.queue === queue && other.ids === ids && other.index == index &&
            other.engine === engine && other.engineIndex == engineIndex && other.item === item
    }

    private var key: Key? = null
    private var view: View? = null
    private val items = HashMap<String, MediaItem>()

    /** The queue's timeline, or null when the engine does not play a title of the queue (then its own item is shown). */
    fun view(state: PlayerUiState, engine: Timeline, engineIndex: Int, engineItem: MediaItem?, artwork: (Track) -> Uri?): View? {
        val item = engineItem ?: return null
        if (engine.isEmpty || engineIndex !in 0 until engine.windowCount) return null
        val next = Key(state.queue, state.queueEntryIds, state.index, engine, engineIndex, item)
        if (next.same(key)) return view
        key = next
        view = build(state, engine, engineIndex, item, artwork)
        return view
    }

    /** Where the title the engine plays sits in the queue: the queue's own index, else its nearest copy. */
    internal fun playingIndex(state: PlayerUiState, mediaId: String): Int? {
        val queue = state.queue
        if (state.index in queue.indices && queue[state.index].id == mediaId) return state.index
        return queue.indices.filter { queue[it].id == mediaId }.minByOrNull { kotlin.math.abs(it - state.index) }
    }

    private fun build(state: PlayerUiState, engine: Timeline, engineIndex: Int, item: MediaItem, artwork: (Track) -> Uri?): View? {
        val playing = playingIndex(state, item.mediaId) ?: return null
        val size = state.queue.size
        val count = minOf(size, MAX_WINDOWS)
        val start = (playing - BEHIND).coerceIn(0, size - count)
        val uids = ArrayList<Any>(count)
        val windows = ArrayList<MediaItem>(count)
        val durations = LongArray(count)
        if (items.size > ITEM_CACHE) items.clear()
        for (window in 0 until count) {
            val index = start + window
            val track = state.queue[index]
            val uid = (state.queueEntryIds.getOrNull(index) ?: "q$index") + "|" + track.id
            uids += uid
            windows += if (index == playing) item else items.getOrPut(uid) { itemFor(track, artwork) }
            durations[window] = if (track.durationMs > 0) track.durationMs * 1000 else C.TIME_UNSET
        }
        val live = engine.getWindow(engineIndex, Timeline.Window())
        val period = engine.getPeriod(live.firstPeriodIndex, Timeline.Period())
        val timeline = QueueTimeline(uids, windows, durations, playing - start, live, period.durationUs, period.positionInWindowUs)
        return View(timeline, playing - start, start, listOf(start, playing, uids, item.mediaId))
    }

    private fun itemFor(track: Track, artwork: (Track) -> Uri?): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { null })
            .setArtworkUri(runCatching { artwork(track) }.getOrNull())
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build())
        .build()
}

/** One window and one period per queued title; the window [current] copies the engine's timing. */
@UnstableApi
internal class QueueTimeline(
    private val uids: List<Any>,
    private val items: List<MediaItem>,
    private val durationsUs: LongArray,
    private val current: Int,
    private val live: Timeline.Window,
    private val livePeriodUs: Long,
    private val livePeriodOffsetUs: Long
) : Timeline() {
    override fun getWindowCount(): Int = items.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        if (windowIndex == current) {
            window.set(uids[windowIndex], items[windowIndex], null, live.presentationStartTimeMs, live.windowStartTimeMs,
                live.elapsedRealtimeEpochOffsetMs, live.isSeekable, live.isDynamic, live.liveConfiguration, live.defaultPositionUs,
                live.durationUs, windowIndex, windowIndex, live.positionInFirstPeriodUs)
            window.isPlaceholder = live.isPlaceholder
            return window
        }
        return window.set(uids[windowIndex], items[windowIndex], null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
            true, false, null, 0L, durationsUs[windowIndex], windowIndex, windowIndex, 0L)
    }

    override fun getPeriodCount(): Int = items.size

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val uid = if (setIds) uids[periodIndex] else null
        if (periodIndex == current) return period.set(uid, uid, periodIndex, livePeriodUs, livePeriodOffsetUs)
        return period.set(uid, uid, periodIndex, durationsUs[periodIndex], 0L)
    }

    override fun getIndexOfPeriod(uid: Any): Int = uids.indexOf(uid)

    override fun getUidOfPeriod(periodIndex: Int): Any = uids[periodIndex]
}
