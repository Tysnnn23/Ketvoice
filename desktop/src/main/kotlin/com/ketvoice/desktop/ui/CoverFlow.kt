package com.ketvoice.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The full player's moving background, as the phone's `ArtworkFlow`: three copies of the cover blurred to colour
 * fields, larger than the window, turning slowly in opposite directions and drifting, so the artwork's colours
 * flow into each other. The blur is done once on a 48 px copy; each frame draws three small bitmaps scaled and
 * rotated. Paused, the colours slow to a stop; reduced motion keeps one still field; a new title's colours glide in.
 */
internal object CoverFlow {
    const val SIDE = 48
    val PERIODS = floatArrayOf(64f, -86f, 112f)
    val SCALES = floatArrayOf(2.1f, 1.7f, 1.35f)
    val ALPHAS = floatArrayOf(1f, .72f, .55f)
    const val GLIDE_MS = 900

    fun angle(index: Int, seconds: Float): Float = (seconds / PERIODS[index]) * 360f % 360f

    fun drift(index: Int, seconds: Float): Pair<Float, Float> {
        val t = seconds / (40f + index * 17f) * 2f * Math.PI.toFloat()
        val r = .10f + index * .04f
        return (cos(t) * r) to (sin(t * 1.3f) * r)
    }

    class Fields(val base: ImageBitmap, val soft: ImageBitmap)

    private val cache = object : LinkedHashMap<String, Fields>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Fields>?) = size > 8
    }

    fun cached(url: String): Fields? = synchronized(cache) { cache[url] }

    /** The fields of a decoded cover: blurred whole, and the same with a feathered edge. */
    fun fields(url: String, cover: ImageBitmap): Fields {
        cached(url)?.let { return it }
        val small = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(cover.toAwtImage(), 0, 0, SIDE, SIDE, null); dispose()
        }
        val pixels = small.getRGB(0, 0, SIDE, SIDE, null, 0, SIDE)
        blur(pixels, SIDE)
        val base = image(pixels)
        feather(pixels, SIDE)
        return Fields(base, image(pixels)).also { synchronized(cache) { cache[url] = it } }
    }

    private fun image(pixels: IntArray): ImageBitmap =
        BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, SIDE, SIDE, pixels, 0, SIDE) }.toComposeImageBitmap()

    /** Box blur, three passes: close to a gaussian. */
    fun blur(pixels: IntArray, side: Int, radius: Int = 4, passes: Int = 3) {
        val tmp = IntArray(pixels.size)
        repeat(passes) { pass(pixels, tmp, side, radius, true); pass(tmp, pixels, side, radius, false) }
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

    /** Fades a field's edge out: whole within 30 % of the side from its centre, gone at its edge. */
    fun feather(pixels: IntArray, side: Int, inner: Float = .30f, outer: Float = .5f) {
        val c = (side - 1) / 2f
        for (y in 0 until side) for (x in 0 until side) {
            val dx = (x - c) / side; val dy = (y - c) / side
            val t = ((outer - kotlin.math.sqrt(dx * dx + dy * dy)) / (outer - inner)).coerceIn(0f, 1f)
            val k = t * t * (3f - 2f * t)
            val i = y * side + x
            pixels[i] = (((pixels[i] ushr 24) * k).toInt().coerceIn(0, 255) shl 24) or (pixels[i] and 0xFFFFFF)
        }
    }
}

@Composable
internal fun CoverFlowBackground(url: String?, playing: Boolean, modifier: Modifier = Modifier) {
    val cover = rememberCover(url)
    val loaded by produceState(url?.let(CoverFlow::cached), url, cover) {
        if (url != null && cover != null) value = withContext(Dispatchers.Default) { runCatching { CoverFlow.fields(url, cover) }.getOrNull() }
    }
    // The previous title's colours stay until the new ones are ready, then fade out under them.
    var shown by remember { mutableStateOf(loaded) }
    var leaving by remember { mutableStateOf<CoverFlow.Fields?>(null) }
    val arrival = remember { Animatable(1f) }
    LaunchedEffect(loaded) {
        val next = loaded ?: return@LaunchedEffect
        if (next === shown) return@LaunchedEffect
        val instant = Motion.reduced || AppSettings.highPerformanceMode.value || shown == null
        leaving = if (instant) null else shown
        shown = next
        if (instant) { arrival.snapTo(1f); return@LaunchedEffect }
        arrival.snapTo(0f)
        arrival.animateTo(1f, tween(Motion.duration(CoverFlow.GLIDE_MS)))
        leaving = null
    }
    val still = Motion.reduced
    val seconds = rememberEasedClock(playing && !still, if (AppSettings.highPerformanceMode.value) 4 else 2)
    Canvas(modifier.fillMaxSize().testTag("cover-flow")) {
        val a = arrival.value
        val t = seconds.floatValue
        leaving?.let { if (still) drawStill(it, 1f - a) else drawFields(it, t, 1f - a) }
        shown?.let { if (still) drawStill(it, a) else drawFields(it, t, a) }
    }
}

private fun DrawScope.drawStill(field: CoverFlow.Fields, alpha: Float) {
    if (alpha <= 0f) return
    val side = (max(size.width, size.height) * 1.18f).toInt()
    drawImage(field.base, IntOffset.Zero, IntSize(field.base.width, field.base.height),
        IntOffset(((size.width - side) / 2f).toInt(), ((size.height - side) / 2f).toInt()), IntSize(side, side),
        alpha = .9f * alpha, filterQuality = FilterQuality.Medium)
}

private fun DrawScope.drawFields(fields: CoverFlow.Fields, t: Float, alpha: Float) {
    if (alpha <= 0f) return
    val side = max(size.width, size.height)
    for (i in 0 until 3) {
        val target = (side * CoverFlow.SCALES[i]).toInt()
        val (dx, dy) = CoverFlow.drift(i, t)
        val center = Offset(size.width / 2f + dx * size.width, size.height / 2f + dy * size.height)
        val field = if (i == 0) fields.base else fields.soft
        withTransform({ rotate(CoverFlow.angle(i, t), center) }) {
            drawImage(field, IntOffset.Zero, IntSize(field.width, field.height),
                IntOffset((center.x - target / 2f).toInt(), (center.y - target / 2f).toInt()), IntSize(target, target),
                alpha = CoverFlow.ALPHAS[i] * alpha, filterQuality = FilterQuality.Medium)
        }
    }
}

/** Seconds that run while [running]; at a pause they slow to a stop instead of freezing (the phone's eased clock). */
@Composable
private fun rememberEasedClock(running: Boolean, every: Int): MutableFloatState {
    val seconds = remember { mutableFloatStateOf(17f) } // a little way in: the fields already overlap at the first frame
    val speed = remember { Animatable(if (running) 1f else 0f) }
    LaunchedEffect(running) {
        val target = if (running) 1f else 0f
        if (Motion.reduced) speed.snapTo(target) else speed.animateTo(target, tween(Motion.duration(1400)))
    }
    LaunchedEffect(running, every) {
        var last = 0L; var pending = 0f; var frame = 0
        while (running || speed.value > 0f) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) pending += ((now - last) / 1e9f).coerceIn(0f, .1f) * speed.value
                last = now
                if (frame++ % every == 0) { seconds.floatValue += pending; pending = 0f }
            }
        }
    }
    return seconds
}
