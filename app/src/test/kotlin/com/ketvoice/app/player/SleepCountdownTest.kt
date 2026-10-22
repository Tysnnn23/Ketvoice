package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test

class SleepCountdownTest {
    @Test fun neverRoundsDownToEarlyPause() {
        assertEquals(2L, SleepCountdown.remainingSeconds(2001, 1000))
        assertEquals(1L, SleepCountdown.remainingSeconds(2000, 1000))
        assertEquals(1L, SleepCountdown.remainingSeconds(1001, 1000))
    }
    @Test fun expiresAtTheDeadlineAndNeverBecomesNegative() {
        assertEquals(0L, SleepCountdown.remainingSeconds(1000, 1000))
        assertEquals(0L, SleepCountdown.remainingSeconds(1000, 2000))
    }
}
