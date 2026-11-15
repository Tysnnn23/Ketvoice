package com.ketvoice.app.ui.chrome

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.dp

class DropLensTest {
    @Test fun atRestTheLensIsExactlyOneSlot() {
        assertEquals(DropLens(2f, 1f, 1f), dropLens(2f, 2f))
    }

    @Test fun inFlightItSpansFromTheSlowEdgeToTheFastEdge() {
        val right = dropLens(lead = 1.6f, trail = .4f)
        assertEquals(.4f, right.start, 1e-4f)
        assertEquals(2.2f, right.span, 1e-4f)
        assertTrue(right.squash < 1f && right.squash >= .92f)
        val left = dropLens(lead = .4f, trail = 1.6f)
        assertEquals(right, left)
    }

    @Test fun stretchIsBoundedAndInvalidValuesAreHarmless() {
        assertEquals(2.2f, dropLens(0f, 5f).span, 1e-4f)
        assertEquals(DropLens(0f, 1f, 1f), dropLens(Float.NaN, Float.NaN))
    }

    @Test fun aHeldSelectionSwellsIntoALensAndSettlesBack() {
        assertEquals(HeldLens.Grow(0.dp, 0.dp), HeldLens.grow(0f))
        assertEquals(16f, HeldLens.grow(1f).width.value, 1e-4f); assertEquals(8f, HeldLens.grow(1f).height.value, 1e-4f)
        // The overshoot of the spring swells it a little further, never without bound.
        assertTrue(HeldLens.grow(1.2f).width > HeldLens.grow(1f).width)
        assertEquals(HeldLens.grow(1.25f), HeldLens.grow(3f))
        assertEquals(HeldLens.grow(0f), HeldLens.grow(Float.NaN))
        assertTrue("a spring that overshoots", HeldLens.DAMPING < 1f)
    }
}
