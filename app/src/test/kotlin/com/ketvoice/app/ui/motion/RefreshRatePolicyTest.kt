package com.ketvoice.app.ui.motion

import org.junit.Assert.*
import org.junit.Test

class RefreshRatePolicyTest {
    private val current = RefreshMode(1, 1080, 2400, 60f)
    @Test fun selectsNative120IncludingFractionalPanelClocks() {
        assertEquals(2, RefreshRatePolicy.select(current, listOf(current, current.copy(id = 2, hz = 119.88f)))?.id)
    }
    @Test fun neverLowersResolutionToObtain120() {
        assertNull(RefreshRatePolicy.select(current, listOf(current, RefreshMode(2, 720, 1600, 120f))))
    }
    @Test fun doesNotPretend60Or90HzHardwareSupports120() {
        assertNull(RefreshRatePolicy.select(current, listOf(current, current.copy(id = 2, hz = 90f))))
    }
    @Test fun doesNotRequest144OrA24HzVideoModeInstead() {
        assertNull(RefreshRatePolicy.select(current, listOf(current.copy(hz = 144f), current.copy(hz = 24f))))
    }
    @Test fun requestsAreOptInForegroundOnlyAndRespectBatterySaver() {
        assertTrue(RefreshRatePolicy.mayRequest(true, true, false))
        assertFalse(RefreshRatePolicy.mayRequest(false, true, false))
        assertFalse(RefreshRatePolicy.mayRequest(true, false, false))
        assertFalse(RefreshRatePolicy.mayRequest(true, true, true))
    }
}
