package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class LyricsOffsetsTest {
    @Test fun advancingAndDelayingShiftOnlyTheDisplayClockAndSeekTarget() {
        assertEquals(2_100L, LyricsOffsets.adjustedPosition(2_000, 100))
        assertEquals(1_900L, LyricsOffsets.adjustedPosition(2_000, -100))
        assertEquals(1_900L, LyricsOffsets.seekPosition(2_000, 100))
        assertEquals(2_100L, LyricsOffsets.seekPosition(2_000, -100))
        assertEquals(0L, LyricsOffsets.seekPosition(50, 100))
    }
}
