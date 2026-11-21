package com.ketvoice.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.ketvoice.app.ui.common.Icon

/**
 * Ket 27's animated symbols, in the manner of the effects of SF Symbols: a symbol replaced by
 * another through one shape, a bounce, a pulse, a fill that follows a progress. The numbers live
 * here so they can be checked without a screen; every effect snaps with reduced motion.
 */
internal object SymbolEffects {
    const val MORPH_DAMPING = .62f
    const val MORPH_STIFFNESS = 520f
    const val BOUNCE_MS = 110
    const val BOUNCE_DOWN = .80f
    const val BOUNCE_UP = 1.24f
    const val PULSE_PEAK = 1.20f
    /** How far "next" darts on a tap, in widths of its symbol. */
    const val SLIDE_SHARE = .2f
    /** The throw of a ringing bell (degrees per second) and the widest it swings. */
    const val RING_VELOCITY = 900f
    const val RING_MAX = 22f
    /** The breath of a symbol at work: how much it swells, and how long one way takes. */
    const val BREATH = .14f
    const val BREATH_MS = 640
    /** The size a replacing symbol starts from. */
    const val REPLACE_FROM = .5f

    /** While one symbol becomes another it dips: full size at both ends, 12 % smaller half-way. */
    fun replaceDip(progress: Float): Float {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
        return 1f - .12f * (4f * p * (1f - p))
    }

    /** How high a fill stands in its glyph for a progress of 0..1 (never quite empty once started, full only when done). */
    fun fillLevel(progress: Float): Float {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        return if (p <= 0f) 0f else if (p >= 1f) 1f else .06f + .88f * p
    }

    /** The part of a glyph, from its top, that a fill of [level] leaves empty: the fill rises from the bottom. */
    fun fillTop(level: Float, height: Float): Float = height * (1f - level.coerceIn(0f, 1f))

    /** The scale of a filled symbol that grows inside its outline: nothing below a fifth, then up to its size. */
    fun fillScale(amount: Float): Float = if (!amount.isFinite() || amount <= 0f) 0f else (.55f + .45f * amount).coerceAtMost(1.12f)
}

/**
 * Says once per change of a symbol's state that its effect should play (8.0): the first value never plays,
 * the same value twice plays once. Kept apart from the modifiers so it can be checked without a screen.
 */
internal class SymbolTrigger(initial: Any?) {
    private var seen: Any? = initial
    /** How many times an effect was asked for. */
    var plays: Int = 0
        private set
    fun changed(key: Any?): Boolean {
        if (key == seen) return false
        seen = key
        plays++
        return true
    }
}

/**
 * The bounce of a symbol whose state was just set by a tap: it gives way, springs past its size and
 * settles. [key] changes when the state does; the first value never bounces. With [enabled] false
 * (a heart emptied, a tab left) the change is taken without a bounce.
 */
internal fun Modifier.symbolBounce(key: Any?, enabled: Boolean = true): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val trigger = remember { SymbolTrigger(key) }
    LaunchedEffect(key, enabled) {
        if (!trigger.changed(key)) return@LaunchedEffect
        if (!enabled || Motion.reduced) { scale.snapTo(1f); return@LaunchedEffect }
        // 8.0: springs all the way (was a 110 ms tween in): a second tap mid-bounce carries on from where it is.
        scale.animateTo(SymbolEffects.BOUNCE_DOWN, spring(dampingRatio = 1f, stiffness = 1_600f))
        scale.animateTo(SymbolEffects.BOUNCE_UP, spring(dampingRatio = .5f, stiffness = 900f))
        scale.animateTo(1f, Motion.Springs.BOUNCY.spec())
    }
    graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/**
 * The pulse of a control switched on or off (shuffle, repeat): one swell, and, with [spin], half a
 * turn — the arrows of "repeat" go round once.
 */
