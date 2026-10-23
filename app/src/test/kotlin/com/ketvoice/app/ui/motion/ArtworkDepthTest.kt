package com.ketvoice.app.ui.motion

import org.junit.Assert.*
import org.junit.Test

class ArtworkDepthTest {
    @Test fun motionIsNeutralAtCenterAndBoundedAtEdges() {
        assertEquals(0f, ArtworkDepth.axis(100f, 200))
        assertEquals(-1f, ArtworkDepth.axis(-100f, 200))
        assertEquals(1f, ArtworkDepth.axis(999f, 200))
    }
    @Test fun incompleteLayoutOrInvalidInputNeverCreatesAnInvalidTransform() {
        assertEquals(0f, ArtworkDepth.axis(1f, 0))
        assertEquals(0f, ArtworkDepth.axis(Float.NaN, 200))
        assertEquals(0f, ArtworkDepth.axis(Float.POSITIVE_INFINITY, 200))
    }
}
