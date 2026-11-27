package com.ketvoice.app.tv

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import coil.request.ImageRequest
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cover's colours for the TV's backgrounds (1.1.0): four soft tones taken once from a 16 × 16 decode, kept per
 * picture. The colour field behind Now Playing is drawn from them, never from the picture itself.
 */
data class TvPalette(val colors: List<Color>) {
    init { require(colors.size == SIZE) }

    fun lerp(to: TvPalette, t: Float): TvPalette =
        if (t <= 0f) this else if (t >= 1f) to else TvPalette(List(SIZE) { lerp(colors[it], to.colors[it], t) })

    companion object {
        const val SIZE = 4
        const val SAMPLE = 16

        /** A calm violet-grey field while a cover's colours are not known (or it has none). */
        val Neutral = TvPalette(listOf(Color(0xFF2A2633), Color(0xFF1C2030), Color(0xFF30283A), Color(0xFF14141C)))

        /**
         * The four tones of an [w] × [h] picture: the average of each quarter, brought to a dark, slightly richer tone
         * so that white text always reads over them. Pure.
         */
        fun from(pixels: IntArray, w: Int, h: Int): TvPalette {
            if (w <= 1 || h <= 1 || pixels.size < w * h) return Neutral
            val hw = w / 2; val hh = h / 2
            val quarters = listOf(0 to 0, hw to 0, 0 to hh, hw to hh).map { (x0, y0) -> average(pixels, w, x0, y0, hw, hh) }
            return TvPalette(quarters.map(::tone))
        }

        private fun average(pixels: IntArray, w: Int, x0: Int, y0: Int, cw: Int, ch: Int): FloatArray {
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in y0 until y0 + ch) for (x in x0 until x0 + cw) {
                val p = pixels[y * w + x]
                if ((p ushr 24) < 32) continue
                r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
            }
            if (n == 0L) return floatArrayOf(.16f, .15f, .2f)
            return floatArrayOf(r / (255f * n), g / (255f * n), b / (255f * n))
        }

        /** A tone a little more saturated than the average and never brighter than 46 % (white text stays readable). */
        fun tone(rgb: FloatArray): Color {
            val hsv = hsv(rgb[0], rgb[1], rgb[2])
            val s = (hsv[1] * 1.15f).coerceIn(0f, .78f)
            val v = (hsv[2] * .62f).coerceIn(.1f, .46f)
            return Color.hsv(hsv[0], s, v)
        }

        /** Hue (degrees), saturation and value of an RGB colour in 0..1. */
        fun hsv(r: Float, g: Float, b: Float): FloatArray {
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
            val h = when {
                d <= 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }.let { if (it < 0f) it + 360f else it }
            return floatArrayOf(h, if (max <= 0f) 0f else d / max, max)
        }

        private val cache = object : LinkedHashMap<String, TvPalette>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TvPalette>?) = size > 48
        }

        @Synchronized fun cached(url: String): TvPalette? = cache[url]
        @Synchronized fun store(url: String, palette: TvPalette) { cache[url] = palette }
    }
}

/** [url]'s palette, read once (16 × 16, off the main thread) and remembered; [TvPalette.Neutral] until it is known. */
@Composable
fun rememberTvPalette(url: String?): State<TvPalette> {
    val context = LocalContext.current
    val state = remember(url) { mutableStateOf(url?.let(TvPalette::cached) ?: TvPalette.Neutral) }
    LaunchedEffect(url) {
        if (url == null || TvPalette.cached(url) != null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(TvPalette.SAMPLE, TvPalette.SAMPLE)
            .precision(coil.size.Precision.EXACT).allowHardware(false).build()
        val bitmap = (runCatching { coil.Coil.imageLoader(context).execute(request) }.getOrNull()?.drawable as? BitmapDrawable)?.bitmap
            ?: return@LaunchedEffect
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val palette = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { TvPalette.from(pixels, w, h) }
        TvPalette.store(url, palette)
        state.value = palette
    }
    return state
}

/**
 * The colour field behind Now Playing: four large soft lights in the cover's tones over the page's ink, drifting very
 * slowly where the TV has room for it ([TvTier.livingBackground]; still on light TVs and under reduced motion). The
 * colours and the drift are read while drawing: nothing recomposes as they move. [palette] gives the tones to draw
 * now (a blend during a Ketmix mix or a change of title).
 */
@Composable
fun TvColorField(palette: () -> TvPalette, modifier: Modifier = Modifier) {
    val moving = tvTier().livingBackground && !com.ketvoice.app.ui.motion.Motion.reduced
    val phase = remember { mutableFloatStateOf(0f) }
    if (moving) LaunchedEffect(Unit) {
        // About 15 steps a second are plenty for lights that take minutes to go round; the field is drawn only then.
        var last = 0L
        while (true) {
            androidx.compose.animation.core.withInfiniteAnimationFrameMillis { now ->
                if (now - last >= STEP_MS) { last = now; phase.floatValue = (now % PERIOD_MS) / PERIOD_MS.toFloat() }
            }
        }
    }
    // Its own layer: what moves over it never makes it draw again.
    Box(modifier.testTag("np-field").graphicsLayer().drawBehind { drawField(palette(), phase.floatValue) })
}

private const val STEP_MS = 66L
private const val PERIOD_MS = 90_000L

/** Where light [i] is at [phase] (0..1 of a slow loop), as a share of the size. */
internal fun fieldCenter(i: Int, phase: Float): Offset {
    val a = (phase + i * .25f) * 2f * Math.PI.toFloat()
    val base = FIELD_BASE[i]
    return Offset(base.x + .10f * cos(a), base.y + .08f * sin(a * (if (i % 2 == 0) 1f else -1f)))
}

private val FIELD_BASE = listOf(Offset(.18f, .22f), Offset(.82f, .2f), Offset(.25f, .85f), Offset(.78f, .8f))

private fun DrawScope.drawField(palette: TvPalette, phase: Float) {
    drawRect(lerp(TvDesign.Ink.page, palette.colors[3], .55f))
    val radius = size.maxDimension * .62f
    palette.colors.forEachIndexed { i, color ->
        val c = fieldCenter(i, phase)
        val center = Offset(c.x * size.width, c.y * size.height)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .9f), color.copy(alpha = 0f)), center, radius), radius, center)
    }
    // A soft veil keeps white text readable over the brightest of them.
    drawRect(Color.Black.copy(alpha = .22f))
}
