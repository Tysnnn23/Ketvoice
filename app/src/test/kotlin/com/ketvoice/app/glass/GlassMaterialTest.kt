package com.ketvoice.app.glass

import org.junit.Assert.*
import org.junit.Test

class GlassMaterialTest {
    @Test fun clearControlsHaveAQuietCentreAndABoundedOpticalEdge() {
        for (dark in listOf(false, true)) {
            for (material in listOf(GlassParams.pill(dark = dark), GlassParams.circle(dark = dark),
                GlassParams.navigation(dark), GlassParams.selection(dark))) {
                assertEquals(GlassRole.Control, material.role)
                assertTrue(material.blurPx <= 8f)
                assertTrue(material.saturation <= 1.1f)
                val lens = ControlLens.forControl(48f, 1f, material.refractionHeightDp, material.refractionAmountDp)
                assertTrue(lens.heightPx <= 4f)
                assertTrue(lens.amountPx <= 7f)
            }
        }
    }

    @Test fun contentIsNotPromotedToOpticalGlass() {
        assertEquals(GlassRole.Panel, GlassParams.card(24f).role)
        assertEquals(GlassRole.Sheet, GlassParams.sheet(24f).role)
    }
}
