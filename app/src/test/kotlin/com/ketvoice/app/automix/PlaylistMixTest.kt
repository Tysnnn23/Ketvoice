package com.ketvoice.app.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Mixer cette playlist »: a gentle reorder for tempo and key, pinned titles kept, a bounded preparation. */
class PlaylistMixTest {
    private fun measured(bpm: Float, key: Int = 0) = SegmentAnalysis(startMs = 0, lengthMs = 40_000, bpm = bpm, confidence = .9f, key = key)

    @Test fun theFirstTitleStaysAndTheNextFollowTheTempo() {
        // 120 → (90, 121, 95, 122): the near tempos come first, then 95 (closer to 122 than 90 is), then 90.
        val intros = listOf(measured(120f), measured(90f), measured(121f), measured(95f), measured(122f))
        val order = PlaylistMix.order(intros, List(5) { null }, pinned = setOf(0))
        assertEquals(0, order.first())
        assertEquals(listOf(0, 2, 4, 3, 1), order)
        assertEquals(order.sorted(), (0 until 5).toList())
    }

    @Test fun aTitleMovesAFewPlacesAtMost() {
        // The only match for 128 is the last of ten: out of the window, it is not pulled to the front.
        val intros = listOf(measured(128f)) + List(8) { measured(80f + it * 3) } + measured(128f)
        val order = PlaylistMix.order(intros, List(10) { null }, pinned = setOf(0))
        assertTrue(order.indexOf(9) >= 9 - PlaylistMix.WINDOW)
        order.forEachIndexed { place, title -> assertTrue("$title moved from far behind", title - place <= PlaylistMix.WINDOW) }
    }

    @Test fun pinnedTitlesKeepTheirPlaceAndUnmeasuredOnesTheirOrder() {
        val intros = listOf(measured(120f), null, measured(70f), null, measured(121f), null)
        val order = PlaylistMix.order(intros, List(6) { null }, pinned = setOf(0, 2))
        assertEquals(0, order[0])
        assertEquals(2, order[2])
        val unmeasured = order.filter { intros[it] == null }
        assertEquals(listOf(1, 3, 5), unmeasured)
        assertEquals((0 until 6).toList(), order.sorted())
    }

    @Test fun aClearlyBetterKeyIsTakenAndACloseCallIsNot() {
        // Same tempo: the clashing key gives way to the same key.
        val clash = PlaylistMix.order(listOf(measured(120f, 0), measured(120f, 6), measured(120f, 0)), List(3) { null }, setOf(0))
        assertEquals(listOf(0, 2, 1), clash)
        // Nearly as good: the list's own order is kept.
        val close = PlaylistMix.order(listOf(measured(120f), measured(121f), measured(120f)), List(3) { null }, setOf(0))
        assertEquals(listOf(0, 1, 2), close)
    }

    @Test fun aTitlesEndingIsReadWhenItIsKnown() {
        // Its start is slow, its ending at 128: the next one follows the ending.
        val intros = listOf(measured(90f), measured(91f), measured(128f))
        val endings = listOf(measured(128f), null, null)
        assertEquals(listOf(0, 2, 1), PlaylistMix.order(intros, endings, setOf(0)))
    }

    @Test fun preparationIsBoundedByTheAnalysisSetting() {
        assertEquals(4, PlaylistMix.budget(AnalysisDepth.Light, 40))
        assertEquals(6, PlaylistMix.budget(AnalysisDepth.Balanced, 40))
        assertEquals(8, PlaylistMix.budget(AnalysisDepth.Maximum, 40))
        assertEquals(3, PlaylistMix.budget(AnalysisDepth.Maximum, 3))
        assertEquals(listOf("a", "c", "b"), PlaylistMix.apply(listOf("a", "b", "c"), listOf(0, 2, 1)))
    }
}
