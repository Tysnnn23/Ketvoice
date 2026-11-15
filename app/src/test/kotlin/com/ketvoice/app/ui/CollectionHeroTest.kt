package com.ketvoice.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.ketvoice.app.ui.common.heroArtworkSide
import com.ketvoice.app.ui.common.heroEntryScale
import com.ketvoice.app.ui.common.savedFormatBadge
import com.ketvoice.app.ui.player.sheetEntryScale
import com.ketvoice.app.ui.player.sheetOrigin
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class CollectionHeroTest {
    @Test fun floatingArtworkIsLargeButNeverFillsThePage() {
        assertEquals(248f, heroArtworkSide(400f, beside = false), .5f)
        assertEquals("A small phone keeps a readable cover", 198.4f, heroArtworkSide(320f, beside = false), .5f)
        assertEquals("A tablet does not blow the cover up", 300f, heroArtworkSide(1200f, beside = false), 0f)
        assertEquals(288f, heroArtworkSide(900f, beside = true), .5f)
        assertEquals(120f, heroArtworkSide(300f, beside = true), 0f)
    }

    @Test fun artworkContinuesTheMovementOfTheCardThatOpenedIt() {
        assertEquals(.92f, heroEntryScale(null, 500f), 0f)
        assertEquals(.6f, heroEntryScale(Rect(0f, 0f, 300f, 300f), 500f), 1e-4f)
        assertEquals("Never from a dot", .45f, heroEntryScale(Rect(0f, 0f, 20f, 20f), 500f), 0f)
        assertEquals("Never larger than its place", .98f, heroEntryScale(Rect(0f, 0f, 900f, 900f), 500f), 0f)
        assertEquals(.92f, heroEntryScale(Rect(0f, 0f, 300f, 300f), 0f), 0f)
    }

    @Test fun aSheetGrowsOutOfTheTouchThatOpenedIt() {
        val bounds = Rect(0f, 400f, 400f, 800f)
        val fromButton = sheetOrigin(Offset(300f, 500f), bounds)
        assertEquals(.75f, fromButton.pivotFractionX, 1e-4f)
        assertEquals(.25f, fromButton.pivotFractionY, 1e-4f)
        // A touch above the sheet still pulls it from that side, within reason.
        assertEquals(-1f, sheetOrigin(Offset(200f, -5000f), bounds).pivotFractionY, 0f)
        val fallback = sheetOrigin(null, bounds)
        assertEquals(.5f, fallback.pivotFractionX, 0f)
        assertEquals(1f, fallback.pivotFractionY, 0f)
        assertEquals(1f, sheetOrigin(Offset(10f, 10f), Rect.Zero).pivotFractionY, 0f)
    }

    @Test fun aSheetNeverStartsFromNothing() {
        assertEquals(.82f, sheetEntryScale(0f), 1e-6f)
        assertEquals(1f, sheetEntryScale(1f), 1e-6f)
        assertEquals(1f, sheetEntryScale(Float.NaN), 0f)
        assertEquals(1f, sheetEntryScale(7f), 1e-6f)
    }

    @Test fun onlySavedTitlesCarryAFormatBadge() {
        assertNull(savedFormatBadge(null))
        assertNull(savedFormatBadge(" "))
        assertEquals("FLAC", savedFormatBadge("DASH · FLAC"))
        assertEquals("MP3", savedFormatBadge("MP3 · Haute qualité"))
    }

    @Test fun anArtistPageIsPulledTowardsThePortraitColour() {
        val light = androidx.compose.ui.graphics.Color.White
        val dark = androidx.compose.ui.graphics.Color.Black
        val red = androidx.compose.ui.graphics.Color(1f, 0f, 0f)
        assertEquals("Without a colour the page keeps its background", light, com.ketvoice.app.ui.sub.artistPageTint(light, null, dark = false))
        // Colours are blended perceptually, so only the direction and the range are asserted.
        val pastel = com.ketvoice.app.ui.sub.artistPageTint(light, red, dark = false)
        assertTrue("A light page leans towards the portrait", pastel.red > pastel.green + .15f && pastel.red > pastel.blue + .15f)
        assertTrue("and stays a pastel, bright enough for dark text", pastel.green > .55f && pastel.red > .9f)
        val deep = com.ketvoice.app.ui.sub.artistPageTint(dark, red, dark = true)
        assertTrue("A dark page leans towards the portrait", deep.red > deep.green + .1f && deep.red > deep.blue + .1f)
        assertTrue("and stays dark enough for light text", deep.red < .55f && deep.green < .2f)
        assertTrue("A dark page takes less of the colour than a light one",
            com.ketvoice.app.ui.sub.artistPageTint(light, red, dark = true).green > pastel.green)
    }

    @Test fun aDarkPageNeverLightsUpTheScreenAtNight() {
        val night = androidx.compose.ui.graphics.Color(0xFF050507)
        for (tone in listOf(androidx.compose.ui.graphics.Color(1f, .9f, .2f), androidx.compose.ui.graphics.Color(.9f, .95f, 1f),
                androidx.compose.ui.graphics.Color(1f, .3f, .6f), androidx.compose.ui.graphics.Color(.2f, .9f, .4f))) {
            val page = com.ketvoice.app.ui.sub.artistPageTint(night, tone, dark = true)
            assertTrue("$tone gives ${page.luminance()}", page.luminance() <= com.ketvoice.app.ui.sub.PageTint.NIGHT_MAX_LUMINANCE * 1.03f)
            // The hue stays: the strongest component of the colour is still the strongest of the page.
            val strongest = listOf(tone.red, tone.green, tone.blue).indexOf(maxOf(tone.red, tone.green, tone.blue))
            val pageStrongest = listOf(page.red, page.green, page.blue).indexOf(maxOf(page.red, page.green, page.blue))
            assertEquals(strongest, pageStrongest)
        }
        // A colour already dim enough is left exactly as it was.
        val dim = androidx.compose.ui.graphics.Color(.12f, .05f, .08f)
        assertEquals(dim, com.ketvoice.app.ui.sub.PageTint.nightCap(dim))
    }

    @Test fun aLightPageStaysLightUnderADarkArtwork() {
        val white = androidx.compose.ui.graphics.Color.White
        val page = com.ketvoice.app.ui.sub.artistPageTint(white, androidx.compose.ui.graphics.Color(.06f, .06f, .06f), dark = false)
        assertTrue("${page.luminance()}", page.luminance() >= com.ketvoice.app.ui.sub.PageTint.DAY_MIN_LUMINANCE - .01f)
        assertTrue(page.luminance() < .99f)
    }
}