internal fun Modifier.symbolPulse(key: Any?, enabled: Boolean = true, spin: Boolean = false): Modifier = composed {
    val pulse = remember { Animatable(0f) }
    val trigger = remember { SymbolTrigger(key) }
    LaunchedEffect(key, enabled) {
        if (!trigger.changed(key)) return@LaunchedEffect
        if (!enabled || Motion.reduced) { pulse.snapTo(0f); return@LaunchedEffect }
        pulse.animateTo(1f, Motion.Springs.SNAPPY.spec())
        pulse.animateTo(0f, spring(dampingRatio = .48f, stiffness = 380f))
    }
    graphicsLayer {
        val swell = 1f + (SymbolEffects.PULSE_PEAK - 1f) * pulse.value
        scaleX = swell; scaleY = swell
        if (spin) rotationZ = 180f * pulse.value
    }
}

/**
 * The slide of "next" and "previous" on a tap (8.0): the arrows dart [direction] (1 forward, −1 back) by a
 * fifth of their width, dimming, and spring back. [key] changes on every tap (a count).
 */
internal fun Modifier.symbolSlide(key: Any?, direction: Int): Modifier = composed {
    val shift = remember { Animatable(0f) }
    val trigger = remember { SymbolTrigger(key) }
    LaunchedEffect(key) {
        if (!trigger.changed(key) || Motion.reduced) return@LaunchedEffect
        shift.animateTo(1f, spring(dampingRatio = 1f, stiffness = 1_400f))
        shift.animateTo(0f, Motion.Springs.BOUNCY.spec())
    }
    graphicsLayer {
        translationX = direction * size.width * SymbolEffects.SLIDE_SHARE * shift.value
        alpha = 1f - .4f * shift.value.coerceIn(0f, 1f)
    }
}

/**
 * A bell that rings (8.0, new releases switched on): it swings from its top on an underdamped spring
 * thrown with [SymbolEffects.RING_VELOCITY] degrees a second, so it rings a few times and settles by itself.
 */
internal fun Modifier.symbolWiggle(key: Any?, enabled: Boolean = true): Modifier = composed {
    val angle = remember { Animatable(0f) }
    val trigger = remember { SymbolTrigger(key) }
    LaunchedEffect(key, enabled) {
        if (!trigger.changed(key)) return@LaunchedEffect
        if (!enabled || Motion.reduced) { angle.snapTo(0f); return@LaunchedEffect }
        angle.animateTo(0f, spring(dampingRatio = .16f, stiffness = 520f), initialVelocity = SymbolEffects.RING_VELOCITY)
    }
    graphicsLayer {
        rotationZ = angle.value.coerceIn(-SymbolEffects.RING_MAX, SymbolEffects.RING_MAX)
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .12f)
    }
}

/**
 * A symbol that breathes while something works ([active]: the Ketmix sign while it mixes): it swells by
 * [SymbolEffects.BREATH] and back, again and again, until the work ends. Still under reduced motion.
 * Read while drawing.
 */
internal fun Modifier.symbolBreathe(active: Boolean): Modifier = composed {
    if (!active || Motion.reduced) return@composed Modifier
    // An infinite transition, so tests and the system's animation policy can pause it.
    val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "symbol-breath").animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(SymbolEffects.BREATH_MS, easing = Motion.EASE_STANDARD),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "symbol-breath")
    graphicsLayer {
        val s = 1f + SymbolEffects.BREATH * breath.value
        scaleX = s; scaleY = s
    }
}

/**
 * One symbol replaced by another (8.0, as SF Symbols' replace): the old one shrinks and fades as the new one
 * grows in on a bouncy spring — shuffle on/off, the modes of repeat. The first symbol never animates.
 */
