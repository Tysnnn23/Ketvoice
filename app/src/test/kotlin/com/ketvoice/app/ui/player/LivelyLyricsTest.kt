package com.ketvoice.app.ui.player

import com.ketvoice.app.data.MusicApi
import org.junit.Assert.*
import org.junit.Test

/** 8.3 lyrics: the constant-pace feathered fill, the lift, held notes, the blur by distance and the break dots. */
class LivelyLyricsTest {
    private val w = LyricsWords

    @Test fun theFillMovesAtOneConstantPaceInsideAWord() {
        val width = 200f; val feather = 30f
        assertEquals(0f, w.edgePx(-1, 1_000, width, feather), 0f)
        assertEquals(0f, w.edgePx(0, 1_000, width, feather), 0f)
        assertEquals(width, w.edgePx(1_000, 1_000, width, feather), 1e-3f)
        // The same distance for every millisecond of the word: no easing that speeds up or slows down.
        val steps = (0..1_000 step 50).map { w.edgePx(it.toLong(), 1_000, width, feather) }
        steps.zipWithNext { a, b -> assertEquals(10f, b - a, 1e-3f) }
        assertEquals(.5f, segmentProgress(1_500, 1_000, 2_000), 1e-6f)
        assertEquals(.25f, segmentProgress(1_250, 1_000, 2_000), 1e-6f)
    }

    @Test fun theFeatheredEdgeFinishesAfterTheWordWithinThePause() {
        val width = 200f; val feather = 30f
        // At its own pace (30 px of a 200 px word sung in 1 s: 150 ms), and all lit after that.
        assertEquals(150L, w.tailMs(1_000, width, feather))
        assertEquals(width + feather / 2, w.edgePx(1_075, 1_000, width, feather), .5f)
        assertEquals(width + feather, w.edgePx(1_150, 1_000, width, feather), 1e-3f)
        assertEquals(width + feather, w.edgePx(5_000, 1_000, width, feather), 1e-3f)
        // A shorter pause before the next word: the gap fills during it.
        assertEquals(90L, w.tailMs(1_000, width, feather, pauseMs = 90))
        assertEquals(width + feather, w.edgePx(1_090, 1_000, width, feather, pauseMs = 90), 1e-3f)
        // Never longer than TAIL_MAX_MS nor shorter than TAIL_MIN_MS, so the line comes to rest in time.
        assertEquals(w.TAIL_MAX_MS, w.tailMs(5_000, 20f, feather))
        assertEquals(w.TAIL_MIN_MS, w.tailMs(100, 400f, feather))
        // No timing: the whole word at once.
        assertEquals(width + feather, w.edgePx(0, 0, width, feather), 0f)
    }

    @Test fun theInkIsFullAFeatherBehindTheEdgeAndNoneAtIt() {
        assertEquals(1f, w.ink(10f, 100f, 30f), 0f)
        assertEquals(1f, w.ink(70f, 100f, 30f), 1e-6f)
        assertEquals(.5f, w.ink(85f, 100f, 30f), 1e-6f)
        assertEquals(0f, w.ink(100f, 100f, 30f), 0f)
        assertEquals(0f, w.ink(140f, 100f, 30f), 0f)
        assertTrue(w.FEATHER_DP in 28f..32f)
    }

