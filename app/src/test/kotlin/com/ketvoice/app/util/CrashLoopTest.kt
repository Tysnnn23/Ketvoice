package com.ketvoice.app.util

import org.junit.Assert.*
import org.junit.Test

class CrashLoopTest {
    @Test fun twoLaunchesEndedByACrashOpenSafeMode() {
        assertTrue(CrashLoop.needsSafeMode(2, newestReportAt = 1_500, previousLaunchAt = 1_000))
        assertTrue(CrashLoop.needsSafeMode(5, newestReportAt = 9_000, previousLaunchAt = 8_000))
    }

    @Test fun oneCrashAnOldReportOrAQuickCloseDoNot() {
        assertFalse("one launch only", CrashLoop.needsSafeMode(1, 1_500, 1_000))
        assertFalse("no report", CrashLoop.needsSafeMode(3, null, 1_000))
        assertFalse("the report is older than the last launch", CrashLoop.needsSafeMode(3, 500, 1_000))
    }

    @Test fun reportTimesComeFromTheirNames() {
        assertEquals(1763389200000L, CrashLoop.reportTime("crash-1763389200000.txt"))
        assertNull(CrashLoop.reportTime("notes.txt"))
    }
}
