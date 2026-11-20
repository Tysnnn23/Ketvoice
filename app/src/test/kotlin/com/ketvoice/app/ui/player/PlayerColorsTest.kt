package com.ketvoice.app.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/** 7.4: how the full player takes its colours from a cover. */
class PlayerColorsTest {
    private fun srgb(linear: Float): Float =
        if (linear <= .0031308f) linear * 12.92f else (1.055f * linear.toDouble().pow(1 / 2.4) - .055).toFloat()

    @Test fun theBackgroundColourStaysInADimBandWhateverTheCover() {
        for (cover in listOf(0xFFFFFFFF, 0xFFF7F2E8, 0xFFE58B5A, 0xFF2C5364, 0xFF101B36, 0xFF050505, 0xFF000000)) {
            val l = PlayerColors.ambient(cover.toInt()).luminance()
            assertTrue("${cover.toString(16)} gives $l", l >= PlayerColors.AMBIENT_MIN * .9f && l <= PlayerColors.AMBIENT_MAX * 1.1f)
        }
    }

    @Test fun theBackgroundKeepsTheCoversHue() {
        val red = PlayerColors.ambient(0xFFD03030.toInt())
        assertTrue(red.red > red.green * 2f && red.red > red.blue * 2f)
        val blue = PlayerColors.ambient(0xFF2C5364.toInt())
        assertTrue(blue.blue > blue.red)
    }

    @Test fun aWhiteCoverIsNoLongerAMuddyMidGrey() {
        // Before 7.4 the colour was the cover's at 55 %: #8C8C8C behind white controls.
        val white = PlayerColors.ambient(0xFFFFFFFF.toInt())
        assertTrue(white.luminance() < Color(0xFF8C8C8C).luminance() / 2f)
    }

    @Test fun theVeilFollowsTheCoversLightness() {
        assertTrue(PlayerColors.veil(.9f, false) > PlayerColors.veil(.05f, false))
        assertTrue(PlayerColors.veil(.9f, true) > PlayerColors.veil(.05f, true))
        assertTrue("flowing colours take a lighter veil", PlayerColors.veil(.05f, true) < PlayerColors.veil(.05f, false))
        assertEquals("except on a white cover", PlayerColors.veil(1f, false), PlayerColors.veil(1f, true), .001f)
        assertEquals(PlayerColors.VEIL_DARK, PlayerColors.veil(-1f, false), .001f)
        assertEquals(PlayerColors.VEIL_LIGHT, PlayerColors.veil(2f, false), .001f)
    }

    @Test fun whiteTextReadsOverTheVeilOnAnyCover() {
        // The blurred cover at its mean lightness, under the veil (the middle of the screen, where the title sits).
        for (step in 0..10) {
            val mean = step / 10f
            val cover = srgb(mean)
            for (flowing in listOf(true, false)) {
                val veil = PlayerColors.veil(mean, flowing)
                val scrim = PlayerColors.scrim(PlayerColors.ambient(Color(cover, cover, cover).toArgb())).red
                val shown = cover * (1f - veil) + scrim * veil
                val l = Color(shown, shown, shown).luminance()
                val contrast = 1.05f / (l + .05f)
                assertTrue("mean $mean flowing $flowing: $contrast", contrast >= 4.5f)
            }
        }
    }

    @Test fun meanLuminanceLeavesTransparentPixelsOut() {
        assertNull(PlayerColors.meanLuminance(intArrayOf(0x00FFFFFF)))
        assertEquals(1f, PlayerColors.meanLuminance(intArrayOf(0xFFFFFFFF.toInt(), 0x00000000))!!, .001f)
        assertEquals(.5f, PlayerColors.meanLuminance(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))!!, .001f)
    }

    @Test fun ambienceOfACoverCarriesItsLightness() {
        val light = PlayerColors.ambience(IntArray(64) { if (it % 8 == 0) 0xFFE58B5A.toInt() else 0xFFF7F2E8.toInt() })!!
        val dark = PlayerColors.ambience(IntArray(64) { if (it % 8 == 0) 0xFFC9A227.toInt() else 0xFF101B36.toInt() })!!
        assertTrue(light.mean > .6f && dark.mean < .1f)
        assertTrue(light.veil > dark.veil)
        assertNotNull(light.accent)
    }

    @Test fun theSmallerColourFieldsHaveNoEdge() {
        val side = 48
        val pixels = IntArray(side * side) { 0xFF336699.toInt() }
        ArtworkFlow.feather(pixels, side)
        fun alpha(x: Int, y: Int) = pixels[y * side + x] ushr 24
        assertEquals("corners are gone", 0, alpha(0, 0))
        assertEquals(0, alpha(side - 1, side - 1))
        assertTrue("edges are gone", alpha(side / 2, 0) <= 2)
        assertEquals("the centre is whole", 255, alpha(side / 2, side / 2))
        assertEquals("the colour is kept", 0x336699, pixels[0] and 0xFFFFFF)
        // It falls off without a step.
        for (x in side / 2 until side - 1) assertTrue(alpha(x, side / 2) >= alpha(x + 1, side / 2))
    }
}
