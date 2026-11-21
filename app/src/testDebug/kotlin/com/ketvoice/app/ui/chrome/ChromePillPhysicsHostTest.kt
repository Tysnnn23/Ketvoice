package com.ketvoice.app.ui.chrome

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.GlassMotion
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.AppUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 8.3 « Pastille physique »: the tab bar's selection moved by hand-integrated springs ([PillPhysics]). It reaches its
 * tab and rests, its lift overshoots a little and no more, its squash never passes 12 %, reduced motion moves it
 * straight there, and on the bar it runs only while it moves (the frame clock goes idle once it rests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChromePillPhysicsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Trace(val maxX: Float, val maxLift: Float, val maxDeform: Float, val minStretchY: Float,
                        val settledAfterS: Float?, val physics: PillPhysics)

    private fun run(from: Float, to: Float, seconds: Float = 3f, fps: Float = 60f, held: Boolean = false): Trace {
        val p = PillPhysics(from)
        var maxX = from; var maxLift = 0f; var maxDeform = 0f; var minStretchY = 1f
        var settled: Float? = null
        var t = 0f
        while (t < seconds) {
            val moving = p.step(1f / fps, to, held, reduced = false)
            t += 1f / fps
            maxX = maxOf(maxX, p.x); maxLift = maxOf(maxLift, p.lift); maxDeform = maxOf(maxDeform, p.deform)
            minStretchY = minOf(minStretchY, p.stretchY)
            if (!moving && settled == null) settled = t
        }
        return Trace(maxX, maxLift, maxDeform, minStretchY, settled, p)
    }

    @Test fun travelConvergesOnItsTabAndRests() {
        for ((from, to) in listOf(0f to 2f, 2f to 0f, 0f to 1f, 1f to 2f)) {
            val trace = run(from, to)
            val settled = checkNotNull(trace.settledAfterS) { "never rested going $from -> $to" }
            assertTrue("rests within a second ($settled s)", settled < 1.2f)
            assertEquals(to, trace.physics.x, 0f)
            assertEquals(0f, trace.physics.lift, 0f)
            assertEquals(0f, trace.physics.deform, 0f)
            assertFalse(trace.physics.step(1f / 60f, to, held = false, reduced = false))
        }
        // A slow phone (30 fps) and a fast one (120 fps) reach the same rest.
        assertEquals(2f, run(0f, 2f, fps = 30f).physics.x, 0f)
        assertEquals(2f, run(0f, 2f, fps = 120f).physics.x, 0f)
    }

    @Test fun liftOvershootsSlightlyAndStaysBounded() {
        val trace = run(0f, 2f)
        // Under-damped: it rises past 1, by about one overshoot of its damping ratio, never more.
        assertTrue("overshoots (${trace.maxLift})", trace.maxLift > 1.02f)
        assertTrue("bounded (${trace.maxLift})", trace.maxLift <= 1f + GlassMotion.overshoot(PillPhysics.LIFT_DAMPING) + .02f)
        assertTrue(trace.maxLift <= PillPhysics.MAX_LIFT)
        // The travel itself barely passes its tab (damped just under critical).
        assertTrue("travel rebound ${trace.maxX - 2f}", trace.maxX - 2f < .01f)
        // Lifted, the pill is a few dp taller than the bar: at rest it is 10 dp shorter.
        val extra = TabPill.LIFT_HEIGHT_DP * trace.maxLift - 10f
        assertTrue("taller than the bar by $extra dp", extra in 2f..8f)
        // Sinking back, it never shrinks under the flat pill.
        val p = PillPhysics(0f)
        repeat(240) { p.step(1f / 60f, 1f, held = false, reduced = false); assertTrue(p.shownLift >= 0f) }
    }

    @Test fun squashAndStretchAreCappedAtTwelvePercent() {
        val trace = run(0f, 2f)
        assertTrue("stretches (${trace.maxDeform})", trace.maxDeform > .05f)
        assertTrue(trace.maxDeform <= PillPhysics.DEFORM_CAP + 1e-4f)
        assertTrue(trace.minStretchY >= 1f / (1f + PillPhysics.DEFORM_CAP) - 1e-4f)
        // A very long frame or a broken clock does not throw it off.
        val p = PillPhysics(0f)
        p.step(5f, 2f, held = false, reduced = false)
        p.step(Float.NaN, 2f, held = false, reduced = false)
        p.step(1f / 60f, Float.NaN, held = false, reduced = false)
        assertTrue(p.x.isFinite() && p.lift.isFinite() && p.deform <= PillPhysics.DEFORM_CAP + 1e-4f)
    }

    @Test fun reducedMotionMovesStraightThereWithoutLiftOrSquash() {
        val p = PillPhysics(0f)
        assertFalse(p.step(1f / 60f, 2f, held = false, reduced = true))
        assertEquals(2f, p.x, 0f)
        assertEquals(0f, p.lift, 0f)
        assertEquals(1f, p.stretchX, 0f)
        assertEquals(1f, p.stretchY, 0f)
        // Held under reduced motion: it follows the finger, flat.
        assertFalse(p.step(1f / 60f, 1.4f, held = true, reduced = true))
        assertEquals(1.4f, p.x, 0f)
        assertEquals(0f, p.shownLift, 0f)
    }

    @Test fun heldItStaysLiftedAsALensAndMagnifiesTheTabUnderIt() {
        val trace = run(1f, 1f, seconds = 1.5f, held = true)
        assertEquals(1f, trace.physics.lift, .02f)
        assertTrue(trace.settledAfterS == null)
        assertEquals(1f, TabPill.lensAlpha(trace.physics.shownLift), .01f)
        assertEquals(HeldLens.MAGNIFY, HeldLens.magnify(true, 1f, 1f, 1), 1e-5f)
        assertEquals(0f, HeldLens.magnify(true, 1f, 1f, 0), 1e-5f)
        // Travelling without a finger, the symbols are not magnified.
        assertEquals(0f, HeldLens.magnify(false, 1f, 1f, 1), 0f)
        // The lens hands over to the flat pill as the pill sinks.
        assertEquals(0f, TabPill.lensAlpha(0f), 0f)
        var last = 0f
        for (i in 0..20) { val a = TabPill.lensAlpha(i / 20f); assertTrue(a >= last); last = a }
    }

    @Before fun setup() {
        com.ketvoice.app.glass.GlassCompat.api = 35
        AppSettings.highPerformanceMode.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.liquidGlass.value = true
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        AppUi.selectTab(AppUi.HOME)
    }

    @After fun reset() {
        com.ketvoice.app.glass.GlassCompat.api = android.os.Build.VERSION.SDK_INT
        AppUi.selectTab(AppUi.HOME)
    }

    /** The lens is always composed on Android 13+ glass, and placed (drawn) only while the pill is up. */
    private fun lensShown(): Int = compose.onAllNodesWithTag("tab-pill-lens", useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false).count { it.layoutInfo.isPlaced }

    @Test fun onTheBarItLiftsAsALensWhileMovingAndGoesIdleAtRest() {
        compose.setContent { KetvoiceTheme(useDark = false) {
            val backdrop = rememberGlassBackdrop(Color.White)
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Color(0xFF2F6F8F))) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    Box(Modifier.align(Alignment.BottomCenter)) { GlassTabCapsule(collapsed = false) }
                }
            }
        } }
        compose.waitForIdle()
        assertEquals(0, lensShown())
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { AppUi.selectTab(AppUi.LIBRARY) }
        // Mid-flight (Android 13+ glass): the lifted pill bends the bar's glass.
        var shown = 0
        repeat(20) { compose.mainClock.advanceTimeByFrame(); shown = maxOf(shown, lensShown()) }
        assertEquals(1, shown)
        compose.mainClock.autoAdvance = true
        // At rest the flat pill alone remains, and nothing asks for frames any more (waitForIdle returns).
        compose.waitForIdle()
        assertEquals(0, lensShown())
    }
}
