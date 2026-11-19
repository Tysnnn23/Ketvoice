package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class CanvasCompactTest {
    private val hour = 3_600_000L
    private val now = 10 * hour

    private fun source(name: String, mb: Double, w: Int = 1080, h: Int = 1920, seconds: Double = 8.0, kept: Boolean = false,
                       ageMs: Long = hour) =
        CanvasCompact.Source(name, (mb * 1_000_000).toLong(), now - ageMs, w, h, (seconds * 1_000_000).toLong(), 30, kept)

    @Test fun aBigCustomCanvasIsBroughtToTheScreenAndAKeptBitrate() {
        // A 4K phone video of 8 s at ~40 Mb/s, on a 1080×2400 screen.
        val plan = CanvasCompact.plan(source("kvlink-a.mp4", 40.0, 2160, 3840), now, longSide = 2400.coerceAtMost(1920), hevc = true, done = false)!!
        assertEquals(1080, plan.width); assertEquals(1920, plan.height)
        // HEVC at 0.07 bit per pixel and frame: about 4.4 Mb/s, ~4.4 MB for 8 s instead of 40.
        assertEquals(4_354_560, plan.bitrate)
        assertTrue(plan.expectedBytes in 4_000_000L..4_500_000L)
        assertTrue(plan.hevc)
    }

    @Test fun aNormalCanvasIsLeftAsItIs() {
        // A catalogue Canvas: 720×1280, 8 s, 2.5 MB (2.5 Mb/s) is already near what it needs.
        assertNull(CanvasCompact.plan(source("abc.mp4", 2.5, 720, 1280), now, 1920, hevc = true, done = false))
        assertNull(CanvasCompact.plan(source("small.mp4", 1.2), now, 1920, hevc = true, done = false))
    }

    @Test fun withoutHevcTheBitrateIsHigherAndTheGainMustStillBeClear() {
        val avc = CanvasCompact.plan(source("big.mp4", 12.0), now, 1920, hevc = false, done = false)!!
        assertFalse(avc.hevc)
        assertEquals(6_220_800, avc.bitrate)
        // 6 MB from 12: kept only if clearly smaller; 7 MB from 9 is not worth it.
        assertNull(CanvasCompact.plan(source("mid.mp4", 7.0), now, 1920, hevc = false, done = false))
    }

    @Test fun freshDoneOrUnreadFilesWait() {
        assertNull(CanvasCompact.plan(source("new.mp4", 40.0, ageMs = 30_000), now, 1920, hevc = true, done = false))
        assertNull(CanvasCompact.plan(source("done.mp4", 40.0), now, 1920, hevc = true, done = true))
        assertNull(CanvasCompact.plan(source("bad.mp4", 40.0, w = 0, h = 0), now, 1920, hevc = true, done = false))
    }

    @Test fun neverLargerThanTheSourceAndAlwaysEven() {
        assertEquals(720 to 1280, CanvasCompact.size(720, 1280, 1920))
        assertEquals(1080 to 1920, CanvasCompact.size(2160, 3840, 1920))
        assertEquals(720 to 1280, CanvasCompact.size(1081, 1921, 1280))
        assertEquals(1920 to 1080, CanvasCompact.size(3840, 2160, 1920))
    }

    @Test fun filesDownloadsNeedComeFirstThenTheBiggestGain() {
        val a = source("a.mp4", 40.0, 2160, 3840)
        val b = source("b.mp4", 20.0, 2160, 3840, kept = true)
        val c = source("c.mp4", 60.0, 2160, 3840)
        val plans = listOf(a, b, c).map { it to CanvasCompact.plan(it, now, 1920, true, false)!! }
        assertEquals(listOf("b.mp4", "c.mp4", "a.mp4"), CanvasCompact.order(plans).map { it.name })
        val many = (1..20).map { source("f$it.mp4", 40.0, 2160, 3840) }.map { it to CanvasCompact.plan(it, now, 1920, true, false)!! }
        assertEquals(CanvasCompact.PER_PASS, CanvasCompact.order(many).size)
    }

    @Test fun theCopyReplacesTheOriginalOnlyWhenCheckedOtherwiseTheOriginalStays() {
        val plan = CanvasCompact.plan(source("x.mp4", 40.0, 2160, 3840), now, 1920, true, false)!!
        val dur = 8_000_000L
        assertTrue(CanvasCompact.accept(40_000_000, dur, 4_400_000, dur + 30_000, 1080, 1920, plan))
        // Truncated, the wrong size, not smaller, or empty: refused.
        assertFalse(CanvasCompact.accept(40_000_000, dur, 2_000_000, 4_000_000, 1080, 1920, plan))
        assertFalse(CanvasCompact.accept(40_000_000, dur, 4_400_000, dur, 720, 1280, plan))
        assertFalse(CanvasCompact.accept(40_000_000, dur, 39_000_000, dur, 1080, 1920, plan))
        assertFalse(CanvasCompact.accept(40_000_000, dur, 5_000, dur, 1080, 1920, plan))
    }

    @Test fun thePassNeedsRoomForTheCopyAndRemembersEachFileByItsSize() {
        val plan = CanvasCompact.plan(source("x.mp4", 40.0, 2160, 3840), now, 1920, true, false)!!
        assertFalse(CanvasCompact.hasRoom(70L * 1024 * 1024, plan))
        assertTrue(CanvasCompact.hasRoom(200L * 1024 * 1024, plan))
        assertNotEquals(CanvasCompact.doneKey("x.mp4", 40_000_000), CanvasCompact.doneKey("x.mp4", 4_400_000))
    }
}
