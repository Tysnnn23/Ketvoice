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
        assertNull(LiteTuning.imageDecoders(lite = false, modest = false))
        assertEquals(3, LiteTuning.ketmixLookahead(lite = false, modest = true))
        assertNull(LiteTuning.canvasBuffer(lite = false))
        // Every edition starts the same late work after the first screen; mobile and Quest sooner than Lite.
        assertEquals(LiteTuning.StartWork.entries.toSet(), LiteTuning.deferred(lite = false))
        assertTrue(LiteTuning.deferredStartMs(lite = false) < LiteTuning.deferredStartMs(lite = true))
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
        assertFalse(BuildConfig.IS_TV && (BuildConfig.IS_LITE || BuildConfig.IS_QUEST))
        assertEquals(if (BuildConfig.IS_TV) Edition.Tv else if (BuildConfig.IS_LITE) Edition.Lite
            else if (BuildConfig.IS_QUEST) Edition.Quest else Edition.Mobile, Edition.current)
    }

    // ---------------------------------------------------------------- 6.5: modest phones and tablets, Android 11

    @Test fun modestIsLowRamThreeGigabytesOrFourWithAModestGraphicsChip() {
        val fast = LiteTuning.GLES_3_2
        assertEquals(LiteTuning.Why.LowRamFlag, LiteTuning.device(true, 8_000_000_000L, fast).why)
        assertEquals(LiteTuning.Why.Memory, LiteTuning.device(false, 1_900_000_000L, fast).why)
        assertEquals(LiteTuning.Why.Memory, LiteTuning.device(false, 2_900_000_000L, fast).why)
        // 4 GB: modest only with an older chip (no OpenGL ES 3.2).
        assertEquals(LiteTuning.Why.MemoryAndGraphics, LiteTuning.device(false, 3_700_000_000L, 0x30001).why)
        assertFalse(LiteTuning.modest(false, 3_700_000_000L, fast))
        assertTrue(LiteTuning.modest(false, 3_700_000_000L, 0x30001))
        assertFalse(LiteTuning.modest(false, 5_700_000_000L, 0x30000))
        assertEquals(LiteTuning.Why.Roomy, LiteTuning.device(false, 0L, 0).why)
    }

    @Test fun memoryIsShownAsTheSizeThePhoneIsSoldWith() {
        assertEquals(2, LiteTuning.marketedGigabytes(1_880_000_000L))
        assertEquals(3, LiteTuning.marketedGigabytes(2_800_000_000L))
        assertEquals(4, LiteTuning.marketedGigabytes(3_750_000_000L))
        assertEquals(6, LiteTuning.marketedGigabytes(5_700_000_000L))
        assertEquals(8, LiteTuning.marketedGigabytes(7_600_000_000L))
        assertEquals(0, LiteTuning.marketedGigabytes(0L))
    }

    @Test fun picturesAreDecodedNoLargerThanTheScreenOnEveryDevice() {
        // 6.6: every edition and every device, the mobile app included (nothing is shown larger than the screen).
        assertEquals(2400, LiteTuning.decodeBoundPx(2400))
        assertNull("Unknown screen: Coil's own size", LiteTuning.decodeBoundPx(0))
        assertEquals(1200 to 1200, LiteTuning.boundedDecode(-1, -1, 1200))
        assertEquals(1200 to 300, LiteTuning.boundedDecode(-1, 300, 1200))
        assertEquals(400 to 400, LiteTuning.boundedDecode(400, 400, 1200))
        assertEquals(1200 to 600, LiteTuning.boundedDecode(4000, 2000, 1200))
        assertTrue(LiteTuning.imageRgb565(lite = true, modest = true))
        assertFalse(LiteTuning.imageRgb565(lite = false, modest = true))
        assertFalse(LiteTuning.imageRgb565(lite = true, modest = false))
    }

    @Test fun oneCanvasAtATimeOnAModestDevice() {
        // 6.6: a modest phone in the mobile edition follows the same rules (modest is never found on Quest).
        assertEquals(1, LiteTuning.canvasAtOnce(modest = true))
        assertEquals(Int.MAX_VALUE, LiteTuning.canvasAtOnce(modest = false))
        assertEquals(720 to 1280, LiteTuning.canvasMaxVideo(modest = true))
        assertNull(LiteTuning.canvasMaxVideo(modest = false))
        assertTrue(LiteTuning.canvasReleasedWhenHidden(modest = true))
        assertFalse(LiteTuning.canvasReleasedWhenHidden(modest = false))
        assertEquals(2, LiteTuning.imageDecoders(lite = false, modest = true))
        assertNull(LiteTuning.imageDecoders(lite = false, modest = false))
        // What one could see stays Lite's own choice: 16-bit covers and the performance mode by default.
        assertFalse(LiteTuning.imageRgb565(lite = false, modest = true))
        assertFalse(LiteTuning.defaultPerformanceMode(lite = false))
    }

    @Test fun awayFromTheChargerAModestDeviceSeparatesWithTheQuickModel() {
        assertEquals(2, LiteTuning.separationSetting(1, charging = false, lite = true, modest = true))
        assertEquals(2, LiteTuning.separationSetting(0, charging = false, lite = true, modest = true))
        assertEquals(1, LiteTuning.separationSetting(1, charging = true, lite = true, modest = true))
        assertEquals(1, LiteTuning.separationSetting(1, charging = false, lite = true, modest = false))
        assertEquals(1, LiteTuning.separationSetting(1, charging = false, lite = false, modest = true))
    }

    @Suppress("DEPRECATION")
    @Test fun aModestDeviceLetsPicturesGoAtTheFirstMemoryWarning() {
        val moderate = android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
        val low = android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        assertTrue(LiteTuning.releaseImagesAt(moderate, modest = true))
        assertFalse(LiteTuning.releaseImagesAt(moderate, modest = false))
        // Elsewhere: as MemoryRelief always did.
        for (level in listOf(moderate, low, 15, 20, 40, 60, 80))
            assertEquals(MemoryRelief.releaseImages(level), LiteTuning.releaseImagesAt(level, modest = false))
    }

    @Test fun aboutSaysWhichModeIsActiveAndWhy() {
        val twoGb = LiteTuning.device(false, 1_900_000_000L, LiteTuning.GLES_3_2)
        assertEquals("Ce téléphone a 2 Go de mémoire : effets allégés, identiques à l'œil", LiteTuning.aboutLine(twoGb))
        assertEquals("Cette tablette a 2 Go de mémoire : effets allégés, identiques à l'œil", LiteTuning.aboutLine(twoGb, tablet = true))
        val fourGb = LiteTuning.device(false, 3_700_000_000L, 0x30000)
        assertTrue("puce graphique" in LiteTuning.aboutLine(fourGb))
        assertTrue("Android" in LiteTuning.aboutLine(LiteTuning.device(true, 8_000_000_000L, 0)))
        assertTrue("tous les effets" in LiteTuning.aboutLine(LiteTuning.device(false, 7_600_000_000L, LiteTuning.GLES_3_2)))
    }

    @Test fun liteKeepsTheGlassBlurOnALowRamPhoneMobileAsBefore() {
        val blur = com.ketvoice.app.glass.GlassRenderMode.Blur
        assertEquals(16f, com.ketvoice.app.glass.glassBlurRadius(16f, blur, lowMemory = true, reducedMotion = false, lite = true), 0f)
        assertEquals(3f, com.ketvoice.app.glass.glassBlurRadius(16f, blur, lowMemory = true, reducedMotion = false, lite = false), 0f)
        assertEquals(3f, com.ketvoice.app.glass.glassBlurRadius(16f, blur, lowMemory = false, reducedMotion = true, lite = true), 0f)
        assertEquals(16f, com.ketvoice.app.glass.glassBlurRadius(16f, com.ketvoice.app.glass.GlassRenderMode.Optical, true, true, false), 0f)
    }
}
