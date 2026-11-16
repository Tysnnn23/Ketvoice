package com.ketvoice.app.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The player's moving background when a title has no Canvas, the way Apple Music does it: three
 * copies of the cover, blurred to colour fields, larger than the screen and turning slowly in
 * opposite directions, so that the colours of the artwork flow into each other.
 *
 * The blur is done once, on a 48 px copy (a few box-blur passes); each frame only draws three
 * small bitmaps scaled up and rotated, which costs the GPU almost nothing and never recomposes.
 */
internal object ArtworkFlow {
    const val SIDE = 48
    /** Seconds for a full turn of each layer; opposite signs turn the other way. */
    val PERIODS = floatArrayOf(64f, -86f, 112f)
    /** Each layer's size against the larger side of the screen. */
    val SCALES = floatArrayOf(2.1f, 1.7f, 1.35f)
    val ALPHAS = floatArrayOf(1f, .72f, .55f)

    /** The angle in degrees of a layer [index] at [seconds]. */
    fun angle(index: Int, seconds: Float): Float = (seconds / PERIODS[index]) * 360f % 360f

    /** A small circular drift of each layer's centre, as a share of the screen. */
    fun drift(index: Int, seconds: Float): Pair<Float, Float> {
        val t = seconds / (40f + index * 17f) * 2f * Math.PI.toFloat()
        val r = .10f + index * .04f
        return (cos(t) * r) to (sin(t * 1.3f) * r)
    }

    /** Box blur of [pixels] (ARGB, [side]×[side]) by [radius], in place, [passes] times: close to a gaussian. */
    fun blur(pixels: IntArray, side: Int, radius: Int = 4, passes: Int = 3) {
        val tmp = IntArray(pixels.size)
        repeat(passes) {
            pass(pixels, tmp, side, radius, horizontal = true)
            pass(tmp, pixels, side, radius, horizontal = false)
        }
    }

    private fun pass(src: IntArray, dst: IntArray, side: Int, radius: Int, horizontal: Boolean) {
        for (line in 0 until side) for (i in 0 until side) {
            var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
            for (k in -radius..radius) {
                val j = (i + k).coerceIn(0, side - 1)
                val p = if (horizontal) src[line * side + j] else src[j * side + line]
                a += p ushr 24 and 255; r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255; n++
            }
            val v = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            if (horizontal) dst[line * side + i] = v else dst[i * side + line] = v
        }
    }
}

@Composable
internal fun ArtworkFlowBackground(url: String?, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loaded: ImageBitmap? = produceState<ImageBitmap?>(null, url) {
        value = null
        if (url == null) return@produceState
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(ArtworkFlow.SIDE * 2)
            .allowHardware(false).build()) as? SuccessResult ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val small = result.drawable.toBitmap(ArtworkFlow.SIDE, ArtworkFlow.SIDE, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(ArtworkFlow.SIDE * ArtworkFlow.SIDE)
            small.getPixels(pixels, 0, ArtworkFlow.SIDE, 0, 0, ArtworkFlow.SIDE, ArtworkFlow.SIDE)
            ArtworkFlow.blur(pixels, ArtworkFlow.SIDE)
            Bitmap.createBitmap(pixels, ArtworkFlow.SIDE, ArtworkFlow.SIDE, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }.value
    val field = loaded ?: return
    val moving = playing && !Motion.reduced && !AppSettings.highPerformanceMode.value && !com.ketvoice.app.BuildConfig.IS_QUEST
    // The clock only runs while a title plays: paused, the colours hold still where they were.
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        var pending = 0f
        var frame = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) pending += ((now - last) / 1e9f).coerceIn(0f, .1f)
                last = now
                // Half rate: the fields move slowly, thirty images a second are plenty.
                if (frame++ % 2 == 0) { seconds.floatValue += pending; pending = 0f }
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val t = seconds.floatValue
        val side = max(size.width, size.height)
        for (i in 0 until 3) {
            val target = (side * ArtworkFlow.SCALES[i]).toInt()
            val (dx, dy) = ArtworkFlow.drift(i, t)
            val center = Offset(size.width / 2f + dx * size.width, size.height / 2f + dy * size.height)
            withTransform({ rotate(ArtworkFlow.angle(i, t), center) }) {
                drawImage(field, srcOffset = IntOffset.Zero, srcSize = IntSize(field.width, field.height),
                    dstOffset = IntOffset((center.x - target / 2f).toInt(), (center.y - target / 2f).toInt()),
                    dstSize = IntSize(target, target), alpha = ArtworkFlow.ALPHAS[i], filterQuality = FilterQuality.Medium)
            }
        }
    }
}
