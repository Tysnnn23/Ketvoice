package com.ketvoice.app.ui.motion

import com.ketvoice.app.ui.PageCard
import com.ketvoice.app.ui.player.LivingArtwork
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MotionPiecesTest {
    @Test fun aListPulledPastItsEndFollowsLessAndLess() {
        val d = 2_000f
        assertEquals(0f, RubberBand.displayed(0f, d), 0f)
        val small = RubberBand.displayed(100f, d); val big = RubberBand.displayed(1_000f, d)
        assertTrue("it follows at first", small > 40f && small < 100f)
        assertTrue("then less and less", big / 1_000f < small / 100f)
        assertTrue("never past the size of the list", RubberBand.displayed(1e7f, d) < d)
        assertEquals("the same the other way", -small, RubberBand.displayed(-100f, d), 1e-3f)
        assertEquals(0f, RubberBand.displayed(500f, 0f), 0f)
    }

    @Test fun theBurstSpreadsAndFades() {
        assertEquals(14, BurstShape.particles.size)
        assertEquals(1f, BurstShape.alpha(.2f), 0f); assertEquals(0f, BurstShape.alpha(1f), 1e-4f)
        val p = BurstShape.particles.first()
        val near = BurstShape.position(p, .1f, 48f); val far = BurstShape.position(p, .9f, 48f)
        assertTrue(abs(far.x) + abs(far.y) > abs(near.x) + abs(near.y))
    }

    @Test fun theArtworkBreathesOnlyWithTheBass() {
        assertEquals(0f, LivingArtwork.target(0f), 0f)
        assertEquals("the quiet floor does nothing", 0f, LivingArtwork.target(.15f), 0f)
        assertEquals(LivingArtwork.MAX, LivingArtwork.target(1f), 1e-6f)
        assertTrue(LivingArtwork.target(.6f) in 0f..LivingArtwork.MAX)
        assertEquals(0f, LivingArtwork.target(Float.NaN), 0f)
    }

    @Test fun aPageGrowsOutOfACardAndEndsSquare() {
        assertEquals(PageCard.CARD_RADIUS, PageCard.radius(0f), 1e-4f)
        assertTrue(PageCard.radius(.5f) in 0f..PageCard.CARD_RADIUS)
        assertEquals(0f, PageCard.radius(.98f), 0f); assertEquals(0f, PageCard.radius(1f), 0f)
    }
}
