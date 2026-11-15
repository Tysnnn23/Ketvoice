package com.ketvoice.app.ui.design

import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.GlassMotion
import com.ketvoice.app.player.QueueOrder
import com.ketvoice.app.ui.ScrollEdge
import com.ketvoice.app.ui.common.LiquidSwitchLens
import com.ketvoice.app.ui.player.MixVisuals
import com.ketvoice.app.ui.player.QueueSelection
import org.junit.Assert.*
import org.junit.Test

class DesignRulesTest {
    private fun track(id: String, artist: String) = Track("t:$id", "Titre $id", artist, "Album")

    @Test fun aShuffleWithFewerRepeatsSpreadsArtistsAndKeepsRecentTitlesForLater() {
        val tracks = listOf(track("a1", "Alpha"), track("a2", "Alpha"), track("a3", "Alpha feat. Beta"), track("b1", "Beta"),
            track("b2", "Beta"), track("c1", "Gamma"), track("c2", "Gamma"), track("d1", "Delta"))
        val recent = setOf("t:c1", "t:d1")
        val spread = QueueOrder.spread(tracks, QueueOrder::artistKey, { it.id in recent })
        assertEquals(tracks.toSet(), spread.toSet())
        // No artist twice in a row here, where the titles allow it.
        for (i in 1 until spread.size) assertNotEquals("$spread", QueueOrder.artistKey(spread[i - 1]), QueueOrder.artistKey(spread[i]))
        // The titles heard recently come after the others.
        val lastFresh = spread.indexOfLast { it.id !in recent }
        assertTrue(spread.indexOfFirst { it.id in recent } > spread.indexOfFirst { it.id !in recent })
        assertTrue("recent ones at the end ($spread)", spread.takeLast(2).count { it.id in recent } >= 1 || lastFresh < spread.size - 2)
        // Only one artist: the order is kept, nothing is lost.
        val alone = listOf(track("x", "Solo"), track("y", "Solo"), track("z", "Solo"))
        assertEquals(alone, QueueOrder.spread(alone, QueueOrder::artistKey, { false }))
        // The title playing counts: the first one after it is by someone else.
        assertNotEquals("alpha", QueueOrder.artistKey(QueueOrder.spread(tracks, QueueOrder::artistKey, { false }, "alpha").first()))
        // The visible shuffle keeps the current title first and loses nothing.
        val keys = tracks.indices.map { "k$it" }
        val result = QueueOrder.shuffle(tracks, keys, 3, kotlin.random.Random(5), recent, spread = true)
        assertEquals(tracks[3], result.queue.first()); assertEquals(0, result.index)
        assertEquals(keys.toSet(), result.entryIds.toSet()); assertEquals(keys, result.linearOrder)
        for (i in 1 until result.queue.size) assertNotEquals(QueueOrder.artistKey(result.queue[i - 1]), QueueOrder.artistKey(result.queue[i]))
    }

    @Test fun aSelectionOfTheQueueActsOnUpcomingEntriesOnly() {
        val ids = listOf("k0", "k1", "k2", "k3", "k4", "k5")
        // The playing entry (k2) and the ones before it are never part of a selection's action.
        assertEquals(listOf("k3", "k5"), QueueSelection.ordered(ids, 2, setOf("k5", "k1", "k3", "k2")))
        assertEquals(listOf(5, 3), QueueSelection.removals(ids, 2, setOf("k3", "k5", "k0")))
        assertTrue(QueueSelection.removals(ids, 2, emptySet()).isEmpty())
    }

    @Test fun theScrollEdgesEaseOutWithoutALine() {
        val top = ScrollEdge.stops(ScrollEdge.TOP_ALPHA, fromEdge = true)
        assertEquals(ScrollEdge.TOP_ALPHA, top.first().second, 1e-6f)
        assertEquals(0f, top.last().second, 1e-6f)
        for (i in 1 until top.size) assertTrue(top[i].second <= top[i - 1].second)
        // It lands flat: the last step is small, so no edge is seen where it ends; the middle carries the change.
        val steps = (1 until top.size).map { top[it - 1].second - top[it].second }
        assertTrue("last step ${steps.last()}", steps.last() < .03f)
        assertTrue(steps.max() > steps.last() * 4f)
        val bottom = ScrollEdge.stops(ScrollEdge.BOTTOM_ALPHA, fromEdge = false)
        assertEquals(0f, bottom.first().second, 1e-6f); assertEquals(ScrollEdge.BOTTOM_ALPHA, bottom.last().second, 1e-6f)
    }

    @Test fun glassSettlesWithOneSmallOvershootAndTheSwitchBecomesALens() {
        // The release used to overshoot by 17 %; now by about 5 %, once.
        assertTrue(GlassMotion.overshoot(GlassMotion.RELEASE_DAMPING) < .06f)
        assertTrue(GlassMotion.overshoot(.52f) > .14f)
        assertEquals(0f, GlassMotion.overshoot(1f), 1e-3f)
        assertEquals(158f, GlassMotion.MATERIALIZE_STIFFNESS, 1f)
        assertEquals(0f, LiquidSwitchLens.size(0f).width.value, 0f)
        assertEquals(10f, LiquidSwitchLens.size(1f).width.value, 1e-4f)
        assertTrue(LiquidSwitchLens.tint(1f) < LiquidSwitchLens.tint(0f))
        assertTrue(LiquidSwitchLens.tint(1f) >= .04f)
    }

    @Test fun theMixStripFadesOneTitleIntoTheOther() {
        val count = 36
        val start = MixVisuals.stripLevel(0, count, outgoing = true); val end = MixVisuals.stripLevel(count - 1, count, outgoing = true)
        assertTrue(start > .3f); assertEquals(0f, end, 1e-4f)
        assertEquals(0f, MixVisuals.stripLevel(0, count, outgoing = false), 1e-4f)
        assertTrue(MixVisuals.stripLevel(count - 1, count, outgoing = false) > .3f)
        for (i in 0 until count) for (outgoing in listOf(true, false)) assertTrue(MixVisuals.stripLevel(i, count, outgoing, 1f) in 0f..1f)
        assertTrue(MixVisuals.stripLevel(10, count, true, 1f) > MixVisuals.stripLevel(10, count, true, 0f))
        assertEquals(0f, MixVisuals.stripLevel(3, 1, true), 0f)
    }

    @Test fun aPageTakesTheColourItsArtworkIsMostlyMadeOf() {
        // A black-and-white photo with a small gold flame: grey-black, not gold.
        val pixels = IntArray(1000) { i -> when {
            i < 40 -> 0xFFD4A020.toInt()
            i < 700 -> 0xFF101010.toInt()
            else -> 0xFFC8C8C8.toInt()
        } }
        val tone = com.ketvoice.app.theme.CoverTone.dominant(pixels)!!
        assertEquals(16f / 255f, tone.red, .01f); assertEquals(tone.red, tone.blue, .01f)
        assertNull(com.ketvoice.app.theme.CoverTone.dominant(IntArray(10) { 0x00FFFFFF }))
    }
}
