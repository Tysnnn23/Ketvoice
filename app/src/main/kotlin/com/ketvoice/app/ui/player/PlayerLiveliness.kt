package com.ketvoice.app.ui.player

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.ketvoice.app.ui.motion.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * A livelier player (6.2, owner: « pochettes animées générées (léger mouvement) quand il n'y a pas de Canvas, fond
 * qui suit le rythme, transitions entre titres façon Apple Music »). Everything here is subtle, never warps the
 * artwork, is off under reduced motion and lighter in the performance mode and in Ketvoice Lite.
 */

private const val TAU = 2f * PI.toFloat()

/**
 * The generated motion of a cover without a Canvas: a slow zoom that breathes in and out, a drift that never shows
 * an edge (it stays within the zoom's margin), and now and then a soft light sweeping across, as over glass.
 */
internal object CoverDrift {
    /** Seconds of one breath of the zoom and one turn of the drift. */
    const val PERIOD_S = 28f
    /** The zoom at its deepest: three and a half percent. */
    const val ZOOM = .035f
    /** A light sweep every [SWEEP_EVERY_S] seconds of playback, lasting [SWEEP_S]. */
    const val SWEEP_EVERY_S = 14f
    const val SWEEP_S = 2.6f
    /** The sweep's brightest point. */
    const val SWEEP_ALPHA = .09f

    /** The scale at [seconds] of playback: 1 at the start, back to 1 every period. */
    fun scale(seconds: Float): Float = 1f + ZOOM * (.5f - .5f * cos(TAU * seconds / PERIOD_S))

    /** The shift at [seconds], as shares of the artwork's width and height, inside the zoom's margin. */
    fun shift(seconds: Float): Pair<Float, Float> {
        val margin = (scale(seconds) - 1f) * .45f
        val a = TAU * seconds / (PERIOD_S * 1.5f)
        return cos(a) * margin to sin(a * .8f) * margin
    }

    /** Where the sweep's band is (−0.4..1.4 across the artwork), or null between sweeps. */
    fun sweep(seconds: Float): Float? {
        val within = seconds % SWEEP_EVERY_S - (SWEEP_EVERY_S - SWEEP_S)
        if (seconds < SWEEP_EVERY_S - SWEEP_S || within < 0f) return null
        val u = within / SWEEP_S
        return -.4f + 1.8f * (u * u * (3f - 2f * u))
    }

    /** Whether the cover moves at all: the living artwork on, motion not reduced, not in the headset's window. */
    fun enabled(living: Boolean, reduced: Boolean, quest: Boolean): Boolean = living && !reduced && !quest

    /** In the performance mode and in Lite: the drift alone, a quarter of the frames. */
    fun light(performance: Boolean, lite: Boolean): Boolean = performance || lite
}

/** A clock of playback seconds that runs only while [running], every [every] frames. */
@Composable
internal fun rememberPlaybackClock(running: Boolean, every: Int): MutableFloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, every) {
        if (!running) return@LaunchedEffect
        var last = 0L
        var pending = 0f
        var frame = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) pending += ((now - last) / 1e9f).coerceIn(0f, .1f)
                last = now
                if (frame++ % every == 0) { seconds.floatValue += pending; pending = 0f }
            }
        }
    }
    return seconds
}

/**
 * Applies the generated motion to a cover (not to a Canvas). Paused, the cover holds where it is; read in the
 * layer and draw phases only, so the player never recomposes for it.
 */
@Composable
internal fun Modifier.generatedCoverMotion(playing: Boolean): Modifier {
    val on = CoverDrift.enabled(com.ketvoice.app.data.AppSettings.livingArtwork.value, Motion.reduced,
        com.ketvoice.app.BuildConfig.IS_QUEST)
    val light = CoverDrift.light(com.ketvoice.app.data.AppSettings.highPerformanceMode.value, com.ketvoice.app.LiteTuning.lite)
    val clock = rememberPlaybackClock(on && playing, if (light) 4 else 2)
    if (!on) return this
    return this.graphicsLayer {
        val s = clock.floatValue
        val zoom = CoverDrift.scale(s)
        val (dx, dy) = CoverDrift.shift(s)
        scaleX = zoom; scaleY = zoom
        translationX = dx * size.width; translationY = dy * size.height
    }.then(if (light) Modifier else Modifier.drawWithContent {
        drawContent()
        CoverDrift.sweep(clock.floatValue)?.let { at -> lightSweep(at) }
    })
}

/** A soft diagonal band of light at [at] (0 = top left, 1 = bottom right). */
private fun DrawScope.lightSweep(at: Float) {
    val d = size.width + size.height
    val centre = Offset(size.width * at, size.height * at)
    val reach = Offset(d * .16f, d * .16f)
    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = CoverDrift.SWEEP_ALPHA), Color.Transparent),
        centre - reach, centre + reach))
}

/**
 * The change of title in the player, after Apple Music: the outgoing cover steps back and fades, the new one comes
 * forward from a little further, on the side of the queue it comes from ([direction]: 1 next, −1 previous, 0 unknown).
 */
internal fun trackChange(direction: Int): ContentTransform {
    if (Motion.reduced) return fadeIn(tween(Motion.duration(160))).togetherWith(fadeOut(tween(Motion.duration(160))))
    val enter = fadeIn(tween(Motion.duration(380), delayMillis = 60)) +
        scaleIn(spring(dampingRatio = .82f, stiffness = 260f), initialScale = TrackChange.ENTER_SCALE) +
        slideInHorizontally(spring(dampingRatio = .9f, stiffness = 300f)) { w -> (direction * w * TrackChange.SLIDE).toInt() }
    val exit = fadeOut(tween(Motion.duration(260))) +
        scaleOut(tween(Motion.duration(360)), targetScale = TrackChange.EXIT_SCALE) +
        slideOutHorizontally(tween(Motion.duration(360))) { w -> (-direction * w * TrackChange.SLIDE).toInt() }
    return enter.togetherWith(exit)
}

/** The numbers of [trackChange], apart so they can be checked. */
internal object TrackChange {
    const val ENTER_SCALE = .92f
    const val EXIT_SCALE = .88f
    const val SLIDE = .08f

    /** 1 when [to] follows [from] in [queue], −1 when it comes before, 0 when either is not in it. */
    fun direction(queue: List<String>, from: String, to: String): Int {
        val a = queue.indexOf(from); val b = queue.indexOf(to)
        return if (a < 0 || b < 0 || a == b) 0 else if (b > a) 1 else -1
    }
}

/** The title and artist under the cover cross-fade from one title to the next, rising a little. */
internal fun <S> AnimatedContentTransitionScope<S>.titleChange(): ContentTransform =
    if (Motion.reduced) fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
    else (fadeIn(tween(Motion.duration(320), delayMillis = 80)) + slideInVertically(tween(Motion.duration(320))) { it / 5 })
        .togetherWith(fadeOut(tween(Motion.duration(180))))

/**
 * How much the background follows the music (the flowing cover colours breathe with the bass): none with reduced
 * motion, half in the performance mode and in Lite.
 */
internal object FlowBreath {
    /** The largest extra scale of the colour fields at full energy. */
    const val SCALE = .07f
    fun amplitude(reduced: Boolean, performance: Boolean, lite: Boolean): Float = when {
        reduced -> 0f
        performance || lite -> .5f
        else -> 1f
    }
    /** The energy 0..1 a bass level brings: the quiet floor stays still. */
    fun energy(low: Float): Float = if (!low.isFinite()) 0f else ((low - .15f) / .85f).coerceIn(0f, 1f)
}
