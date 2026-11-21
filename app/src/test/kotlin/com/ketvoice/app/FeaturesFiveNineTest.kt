package com.ketvoice.app

import com.ketvoice.app.data.DayStat
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.player.SkipFade
import com.ketvoice.app.ui.player.ArtworkFlow
import com.ketvoice.app.ui.player.EqualizerCurveMath
import com.ketvoice.app.ui.player.LyricsWords
import com.ketvoice.app.ui.sub.WrappedPages
import org.junit.Assert.*
import org.junit.Test

class FeaturesFiveNineTest {
    private fun track(id: String, artist: String) = Track(id, "Title $id", artist, "Album")

    @Test fun wrappedUsesTheYearOnceItHasEnoughPlaysAndAllTimeBefore() {
        val known = listOf(track("a", "Nova"), track("b", "Nova"), track("c", "Lune"))
        val counts = mapOf("a" to 40, "b" to 5, "c" to 12)
        val thin = mapOf("2026" to DayStat(600_000, mapOf("a" to 3)))
        val allTime = Wrapped.recap("2026", thin, counts, 90 * 60_000L, known)
        assertNull(allTime.year); assertEquals(90, allTime.minutes); assertEquals(57, allTime.plays)
        assertEquals("Nova", allTime.topArtist?.name); assertEquals(45, allTime.topArtist?.plays)
        assertEquals("a", allTime.topTrack?.id)
        val full = mapOf("2026" to DayStat(30 * 60_000L, mapOf("c" to 20, "a" to 6)))
        val year = Wrapped.recap("2026", full, counts, 90 * 60_000L, known)
        assertEquals("2026", year.year); assertEquals(30, year.minutes); assertEquals("c", year.topTrack?.id)
        assertEquals("Lune", year.topArtist?.name)
    }

    @Test fun wrappedYearsAddUpAndSurviveTheirEncoding() {
        var years = Wrapped.addTime(emptyMap(), "2026", 60_000)
        years = Wrapped.addPlay(Wrapped.addPlay(years, "2026", "a"), "2026", "a")
        assertEquals(2, years["2026"]?.plays?.get("a")); assertEquals(60_000L, years["2026"]?.ms)
        val back = Wrapped.decode(com.ketvoice.app.data.ListeningWeek.encode(years))
        assertEquals(years, back)
        assertTrue(Wrapped.decode("{\"not-a-year\":{}}").isEmpty())
    }

    @Test fun wrappedStoryTapsGoBackOnTheLeftThirdAndForwardElsewhere() {
        assertEquals(1, WrappedPages.next(2, 10f, 300f)); assertEquals(3, WrappedPages.next(2, 250f, 300f))
        assertEquals(0, WrappedPages.next(0, 10f, 300f)); assertEquals(WrappedPages.COUNT, WrappedPages.next(WrappedPages.COUNT - 1, 250f, 300f))
    }

    @Test fun sungWordsRiseAndOnlyHeldNotesGlow() {
        // 8.3: a word rises over its own time and settles back once sung (it no longer stays risen).
        assertEquals(0f, LyricsWords.lift(0, 1_000), 0f); assertEquals(1f, LyricsWords.lift(1_000, 1_000), 1e-6f)
        assertEquals(0f, LyricsWords.lift(1_000 + LyricsWords.FALL_MS, 1_000), 1e-6f)
        assertEquals(0f, LyricsWords.glow(.5f, 300), 0f)
        assertTrue(LyricsWords.glow(.5f, 1_500) > .5f); assertEquals(0f, LyricsWords.glow(1f, 1_500), 0f)
        assertEquals(0f, LyricsWords.breath(Float.NaN), 0f); assertTrue(LyricsWords.breath(1f) <= .015f + 1e-6f)
        assertTrue(LyricsWords.covers("I get high", listOf("I ", "get ", "high")))
        assertFalse(LyricsWords.covers("I get high", listOf("I ", "get")))
    }

    @Test fun theFlowingBackgroundTurnsLayersBothWaysAndBlursEvenly() {
        assertTrue(ArtworkFlow.angle(0, 10f) > 0f); assertTrue(ArtworkFlow.angle(1, 10f) < 0f)
        val side = 8
        val pixels = IntArray(side * side) { if (it == 27) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        ArtworkFlow.blur(pixels, side, radius = 1, passes = 1)
        assertTrue((pixels[27] and 0xFF) in 1..254)            // the white dot spread
        assertTrue((pixels[26] and 0xFF) > 0)                    // onto its neighbours
    }

    @Test fun equalizerCurveFingerMapsToBandAndGain() {
        assertEquals(0, EqualizerCurveMath.band(0f, 900f, 10)); assertEquals(9, EqualizerCurveMath.band(900f, 900f, 10))
        assertEquals(5, EqualizerCurveMath.band(500f, 900f, 10))
        assertEquals(12, EqualizerCurveMath.gain(0f, 100f)); assertEquals(-12, EqualizerCurveMath.gain(100f, 100f))
        assertEquals(0, EqualizerCurveMath.gain(50f, 100f))
        assertEquals(50f, EqualizerCurveMath.y(0, 100f), 1e-4f)
    }

    @Test fun skipFadeGlidesEvenlyBetweenItsEnds() {
        assertEquals(1f, SkipFade.level(1f, 0f, 0f), 1e-6f); assertEquals(0f, SkipFade.level(1f, 0f, 1f), 1e-6f)
        assertEquals(0f, SkipFade.level(0f, 1f, 0f), 1e-6f); assertEquals(1f, SkipFade.level(0f, 1f, 1f), 1e-6f)
        assertEquals(.707f, SkipFade.level(0f, 1f, .5f), .01f)
    }

}
