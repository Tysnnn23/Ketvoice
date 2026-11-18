package com.ketvoice.app.glass

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** The glass blur of Android 11 (SoftBlur): its Gaussian, its pyramid and its colour effects. */
class SoftBlurTest {
    @Test fun onlyAndroid11AndOlderBlurThemselves() {
        assertTrue(SoftBlur.needed(30))
        assertFalse(SoftBlur.needed(31))
        assertFalse(SoftBlur.needed(36))
    }

    @Test fun threeBoxesApproximateTheGaussianVariance() {
        for (sigma in listOf(1.5f, 3f, 5f, 9.7f, 20f)) {
            val radii = SoftBlur.boxRadii(sigma)
            assertEquals(3, radii.size)
            // A box of width w has variance (w² - 1) / 12; three in a row add up.
            val variance = radii.sumOf { r -> val w = 2.0 * r + 1; (w * w - 1) / 12 }
            assertEquals("sigma $sigma", sigma.toDouble(), sqrt(variance), .2 * sigma + .5)
        }
        assertArrayEquals(IntArray(3), SoftBlur.boxRadii(0f))
    }

    @Test fun theBlurKeepsColoursAndSoftensEdges() {
        val w = 64; val h = 16
        val red = 0xFFE53935.toInt(); val blue = 0xFF1E88E5.toInt()
        val pixels = IntArray(w * h) { if (it % w < w / 2) red else blue }
        SoftBlur.blur(pixels, w, h, 4f)
        // Far from the edge each side keeps its colour (clamped edges, no darkening).
        assertEquals(red, pixels[3 * w + 1])
        assertEquals(blue, pixels[3 * w + w - 2])
        // At the edge the two meet halfway; along the row the step is gradual.
        val row = (0 until w).map { pixels[8 * w + it] shr 16 and 255 }
        val steepest = row.zipWithNext { a, b -> abs(a - b) }.max()
        val edge = abs((red shr 16 and 255) - (blue shr 16 and 255))
        assertTrue("steepest $steepest of $edge", steepest < edge / 4)
        // A Gaussian of sigma 4 crosses from 16 % to 84 % over about 2 sigma.
        val mid = row[w / 2 - 1]
        assertTrue(mid in 70..200)
        assertEquals(255, pixels[0] ushr 24)
    }

    @Test fun theBlurIsSymmetricLikeAGaussian() {
        val w = 41
        val pixels = IntArray(w) { if (it == 20) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        SoftBlur.blur(pixels, w, 1, 3f)
        val values = pixels.map { it and 255 }
        for (d in 1..8) assertEquals(values[20 - d], values[20 + d])
        // Close to exp(-d²/2σ²) shape: decreasing from the centre.
        for (d in 0..8) assertTrue(values[20 + d] >= values[20 + d + 1])
        assertTrue(values[20] > 0 && values[26] < values[20] * exp(-1.0) + 3)
    }

    @Test fun thePyramidShrinksMoreForAStrongerBlur() {
        assertEquals(2, SoftBlur.pyramidFactor(.5f))
        val soft = SoftBlur.pyramidFactor(SoftBlur.sigma(6f))
        val strong = SoftBlur.pyramidFactor(SoftBlur.sigma(34f))
        assertTrue(soft < strong)
        assertTrue(strong <= 32)
        assertEquals(4, SoftBlur.firstStep(16))
        assertEquals(2, SoftBlur.firstStep(2))
        val sizes = BackdropBlur.pyramidSizes(1000f, 300f, 4, 16)
        assertEquals(listOf(250 to 75, 125 to 38, 63 to 19), sizes.map { it.width to it.height })
        // Never an empty layer, whatever the panel.
        assertTrue(BackdropBlur.pyramidSizes(3f, 1f, 4, 32).all { it.width >= 1 && it.height >= 1 })
    }

    @Test fun theMarginLetsTheEdgesBlurWithTheirSurroundings() {
        val m = SoftBlur.margin(9.7f, 16)
        assertEquals(0, m % 16)
        assertTrue(m >= 2 * 9.7f)
        assertTrue(SoftBlur.softwareFactor(2f) == 1 && SoftBlur.softwareFactor(9.7f) == 2 && SoftBlur.softwareFactor(40f) == 16)
    }

    @Test fun neutralColourControlsChangeNothing() {
        val m = SoftBlur.colorMatrix(saturation = 1f, brightness = 0f, contrast = 1f, exposure = 0f).values
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        for (i in identity.indices) assertEquals(identity[i], m[i], 1e-4f)
        // Vibrancy raises saturation: grey stays grey, a colour moves away from it.
        val v = SoftBlur.colorMatrix(1.5f, 0f, 1f, 0f).values
        assertEquals(1f, v[0] + v[1] + v[2], 1e-4f)
        assertTrue(v[0] > 1f)
    }
}
