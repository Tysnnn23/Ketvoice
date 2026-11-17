package com.ketvoice.app.ui.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** The livelier player (6.2): subtle numbers, never an edge shown, nothing under reduced motion. */
class PlayerLivelinessTest {
    @Test fun theCoverZoomStaysSubtleAndStartsAtRest() {
        assertEquals(1f, CoverDrift.scale(0f), 1e-6f)
        assertEquals(1f, CoverDrift.scale(CoverDrift.PERIOD_S), 1e-4f)
        for (i in 0..2000) {
            val s = i * .37f
            val zoom = CoverDrift.scale(s)
            assertTrue(zoom in 1f..1f + CoverDrift.ZOOM + 1e-6f)
        }
        assertEquals(1f + CoverDrift.ZOOM, CoverDrift.scale(CoverDrift.PERIOD_S / 2f), 1e-4f)
    }

    @Test fun theDriftNeverUncoversAnEdge() {
        for (i in 0..4000) {
            val s = i * .113f
            val margin = (CoverDrift.scale(s) - 1f) / 2f
            val (dx, dy) = CoverDrift.shift(s)
            assertTrue("x at $s", abs(dx) <= margin + 1e-6f)
            assertTrue("y at $s", abs(dy) <= margin + 1e-6f)
        }
    }

    @Test fun theLightSweepsNowAndThen() {
        assertNull("not at the start", CoverDrift.sweep(1f))
        val inSweep = CoverDrift.SWEEP_EVERY_S - CoverDrift.SWEEP_S / 2f
        assertNotNull(CoverDrift.sweep(inSweep))
        assertNull(CoverDrift.sweep(CoverDrift.SWEEP_EVERY_S + 1f))
        assertEquals(-.4f, CoverDrift.sweep(CoverDrift.SWEEP_EVERY_S - CoverDrift.SWEEP_S)!!, 1e-4f)
        assertTrue(CoverDrift.SWEEP_ALPHA <= .1f)
    }

    @Test fun theMotionFollowsTheSettings() {
        assertTrue(CoverDrift.enabled(living = true, reduced = false, quest = false))
        assertFalse("reduced motion", CoverDrift.enabled(living = true, reduced = true, quest = false))
        assertFalse("living artwork off", CoverDrift.enabled(living = false, reduced = false, quest = false))
        assertFalse("Quest", CoverDrift.enabled(living = true, reduced = false, quest = true))
        assertTrue(CoverDrift.light(performance = true, lite = false))
        assertTrue(CoverDrift.light(performance = false, lite = true))
        assertFalse(CoverDrift.light(performance = false, lite = false))
    }

    @Test fun theNewTitleComesFromItsSideOfTheQueue() {
        val queue = listOf("a", "b", "c")
        assertEquals(1, TrackChange.direction(queue, "a", "b"))
        assertEquals(-1, TrackChange.direction(queue, "c", "a"))
        assertEquals(0, TrackChange.direction(queue, "a", "x"))
        assertEquals(0, TrackChange.direction(queue, "b", "b"))
        assertTrue(TrackChange.EXIT_SCALE < 1f && TrackChange.ENTER_SCALE < 1f && TrackChange.SLIDE <= .1f)
    }

    @Test fun theBackgroundBreathesWithTheBassOnlyWhenAllowed() {
        assertEquals(0f, FlowBreath.amplitude(reduced = true, performance = false, lite = false))
        assertEquals(.5f, FlowBreath.amplitude(reduced = false, performance = true, lite = false))
        assertEquals(.5f, FlowBreath.amplitude(reduced = false, performance = false, lite = true))
        assertEquals(1f, FlowBreath.amplitude(reduced = false, performance = false, lite = false))
        assertEquals(0f, FlowBreath.energy(.1f))
        assertEquals(1f, FlowBreath.energy(1f))
        assertEquals(0f, FlowBreath.energy(Float.NaN))
        assertTrue(FlowBreath.SCALE <= .08f)
    }
}
