package com.ketvoice.app.ui.sub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPresentationTest {
    @Test fun collapseIsBoundedAndContinuousAtTheHeroBoundary() {
        assertEquals(0f, AlbumPresentation.collapse(0, 600, 0), 0f)
        assertEquals(0f, AlbumPresentation.collapse(12, 600, 0), 0f)
        assertEquals(.5f, AlbumPresentation.collapse(-216, 600, 0), .0001f)
        assertEquals(1f, AlbumPresentation.collapse(-432, 600, 0), .0001f)
        assertEquals(1f, AlbumPresentation.collapse(null, 600, 1), 0f)
        assertEquals(0f, AlbumPresentation.collapse(null, 0, 0), 0f)
        assertEquals(1f, AlbumPresentation.collapse(-12, 0, 0), 0f)
    }

    @Test fun imageColourIntegrationHasNoHardEdgeAndNeverOvershoots() {
        assertEquals(0f, AlbumPresentation.smoothProgress(-1f), 0f)
        assertEquals(1f, AlbumPresentation.smoothProgress(2f), 0f)
        assertEquals(0f, AlbumPresentation.smoothProgress(Float.NaN), 0f)
        assertEquals(.5f, AlbumPresentation.smoothProgress(.5f), 0f)
        val values = (0..100).map { AlbumPresentation.smoothProgress(it / 100f) }
        assertTrue(values.zipWithNext().all { (left, right) -> right >= left })
        assertTrue(values[1] - values[0] < .001f)
        assertTrue(values[100] - values[99] < .001f)
    }

    @Test fun parallaxUsesOnlyABoundedTranslationAndReducedMotionIsStationary() {
        assertEquals(6f, AlbumPresentation.artworkTranslation(100f, 400f, false), 0f)
        assertEquals(14f, AlbumPresentation.artworkTranslation(10_000f, 400f, false), 0f)
        assertEquals(0f, AlbumPresentation.artworkTranslation(-100f, 400f, false), 0f)
        assertEquals(0f, AlbumPresentation.artworkTranslation(100f, 400f, true), 0f)
        assertEquals(0f, AlbumPresentation.artworkTranslation(Float.NaN, 400f, false), 0f)
        assertEquals(0f, AlbumPresentation.artworkTranslation(100f, -400f, false), 0f)
    }

    @Test fun landscapeFallsBackToStackedBeforeLargeTypeCrushesTheIdentity() {
        assertFalse(AlbumPresentation.sideBySide(400f, 1f))
        assertTrue(AlbumPresentation.sideBySide(900f, 1f))
        assertTrue(AlbumPresentation.sideBySide(900f, 1.4f))
        assertFalse(AlbumPresentation.sideBySide(720f, 1.4f))
        assertFalse(AlbumPresentation.sideBySide(900f, 2f))
    }

    @Test fun artworkIsTheFullSquareWhenTheWindowAllowsItAndNeverOversized() {
        assertEquals("A phone shows the whole square cover", 400f, AlbumPresentation.heroHeight(400f, 900f), 0f)
        assertEquals("A short phone keeps the title on the first screen", 340.4f, AlbumPresentation.heroHeight(360f, 740f), .1f)
        assertEquals("Never taller than 420 dp", 420f, AlbumPresentation.heroHeight(700f, 1400f), 0f)
        assertEquals("Never a thin strip", 220f, AlbumPresentation.heroHeight(700f, 320f), 0f)
    }

    @Test fun aCoverThatWouldBeCutToABandIsShownWhole() {
        assertFalse(AlbumPresentation.showsWholeCover(400f, 400f))
        assertFalse(AlbumPresentation.showsWholeCover(360f, 340f))
        assertTrue(AlbumPresentation.showsWholeCover(600f, 368f))
        assertFalse(AlbumPresentation.showsWholeCover(600f, 0f))
    }
}
