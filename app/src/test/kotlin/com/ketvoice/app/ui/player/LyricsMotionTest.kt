package com.ketvoice.app.ui.player

import org.junit.Assert.*
import org.junit.Test

class LyricsMotionTest {
    @Test fun introOnlyExistsBeforeARealFirstTimestamp() {
        assertFalse(LyricsMotion.showIntro(0,0))
        assertTrue(LyricsMotion.showIntro(0,9000))
        assertFalse(LyricsMotion.showIntro(9000,9000))
        assertFalse(LyricsMotion.showIntro(12000,9000))
    }
    @Test fun onlyTheNumberOfIlluminatedDotsChanges() {
        assertEquals(0,LyricsMotion.litDots(0,9000))
        assertEquals(1,LyricsMotion.litDots(3000,9000))
        assertEquals(2,LyricsMotion.litDots(6000,9000))
        assertEquals(0,LyricsMotion.litDots(-100,9000))
        assertEquals(3,LyricsMotion.litDots(999999,9000))
        assertEquals(3,LyricsMotion.litDots(8999,9000))
        assertEquals(2,LyricsMotion.litDots(4500,9000))
        assertEquals(1,LyricsMotion.litDots(2250,9000))
    }
    @Test fun introItemDoesNotShiftTheActiveLyricToTheWrongLine() {
        assertEquals(0,LyricsMotion.targetIndex(-1,true))
        assertEquals(1,LyricsMotion.targetIndex(0,true))
        assertEquals(6,LyricsMotion.targetIndex(5,true))
        assertEquals(5,LyricsMotion.targetIndex(5,false))
    }
}