    @Test fun theLiftFollowsTheWordsLengthAndSettlesBack() {
        assertEquals(w.LIFT_FLOOR, w.liftHeight(100), 1e-6f)
        assertEquals(.25f, w.liftHeight(w.LIFT_QUICK_MS), 1e-6f)
        assertEquals(1f, w.liftHeight(900), 1e-6f)
        assertEquals(1f, w.liftHeight(3_000), 1e-6f)
        (100L..1_000L step 20).map(w::liftHeight).zipWithNext { a, b -> assertTrue(b >= a) }
        // A quick word rises over the shortest rise, a held one over the longest: never longer.
        assertTrue("a quick word takes RISE_MIN_MS to rise", w.lift(120, 120) < w.liftHeight(120))
        assertEquals(1f, w.lift(w.RISE_MAX_MS, 2_000), 1e-6f)
        assertTrue(w.lift(350, 2_000) < 1f)
        assertEquals(w.liftHeight(500), w.lift(500, 500), 1e-6f)
        // Up while sung, then back down after it, at rest by FALL_MS.
        assertTrue(w.lift(700, 500) in .01f..1f)
        assertEquals(0f, w.lift(500 + w.FALL_MS, 500), 1e-6f)
        assertEquals(0f, w.lift(9_000, 500), 0f)
        assertEquals(0f, w.lift(-10, 500), 0f)
        for (ms in 0L..2_000L step 10) assertTrue(w.lift(ms, 400) <= w.liftHeight(400) + 1e-6f)
    }

    @Test fun everyWordEffectIsAtRestByRestMs() {
        assertTrue(w.REST_MS >= w.FALL_MS && w.REST_MS >= w.TAIL_MAX_MS && w.REST_MS >= w.SETTLE_MS && w.REST_MS >= w.HELD_TAIL_MS)
        assertEquals(320L, w.SETTLE_MS)
        assertTrue((0..20).all { w.grow(it / 20f, -1) <= 1.035f + 1e-6f })
        assertEquals(1f, w.grow(1f, w.SETTLE_MS), 1e-6f)
        for (i in 0 until 4) assertEquals(0f, w.swell(i, 4, 2_000 + w.HELD_TAIL_MS, 2_000), 0f)
    }

    @Test fun onlyShortWordsHeldLongInSeparableScriptsSwell() {
        assertTrue(w.holds("love", 1_200))
        assertTrue(w.holds("oh ", 900))
        assertTrue(w.holds("да", 1_500))
        assertTrue(w.holds("φως", 1_500))
        assertTrue(w.holds("baby,", 1_000))
        assertFalse("too quick", w.holds("love", 899))
        assertFalse("too long a word", w.holds("tonight!", 2_000))
        assertTrue(w.holds("tonight", 2_000))
        assertFalse("Chinese", w.holds("爱", 2_000))
        assertFalse("Japanese", w.holds("あい", 2_000))
        assertFalse("Korean", w.holds("사랑", 2_000))
        assertFalse("Arabic", w.holds("حب", 2_000))
        assertFalse("Hebrew", w.holds("אהבה", 2_000))
        assertFalse("no letters", w.holds("...", 2_000))
        assertFalse(w.holds("   ", 2_000))
        assertFalse("a combining accent", w.holds("é", 2_000))
    }

    @Test fun lettersSwellOneAfterAnother() {
        val duration = 1_600L
        assertEquals(0f, w.swell(0, 4, 0, duration), 0f)
        // The first letter is already swelling when the last one has not started.
        assertTrue(w.swell(0, 4, 200, duration) > 0f)
        assertEquals(0f, w.swell(3, 4, 200, duration), 0f)
        // Each reaches the top and holds a while, then falls back to nothing.
        for (i in 0 until 4) {
            val peak = (0L..duration + w.HELD_TAIL_MS step 10).maxOf { w.swell(i, 4, it, duration) }
            assertEquals(1f, peak, 1e-6f)
        }
        for (ms in 0L..3_000L step 7) for (i in 0 until 4) assertTrue(w.swell(i, 4, ms, duration) in 0f..1f)
        assertFalse(w.inHold(duration + w.HELD_TAIL_MS, duration))
        assertTrue(w.inHold(0, duration))
        assertEquals(.5f, w.heldStrength(900), 1e-6f)
        assertEquals(1f, w.heldStrength(3_000), 1e-6f)
        assertTrue(w.SWELL in .02f..0.06f)
        assertEquals(.6f, w.GLOW_ALPHA, .05f)
    }

