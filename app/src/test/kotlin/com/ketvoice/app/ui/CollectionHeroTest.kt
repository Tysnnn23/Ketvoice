package com.ketvoice.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.ketvoice.app.ui.common.heroArtworkSide
import com.ketvoice.app.ui.common.heroEntryScale
import com.ketvoice.app.ui.common.savedFormatBadge
import com.ketvoice.app.ui.player.sheetEntryScale
import com.ketvoice.app.ui.player.sheetOrigin
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
}
