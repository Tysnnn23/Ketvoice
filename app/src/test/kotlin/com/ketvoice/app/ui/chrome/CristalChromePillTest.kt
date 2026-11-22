package com.ketvoice.app.ui.chrome

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The Cristal tab bar's selection (8.4) and its geometry, frame by frame at 60 Hz. */
class CristalChromePillTest {
    private class Trace(var maxLiftX: Float = 0f, var maxLiftY: Float = 0f, var minLiftX: Float = 0f, var maxDeviation: Float = 0f,
                        var maxPosition: Float = 0f, var settledAt: Double = -1.0, var materialAt100ms: Float = 0f,
                        var presenceAt100ms: Float = 0f, var liftDownBeforeRest: Boolean = false)

    private fun run(pill: CristalPillPhysics, seconds: Double, start: Double = 0.0, trace: Trace = Trace()): Trace {
        var t = start
        var landed = false
        while (t < start + seconds) {
            val dt = 1.0 / 60.0
            t += dt
            val moving = pill.step(dt, t)
            trace.maxLiftX = maxOf(trace.maxLiftX, pill.liftX)
            trace.maxLiftY = maxOf(trace.maxLiftY, pill.liftY)
            trace.maxDeviation = maxOf(trace.maxDeviation, abs(pill.deviation))
            trace.maxPosition = maxOf(trace.maxPosition, pill.position)
            if (t - start in .095..0.11) { trace.materialAt100ms = pill.material; trace.presenceAt100ms = pill.glassPresence }
            if (pill.position >= .92f && !landed) landed = true
            if (landed) trace.minLiftX = minOf(trace.minLiftX, pill.liftX)
            if (landed && pill.liftX < trace.maxLiftX - .05f && abs(pill.position - pill.index) > 1e-4f) trace.liftDownBeforeRest = true
            if (!moving && trace.settledAt < 0) trace.settledAt = t - start
        }
        return trace
    }

    @Test fun aTapCarriesThePillToItsTabAndItComesToRestFlat() {
        val pill = CristalPillPhysics(0).apply { stepDp = 78f }
        pill.select(1)
        assertFalse(pill.isFlat)
        val trace = run(pill, 3.0)
        assertTrue("settles within 1.5 s, not ${trace.settledAt}", trace.settledAt in 0.2..1.5)
        assertEquals(1f, pill.position, 0f)
        assertTrue(pill.isFlat)
        assertEquals(0f, pill.liftX, 0f); assertEquals(0f, pill.liftY, 0f)
        assertEquals(0f, pill.deviation, 0f); assertEquals(0f, pill.glassPresence, 0f)
        // Travel is damped just under critical (31.4 against 33.5): hardly any overshoot.
        assertTrue("position overshoot ${trace.maxPosition}", trace.maxPosition < 1.01f)
    }

    @Test fun theLiftOvershootsOnBothAxesWidthMoreAndDipsOnLanding() {
        val pill = CristalPillPhysics(0).apply { stepDp = 78f }
        pill.select(2)
        val trace = run(pill, 3.0)
        assertTrue("width overshoots (ratio 0.6): ${trace.maxLiftX}", trace.maxLiftX > 1.05f)
        assertTrue("height overshoots (ratio 0.7): ${trace.maxLiftY}", trace.maxLiftY > 1.01f)
        assertTrue("the width a shade further", trace.maxLiftX > trace.maxLiftY)
        assertTrue("bounded", trace.maxLiftX < 1.2f)
        assertTrue("it deflates past its resting size: ${trace.minLiftX}", trace.minLiftX < 0f)
        assertTrue("the lift is let go from 92 % of the way", trace.liftDownBeforeRest)
    }

    @Test fun theSquashNeverPassesTwelvePercent() {
        val pill = CristalPillPhysics(0).apply { stepDp = 120f }
        pill.select(2)
        val trace = run(pill, 3.0)
        assertTrue("some squash on the way: ${trace.maxDeviation}", trace.maxDeviation > .005f)
        assertTrue("never past 12 %: ${trace.maxDeviation}", trace.maxDeviation <= .12f + 1e-6f)
        // A flick held by a finger: the raw force is clamped too.
        val held = CristalPillPhysics(0).apply { stepDp = 400f }
        held.grab()
        var t = 0.0
        var worst = 0f
        repeat(120) { i ->
            held.hold(if (i % 10 < 5) 2f else 0f)
            t += 1.0 / 60.0
            held.step(1.0 / 60.0, t)
            worst = maxOf(worst, abs(held.deviation))
        }
        assertTrue("held: $worst", worst <= .12f + 1e-6f)
    }

    @Test fun theGlassComesOnFastAndLeavesFromTheLanding() {
        val pill = CristalPillPhysics(0).apply { stepDp = 78f }
        pill.select(1)
        val trace = run(pill, 3.0)
        assertTrue("material within 0.1 s: ${trace.materialAt100ms}", trace.materialAt100ms > .8f)
        assertTrue("glass presence within 0.1 s: ${trace.presenceAt100ms}", trace.presenceAt100ms > .8f)
        assertEquals(0f, CristalPillPhysics.rim(.45f), 0f)
        assertEquals(1f, CristalPillPhysics.rim(1f), 0f)
        assertEquals(.5f, CristalPillPhysics.rim(.725f), 1e-4f)
    }