@Composable
internal fun ReplaceSymbol(icon: ImageVector, description: String?, tint: Color, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf(icon) }
    var previous by remember { mutableStateOf<ImageVector?>(null) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(icon) {
        if (icon == current) return@LaunchedEffect
        previous = current
        current = icon
        if (Motion.reduced) { swap.snapTo(1f); previous = null; return@LaunchedEffect }
        swap.snapTo(0f)
        swap.animateTo(1f, Motion.Springs.BOUNCY.spec())
        previous = null
    }
    Box(modifier) {
        previous?.let { old ->
            Icon(old, null, tint = tint, modifier = Modifier.matchParentSize().graphicsLayer {
                val q = swap.value.coerceIn(0f, 1f)
                alpha = 1f - q
                scaleX = 1f - .5f * q; scaleY = 1f - .5f * q
            })
        }
        Icon(current, description, tint = tint, modifier = Modifier.matchParentSize().graphicsLayer {
            val q = swap.value
            alpha = q.coerceIn(0f, 1f)
            val s = SymbolEffects.REPLACE_FROM + (1f - SymbolEffects.REPLACE_FROM) * q
            scaleX = s; scaleY = s
        })
    }
}

/**
 * A heart that fills: the outline stays, the filled heart grows inside it on a spring when the
 * title becomes a favourite and drains when it stops being one.
 */
@Composable
internal fun FillingHeart(filled: Boolean, outline: ImageVector, full: ImageVector, description: String?, tint: Color,
                          modifier: Modifier = Modifier) {
    val amount by animateFloatAsState(if (filled) 1f else 0f,
        if (Motion.reduced) snap() else if (filled) spring(dampingRatio = .5f, stiffness = 520f) else Motion.Springs.SNAPPY.spec(), label = "heart-fill")
    Box(modifier) {
        Icon(outline, description, tint = tint, modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - amount.coerceIn(0f, 1f) })
        if (amount > 0f) Icon(full, null, tint = tint, modifier = Modifier.matchParentSize().graphicsLayer {
            val grown = SymbolEffects.fillScale(amount)
            scaleX = grown; scaleY = grown
            alpha = (amount * 2f).coerceIn(0f, 1f)
        })
    }
}

/** The arrow into its tray, on a 24 grid scaled to the glyph. */
private fun downloadPath(size: Float): Path = Path().apply {
    val u = size / 24f
    // The shaft and the head of the arrow.
    moveTo(10.4f * u, 3f * u); lineTo(13.6f * u, 3f * u); lineTo(13.6f * u, 10.2f * u); lineTo(17.4f * u, 10.2f * u)
    lineTo(12f * u, 16.4f * u); lineTo(6.6f * u, 10.2f * u); lineTo(10.4f * u, 10.2f * u); close()
    // The tray.
    addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(4.4f * u, 18.2f * u, 19.6f * u, 21f * u),
        androidx.compose.ui.geometry.CornerRadius(1.4f * u)))
}

private fun DrawScope.symbol(path: Path, color: Color) {
    drawPath(path, color)
    drawPath(path, color, style = Stroke(size.minDimension / 24f * 1.1f, join = StrokeJoin.Round))
}

/**
 * A download that fills as it progresses: the arrow is drawn dim, and [fill] rises through it from
 * the bottom with the share of the file received (0..1). One path, drawn twice; no layer of its own.
 */
@Composable
internal fun FillingDownloadGlyph(progress: Float, modifier: Modifier = Modifier, fill: Color = Color.White,
                                  empty: Color = Color.White.copy(alpha = .32f)) {
    val level by animateFloatAsState(SymbolEffects.fillLevel(progress),
        if (Motion.reduced) snap() else spring(dampingRatio = .9f, stiffness = 180f), label = "download-fill")
    Canvas(modifier) {
        val path = downloadPath(size.minDimension)
        symbol(path, empty)
        if (level > 0f) clipRect(top = SymbolEffects.fillTop(level, size.height)) { symbol(path, fill) }
    }
}

/** A check mark that draws itself, for a download that just finished ([progress] 0..1 along the stroke). */
@Composable
internal fun DrawnCheck(progress: Float, modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val full = Path().apply {
            moveTo(size.width * .22f, size.height * .54f); lineTo(size.width * .43f, size.height * .74f)
            lineTo(size.width * .79f, size.height * .30f)
        }
        val measure = PathMeasure().apply { setPath(full, false) }
        val shown = Path()
        measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), shown, true)
        drawPath(shown, tint, style = Stroke(size.minDimension * .12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

