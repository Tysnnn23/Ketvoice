package com.ketvoice.app.glass

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kyant.backdrop.backdrops.LayerBackdrop
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 8.3 « Verre plus fluide » and « Verre plus propre »: which fraction of its size a glass blurs what is behind it at
 * ([GlassResolution]), that the copy blurred small matches the full-size blur once enlarged, that a capture always has
 * an opaque floor, and when a menu may lie on a still copy of the page ([MovingBackdrops]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlassResolutionHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun reset() { MovingBackdrops.videos.intValue = 0 }

    @Test fun frostedGlassIsBlurredAtAThirdAndAQuarterInEconomy() {
        assertEquals(1f / 3f, GlassResolution.scale(14f, economy = false, api = 35), 1e-6f)
        assertEquals(.25f, GlassResolution.scale(14f, economy = true, api = 35), 1e-6f)
        // About 11 % of the pixels, 6 % in the performance mode.
        assertEquals(.111f, GlassResolution.NORMAL * GlassResolution.NORMAL, .001f)
        assertEquals(.0625f, GlassResolution.ECONOMY * GlassResolution.ECONOMY, 1e-6f)
        // Menus, sheets and alerts blur enough at every transparency to take the third.
        for (i in 0..10) {
            val level = i / 10f
            val sheet = Ket27Glass.regular(GlassParams.sheet(34f), level)
            assertEquals("level $level", GlassResolution.NORMAL, GlassResolution.scale(sheet.blurPx, false, 35), 1e-6f)
        }
    }

    @Test fun androidElevenAndUnblurredGlassKeepTheirFullSize() {
        assertEquals(1f, GlassResolution.scale(14f, economy = false, api = 30), 0f)
        assertEquals(1f, GlassResolution.scale(0f, economy = true, api = 35), 0f)
        assertEquals(1f, GlassResolution.scale(Float.NaN, economy = false, api = 35), 0f)
        assertEquals(1f, GlassResolution.scale(.3f, economy = true, api = 35), 0f)
    }

    @Test fun theFractionNeverGoesBelowWhatTheBlurHides() {
        // The clearest navigation glass barely blurs: it keeps more of its resolution, never a visible enlargement.
        val clear = GlassResolution.scale(liquid27BlurPx(0f, GlassRole.Control), economy = false, api = 35)
        assertTrue("clear glass at $clear", clear > GlassResolution.NORMAL && clear < 1f)
        var r = .25f
        while (r < 60f) {
            for (economy in listOf(false, true)) {
                val s = GlassResolution.scale(r, economy, 35)
                assertTrue(s >= (if (economy) GlassResolution.ECONOMY else GlassResolution.NORMAL) - 1e-6f && s <= 1f)
                if (s < 1f) assertTrue("radius $r", SoftBlur.sigma(r) * s >= GlassResolution.MIN_SMALL_SIGMA - 1e-4f)
            }
            r *= 1.3f
        }
    }

    @Test fun theSmallBlurMatchesTheFullSizeBlurOnceEnlarged() {
        for (r in listOf(5f, 14f, 34f)) for (s in listOf(GlassResolution.NORMAL, GlassResolution.ECONOMY)) {
            val small = GlassResolution.smallRadius(r, s)
            assertEquals("r $r s $s", SoftBlur.sigma(r), SoftBlur.sigma(small) / s, .01f)
        }
        assertEquals(0f, GlassResolution.smallRadius(0f, .5f), 0f)
        assertEquals(androidx.compose.ui.unit.IntSize(34, 21), GlassResolution.smallSize(100f, 61f, 1f / 3f))
        assertEquals(androidx.compose.ui.unit.IntSize(1, 1), GlassResolution.smallSize(0f, 0f, .25f))
    }

    @Test fun aCaptureAlwaysHasAnOpaqueFloor() {
        val base = Color(0xFF101014)
        assertEquals(1f, glassFloor(Color.Red.copy(alpha = .4f), base).alpha, 0f)
        assertEquals(1f, glassFloor(Color.White.copy(alpha = .9f), base.copy(alpha = .2f)).alpha, 0f)
        assertEquals(Color.Blue, glassFloor(Color.Blue, base))
        // Only a lens's own small capture asks for none.
        assertEquals(Color.Transparent, glassFloor(Color.Transparent, base))
    }

    @Test fun aMenuFreezesThePageUnlessSomethingMovesUnderIt() {
        var still: LayerBackdrop? = null
        var moving: LayerBackdrop? = null
        compose.setContent {
            still = rememberGlassBackdrop(Color.White)
            moving = rememberGlassBackdrop(Color.Black, moving = true)
        }
        compose.waitForIdle()
        assertTrue(MovingBackdrops.mayFreeze(still, api = 35, glass = true))
        assertFalse(MovingBackdrops.mayFreeze(moving, api = 35, glass = true))
        assertFalse("Android 11 keeps its live soft blur", MovingBackdrops.mayFreeze(still, api = 30, glass = true))
        assertFalse(MovingBackdrops.mayFreeze(still, api = 35, glass = false))
        assertFalse(MovingBackdrops.mayFreeze(null, api = 35, glass = true))
        // A Canvas playing anywhere keeps the glass live.
        MovingBackdrops.videos.intValue = 1
        assertFalse(MovingBackdrops.mayFreeze(still, api = 35, glass = true))
    }
}
