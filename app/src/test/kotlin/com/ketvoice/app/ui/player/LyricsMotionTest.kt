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
    @Test fun theDotsFillOneAfterTheOtherWithoutSteps() {
        val first = 9000L
        assertEquals(0f, LyricsMotion.dotFill(0, 0, first), 0f)
        assertEquals(0f, LyricsMotion.dotFill(2, 0, first), 0f)
        // A third of the way: the first dot is full, the second one has not started.
        val third = (first - LyricsMotion.exitMs(first)) / 3
        assertEquals(1f, LyricsMotion.dotFill(0, third, first), .001f)
        assertEquals(0f, LyricsMotion.dotFill(1, third, first), .001f)
        // Each dot only ever gets fuller, and by small amounts from one frame to the next.
        for (index in 0..2) {
            var before = 0f
            for (ms in 0..first step 16) {
                val now = LyricsMotion.dotFill(index, ms, first)
                assertTrue("dot $index at $ms", now >= before && now - before < .03f)
                before = now
            }
        }
        // All three are full when they start leaving.
        val leaving = first - LyricsMotion.exitMs(first)
        for (index in 0..2) assertEquals(1f, LyricsMotion.dotFill(index, leaving, first), .001f)
        assertEquals(1f, LyricsMotion.dotFill(1, 500, 0), 0f)
    }
    @Test fun theDotsSwellThenVanishAsTheFirstLineArrives() {
        val first = 9000L
        assertEquals(720L, LyricsMotion.exitMs(first))
        assertEquals(250L, LyricsMotion.exitMs(1000))
        assertEquals(0f, LyricsMotion.dotExit(first - 721, first), 0f)
        assertEquals(1f, LyricsMotion.dotExit(first, first), 0f)
        assertEquals(1f, LyricsMotion.exitScale(0f), 0f)
        assertTrue(LyricsMotion.exitScale(.42f) > 1.2f)
        assertTrue(LyricsMotion.exitScale(1f) < .55f)
        assertEquals(1f, LyricsMotion.exitAlpha(.4f), 0f)
        assertEquals(0f, LyricsMotion.exitAlpha(1f), 0f)
        var before = 1f
        for (i in 42..100) { val now = LyricsMotion.exitAlpha(i / 100f); assertTrue(now <= before); before = now }
    }
    @Test fun theBreathTravelsFromTheFirstDotToTheLast() {
        for (ms in 0..4000L step 50) for (index in 0..2) {
            val breath = LyricsMotion.breath(ms, index)
            assertTrue(breath in 1f..1.0901f)
        }
        // The second dot is where the first one was 170 ms earlier.
        assertEquals(LyricsMotion.breath(600, 0), LyricsMotion.breath(770, 1), .0001f)
        assertEquals(0f, LyricsMotion.enter(0), 0f)
        assertEquals(1f, LyricsMotion.enter(340), 0f)
    }
    @Test fun introItemDoesNotShiftTheActiveLyricToTheWrongLine() {
        assertEquals(0,LyricsMotion.targetIndex(-1,true))
        assertEquals(1,LyricsMotion.targetIndex(0,true))
        assertEquals(6,LyricsMotion.targetIndex(5,true))
        assertEquals(5,LyricsMotion.targetIndex(5,false))
    }
}
