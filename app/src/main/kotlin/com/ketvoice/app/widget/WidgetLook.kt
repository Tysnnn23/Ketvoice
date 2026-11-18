package com.ketvoice.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import androidx.glance.color.ColorProvider
import com.ketvoice.app.theme.CoverColors

/**
 * The look of the widgets since 6.4 (owner, 2026-11-18: « refonte complète des widgets Android », in the Ket 27
 * language, after iOS 27 / Apple Music widgets): the launcher's own corner radius, the cover's colours as a soft
 * gradient, a veil that follows the system's light or dark mode, crisp type and generous padding.
 *
 * RemoteViews cannot blur, so the gradient is drawn once per cover into a tiny bitmap ([ground], 64 × 64: the
 * launcher stretches it smoothly). Light and dark are not two bitmaps: a day/night veil lies over the same ground
 * and every colour is a day/night pair, so switching the system theme needs no update from Ketvoice.
 */
internal data class WidgetTint(
    /** The cover's main colour, brought to a middle tone. */
    val base: Int,
    /** Its second colour (or a neighbour of the first), the other end of the gradient. */
    val second: Int,
    /** A brighter accent from the cover: the glow in a corner, the week's bar of today. */
    val glow: Int,
)

internal object WidgetLook {
    /** Ketvoice's own colours, without a cover (and the empty state). */
    val DEFAULT = WidgetTint(0xFFD9466E.toInt(), 0xFF5B47C9.toInt(), 0xFFFF6B8B.toInt())
    /** Covers almost without colour: a graphite gradient. */
    val GRAPHITE = WidgetTint(0xFF6E6E78.toInt(), 0xFF3C3C44.toInt(), 0xFF9A9AA6.toInt())
    const val GROUND = 64

    // Colours: (light, dark). The veil makes the ground pale in light mode and deep in dark mode.
    val veil = ColorProvider(Color(0xADFFFFFF), Color(0x85000000))
    val ink = ColorProvider(Color(0xFF111114), Color(0xFFFFFFFF))
    val inkSoft = ColorProvider(Color(0xA8111114), Color(0xBDFFFFFF))
    val inkFaint = ColorProvider(Color(0x73111114), Color(0x85FFFFFF))
    /** The glyph inside a filled button: the opposite of [ink]. */
    val onInk = ColorProvider(Color(0xFFFFFFFF), Color(0xFF111114))
    /** Quiet fills: a secondary button, an empty cover, a bar's track. */
    val chip = ColorProvider(Color(0x14000000), Color(0x24FFFFFF))
    val track = ColorProvider(Color(0x1F000000), Color(0x33FFFFFF))
    /** Over a full-bleed cover, whatever the theme. */
    val white = ColorProvider(Color.White, Color.White)
    val whiteSoft = ColorProvider(Color(0xD9FFFFFF), Color(0xD9FFFFFF))
    val whiteChip = ColorProvider(Color(0x38FFFFFF), Color(0x38FFFFFF))
    /** Ketmix's switch when on (the system green of iOS, a little deeper at night). */
    val switchOn = ColorProvider(Color(0xFF34C759), Color(0xFF30D158))
    val switchOff = ColorProvider(Color(0x29000000), Color(0x40FFFFFF))
    val knob = ColorProvider(Color.White, Color.White)

    /** The colours of a cover: its two hues at a middle tone, or graphite for a cover almost without colour. */
    fun tint(cover: Bitmap?): WidgetTint {
        if (cover == null) return DEFAULT
        val small = runCatching { cover.scale(24, 24, filter = true) }.getOrNull() ?: return DEFAULT
        val pixels = IntArray(24 * 24)
        small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        if (small !== cover) small.recycle()
        val found = CoverColors.analyze(pixels) ?: return DEFAULT
        return tintOf(found.base, found.second, found.colourful)
    }

    /**
     * The tint from a cover's colours (host-tested). Both ends sit at a middle lightness, so the light veil turns
     * them pastel and the dark veil deep, and the text keeps its contrast whatever the cover.
     */
    fun tintOf(base: Int, second: Int?, colourful: Boolean): WidgetTint {
        if (!colourful) return GRAPHITE
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(base, hsl)
        val hue = hsl[0]
        val sat = hsl[1].coerceIn(.32f, .72f)
        val a = ColorUtils.HSLToColor(floatArrayOf(hue, sat, hsl[2].coerceIn(.40f, .50f)))
        val b = if (second != null) {
            ColorUtils.colorToHSL(second, hsl)
            ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(.30f, .70f), hsl[2].coerceIn(.34f, .46f)))
        } else ColorUtils.HSLToColor(floatArrayOf((hue + 28f) % 360f, sat, .36f))
        val glow = ColorUtils.HSLToColor(floatArrayOf(hue, sat.coerceAtLeast(.55f), .56f))
        return WidgetTint(opaque(a), opaque(b), opaque(glow))
    }

    private fun opaque(c: Int) = c or (0xFF shl 24)

    /**
     * The ground: a diagonal gradient between the cover's two colours, a glow of its accent in the top corner and,
     * when there is a cover, the cover itself melted into it (shrunk to a few pixels, then smoothed back up).
     */
    fun ground(cover: Bitmap?, tint: WidgetTint, size: Int = GROUND): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val s = size.toFloat()
        canvas.drawRect(0f, 0f, s, s, Paint().apply {
            shader = LinearGradient(0f, 0f, s, s, tint.base, tint.second, Shader.TileMode.CLAMP)
        })
        if (cover != null) runCatching { melt(canvas, cover, size) }
        canvas.drawRect(0f, 0f, s, s, Paint().apply {
            shader = RadialGradient(s * .08f, s * .04f, s * .8f, ColorUtils.setAlphaComponent(tint.glow, 120),
                ColorUtils.setAlphaComponent(tint.glow, 0), Shader.TileMode.CLAMP)
        })
        return out
    }

    private fun melt(canvas: Canvas, cover: Bitmap, size: Int) {
        val tiny = cover.scale(5, 5, filter = true)
        val soft = tiny.scale(size, size, filter = true)
        canvas.drawBitmap(soft, Rect(0, 0, size, size), Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 105 })
        if (tiny !== cover) tiny.recycle()
        soft.recycle()
    }

    /** A dark veil from clear to deep, under the words on a full-bleed cover. */
    val scrim: Bitmap by lazy {
        val out = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            // Clear over the top third, then rising smoothly to deep under the words.
            val t = ((y / 63f - .3f) / .7f).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            out.setPixel(0, y, ColorUtils.setAlphaComponent(android.graphics.Color.BLACK, (eased * 215).toInt().coerceIn(0, 255)))
        }
        out
    }

    /** Minutes as the interface language writes a number: « 1 204 » in French, « 1,204 » in English. */
    fun minutes(value: Long, locale: java.util.Locale = com.ketvoice.app.ui.sub.WrappedPeriod.locale()): String =
        java.text.NumberFormat.getIntegerInstance(locale).format(value.coerceAtLeast(0))
}