    @Test fun aFingerHoldsItAndItSettlesWhereItIsLetGo() {
        val pill = CristalPillPhysics(0).apply { stepDp = 78f }
        pill.grab()
        pill.hold(1.6f)
        var t = 0.0
        repeat(3) { t += 1.0 / 60.0; pill.step(1.0 / 60.0, t) }
        // It chases the finger with a 50 ms time constant: about 63 % after 50 ms.
        assertEquals(1.6f * .63f, pill.position, .12f)
        repeat(60) { t += 1.0 / 60.0; pill.step(1.0 / 60.0, t) }
        assertEquals(1.6f, pill.position, .01f)
        assertTrue("held, it stays lifted", pill.liftX > .9f)
        pill.letGo(2)
        run(pill, 3.0, t)
        assertEquals(2f, pill.position, 0f)
        assertTrue(pill.isFlat)
    }

    @Test fun reducedMotionLandsAtOnce() {
        val pill = CristalPillPhysics(0).apply { reduced = true }
        pill.select(2)
        assertEquals(2f, pill.position, 0f)
        assertTrue(pill.isFlat)
        assertFalse(pill.step(1.0 / 60.0, .016))
    }

    @Test fun theLiftedPillIsNineDpTallerThanTheWholeBar() {
        val rest = Size(78.5f, 54f)
        val lifted = CristalPillPhysics.liftedSize(rest, 4f, 4f, 9f)
        assertEquals(71f, lifted.height, 1e-4f)
        assertEquals(71f * 78.5f / 54f, lifted.width, 1e-3f)
        val squashed = CristalPillPhysics.sizeOf(rest, lifted, 1f, 1f, .1f)
        assertEquals(lifted.width * 1.1f, squashed.width, 1e-3f)
        assertEquals(lifted.height * .9f, squashed.height, 1e-3f)
        assertEquals(280.0, CristalPillPhysics.TRAVEL_STIFFNESS, 0.0); assertEquals(31.4, CristalPillPhysics.TRAVEL_DAMPING, 0.0)
        assertEquals(1000.0, CristalPillPhysics.MATERIAL_STIFFNESS, 0.0); assertEquals(63.3, CristalPillPhysics.MATERIAL_DAMPING, 0.0)
        assertEquals(.92, CristalPillPhysics.HANDOVER_START, 0.0)
    }

    @Test fun theBarsTwoLayoutsAndTheFoldBetweenThem() {
        val expanded = CristalBar.expanded(400f, track = true)
        assertEquals(118f, expanded.height, 0f)
        assertEquals(CristalBar.Place(19f, 0f, 362f, 62f), expanded.tabs)
        assertEquals(CristalBar.Place(19f, 70f, 362f, 48f), expanded.accessory)
        val inline = CristalBar.inline(400f, track = true)
        assertEquals(55f, inline.height, 0f)
        assertEquals(CristalBar.Place(28f, 7f, 48f, 48f), inline.tabs)
        assertEquals(CristalBar.Place(90f, 7f, 220f, 48f), inline.accessory)
        assertEquals(CristalBar.Place(324f, 7f, 48f, 48f), inline.search)
        // Nothing playing: the two circles, centred.
        val alone = CristalBar.inline(400f, track = false)
        assertEquals(400f - (alone.search.x + 48f), alone.tabs.x, 1e-3f)
        // Half-way, every piece is half-way.
        val half = CristalBar.frame(400f, true, .5f, 0f)
        assertEquals((19f + 28f) / 2f, half.tabs.x, 1e-3f)
        assertEquals((118f + 55f) / 2f, half.height, 1e-3f)
        // The search field spans from after the tab circle to the margin.
        val field = CristalBar.frame(400f, true, 0f, 1f).search
        assertEquals(90f, field.x, 1e-3f); assertEquals(400f - 28f - 90f, field.w, 1e-3f)
        // The selected glyph starts in its tab and ends in the middle of the circle.
        val glyph0 = CristalBar.glyphCenter(400f, true, 1, 0f)
        assertEquals(19f + 4f + (362f - 8f) / 4f * 1.5f, glyph0.x, 1e-3f)
        assertEquals(62f - 4f - 6.5f - 14f, glyph0.y, 1e-3f)
        val glyph1 = CristalBar.glyphCenter(400f, true, 1, 1f)
        assertEquals(52f, glyph1.x, 1e-3f); assertEquals(31f, glyph1.y, 1e-3f)
        // The capsule's height: 4 + 6.5 + 28 + 1 + 12 + 6.5 + 4.
        assertEquals(CristalBar.HEIGHT, CristalBar.INSET * 2 + CristalBar.TAB_PADDING * 2 + CristalBar.ICON + CristalBar.ICON_GAP +
            CristalBar.LABEL_LINE_SP, 1e-4f)
    }

    @Test fun tabsComeBackOutOfABlurInTheSecondHalfOf150Ms() {
        assertEquals(0f, CristalBar.enter(0f).first, 0f); assertEquals(12f, CristalBar.enter(0f).second, 0f)
        assertEquals(0f, CristalBar.enter(75f).first, 1e-4f)
        assertEquals(1f, CristalBar.enter(120f).first, 1e-4f); assertEquals(0f, CristalBar.enter(120f).second, 1e-4f)
        assertEquals(1f, CristalBar.enter(150f).first, 0f)
        val mid = CristalBar.enter(97.5f)
        assertTrue(mid.first in .05f..0.95f && mid.second in .5f..11.5f)
    }
}
