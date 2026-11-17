package com.ketvoice.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.unit.ColorProvider
import com.ketvoice.app.theme.CoverColors

/**
 * The Ket 27 look of the widgets (6.1): a glass card tinted by the cover. RemoteViews cannot blur or refract
 * what lies behind them, so the material is drawn once per cover into a small bitmap — the cover softened,
 * deepened in its own colour, a glow of its second colour and a specular sheen at the top — and the card's rim
 * is a hairline of light around it. Nothing is drawn per frame or per lyric line: the ground changes with the cover.
 */
internal data class WidgetTint(
    /** The deep colour of the glass, from the cover's main hue. */
    val base: Int,
    /** The glow, from the cover's second hue (or a lighter main hue). */
    val glow: Int,
)

internal object WidgetGlass {
    /** Ketvoice's own tint, without a cover. */
    val DEFAULT = WidgetTint(0xFF2A1E2E.toInt(), 0xFFFF375F.toInt())
    /** The ground's size: blurred and stretched, it needs few pixels (2 bytes each). */
    const val GROUND_W = 192
    const val GROUND_H = 96

    val text = ColorProvider(Color.White)
    val textSoft = ColorProvider(Color(0xC7FFFFFF))
    val textFaint = ColorProvider(Color(0x8CFFFFFF))
    val rim = ColorProvider(Color(0x47FFFFFF))
    val fill = ColorProvider(Color(0x2EFFFFFF))
    val track = ColorProvider(Color(0x38FFFFFF))
    val fallback = ColorProvider(Color(0xFF2A1E2E))

    /** The glass tint of a cover; covers almost without colour give a graphite glass. */
    fun tint(cover: Bitmap?): WidgetTint {
        if (cover == null) return DEFAULT
        val small = runCatching { cover.scale(24, 24, filter = true) }.getOrNull() ?: return DEFAULT
        val pixels = IntArray(24 * 24)
        small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        val found = CoverColors.analyze(pixels) ?: return DEFAULT
        return tintOf(found.base, found.second, found.colourful)
    }

    /** The tint from the cover's colours (host-tested): deep enough for white text, never neon. */
    fun tintOf(base: Int, second: Int?, colourful: Boolean): WidgetTint {
        if (!colourful) return WidgetTint(0xFF26262B.toInt(), 0xFF6A6A76.toInt())
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(base, hsl)
        val deep = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(.25f, .62f), hsl[2].coerceIn(.13f, .22f)))
        val glowSource = second ?: base
        ColorUtils.colorToHSL(glowSource, hsl)
        val glow = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(.45f, .85f), hsl[2].coerceIn(.45f, .6f)))
        return WidgetTint(deep or (0xFF shl 24), glow or (0xFF shl 24))
    }

    /** The glass ground, opaque: the softened cover (when there is one) in the tint, the glow and the sheen. */
    fun ground(cover: Bitmap?, tint: WidgetTint, width: Int = GROUND_W, height: Int = GROUND_H): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(tint.base)
        if (cover != null) runCatching {
            // Shrunk to a few pixels and smoothed back up: a blur RemoteViews can show.
            // The middle band of the cover at the card's proportions, so the colours keep their places.
            val aspect = width.toFloat() / height
            val bandW = if (cover.width.toFloat() / cover.height > aspect) (cover.height * aspect).toInt() else cover.width
            val bandH = if (bandW == cover.width) (cover.width / aspect).toInt().coerceAtMost(cover.height) else cover.height
            val band = Bitmap.createBitmap(cover, (cover.width - bandW) / 2, (cover.height - bandH) / 2, bandW, bandH)
            val soft = band.scale(12, (12 / aspect).toInt().coerceAtLeast(2), filter = true).scale(width, height, filter = true)
            canvas.drawBitmap(soft, Rect(0, 0, soft.width, soft.height), Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 })
        }
        // Deepened toward the bottom, where the text and controls sit.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), ColorUtils.setAlphaComponent(tint.base, 70),
                ColorUtils.setAlphaComponent(android.graphics.Color.BLACK, 120), Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = RadialGradient(width * .12f, height * .05f, width * .75f, ColorUtils.setAlphaComponent(tint.glow, 92),
                ColorUtils.setAlphaComponent(tint.glow, 0), Shader.TileMode.CLAMP)
        })
        // The sheen of glass at the top edge.
        canvas.drawRect(0f, 0f, width.toFloat(), height * .5f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height * .5f, ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, 34),
                ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, 0), Shader.TileMode.CLAMP)
        })
        return out.copy(Bitmap.Config.RGB_565, false).also { out.recycle() }
    }

    /** A dark veil from transparent to deep, laid under the words on a full-bleed cover. */
    val scrim: Bitmap by lazy {
        val out = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            val t = (y / 63f)
            out.setPixel(0, y, ColorUtils.setAlphaComponent(android.graphics.Color.BLACK, (t * t * 200).toInt().coerceIn(0, 255)))
        }
        out
    }
}

/** A Ket 27 glass card: the ground inside a hairline rim of light. */
@Composable
internal fun GlassCard(ground: Bitmap?, radius: Dp, onClick: Action?, content: @Composable () -> Unit) {
    var outer = GlanceModifier.fillMaxSize().cornerRadius(radius).background(WidgetGlass.rim)
    if (onClick != null) outer = outer.clickable(onClick)
    Box(outer) {
        Box(GlanceModifier.fillMaxSize().padding(1.dp)) {
            val inner = GlanceModifier.fillMaxSize().cornerRadius((radius.value - 1f).coerceAtLeast(0f).dp)
            Box(if (ground != null) inner.background(ImageProvider(ground), ContentScale.FillBounds) else inner.background(WidgetGlass.fallback)) {
                content()
            }
        }
    }
}
