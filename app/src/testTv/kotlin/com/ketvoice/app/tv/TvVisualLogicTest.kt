package com.ketvoice.app.tv

import androidx.compose.ui.geometry.Rect
import com.ketvoice.app.data.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ketvoice TV 1.1.0's pure visual rules: Now Playing's layouts, the mix's pictures, palettes, the zoom, Home's poster. */
class TvVisualLogicTest {
    private val w = 960f
    private val h = 540f

    @Test fun theFrameTakesTheMediasShapeNeverABox() {
        val square = TvPlayerLayout.solo(w, h, 1f).art
        assertEquals(square.w, square.h, .01f)
        val tall = TvPlayerLayout.solo(w, h, 9f / 16f).art
        assertEquals(9f / 16f, tall.w / tall.h, .001f)
        assertEquals("a 9:16 Canvas is as tall as a cover", square.h, tall.h, .01f)
        val wide = TvPlayerLayout.solo(w, h, 16f / 9f).art
        assertEquals(16f / 9f, wide.w / wide.h, .001f)
        assertTrue("capped by the width", wide.w <= w * .62f + .01f)
        // Unknown or absurd shapes fall back to a square or the caps.
        assertEquals(1f, TvPlayerLayout.aspect(null), 0f)
        assertEquals(TvPlayerLayout.MAX_ASPECT, TvPlayerLayout.aspect(9f), 0f)
        assertEquals(1f, TvPlayerLayout.aspect(Float.NaN), 0f)
    }

    @Test fun soloIsCentredAndTheLyricsLayoutKeepsAColumnOnTheLeft() {
        val solo = TvPlayerLayout.solo(w, h, 1f)
        assertEquals(w / 2, solo.art.centerX, .01f)
        assertTrue("the controls along the bottom", solo.controls.bottom <= h - TvPlayerLayout.SAFE_Y + .01f)
        assertTrue("art, title, Ketmix line, progress and controls in order",
            solo.art.bottom <= solo.info.y && solo.info.bottom <= solo.ketmix.y && solo.ketmix.bottom <= solo.progress.y && solo.progress.bottom <= solo.controls.y)
        val lyrics = TvPlayerLayout.withLyrics(w, h, 1f)
        assertTrue(lyrics.art.x >= TvPlayerLayout.SAFE_X && lyrics.art.x + lyrics.art.w <= TvPlayerLayout.SAFE_X + TvPlayerLayout.COLUMN_W + .01f)
        assertTrue("the lyrics take the right side", lyrics.lyrics.x > w / 2 - 20 && lyrics.lyrics.x + lyrics.lyrics.w <= w - TvPlayerLayout.SAFE_X + .01f)
        assertTrue(lyrics.controls.bottom <= h - TvPlayerLayout.SAFE_Y + .01f && lyrics.controls.x + lyrics.controls.w <= lyrics.lyrics.x)
        // The glide passes through every step between them.
        val half = TvPlayerLayout.at(.5f, w, h, 1f)
        assertEquals((solo.art.x + lyrics.art.x) / 2, half.art.x, .01f)
        assertEquals(.5f, half.stacked, 0f)
        assertEquals(solo, TvPlayerLayout.at(-1f, w, h, 1f))
        assertEquals(lyrics, TvPlayerLayout.at(2f, w, h, 1f))
    }

    @Test fun theControlsAreOneBarOrTwoRows() {
        val sizes = floatArrayOf(44f, 44f, 44f, 52f, 64f, 52f, 44f, 44f, 44f)
        val bar = TvPlayerLayout.controls(sizes, 864f, 0f)
        assertEquals("like at the bar's left end", 0f, bar[0].first, 0f)
        assertEquals("play centred", 432f, bar[4].first + 32f, .01f)
        assertEquals("queue at its right end", 864f, bar[8].first + 44f, .01f)
        assertTrue("one row", bar.all { it.second < TvPlayerLayout.ROW_H })
        val rows = TvPlayerLayout.controls(sizes, 380f, 1f)
        assertEquals("play centred in the column", 190f, rows[4].first + 32f, .01f)
        assertTrue("like, Ketmix, lyrics and queue on the second row", listOf(0, 1, 7, 8).all { rows[it].second >= TvPlayerLayout.ROW_H })
        assertTrue("nothing leaves the column", rows.indices.all { rows[it].first >= 0f && rows[it].first + sizes[it] <= 380f })
    }

    @Test fun duringAMixTheArtworkLandsOnTheBassSwap() {
        val swap = TvMixVisual.swapPoint(.6f)
        assertEquals(.6f, swap, 0f)
        assertEquals("no swap in the plan: the hand-over", com.ketvoice.app.ui.player.MixVisuals.HANDOVER, TvMixVisual.swapPoint(-1f), 0f)
        assertEquals(0f, TvMixVisual.artIn(.2f, swap), 0f)
        assertEquals("whole on the swap", 1f, TvMixVisual.artIn(swap, swap), 0f)
        val rising = TvMixVisual.artIn(swap - TvMixVisual.ART_WINDOW / 2, swap)
        assertTrue(rising > 0f && rising < 1f)
        assertFalse(TvMixVisual.swapped(.59f, swap))
        assertTrue(TvMixVisual.swapped(.6f, swap))
        // The colours follow the whole mix.
        assertEquals(0f, TvMixVisual.fieldIn(0f), 0f)
        assertEquals(.5f, TvMixVisual.fieldIn(.5f), .001f)
        assertEquals(1f, TvMixVisual.fieldIn(1f), 0f)
    }

