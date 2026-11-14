package com.ketvoice.app.ui

import com.ketvoice.app.ui.player.MixVisuals
import org.junit.Assert.*
import org.junit.Test

class MixVisualsTest {
    @Test fun progressRunsFromTheFirstHeardMomentToTheEndOfTheOverlap() {
        assertEquals(0f, MixVisuals.progress(900, 1_000, 10_000), 0f)
        assertEquals(.25f, MixVisuals.progress(3_500, 1_000, 10_000), 1e-6f)
        assertEquals(1f, MixVisuals.progress(99_000, 1_000, 10_000), 0f)
        assertEquals(0f, MixVisuals.progress(5, 0, 0), 0f)
    }

    @Test fun theCoversDissolveIntoEachOtherWithoutAJumpAtTheHandOver() {
        assertEquals(0f, MixVisuals.overlayAlpha(0f, promoted = false), 0f)
        assertEquals(.5f, MixVisuals.overlayAlpha(MixVisuals.HANDOVER, promoted = false), 1e-6f)
        // The covers swap places at the hand-over: half over half is the same picture.
        assertEquals(.5f, MixVisuals.overlayAlpha(MixVisuals.HANDOVER, promoted = true), 1e-6f)
        assertEquals(0f, MixVisuals.overlayAlpha(1f, promoted = true), 1e-6f)
        var last = 0f
        for (i in 0..55) { val a = MixVisuals.overlayAlpha(i / 100f, false); assertTrue(a >= last); last = a }
        for (i in 55..100) { val a = MixVisuals.overlayAlpha(i / 100f, true); assertTrue(a <= last + 1e-6f); last = a }
        // A hand-over that comes a little early or late stays at one half, never above.
        assertEquals(.5f, MixVisuals.overlayAlpha(.7f, promoted = false), 0f)
        assertEquals(.5f, MixVisuals.overlayAlpha(.4f, promoted = true), 0f)
    }

    @Test fun theIncomingCoverComesFromTheRightAndTheOutgoingLeavesToTheLeft() {
        assertEquals(.10f, MixVisuals.overlayShift(0f, false), 1e-6f)
        assertEquals(0f, MixVisuals.overlayShift(MixVisuals.HANDOVER, false), 1e-6f)
        assertEquals(0f, MixVisuals.overlayShift(MixVisuals.HANDOVER, true), 1e-6f)
        assertEquals(-.10f, MixVisuals.overlayShift(1f, true), 1e-6f)
    }

    @Test fun theBadgeBreathesOnTheBeat() {
        assertEquals(1f, MixVisuals.pulse(0, 120f), 1e-4f)
        assertEquals(0f, MixVisuals.pulse(250, 120f), 1e-4f)
        assertEquals(1f, MixVisuals.pulse(500, 120f), 1e-4f)
        assertEquals("Steady without a tempo", .5f, MixVisuals.pulse(123, 0f), 0f)
        for (t in 0L..2_000L step 37) assertTrue(MixVisuals.pulse(t, 97.3f) in 0f..1f)
    }

    @Test fun theRingsMergeAtTheHandOverAndPartAgain() {
        assertEquals(.42f, MixVisuals.ringOverlap(null), 0f)
        assertEquals(.92f, MixVisuals.ringOverlap(MixVisuals.HANDOVER), 1e-6f)
        assertEquals(.42f, MixVisuals.ringOverlap(0f), 1e-6f)
        assertTrue(MixVisuals.ringOverlap(1f) < MixVisuals.ringOverlap(.6f))
    }
}
