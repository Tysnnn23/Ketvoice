package com.ketvoice.app.glass

import org.junit.Assert.*
import org.junit.Test

class ControlLensTest {
    @Test fun recipeUsesDensityIndependentDimensions() {
        assertEquals(ControlLens(30f, 60f), ControlLens.forControl(180f, 3f, 10f, 20f))
    }
    @Test fun tinyThumbNeverSamplesAnUnboundedDisplacement() {
        val lens = ControlLens.forControl(24f, 3f, 14f, 32f)
        assertEquals(10.8f, lens.heightPx, .001f)
        assertEquals(19.2f, lens.amountPx, .001f)
    }
    @Test fun invalidGeometryAndNonFiniteParametersAreSafe() {
        for (edge in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(ControlLens(0f, 0f), ControlLens.forControl(edge, 3f, 10f, 20f))
        }
        assertEquals(ControlLens(0f, 0f), ControlLens.forControl(48f, 1f, Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(ControlLens(0f, 0f), ControlLens.forControl(48f, 0f, 10f, 20f))
    }
    @Test fun disabledLensHasNoDisplacement() {
        assertEquals(ControlLens(0f, 0f), ControlLens.forControl(48f, 1f, -1f, 0f))
    }
}
