package com.ketvoice.app.glass

import org.junit.Assert.*
import org.junit.Test

class GlassTuningTest {
    @Test fun inputIsClampedAndNonFiniteValuesAreSafe() {
        assertEquals(0f, GlassTuning.sanitize(Float.NaN), 0f)
        assertEquals(0f, GlassTuning.sanitize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, GlassTuning.sanitize(-2f), 0f)
        assertEquals(1f, GlassTuning.sanitize(2f), 0f)
    }

    @Test fun tintChangesContinuouslyWithoutReducingFallbackContrast() {
        for (dark in listOf(false, true)) {
            val optical = (0..100).map { GlassTuning.opticalTint(.05f, it / 100f, dark) }
            val fallback = (0..100).map { GlassTuning.fallbackTint(it / 100f, dark) }
            assertTrue(optical.zipWithNext().all { (a, b) -> b > a })
            assertTrue(fallback.zipWithNext().all { (a, b) -> b > a })
            assertTrue(fallback.last() < 1f)
        }
    }
}
