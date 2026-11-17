package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

/** The first-launch welcome (6.2) is for new installs only. */
class FirstRunTest {
    @Test fun aNewInstallHasNothingStored() {
        assertTrue(FirstRun.isFresh(0, 0, 0))
        assertFalse("settings from an earlier version", FirstRun.isFresh(12, 0, 0))
        assertFalse("a listening history", FirstRun.isFresh(0, 3, 0))
        assertFalse("downloads", FirstRun.isFresh(0, 0, 1))
    }

    @Test fun theDecisionIsTakenOnceAndKept() {
        assertEquals(FirstRun.PENDING, FirstRun.decide(null, fresh = true))
        assertEquals(FirstRun.DONE, FirstRun.decide(null, fresh = false))
        // Left halfway: still pending at the next launch, although settings were saved meanwhile.
        assertEquals(FirstRun.PENDING, FirstRun.decide(FirstRun.PENDING, fresh = false))
        assertEquals(FirstRun.DONE, FirstRun.decide(FirstRun.DONE, fresh = true))
        assertEquals("an unknown value counts as no decision", FirstRun.DONE, FirstRun.decide("?", fresh = false))
    }
}
