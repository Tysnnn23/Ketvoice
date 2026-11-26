package com.ketvoice.app.tv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.ketvoice.app.ui.common.Icon

/**
 * Pictures on the TV are decoded at the size they are shown, never larger (a 3000 px cover would take 36 MB on a
 * 2 GB box), without a cross-fade on light TVs, and the backgrounds are a tiny decode blurred on the processor once
 * (about 1 300 pixels), then stretched: the look of a heavy blur for the price of nothing.
 */
object TvImages {
    /** The pixels a picture shown at [dp] takes (rounded to 8 so that close sizes share one cached decode). */
    fun decodePx(dp: Float, density: Float): Int = ((dp * density + 7f) / 8f).toInt().coerceAtLeast(1) * 8

    /** The background's decode: 48 × 27 (16:9), plenty for a colour field. */
    const val BACKDROP_W = 48
    const val BACKDROP_H = 27
}

/** A cover (or an artist's photo) decoded at exactly its displayed size; a calm placeholder until it comes. */
@Composable
fun TvCover(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier, height: Dp = size, placeholder: ImageVector? = null) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val light = tvTier() == TvTier.Light
    val w = TvImages.decodePx(size.value, density)
    val h = TvImages.decodePx(height.value, density)
    val request = remember(url, w, h, light) {
        url?.let {
            ImageRequest.Builder(context).data(it).size(w, h).precision(Precision.INEXACT)
                .allowRgb565(light).crossfade(!light).build()
        }
    }
    Box(modifier.size(width = size, height = height).clip(shape).background(placeholderBrush(url)), contentAlignment = Alignment.Center) {
        if (request == null && placeholder != null)
            Icon(placeholder, null, androidx.compose.ui.Modifier.fillMaxSize(.36f), tint = Color.White.copy(alpha = .5f))
        if (request != null) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** A soft two-colour placeholder, the same for the same picture. */
private fun placeholderBrush(key: String?): Brush {
    val hue = ((key?.hashCode() ?: 0) and 0x7fffffff) % 360
    return Brush.linearGradient(listOf(Color.hsv(hue.toFloat(), .35f, .30f), Color.hsv(((hue + 40) % 360).toFloat(), .45f, .16f)))
}

/**
 * The page's background: [url]'s colours as a soft field under a dark veil. No live blur: the picture is decoded at
 * 48 × 27, blurred once on the processor ([TinyBlur]) and stretched with filtering.
 */
@Composable
fun TvBackdrop(url: String?, modifier: Modifier = Modifier, veil: Float = .55f) {
    val context = LocalContext.current
    val request = remember(url) {
        url?.let {
            ImageRequest.Builder(context).data(it).size(TvImages.BACKDROP_W, TvImages.BACKDROP_H).precision(Precision.EXACT)
                .transformations(TinyBlur()).allowHardware(false).crossfade(false).build()
        }
    }
    Box(modifier.background(TvDesign.Ink.page)) {
        if (request != null) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = veil * .7f), Color.Black.copy(alpha = veil), Color.Black.copy(alpha = (veil + .25f).coerceAtMost(.95f))))))
    }
}

/** A box blur on a tiny bitmap (a few thousand pixels): run once per cover, kept in Coil's memory cache. */
class TinyBlur(private val radius: Int = 3, private val passes: Int = 3) : coil.transform.Transformation {
    override val cacheKey: String = "tv-tiny-blur-$radius-$passes"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(passes) { blur(pixels, w, h, radius) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** One horizontal then one vertical box pass, edges clamped. Pure, host-tested. */
        fun blur(pixels: IntArray, w: Int, h: Int, radius: Int) {
            if (w <= 0 || h <= 0 || radius <= 0) return
            val tmp = IntArray(pixels.size)
            pass(pixels, tmp, w, h, radius, horizontal = true)
            pass(tmp, pixels, w, h, radius, horizontal = false)
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val length = if (horizontal) w else h
            for (line in 0 until lines) for (i in 0 until length) {
                var a = 0; var red = 0; var g = 0; var b = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, length - 1)
                    val p = if (horizontal) src[line * w + j] else src[j * w + line]
                    a += p ushr 24; red += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff
                }
                val n = 2 * r + 1
                dst[if (horizontal) line * w + i else i * w + line] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