    @Test fun distantLinesBlurByDistance() {
        val on = { d: Int -> LyricsBlur.radiusDp(d, enabled = true, timed = true, browsing = false, reduced = false, performance = false, supported = true) }
        assertEquals(listOf(0f, 1f, 1f, 1.7f, 2.4f, 2.4f, 2.4f), (0..6).map(on))
        assertEquals(LyricsBlur.FADE_MS.toFloat(), 400f, 0f)
    }

    @Test fun theBlurIsOffWhereItShouldBe() {
        fun r(enabled: Boolean = true, timed: Boolean = true, browsing: Boolean = false, reduced: Boolean = false,
              performance: Boolean = false, supported: Boolean = true) =
            LyricsBlur.radiusDp(3, enabled, timed, browsing, reduced, performance, supported)
        assertEquals(1.7f, r(), 0f)
        assertEquals("setting off", 0f, r(enabled = false), 0f)
        assertEquals("unsynced lyrics", 0f, r(timed = false), 0f)
        assertEquals("scrolled by hand", 0f, r(browsing = true), 0f)
        assertEquals("reduced motion", 0f, r(reduced = true), 0f)
        assertEquals("performance mode (also the automatic economy mode)", 0f, r(performance = true), 0f)
        assertEquals("unsupported device", 0f, r(supported = false), 0f)
        assertFalse("below Android 12", LyricsBlur.supported(30, quest = false, liteModest = false))
        assertTrue(LyricsBlur.supported(31, quest = false, liteModest = false))
        assertFalse("Quest", LyricsBlur.supported(34, quest = true, liteModest = false))
        assertFalse("Lite on a modest phone", LyricsBlur.supported(34, quest = false, liteModest = true))
    }

    @Test fun aSettledRadiusAlwaysGivesTheSameEffect() {
        assertEquals(0f, LyricsBlur.quantizedPx(0f), 0f)
        assertEquals(0f, LyricsBlur.quantizedPx(Float.NaN), 0f)
        assertEquals(2.5f, LyricsBlur.quantizedPx(2.49f), 0f)
        assertEquals(LyricsBlur.quantizedPx(4.2f), LyricsBlur.quantizedPx(4.2f), 0f)
    }

    private fun line(start: Long, end: Long, text: String = "Une ligne") =
        MusicApi.LyricLine(start, text, listOf(MusicApi.LyricWord(start, text, end)))

    @Test fun dotsFillEveryBreakOfFourSecondsOrMore() {
        val lines = listOf(line(1_000, 3_000), line(7_000, 9_000), line(12_900, 14_000),
            MusicApi.LyricLine(15_000, ""), line(19_500, 21_000), MusicApi.LyricLine(30_000, "Sans fin connue"),
            MusicApi.LyricLine(40_000, "La suivante"))
        val breaks = LyricsMotion.breaks(lines)
        assertEquals(3_000L until 7_000L, breaks[0])
        assertNull("3.9 s is not a break", breaks[1])
        assertEquals("an empty line marks a break", 15_000L until 19_500L, breaks[3])
        assertEquals(21_000L until 30_000L, breaks[4])
        assertNull("a line timed by its start only has no known end", breaks[5])
        assertEquals(setOf(0, 3, 4), breaks.keys)
        assertTrue(LyricsMotion.breaks(listOf(line(0, 500))).isEmpty())
    }

    @Test fun aKaraokeLineReadsTheClockOnlyInsideItsOwnTime() {
        val window = LyricsWords.window(MusicApi.LyricLine(1_000, "Deux mots",
            listOf(MusicApi.LyricWord(1_000, "Deux ", 1_400), MusicApi.LyricWord(1_400, "mots", 2_000))))
        assertEquals(-1, LyricsWords.side(900, window))
        assertEquals(0, LyricsWords.side(1_000, window))
        assertEquals(0, LyricsWords.side(2_000 + LyricsWords.REST_MS, window))
        assertEquals(1, LyricsWords.side(2_001 + LyricsWords.REST_MS, window))
    }
}
