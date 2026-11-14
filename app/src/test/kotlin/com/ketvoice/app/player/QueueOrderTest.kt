package com.ketvoice.app.player

import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QueueOrderTest {
    private fun track(id: String) = Track(id, "Title $id", "Artist", "Album")
    private val queue = listOf("a", "b", "c", "d", "e").map(::track)
    private val keys = listOf("k0", "k1", "k2", "k3", "k4")

    @Test fun shuffleMovesTheCurrentTitleFirstAndKeepsEveryEntry() {
        val result = QueueOrder.shuffle(queue, keys, 2, Random(7))
        assertEquals(0, result.index)
        assertEquals("c", result.queue.first().id)
        assertEquals("k2", result.entryIds.first())
        assertEquals(keys.toSet(), result.entryIds.toSet())
        assertEquals(keys, result.linearOrder)
        // Each entry id still names the same title.
        result.entryIds.forEachIndexed { at, key -> assertEquals(queue[keys.indexOf(key)].id, result.queue[at].id) }
        assertNotEquals(keys, result.entryIds)
    }

    @Test fun restoreReturnsTheOriginalOrderAroundTheCurrentTitle() {
        val shuffled = QueueOrder.shuffle(queue, keys, 2, Random(3))
        // The listener moved on to the third shuffled entry before turning shuffle off.
        val playing = shuffled.entryIds[2]
        val restored = QueueOrder.restore(shuffled.queue, shuffled.entryIds, 2, shuffled.linearOrder)
        assertEquals(keys, restored.entryIds)
        assertEquals(queue.map { it.id }, restored.queue.map { it.id })
        assertEquals(keys.indexOf(playing), restored.index)
        assertTrue(restored.linearOrder.isEmpty())
    }

    @Test fun restoreKeepsEntriesItDoesNotKnowAfterTheKnownOnes() {
        val restored = QueueOrder.restore(listOf(track("new"), track("b"), track("a")), listOf("n", "k1", "k0"), 0, listOf("k0", "k1"))
        assertEquals(listOf("k0", "k1", "n"), restored.entryIds)
        assertEquals(listOf("a", "b", "new"), restored.queue.map { it.id })
        assertEquals(2, restored.index)
    }

    @Test fun restoreWithoutARememberedOrderChangesNothing() {
        val result = QueueOrder.restore(queue, keys, 3, emptyList())
        assertEquals(keys, result.entryIds)
        assertEquals(3, result.index)
    }

    @Test fun aSingleTitleOrAnInvalidIndexIsLeftAlone() {
        assertEquals(listOf("k0"), QueueOrder.shuffle(queue.take(1), keys.take(1), 0, Random(1)).entryIds)
        assertEquals(keys, QueueOrder.shuffle(queue, keys, 9, Random(1)).entryIds)
    }

    @Test fun missingEntryIdsAreRebuiltInsteadOfCrashing() {
        val result = QueueOrder.shuffle(queue, emptyList(), 1, Random(1))
        assertEquals(5, result.entryIds.distinct().size)
        assertEquals("b", result.queue.first().id)
    }

    @Test fun rememberedOrderFollowsAddedAndRemovedEntries() {
        assertEquals(emptyList<String>(), QueueOrder.syncLinear(emptyList(), keys))
        assertEquals(listOf("k0", "k2"), QueueOrder.syncLinear(listOf("k0", "k1", "k2"), listOf("k2", "k0")))
        assertEquals(listOf("k0", "k1", "n"), QueueOrder.syncLinear(listOf("k0", "k1"), listOf("k1", "n", "k0")))
        // "Play next" keeps the new title right after the current one once shuffle is off.
        assertEquals(listOf("k0", "n", "k1"), QueueOrder.syncLinear(listOf("k0", "k1"), listOf("k1", "n", "k0"), afterKey = "k0"))
        assertEquals(listOf("k0", "k1", "n"), QueueOrder.syncLinear(listOf("k0", "k1"), listOf("k1", "n", "k0"), afterKey = "gone"))
    }

    @Test fun shuffledPlayRemembersTheGivenOrderEvenWithDuplicates() {
        val original = listOf(track("a"), track("b"), track("a"))
        val shuffled = listOf(original[1], original[0], original[2])
        val linear = QueueOrder.linearOf(original, shuffled, listOf("x", "y", "z"))
        assertEquals(listOf("y", "x", "z"), linear)
    }

    @Test fun historyReturnsToTheTitleTheListenerCameFrom() {
        val history = PlayHistory(capacity = 3)
        listOf("k0", "k3", "k3", "k1").forEach(history::push)
        assertEquals(3, history.size)
        assertEquals(1, history.popInto(keys, currentIndex = 4))
        assertEquals(3, history.popInto(keys, currentIndex = 1))
        // An entry removed from the queue, or the current one, is skipped.
        history.push("gone"); history.push("k2")
        assertEquals(0, history.popInto(keys, currentIndex = 2))
        assertNull(history.popInto(keys, currentIndex = 0))
    }

    @Test fun historyIsBounded() {
        val history = PlayHistory(capacity = 2)
        listOf("k0", "k1", "k2").forEach(history::push)
        assertEquals(2, history.popInto(keys, 4))
        assertEquals(1, history.popInto(keys, 4))
        assertNull(history.popInto(keys, 4))
    }

    @Test fun nextFollowsTheQueueThenRepeatThenAutoplay() {
        fun next(size: Int, index: Int, one: Boolean = false, all: Boolean = false, auto: Boolean = false, ended: Boolean = true) =
            QueueSteps.next(size, index, one, all, auto, ended)
        assertEquals(QueueStep.Jump(2), next(5, 1))
        assertEquals(QueueStep.Replay, next(5, 1, one = true))
        // A manual skip leaves a repeated title.
        assertEquals(QueueStep.Jump(2), next(5, 1, one = true, ended = false))
        assertEquals(QueueStep.Jump(0), next(5, 4, all = true, auto = true))
        assertEquals(QueueStep.Replay, next(1, 0, all = true))
        assertEquals(QueueStep.Extend, next(5, 4, auto = true))
        assertEquals(QueueStep.Stop, next(5, 4))
        assertEquals(QueueStep.Stop, next(0, -1, all = true, auto = true))
    }

    @Test fun previousOnlyWrapsWhenTheQueueRepeats() {
        assertEquals(1, QueueSteps.previous(5, 2, repeatAll = false))
        assertNull(QueueSteps.previous(5, 0, repeatAll = false))
        assertEquals(4, QueueSteps.previous(5, 0, repeatAll = true))
        assertNull(QueueSteps.previous(1, 0, repeatAll = true))
    }
}