    @Test fun aPaletteHasFourDarkTonesOfItsQuarters() {
        val red = 0xFFE03020.toInt(); val blue = 0xFF2040E0.toInt()
        val pixels = IntArray(16 * 16) { i -> if ((i % 16) < 8) red else blue }
        val palette = TvPalette.from(pixels, 16, 16)
        assertEquals(4, palette.colors.size)
        assertTrue("left quarters red", palette.colors[0].red > palette.colors[0].blue)
        assertTrue("right quarters blue", palette.colors[1].blue > palette.colors[1].red)
        assertTrue("dark enough for white text", palette.colors.all { maxOf(it.red, it.green, it.blue) <= .47f })
        assertEquals(TvPalette.Neutral, TvPalette.from(IntArray(0), 0, 0))
        val half = TvPalette.Neutral.lerp(palette, .5f)
        assertNotEquals(TvPalette.Neutral, half)
        assertEquals(palette, TvPalette.Neutral.lerp(palette, 1f))
        val hsv = TvPalette.hsv(1f, 0f, 0f)
        assertEquals(0f, hsv[0], .01f); assertEquals(1f, hsv[1], .01f); assertEquals(1f, hsv[2], .01f)
        assertEquals(240f, TvPalette.hsv(0f, 0f, 1f)[0], .01f)
    }

    @Test fun theZoomFliesBetweenTheTwoRectangles() {
        val card = Rect(100f, 300f, 396f, 596f)
        val header = Rect(240f, 54f, 704f, 518f)
        assertEquals(card, TvZoom.lerp(card, header, 0f))
        assertEquals(header, TvZoom.lerp(card, header, 1f))
        assertEquals(Rect(170f, 177f, 550f, 557f), TvZoom.lerp(card, header, .5f))
        val grown = TvZoom.inflate(Rect(0f, 0f, 100f, 100f), 1.1f)
        assertEquals(-5f, grown.left, .001f); assertEquals(105f, grown.right, .001f); assertEquals(-5f, grown.top, .001f)
        assertEquals(1f, TvZoom.overlayAlpha(.5f, true), 0f)
        assertEquals("dissolved into the header", 0f, TvZoom.overlayAlpha(1f, true), 0f)
        assertEquals("whole all the way back", 1f, TvZoom.overlayAlpha(1f, false), 0f)
        assertEquals(0f, TvZoom.headerAlpha(.5f), 0f)
        assertEquals(1f, TvZoom.headerAlpha(1f), 0f)
    }

    @Test fun homesPosterTellsTheKindAndAShortLine() {
        val album = TvRows.album(Album("MPRE1", "Marées", "Nova Lane", "Single · 2025", null))
        assertEquals("Single", album.release)
        assertEquals("2025", album.year)
        val spot = TvSpot.of(album)
        assertEquals("Single", spot.eyebrow)
        assertEquals(listOf("Nova Lane" to false, "2025" to false), spot.line)
        val mix = TvSpot.of(TvItem("mix:1", TvItemKind.Mix, "Mix découverte", "Pour vous", null, appText = true))
        assertEquals("Mix", mix.eyebrow)
        assertEquals(listOf("Pour vous" to true), mix.line)
        assertEquals("Artiste", TvSpot.of(TvItem("ar:x", TvItemKind.Artist, "Kael", "", null)).eyebrow)
        assertEquals("2024", TvRows.yearOf("2024-05-17"))
        assertEquals("", TvRows.yearOf(null))
        assertEquals("", TvRows.yearOf("soon"))
        val lead = TvSpot.lead(com.ketvoice.app.data.Track("t", "Minuit", "Kael", "Nuits"), null, playing = true)
        assertEquals("En cours de lecture", lead.eyebrow)
        assertEquals(listOf("Kael" to false, "Nuits" to false), lead.line)
    }

    @Test fun anArtistsBioIsShortAndThePhotoDarkensAsTheRowsComeUp() {
        assertEquals("Court.", TvArtistRows.shortBio("Court."))
        val long = "Première phrase assez longue pour compter. " + "Mot ".repeat(80)
        val cut = TvArtistRows.shortBio(long, max = 120)
        assertTrue(cut.length <= 121)
        assertEquals("Première phrase assez longue pour compter.", TvArtistRows.shortBio("Première phrase assez longue pour compter. " + "x".repeat(200), max = 60))
        assertTrue(TvArtistRows.shortBio("mot ".repeat(100), max = 50).endsWith("…"))
        assertEquals(0f, TvArtistRows.scrim(0, 0f), 0f)
        assertEquals(.5f, TvArtistRows.scrim(0, 150f), .001f)
        assertEquals(1f, TvArtistRows.scrim(2, 0f), 0f)
    }
}
