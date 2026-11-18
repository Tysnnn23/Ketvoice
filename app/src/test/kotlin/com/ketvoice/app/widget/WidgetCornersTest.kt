package com.ketvoice.app.widget

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round widget corners drawn into the pictures on Android 11 (WidgetCorners), on host Skia. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetCornersTest {
    @Test fun onlyAndroid11DrawsItsCorners() {
        assertTrue(WidgetCorners.drawn(30))
        assertFalse(WidgetCorners.drawn(31))
    }

    @Test fun theCardTakesTheWidgetsProportionsSoItsCornersStayRound() {
        assertEquals(320 to 160, WidgetCorners.cardPixels(300f, 150f))
        assertEquals(160 to 320, WidgetCorners.cardPixels(150f, 300f))
        // 16 dp on a 300 dp wide widget drawn 320 px wide.
        assertEquals(16f * 320 / 300, WidgetCorners.cardRadiusPx(300f, 150f), .01f)
    }

    @Test fun theCardIsTransparentInItsCornersAndOpaqueInside() {
        val ground = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF5B47C9.toInt()) }
        val card = WidgetCorners.card(ground, veil = 0x85000000.toInt(), widthDp = 300f, heightDp = 150f)
        assertEquals(320, card.width); assertEquals(160, card.height)
        assertEquals(0, Color.alpha(card.getPixel(0, 0)))
        assertEquals(0, Color.alpha(card.getPixel(319, 159)))
        val middle = card.getPixel(160, 80)
        assertEquals(255, Color.alpha(middle))
        // The veil darkened the ground (0x5B47C9 under 52 % black).
        assertTrue(Color.blue(middle) < 0xC9 * .6)
    }

    @Test fun aFilledShapeKeepsItsTranslucentColour() {
        val chip = 0x24FFFFFF
        val shape = WidgetCorners.shape(density = 2f, widthDp = 40, heightDp = 40, radiusDp = 20f, argb = chip)
        assertEquals(80, shape.width)
        assertEquals(0, Color.alpha(shape.getPixel(1, 1)))
        assertEquals(0x24, Color.alpha(shape.getPixel(40, 40)))
        assertSame(shape, WidgetCorners.shape(2f, 40, 40, 20f, chip))
    }

    @Test fun aPosterIsCroppedToTheWidgetAndRounded() {
        val cover = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val scrim = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888)
        val poster = WidgetCorners.poster(cover, scrim, 200f, 300f)
        assertEquals(320, poster.height)
        assertEquals(0, Color.alpha(poster.getPixel(0, 0)))
        assertEquals(Color.RED, poster.getPixel(poster.width / 2, poster.height / 2))
    }
}
