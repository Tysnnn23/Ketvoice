package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class DownloadRangeTest {
    @Test fun initialPartialResponseCanStartAtZero() {
        assertTrue(DownloadValidation.validRange(0, 0, 65535, 1000000))
        assertTrue(DownloadValidation.validRange(65536, 65536, 999999, 1000000))
    }
    @Test fun wrongOffsetsTruncationAndMalformedRangesFailClosed() {
        assertFalse(DownloadValidation.validRange(65536, 0, 999999, 1000000))
        assertFalse(DownloadValidation.validRange(0, 0, 1000000, 1000000))
        assertFalse(DownloadValidation.validRange(0, null, null, null))
        assertFalse(DownloadValidation.complete(65536, 1000000))
    }
}
