package com.ketvoice.app.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
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

    /**
     * Fades the edge of a square field of [pixels] ([side]×[side]) to nothing (7.4): fully there inside 30 % of
     * the side from its centre, gone at its edge. The smaller turning layers then never show a straight edge or a
     * corner, whatever their angle and drift.
     */
    fun feather(pixels: IntArray, side: Int, inner: Float = .30f, outer: Float = .5f) {
        val c = (side - 1) / 2f
        for (y in 0 until side) for (x in 0 until side) {
            val dx = (x - c) / side; val dy = (y - c) / side
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            val t = ((outer - r) / (outer - inner)).coerceIn(0f, 1f)
            val k = t * t * (3f - 2f * t)
            val i = y * side + x
            val a = ((pixels[i] ushr 24) * k).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (pixels[i] and 0xFFFFFF)
        }
    }

    /** The colour fields of a cover: the whole blurred field, and the same with a feathered edge. */
    class Fields(val base: ImageBitmap, val soft: ImageBitmap)

    private val cache = object : LinkedHashMap<String, Fields>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Fields>?) = size > 8
    }

    fun cached(url: String): Fields? = synchronized(cache) { cache[url] }

    /** The fields of the cover at [url], from memory (a few small bitmaps) or decoded and blurred once. */
    suspend fun load(context: android.content.Context, url: String): Fields? {
        synchronized(cache) { cache[url] }?.let { return it }
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(SIDE * 2)
            .allowHardware(false).build()) as? SuccessResult ?: return null
        val fields = withContext(Dispatchers.Default) {
            val small = result.drawable.toBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(SIDE * SIDE)
            small.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            blur(pixels, SIDE)
            val base = Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
            feather(pixels, SIDE)
            val soft = Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
            Fields(base.asImageBitmap(), soft.asImageBitmap())
        }
        synchronized(cache) { cache[url] = fields }
        return fields
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

/**
 * [still] (Fond animé off) draws one field, still, filling the screen: the blurred artwork as it was, without a
 * live blur of the full picture.
 */
@Composable
internal fun ArtworkFlowBackground(url: String?, playing: Boolean, modifier: Modifier = Modifier, still: Boolean = false) {
    val context = LocalContext.current
    val loaded: ArtworkFlow.Fields? = produceState(url?.let(ArtworkFlow::cached), url) {
        // The previous title's fields stay on screen until this one's are ready (see the crossfade below).
        value = if (url == null) null else runCatching { ArtworkFlow.load(context, url) }.getOrNull()
    }.value
    // The colours of one title glide into the next one's (6.2): the old fields stay until the new ones are ready,
    // then fade out under them. The performance mode drops the cross-fade, as it does for images.
    val fields = rememberFieldCrossfade(loaded)
    val moving = !still && playing && !Motion.reduced && !com.ketvoice.app.BuildConfig.IS_QUEST
    // The performance mode keeps the colours moving, at a quarter of the frames.
    val every = if (AppSettings.highPerformanceMode.value) 4 else 2
    // The clock runs while a title plays: paused, the colours slow down and hold where they were.
    // 7.3: at a pause they slow to a stop instead of freezing, and gather speed again on play.
    val seconds = rememberEasedPlaybackClock(moving, every)
    val energy = rememberFlowBreath(moving)
    if (fields.shown == null && fields.leaving == null) return
    Canvas(modifier.fillMaxSize()) {
        val shown = fields.shown
        val leaving = fields.leaving
        val arrival = fields.arrival.value
        if (still) {
            if (leaving != null) drawStill(leaving, 1f - arrival)
            if (shown != null) drawStill(shown, arrival)
            return@Canvas
        }
        val t = seconds.floatValue
        val breath = 1f + FlowBreath.SCALE * energy.floatValue
        if (leaving != null) drawFields(leaving, t, breath, 1f - arrival)
        if (shown != null) drawFields(shown, t, breath, arrival)
    }
}

