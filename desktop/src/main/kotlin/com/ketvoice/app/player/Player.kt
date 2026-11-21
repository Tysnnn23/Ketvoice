package com.ketvoice.app.player

import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** The desktop queue as the shared Ketmix code reads it (the Android `PlayerUiState` subset). */
data class PlayerUiState(
    val queue: List<Track> = emptyList(),
    val queueEntryIds: List<String> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatOne: Boolean = false,
    val repeatAll: Boolean = false,
    val playWhenReady: Boolean = false,
) {
    val current: Track? get() = queue.getOrNull(index)
}

/**
 * Ketvoice for Windows' player facade: the queue and its order, with the same rules as the
 * Android `Player` (entry ids per queue slot, « Lire ensuite » pins, Ketmix's look-ahead).
 * Phase 0 holds the queue only; the audio engine plugs in behind it in Phase 1.
 */
object Player {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    internal class NextEntry(val index: Int, val track: Track, val entryId: String)

    private val pinnedEntries = HashSet<String>()

    /** Replaces the queue and starts at [index]. */
    fun setQueue(tracks: List<Track>, index: Int = 0) {
        synchronized(pinnedEntries) { pinnedEntries.clear() }
        _state.value = _state.value.copy(
            queue = tracks,
            queueEntryIds = tracks.map { UUID.randomUUID().toString() },
            index = if (tracks.isEmpty()) -1 else index.coerceIn(0, tracks.lastIndex),
            positionMs = 0L,
        )
    }

    fun setShuffle(on: Boolean) { _state.value = _state.value.copy(shuffle = on) }
    fun setRepeat(one: Boolean, all: Boolean) { _state.value = _state.value.copy(repeatOne = one, repeatAll = all && !one) }

    /** Puts [track] right after the current title; Ketmix's smart queue never moves a title in front of it. */
    fun playNext(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) { setQueue(listOf(track)); return }
        val at = (s.index + 1).coerceIn(0, s.queue.size)
        val entry = UUID.randomUUID().toString()
        synchronized(pinnedEntries) { pinnedEntries += entry }
        _state.value = s.copy(
            queue = s.queue.toMutableList().apply { add(at, track) },
            queueEntryIds = s.queueEntryIds.toMutableList().apply { add(at.coerceAtMost(size), entry) },
        )
    }

    /** The title after the current one as Ketmix sees it: on repeat-one, the title itself when loops are on. */
    internal fun automixNext(): NextEntry? {
        val s = _state.value
        if (s.repeatOne) {
            if (!AppSettings.ketmixLoops.value) return null
            val current = s.current ?: return null
            return NextEntry(s.index, current, s.queueEntryIds.getOrNull(s.index) ?: return null)
        }
        val step = QueueSteps.next(s.queue.size, s.index, s.repeatOne, s.repeatAll, autoplay = false, ended = true)
        val target = (step as? QueueStep.Jump)?.index ?: return null
        return NextEntry(target, s.queue[target], s.queueEntryIds.getOrNull(target) ?: return null)
    }

    internal fun automixUpcoming(count: Int): List<NextEntry> {
        val s = _state.value
        if (s.repeatOne) return listOfNotNull(automixNext())
        val out = ArrayList<NextEntry>(count)
        var index = s.index
        while (out.size < count) {
            val step = QueueSteps.next(s.queue.size, index, false, s.repeatAll, autoplay = false, ended = true)
            val target = (step as? QueueStep.Jump)?.index ?: break
            if (target == s.index || out.any { it.index == target }) break
            out += NextEntry(target, s.queue[target], s.queueEntryIds.getOrNull(target) ?: break)
            index = target
        }
        return out
    }

    /** The listener asked for this entry to play next (see [playNext]). */
    internal fun pinnedNext(entryId: String): Boolean = synchronized(pinnedEntries) { entryId in pinnedEntries }

    /** A publication was edited: the queue shows its new title, cover and audio. */
    fun refreshPublication(track: Track) {
        if (com.ketvoice.app.data.ImportedSong.id(track) == null) return
        _state.value = _state.value.let { s -> s.copy(queue = s.queue.map { if (it.id == track.id) track else it }) }
    }

    fun moveQueue(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices || from == to) return
        val q = s.queue.toMutableList()
        q.add(to, q.removeAt(from))
        val keys = s.queueEntryIds.toMutableList()
        if (from in keys.indices && to in keys.indices) keys.add(to, keys.removeAt(from))
        val newIndex = when {
            from == s.index -> to
            from < s.index && to >= s.index -> s.index - 1
            from > s.index && to <= s.index -> s.index + 1
            else -> s.index
        }
        _state.value = s.copy(queue = q, queueEntryIds = keys, index = newIndex)
    }
}
