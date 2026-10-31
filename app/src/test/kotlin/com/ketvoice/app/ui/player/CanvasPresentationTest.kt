package com.ketvoice.app.ui.player

import org.junit.Assert.*
import org.junit.Test

class CanvasPresentationTest {
    @Test fun matchingPortraitAndLandscapeClipsCanFillScreen() {
        assertTrue(CanvasPresentation.canFillScreen(9f / 16f, 9f / 16f))
        assertTrue(CanvasPresentation.canFillScreen(16f / 9f, 16f / 9f))
    }

    @Test fun portraitClipCannotBeMagnifiedIntoLandscapeOrSquare() {
        assertFalse(CanvasPresentation.canFillScreen(9f / 16f, 16f / 9f))
        assertFalse(CanvasPresentation.canFillScreen(9f / 16f, 1f))
        assertFalse(CanvasPresentation.canFillScreen(1f, 9f / 16f))
    }

    @Test fun fullscreenRequiresAtLeastEightyFivePercentOfTheSource() {
        assertTrue(CanvasPresentation.canFillScreen(1f, .85f))
        assertFalse(CanvasPresentation.canFillScreen(1f, .849f))
        assertTrue(CanvasPresentation.canFillScreen(.85f, 1f))
        assertFalse(CanvasPresentation.canFillScreen(.849f, 1f))
        assertFalse(CanvasPresentation.canFillScreen(9f / 16f, 9f / 20f))
    }

    @Test fun unavailableAndInvalidDimensionsNeverEnableFullscreen() {
        listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertFalse(CanvasPresentation.canFillScreen(it, .5f))
        }
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertFalse(CanvasPresentation.canFillScreen(.5f, it))
        }
    }

    @Test fun encodedRotationUsesDisplayProportions() {
        assertEquals(.5625f, CanvasPresentation.aspectRatio(1920, 1080, 90)!!, .0001f)
        assertEquals(.5625f, CanvasPresentation.aspectRatio(1920, 1080, 270)!!, .0001f)
        assertEquals(1920f / 1080f, CanvasPresentation.aspectRatio(1920, 1080, 180)!!, .0001f)
    }

    @Test fun missingVideoDimensionsDoNotPretendToBeSquare() {
        assertNull(CanvasPresentation.aspectRatio(0, 1080, 0))
        assertNull(CanvasPresentation.aspectRatio(1920, -1, 90))
    }
}