/** One field filling the screen, a little larger than it so its blurred edge stays outside. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStill(field: ArtworkFlow.Fields, alpha: Float) {
    if (alpha <= 0f) return
    val side = (max(size.width, size.height) * 1.18f).toInt()
    drawImage(field.base, srcOffset = IntOffset.Zero, srcSize = IntSize(field.base.width, field.base.height),
        dstOffset = IntOffset(((size.width - side) / 2f).toInt(), ((size.height - side) / 2f).toInt()),
        dstSize = IntSize(side, side), alpha = .9f * alpha, filterQuality = FilterQuality.Medium)
}

/** The three turning colour fields of [field] at [t] seconds, larger by [breath], at [alpha]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFields(fields: ArtworkFlow.Fields, t: Float, breath: Float, alpha: Float) {
    if (alpha <= 0f) return
    val side = max(size.width, size.height)
    for (i in 0 until 3) {
        val target = (side * ArtworkFlow.SCALES[i] * breath).toInt()
        val (dx, dy) = ArtworkFlow.drift(i, t)
        val center = Offset(size.width / 2f + dx * size.width, size.height / 2f + dy * size.height)
        // The largest layer always covers the screen; the two smaller ones have a feathered edge (7.4).
        val field = if (i == 0) fields.base else fields.soft
        withTransform({ rotate(ArtworkFlow.angle(i, t), center) }) {
            drawImage(field, srcOffset = IntOffset.Zero, srcSize = IntSize(field.width, field.height),
                dstOffset = IntOffset((center.x - target / 2f).toInt(), (center.y - target / 2f).toInt()),
                dstSize = IntSize(target, target), alpha = ArtworkFlow.ALPHAS[i] * alpha, filterQuality = FilterQuality.Medium)
        }
    }
}

/** The fields shown, those leaving and how far the new ones have come in (0..1). */
internal class FieldCrossfade {
    var shown by androidx.compose.runtime.mutableStateOf<ArtworkFlow.Fields?>(null)
    var leaving by androidx.compose.runtime.mutableStateOf<ArtworkFlow.Fields?>(null)
    val arrival = androidx.compose.animation.core.Animatable(1f)
}

@Composable
private fun rememberFieldCrossfade(loaded: ArtworkFlow.Fields?): FieldCrossfade {
    // Fields already in memory show at once when the player opens; later ones glide in.
    val fields = remember { FieldCrossfade().apply { shown = loaded } }
    LaunchedEffect(loaded) {
        // A cover that cannot be read lets the previous title's fields fade out: its colours are not this title's.
        val next = loaded
        if (next === fields.shown) return@LaunchedEffect
        // 7.4: the first fields fade in over the cover's colour too, instead of appearing at once.
        val instant = Motion.reduced || AppSettings.highPerformanceMode.value
        fields.leaving = if (instant) null else fields.shown
        fields.shown = next
        if (instant) { fields.arrival.snapTo(1f); return@LaunchedEffect }
        fields.arrival.snapTo(0f)
        fields.arrival.animateTo(1f, androidx.compose.animation.core.tween(Motion.duration(PlayerColors.GLIDE_MS)))
        fields.leaving = null
    }
    return fields
}

/**
 * The colour fields breathe with the music (6.2): a smoothed bass energy, 0..1, read in the draw phase. It runs
 * only while the colours move, never under reduced motion, at half strength in the performance mode and in Lite.
 */
@Composable
private fun rememberFlowBreath(moving: Boolean): androidx.compose.runtime.MutableFloatState {
    val energy = remember { mutableFloatStateOf(0f) }
    val amplitude = FlowBreath.amplitude(Motion.reduced, AppSettings.highPerformanceMode.value, com.ketvoice.app.LiteTuning.lite)
    val on = moving && amplitude > 0f
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, on) {
        fun update() = com.ketvoice.app.player.SonicMeter.observe("flow-breath",
            on && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); com.ketvoice.app.player.SonicMeter.observe("flow-breath", false) }
    }
    LaunchedEffect(on, amplitude) {
        if (!on) { energy.floatValue = 0f; return@LaunchedEffect }
        var frame = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos {
                if (frame++ % 3 == 0) {
                    val target = FlowBreath.energy(com.ketvoice.app.player.SonicMeter.levels.value.low) * amplitude
                    energy.floatValue += (target - energy.floatValue) * .18f
                    com.ketvoice.app.player.SonicMeter.expire()
                }
            }
        }
    }
    return energy
}
