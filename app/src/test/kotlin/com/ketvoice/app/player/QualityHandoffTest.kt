package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test

class QualityHandoffTest {
    @Test fun onlySameDurationRecordingsAwayFromTheEndCanSwitch() {
        assertTrue(QualityHandoff.compatible(180000, 180400, 60000))
        assertFalse(QualityHandoff.compatible(180000, 185000, 60000))
        assertFalse(QualityHandoff.compatible(180000, 180000, 179000))
        assertFalse(QualityHandoff.compatible(0, 180000, 0))
        assertFalse(QualityHandoff.compatible(180000, -1, 0))
    }
    @Test fun audibleHandoffRequiresTightPositionAlignment() {
        assertTrue(QualityHandoff.aligned(12000, 12040))
        assertFalse(QualityHandoff.aligned(12000, 12200))
    }
}
