package com.ketvoice.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.ContentScale
import androidx.glance.unit.ColorProvider
import com.ketvoice.app.R

/**
 * Round corners on Android 11 (Ketvoice Lite). Glance rounds a view through RemoteViews outlines, which only
 * Android 12+ has: below it, `cornerRadius` does nothing and every card, cover and round button would be square.
 * There the shape is drawn into the picture itself: the card's ground at the widget's own proportions with
 * Android 12's widget radius, covers clipped, filled buttons drawn as round bitmaps of their exact colour (a
 * tinted shape cannot keep a translucent colour: Android 11 tints with SRC_ATOP). Android 12+ is unchanged.
 */
internal object WidgetCorners {
    fun drawn(api: Int = Build.VERSION.SDK_INT): Boolean = api < Build.VERSION_CODES.S

    /** `system_app_widget_background_radius` of Android 12 (16 dp), drawn by hand on 11. */
    const val CARD_RADIUS_DP = 16

    /** The card's picture: its longer side in pixels (the ground itself is 64 px, stretched). */
    const val CARD_SIDE = 320

    /** Width × height in pixels of the card's picture for a widget of [widthDp] × [heightDp]. */
    fun cardPixels(widthDp: Float, heightDp: Float): Pair<Int, Int> {
        val w = widthDp.coerceAtLeast(1f); val h = heightDp.coerceAtLeast(1f)
        return if (w >= h) CARD_SIDE to (CARD_SIDE * h / w).toInt().coerceAtLeast(1)
            else (CARD_SIDE * w / h).toInt().coerceAtLeast(1) to CARD_SIDE
    }

    /** The corner radius in the card's picture: [CARD_RADIUS_DP] at the picture's scale. */
    fun cardRadiusPx(widthDp: Float, heightDp: Float): Float {
        val (w, _) = cardPixels(widthDp, heightDp)
        return CARD_RADIUS_DP * w / widthDp.coerceAtLeast(1f)
    }

    /** The ground stretched to the widget's proportions, the veil over it, the corners rounded. */
    fun card(ground: Bitmap, veil: Int?, widthDp: Float, heightDp: Float): Bitmap {
        val (w, h) = cardPixels(widthDp, heightDp)
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawBitmap(ground, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            if (veil != null) drawColor(veil)
        }
        return clip(flat, cardRadiusPx(widthDp, heightDp)).also { flat.recycle() }
    }

    /** A full-bleed cover (centre-cropped to the widget's proportions) with [scrim] over it, corners rounded. */
    fun poster(cover: Bitmap, scrim: Bitmap, widthDp: Float, heightDp: Float): Bitmap {
        val (w, h) = cardPixels(widthDp, heightDp)
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = maxOf(w / cover.width.toFloat(), h / cover.height.toFloat())
        val cw = w / scale; val ch = h / scale
        val crop = android.graphics.Rect(((cover.width - cw) / 2).toInt(), ((cover.height - ch) / 2).toInt(),
            ((cover.width + cw) / 2).toInt(), ((cover.height + ch) / 2).toInt())
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        Canvas(flat).apply {
            drawBitmap(cover, crop, RectF(0f, 0f, w.toFloat(), h.toFloat()), filter)
            drawBitmap(scrim, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), filter)
        }
        return clip(flat, cardRadiusPx(widthDp, heightDp)).also { flat.recycle() }
    }

    /** A copy of [source] with corners of [radiusFraction] of its width. */
    fun rounded(source: Bitmap, radiusFraction: Float): Bitmap = clip(source, source.width * radiusFraction)

    private fun clip(source: Bitmap, radiusPx: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(Matrix()) }
        }
        Canvas(out).drawRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radiusPx, radiusPx, paint)
        return out
    }

    private val shapes = LruCache<String, Bitmap>(24)

    /** A filled rounded rectangle of [widthDp] × [heightDp], [radiusDp] corners, in the exact [argb]. */
    fun shape(density: Float, widthDp: Int, heightDp: Int, radiusDp: Float, argb: Int): Bitmap {
        val key = "$density:$widthDp:$heightDp:$radiusDp:$argb"
        shapes.get(key)?.let { if (!it.isRecycled) return it }
        val w = (widthDp * density).toInt().coerceAtLeast(1); val h = (heightDp * density).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radiusDp * density, radiusDp * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb })
        shapes.put(key, out)
        return out
    }
}

/** A fill with round corners, for a view of a known size; Android 11 draws it (see [WidgetCorners]). */
@Composable
internal fun GlanceModifier.roundFill(widthDp: Int, heightDp: Int, radiusDp: Float, fill: ColorProvider): GlanceModifier {
    if (!WidgetCorners.drawn()) return cornerRadius(radiusDp.dp).background(fill)
    val context = LocalContext.current
    val argb = fill.getColor(context).toArgb()
    return background(ImageProvider(WidgetCorners.shape(context.resources.displayMetrics.density, widthDp, heightDp, radiusDp, argb)),
        ContentScale.FillBounds)
}

/**
 * A capsule whose width follows its content: Android 11 draws it with a shape drawable tinted [fill], which must
 * be opaque (an Android 11 tint keeps the shape's own opacity).
 */
@Composable
internal fun GlanceModifier.capsuleFill(heightDp: Int, fill: ColorProvider): GlanceModifier =
    if (!WidgetCorners.drawn()) cornerRadius((heightDp / 2f).dp).background(fill)
    else background(ImageProvider(R.drawable.widget_capsule), ContentScale.FillBounds, ColorFilter.tint(fill))

/** The card of a widget on Android 11: ground and veil in one rounded picture at the widget's size. */
internal fun cardPicture(context: Context, ground: Bitmap, veil: Boolean, size: DpSize): Bitmap =
    WidgetCorners.card(ground, if (veil) WidgetLook.veil.getColor(context).toArgb() else null, size.width.value, size.height.value)
