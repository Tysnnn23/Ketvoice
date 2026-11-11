package com.ketvoice.app.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassRenderModeTest {
    private fun mode(api: Int = 35, backdrop: Boolean = true, enabled: Boolean = true,
                     captured: Boolean = false, opaque: Boolean = false, reduceBlur: Boolean = false,
                     performance: Boolean = false, lowMemory: Boolean = false, reduced: Boolean = false) =
        glassRenderMode(api, backdrop, enabled, captured, opaque, reduceBlur, performance, lowMemory, reduced)

    @Test fun opticalRefractionRequiresApi33AndIndependentBackdrop() {
        assertEquals(GlassRenderMode.Blur, mode(api = 31))
        assertEquals(GlassRenderMode.Blur, mode(api = 32))
        assertEquals(GlassRenderMode.Optical, mode(api = 33))
        assertEquals(GlassRenderMode.Static, mode(backdrop = false))
        assertEquals(GlassRenderMode.Static, mode(captured = true))
    }

    @Test fun reducedMotionAndLowMemoryUseBoundedBlurWithoutAnimatedRefraction() {
        assertEquals(GlassRenderMode.Blur, mode(reduced = true))
        assertEquals(GlassRenderMode.Blur, mode(lowMemory = true))
        assertEquals(GlassRenderMode.Static, mode(performance = true))
        assertEquals(GlassRenderMode.Static, mode(reduceBlur = true))
        assertEquals(GlassRenderMode.Static, mode(opaque = true))
        assertEquals(GlassRenderMode.Static, mode(enabled = false))
    }
}
