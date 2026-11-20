package com.ketvoice.app.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import com.ketvoice.app.theme.CoverColors
import com.ketvoice.app.theme.CoverLegibility

/**
 * The colours of a widget (7.4, owner: « Couleurs de la pochette »), as Apple Music takes them: the card is a soft
 * diagonal gradient between the cover's two colours, kept calm (saturation at most [MAX_SAT]) and dark enough, or light
 * for a pale cover, that the ink reads on it. [ink] (title, glyphs, the play disc) and [inkSoft] (artist, times) both
 * reach [CoverLegibility.TEXT] (WCAG AA) on both ends of the gradient; the play glyph is [bottom] on the [ink] disc, the
 * same contrast. Without a cover the card is [FALLBACK], a dark scrim.
 */
internal data class WidgetPalette(
    /** The gradient's top-left and bottom-right colours (opaque). */
    val top: Int,
    val bottom: Int,
    val ink: Int,
    val inkSoft: Int,
    /** White ink on a dark card (else near-black ink on a light card): which progress bar is shown. */
    val lightInk: Boolean,
) {
    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val NEAR_BLACK = 0xFF1C1C1E.toInt()
        const val MAX_SAT = .5f
        val FALLBACK = WidgetPalette(0xFF2C2C30.toInt(), 0xFF1C1C1F.toInt(), WHITE, 0xC2FFFFFF.toInt(), lightInk = true)

        /** The palette of [cover]; [FALLBACK] without one. */
        fun of(cover: Bitmap?): WidgetPalette {
            cover ?: return FALLBACK
            val small = runCatching { cover.scale(24, 24, filter = true) }.getOrNull() ?: return FALLBACK
            val pixels = IntArray(24 * 24)
            small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            if (small !== cover) small.recycle()
            val found = CoverColors.analyze(pixels) ?: return FALLBACK
            return of(found.base, found.second, found.colourful)
        }

        /** The palette from a cover's colours ([CoverColors.analyze]); host-tested for every hue and lightness. */
        fun of(base: Int, second: Int?, colourful: Boolean): WidgetPalette {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(base, hsl)
            val light = hsl[2] >= .72f
            val sat = if (colourful) hsl[1].coerceIn(.18f, if (light) .42f else MAX_SAT) else .03f
            var a = floatArrayOf(hsl[0], sat, if (light) .86f else hsl[2].coerceIn(.22f, .34f))
            var b = if (colourful && second != null) {
                ColorUtils.colorToHSL(second, hsl)
                floatArrayOf(hsl[0], hsl[1].coerceIn(.18f, if (light) .42f else MAX_SAT), if (light) .80f else hsl[2].coerceIn(.16f, .28f))
            } else floatArrayOf((a[0] + 18f) % 360f, sat, if (light) .80f else a[2] - .07f)
            val ink = if (light) NEAR_BLACK else WHITE
            val softAlpha = (if (light) 180 else 190) / 255f
            // Darken (or lighten) each end until the softer ink still reads on it.
            a = readable(a, ink, softAlpha, light)
            b = readable(b, ink, softAlpha, light)
            return WidgetPalette(opaque(ColorUtils.HSLToColor(a)), opaque(ColorUtils.HSLToColor(b)), ink,
                ColorUtils.setAlphaComponent(ink, Math.round(softAlpha * 255)), lightInk = !light)
        }

        private fun readable(hsl: FloatArray, ink: Int, softAlpha: Float, light: Boolean): FloatArray {
            val out = hsl.copyOf()
            repeat(40) {
                val ground = Color(opaque(ColorUtils.HSLToColor(out)))
                if (CoverLegibility.contrast(Color(ink).copy(alpha = softAlpha), ground) >= CoverLegibility.TEXT) return out
                out[2] = (out[2] + if (light) .02f else -.02f).coerceIn(0f, 1f)
            }
            return out
        }

        private fun opaque(c: Int) = c or (0xFF shl 24)
    }

    /**
     * The card, drawn small (the launcher stretches it smoothly). With [radiusPx] (Android 11, where the card cannot
     * clip its content to the launcher's outline) the corners are cut into it at the widget's own size.
     */
    fun ground(width: Int = 48, height: Int = 48, radiusPx: Float = 0f): Bitmap {
        val out = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radiusPx, radiusPx, paint)
        return out
    }
}

/** A cover with its corners rounded (RemoteViews cannot round an image), [size] pixels square. */
internal fun roundedCover(source: Bitmap, size: Int, radiusFraction: Float = .1f): Bitmap {
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val scaled = if (source.width == size && source.height == size) source else source.scale(size, size, filter = true)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    val r = size * radiusFraction
    Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
    if (scaled !== source) scaled.recycle()
    return out
}
