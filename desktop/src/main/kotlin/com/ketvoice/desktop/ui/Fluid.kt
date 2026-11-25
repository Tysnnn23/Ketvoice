package com.ketvoice.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin

/*
 * Windows 2.0's motion details: inertial wheel scrolling with elastic edges, the magnetic hover, the bounce of a
 * click and the burst of a like. Everything runs on the window's frame clock (vsync: the monitor's own rate, see
 * platform/FrameRate.kt) with time-based steps, so 60, 120 or 144 Hz move the same way, only smoother; under
 * « Réduire les animations » each one stands still.
 */

/** The arithmetic of [fluidScroll], kept apart for tests. All lengths in pixels, times in seconds. */
internal object FluidScroll {
    /** The time constant of a wheel notch's glide: most of it is done in 3 × TAU. */
    const val TAU = .085f
    /** How fast a stretched edge comes back. */
    const val RELAX = .11f
    /** One wheel notch, in dp. */
    const val NOTCH_DP = 76f

    /** The part of [pending] to scroll in a frame of [dt] seconds (an exponential glide, the same at any frame rate). */
    fun step(pending: Float, dt: Float): Float = pending * (1f - exp(-dt / TAU))

    /** How far content is shown past its edge for [over] pixels pushed into it: gives less and less, never past [limit]. */
    fun rubber(over: Float, limit: Float): Float {
        if (over == 0f || limit <= 0f) return 0f
        return sign(over) * limit * (1f - 1f / (abs(over) / limit * .55f + 1f))
    }

    /** [over] eased back towards 0 over [dt] seconds. */
    fun relax(over: Float, dt: Float): Float = if (abs(over) < .5f) 0f else over * exp(-dt / RELAX)
}

/** The state of one list's glide. */
@Stable
internal class FluidGlide(private val state: ScrollableState) {
    var pending = 0f; private set
    /** Pixels pushed past an edge (raw); [shown] is what is drawn. */
    var over by mutableFloatStateOf(0f); private set
    var limit = 120f
    val shown: Float get() = FluidScroll.rubber(over, limit)
    private var job: Job? = null

    fun add(px: Float, scope: CoroutineScope) {
        // A turn the other way cancels what was left of the last one, as a hand on a real wheel would.
        if (pending != 0f && sign(px) != sign(pending)) pending = 0f
        pending += px
        if (job?.isActive != true) job = scope.launch { run() }
    }

    private suspend fun run() {
        var last = withFrameNanos { it }
        while (abs(pending) > .5f || over != 0f) {
            withFrameNanos { now -> frame(((now - last) / 1e9f).coerceIn(0f, .05f)); last = now }
        }
        pending = 0f
    }

    /** One frame: scroll what the glide gives, push what the list refuses into the edge, ease the edge back. */
    fun frame(dt: Float) {
        val move = FluidScroll.step(pending, dt)
        pending -= move
        val left = move - state.dispatchRawDelta(move)
        over = if (abs(left) > .01f) over + left else FluidScroll.relax(over, dt)
        if (abs(pending) <= .5f && abs(left) <= .01f) pending = 0f
    }
}

/**
 * Smooth inertial wheel scrolling with elastic edges for a vertical list: each notch glides on, notches add up into
 * one movement, and pushing past the top or the bottom stretches the content a little before it settles back.
 * Shift + wheel (horizontal shelves) and reduced motion keep Compose's own scrolling.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.fluidScroll(state: ScrollableState): Modifier = composed {
    if (Motion.reduced) return@composed this
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val glide = remember(state) { FluidGlide(state) }
    val notch = with(density) { FluidScroll.NOTCH_DP.dp.toPx() }
    onSizeChanged { glide.limit = it.height * .12f }
        .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { e ->
            if (e.keyboardModifiers.isShiftPressed) return@onPointerEvent
            val change = e.changes.firstOrNull() ?: return@onPointerEvent
            val dy = change.scrollDelta.y
            if (dy == 0f || change.isConsumed) return@onPointerEvent
            e.changes.forEach { it.consume() }
            glide.add(dy * notch, scope)
        }
        .graphicsLayer { translationY = -glide.shown }
}

/** A gentle pull towards the pointer while it is over the element (the island's buttons, capsules). */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.magnetic(strength: Float = .18f, maxDp: Float = 5f): Modifier = composed {
    if (Motion.reduced) return@composed this
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val max = with(LocalDensity.current) { maxDp.dp.toPx() }
    val spec = spring<Offset>(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow)
    onSizeChanged { size = it }
        .onPointerEvent(PointerEventType.Move) { e ->
            val p = e.changes.first().position
            val target = Magnet.pull(p, size, strength, max)
            scope.launch { offset.animateTo(target, spec) }
        }
        .onPointerEvent(PointerEventType.Exit) { scope.launch { offset.animateTo(Offset.Zero, spec) } }
        .graphicsLayer { translationX = offset.value.x; translationY = offset.value.y }
}

internal object Magnet {
    /** The pull for a pointer at [p] in an element of [size]: a part of its distance from the centre, at most [max]. */
    fun pull(p: Offset, size: IntSize, strength: Float, max: Float): Offset {
        val c = Offset(size.width / 2f, size.height / 2f)
        val d = (p - c) * strength
        val len = d.getDistance()
        return if (len <= max || len == 0f) d else d * (max / len)
    }
}

/**
 * The burst of a like: a few accent dots fly out of the heart and fade when [liked] turns on (never when the page
 * opens on a liked title). Draws behind the heart's button.
 */
fun Modifier.likeBurst(liked: Boolean, color: Color): Modifier = composed {
    val progress = remember { Animatable(1f) }
    val before = remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        val turnedOn = liked && !before.value
        before.value = liked
        if (turnedOn && !Motion.reduced) { progress.snapTo(0f); progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 140f)) }
    }
    drawBehind {
        val t = progress.value
        if (t >= 1f) return@drawBehind
        val c = center
        val reach = size.minDimension * (.45f + .55f * t)
        for (i in 0 until Burst.DOTS) {
            val a = (i * 360f / Burst.DOTS + 12f) * (Math.PI / 180f).toFloat()
            drawCircle(color.copy(alpha = (1f - t) * color.alpha), size.minDimension * .07f * (1f - t * .6f),
                Offset(c.x + cos(a) * reach, c.y + sin(a) * reach))
        }
    }
}

internal object Burst { const val DOTS = 8 }

/** A spring scale that pops when [key] changes to true (a toggle's icon: shuffle, repeat, like). */
@Composable
internal fun rememberPop(on: Boolean): Animatable<Float, *> {
    val pop = remember { Animatable(1f) }
    val before = remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        val changed = on != before.value
        before.value = on
        if (changed && !Motion.reduced) {
            pop.snapTo(if (on) .7f else .9f)
            pop.animateTo(1f, spring(dampingRatio = .38f, stiffness = 520f))
        }
    }
    return pop
}
