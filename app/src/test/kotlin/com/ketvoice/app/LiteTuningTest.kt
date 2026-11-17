package com.ketvoice.app

import org.junit.Assert.*
import org.junit.Test

/** Ketvoice Lite's budgets, and the guarantee that mobile and Quest keep the values they had. */
class LiteTuningTest {
    @Test fun mobileAndQuestKeepTheirValues() {
        assertFalse(LiteTuning.defaultPerformanceMode(lite = false))
        assertEquals(0, LiteTuning.defaultStemsQuality(lite = false))
        assertEquals(0.20, LiteTuning.imageMemoryPercent(lite = false, modest = true), 0.0)
        assertEquals(2L * 1024 * 1024 * 1024, LiteTuning.imageDiskBytes(lite = false))
        assertNull(LiteTuning.imageDecoders(lite = false))
        assertEquals(3, LiteTuning.ketmixLookahead(lite = false, modest = true))
        assertNull(LiteTuning.canvasBuffer(lite = false))
        assertTrue(LiteTuning.deferred(lite = false).isEmpty())
    }

    @Test fun liteDefaultsKeepEffectsVisibleAndLightenTheRest() {
        // The performance mode keeps glass blur and Canvas (owner's rule of 5.9.2); Lite starts in it.
        assertTrue(LiteTuning.defaultPerformanceMode(lite = true))
        // The quick separation model (StemModel quality 2 = always quick).
        assertEquals(2, LiteTuning.defaultStemsQuality(lite = true))
    }

    @Test fun liteMemoryBudgetsShrinkFurtherOnModestPhones() {
        val normal = LiteTuning.imageMemoryPercent(lite = true, modest = false)
        val modest = LiteTuning.imageMemoryPercent(lite = true, modest = true)
        assertTrue(normal < 0.20 && modest < normal && modest >= 0.08)
        assertTrue(LiteTuning.imageDiskBytes(lite = true) < LiteTuning.imageDiskBytes(lite = false))
        assertEquals(2, LiteTuning.imageDecoders(lite = true))
        assertEquals(2, LiteTuning.ketmixLookahead(lite = true, modest = false))
        assertEquals(1, LiteTuning.ketmixLookahead(lite = true, modest = true))
    }

    @Test fun canvasBufferStillHoldsALoop() {
        val buffer = LiteTuning.canvasBuffer(lite = true)!!
        // Media3 requires play <= min <= max and rebuffer <= min.
        assertTrue(buffer.playMs <= buffer.minMs && buffer.rebufferMs <= buffer.minMs && buffer.minMs <= buffer.maxMs)
        assertTrue(buffer.maxMs >= 8_000)
        assertTrue(buffer.bytes >= 4 * 1024 * 1024)
    }

    @Test fun modestMeansLowRamOrAboutFourGigabytes() {
        assertTrue(LiteTuning.modest(lowRamDevice = true, totalMemBytes = 12_000_000_000L))
        assertTrue(LiteTuning.modest(lowRamDevice = false, totalMemBytes = 3_800_000_000L))
        assertTrue(LiteTuning.modest(lowRamDevice = false, totalMemBytes = 4_000_000_000L))
        assertFalse(LiteTuning.modest(lowRamDevice = false, totalMemBytes = 6_000_000_000L))
        // An unknown size (0) is not taken for a modest phone.
        assertFalse(LiteTuning.modest(lowRamDevice = false, totalMemBytes = 0L))
    }

    @Test fun liteDefersOnlyWorkTheFirstScreenDoesNotNeed() {
        assertEquals(LiteTuning.StartWork.entries.toSet(), LiteTuning.deferred(lite = true))
        assertTrue(LiteTuning.DEFERRED_START_MS in 1_000L..10_000L)
    }

    @Test fun thisBuildsDefaultsFollowItsEdition() {
        assertEquals(BuildConfig.IS_LITE, LiteTuning.lite)
        assertEquals(BuildConfig.IS_LITE, LiteTuning.defaultPerformanceMode())
        assertEquals(if (BuildConfig.IS_LITE) 2 else 0, LiteTuning.defaultStemsQuality())
        assertFalse(BuildConfig.IS_LITE && BuildConfig.IS_QUEST)
        assertEquals(if (BuildConfig.IS_LITE) Edition.Lite else if (BuildConfig.IS_QUEST) Edition.Quest else Edition.Mobile,
            Edition.current)
    }
}
