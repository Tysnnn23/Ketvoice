package com.ketvoice.app.glass

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cristal (8.4): which material a surface gets, and the numbers it is drawn with. */
class CristalGlassTest {
    private fun path(api: Int = 35, backdrop: Boolean = true, enabled: Boolean = true, captured: Boolean = false,
                     reduceBlur: Boolean = false) = cristalPath(api, backdrop, enabled, captured, reduceBlur)

    @Test fun theLensNeedsAndroid13AndEveryOlderAndroidKeepsALiveBlur() {
        assertEquals(CristalPath.Optical, path(api = 33))
        assertEquals(CristalPath.Optical, path(api = 36))
        assertEquals(CristalPath.Blur, path(api = Build.VERSION_CODES.S))
        assertEquals(CristalPath.Blur, path(api = 32))
        // Ketvoice Lite on Android 11: never a flat panel, SoftBlur blurs the page.
        assertEquals(CristalPath.Soft, path(api = 30))
    }

    @Test fun reducedBlurIsOpaqueAndNoLivePageIsTinted() {
        assertEquals(CristalPath.Solid, path(reduceBlur = true))
        assertEquals(CristalPath.Solid, path(api = 30, reduceBlur = true))
        assertEquals(CristalPath.Tinted, path(backdrop = false))
        assertEquals(CristalPath.Tinted, path(enabled = false))
        assertEquals(CristalPath.Tinted, path(captured = true))
    }

    @Test fun thePerformanceModeDropsTheLensAndFrostsWider() {
        val full = cristalSpec(CristalPath.Optical, economy = false)
        assertEquals(8f, full.blurDp, 0f); assertTrue(full.lens)
        val economy = cristalSpec(CristalPath.Optical, economy = true)
        assertEquals(16f, economy.blurDp, 0f); assertFalse(economy.lens)
        assertFalse("no lens before Android 13", cristalSpec(CristalPath.Blur, economy = false).lens)
        assertEquals(8f, cristalSpec(CristalPath.Blur, economy = false).blurDp, 0f)
    }

    @Test fun thePageIsSampledAtAThirdOrAQuarter() {
        assertEquals(.33f, Cristal.scale(22f, economy = false, api = 35), 0f)
        assertEquals(.25f, Cristal.scale(44f, economy = true, api = 35), 0f)
        assertEquals(.25f, Cristal.menuScale(66f, api = 35), 0f)
        assertEquals("Android 11 blurs with SoftBlur at full size", 1f, Cristal.scale(22f, economy = false, api = 30), 0f)
        assertEquals("nothing blurred, nothing reduced", 1f, Cristal.scale(0f, economy = false, api = 35), 0f)
    }

    @Test fun theMaterialNumbers() {
        assertEquals(1.5f, Cristal.SATURATION, 0f)
        assertEquals(24f, Cristal.LENS_HEIGHT_DP, 0f); assertEquals(24f, Cristal.LENS_AMOUNT_DP, 0f)
        assertEquals(.4f, Cristal.tint(true).alpha, 3e-3f)
        assertEquals(0xFF121212.toInt(), Cristal.solid(true).value.shr(32).toInt())
        assertEquals(0xFFFAFAFA.toInt(), Cristal.solid(false).value.shr(32).toInt())
        assertEquals(.10f, Cristal.EDGE.alpha, 3e-3f); assertEquals(.5f, Cristal.EDGE_WIDTH.value, 0f)
        // Menus: frost.
        assertEquals(1.8f, Cristal.MENU_SATURATION, 0f); assertEquals(24f, Cristal.MENU_BLUR_DP, 0f)
        assertEquals(.60f, Cristal.menuFill(dark = true, frosted = true).alpha, 3e-3f)
        assertEquals(.72f, Cristal.menuFill(dark = false, frosted = true).alpha, 3e-3f)
        assertEquals("opaque without frost", 1f, Cristal.menuFill(dark = true, frosted = false).alpha, 0f)
        assertEquals(24f, Cristal.MENU_SHADOW.value, 0f); assertEquals(.30f, Cristal.MENU_SHADOW_ALPHA, 0f)
        // The selection.
        assertEquals(.5f, Cristal.indicator(false).alpha, 3e-3f); assertEquals(.14f, Cristal.indicator(true).alpha, 3e-3f)
        assertEquals(12f, Cristal.PILL_SHADOW.radius.value, 0f); assertEquals(2f, Cristal.PILL_SHADOW.offset.y.value, 0f)
        assertEquals(.3f, Cristal.PILL_SHADOW.color.alpha, 3e-3f)
    }

    @Test fun theLookIsANewKeyAndLiquid27StaysTheDefault() {
        val previous = com.ketvoice.app.data.AppSettings.glassLook.value
        try {
            com.ketvoice.app.data.AppSettings.glassLook.value = 0
            assertFalse(GlassLook.cristal)
            com.ketvoice.app.data.AppSettings.glassLook.value = GlassLook.CRISTAL
            assertTrue(GlassLook.cristal)
            assertEquals(listOf("Liquid 27", "Cristal"), GlassLook.labels)
            assertEquals(1, GlassLook.MAX)
        } finally { com.ketvoice.app.data.AppSettings.glassLook.value = previous }
    }
}
