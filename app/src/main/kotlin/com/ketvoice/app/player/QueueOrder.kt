package com.ketvoice.app.player

import com.ketvoice.app.data.Track

/**
 * Pure ordering rules of the play queue. Shuffle is visible: it reorders the queue
 * itself, so what the listener sees is exactly what plays next, and turning it off
 * puts every entry back where it was.
 */
internal object QueueOrder {
    data class Result(val queue: List<Track>, val entryIds: List<String>, val index: Int,
                      val linearOrder: List<String>)

    private fun keysFor(queue: List<Track>, entryIds: List<String>): List<String> =
        if (entryIds.size == queue.size && entryIds.distinct().size == entryIds.size) entryIds
        else QueueEntryIds.reconcile(emptyList(), emptyList(), queue.map { it.id })

    /**
     * The current entry moves first and every other entry follows in random order.
     * [Result.linearOrder] remembers the order before the shuffle.
     */
    fun shuffle(queue: List<Track>, entryIds: List<String>, index: Int,
                random: kotlin.random.Random = kotlin.random.Random.Default): Result {
        val keys = keysFor(queue, entryIds)
        if (queue.size < 2 || index !in queue.indices) return Result(queue, keys, index, keys)
        val order = listOf(index) + queue.indices.filter { it != index }.shuffled(random)
        return Result(order.map { queue[it] }, order.map { keys[it] }, 0, keys)
    }

    /**
     * Back to [linearOrder]. The current entry keeps playing from its original place.
     * Entries [linearOrder] does not know stay after the known ones, in their order.
     */
    fun restore(queue: List<Track>, entryIds: List<String>, index: Int, linearOrder: List<String>): Result {
        val keys = keysFor(queue, entryIds)
        if (linearOrder.isEmpty() || queue.isEmpty()) return Result(queue, keys, index, emptyList())
        val rank = HashMap<String, Int>(linearOrder.size)
        linearOrder.forEachIndexed { position, key -> rank.putIfAbsent(key, position) }
        val order = queue.indices.sortedBy { rank[keys[it]] ?: Int.MAX_VALUE }
        val current = keys.getOrNull(index)
        val restored = order.map { keys[it] }
        return Result(order.map { queue[it] }, restored,
            restored.indexOf(current).takeIf { it >= 0 } ?: index.coerceIn(queue.indices), emptyList())
    }

    /**
     * Keeps a remembered order consistent after the queue gained or lost entries:
     * removed entries leave it, new ones join after [afterKey] or at the end.
     */
    fun syncLinear(linearOrder: List<String>, entryIds: List<String>, afterKey: String? = null): List<String> {
        if (linearOrder.isEmpty()) return emptyList()
        val present = entryIds.toHashSet()
        val kept = linearOrder.filter { it in present }
        val known = kept.toHashSet()
        val added = entryIds.filter { it !in known }
        if (added.isEmpty()) return kept
        val at = afterKey?.let { kept.indexOf(it) }?.takeIf { it >= 0 } ?: return kept + added
        return kept.subList(0, at + 1) + added + kept.subList(at + 1, kept.size)
    }

    /** Entry ids of [shuffled] listed in the order of [original] (same tracks, other order). */
    fun linearOf(original: List<Track>, shuffled: List<Track>, entryIds: List<String>): List<String> {
        val pools = HashMap<String, ArrayDeque<String>>()
        shuffled.forEachIndexed { i, track -> entryIds.getOrNull(i)?.let { pools.getOrPut(track.id) { ArrayDeque() }.addLast(it) } }
        return original.mapNotNull { pools[it.id]?.removeFirstOrNull() }
    }
}

/** Entries the listener came from, most recent last: what "previous" really goes back to. */
internal class PlayHistory(private val capacity: Int = 200) {
    private val entries = ArrayDeque<String>()

    fun push(entryId: String) {
        if (entries.lastOrNull() == entryId) return
        entries.addLast(entryId)
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Index in [entryIds] of the most recent entry that still exists and is not [currentIndex]. */
    fun popInto(entryIds: List<String>, currentIndex: Int): Int? {
        while (entries.isNotEmpty()) {
            val at = entryIds.indexOf(entries.removeLast())
            if (at >= 0 && at != currentIndex) return at
        }
        return null
    }

    fun clear() = entries.clear()
    val size: Int get() = entries.size
}

/** Where "next" and the end of a track lead, without touching the player. */
internal sealed interface QueueStep {
    data class Jump(val index: Int) : QueueStep
    data object Replay : QueueStep
    data object Extend : QueueStep
    data object Stop : QueueStep
}

internal object QueueSteps {
    /** [ended] is true when the track finished by itself, false for a manual skip. */
    fun next(size: Int, index: Int, repeatOne: Boolean, repeatAll: Boolean, autoplay: Boolean, ended: Boolean): QueueStep = when {
        size <= 0 -> QueueStep.Stop
        ended && repeatOne -> QueueStep.Replay
        index < size - 1 -> QueueStep.Jump(index + 1)
        repeatAll -> if (size > 1) QueueStep.Jump(0) else QueueStep.Replay
        autoplay -> QueueStep.Extend
        else -> QueueStep.Stop
    }

    /** Fallback when nothing is left in the history. Null restarts the current track. */
    fun previous(size: Int, index: Int, repeatAll: Boolean): Int? = when {
        size <= 1 -> null
        index > 0 -> index - 1
        repeatAll -> size - 1
        else -> null
    }
}
