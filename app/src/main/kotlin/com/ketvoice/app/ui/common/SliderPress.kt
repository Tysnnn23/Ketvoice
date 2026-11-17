package com.ketvoice.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.RubberBand
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * How a slider's track changes under the finger (Ket 27, the press-and-hold of iOS 26/27 sliders):
 * [rest] and [held] are its thickness in dp, [grow] how far each end reaches out while held, and
 * [maxStretch] the most the rubber band shows past an end when the finger overshoots it.
 */
@Immutable
data class SliderPressSpec(val rest: Float, val held: Float, val grow: Float, val maxStretch: Float)

/** The track as drawn this frame, in px: its two ends, its thickness, and the inset of the value's travel. */
@Immutable
data class SliderTrack(val left: Float, val right: Float, val thickness: Float, val inset: Float = 0f) {
    val length: Float get() = right - left
    private val travel: Float get() = (length - 2f * inset).coerceAtLeast(1f)
    /** Where a fraction (0..1, left to right) of the value lies on the track. */
    fun xOf(fraction: Float): Float = left + inset + travel * (if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f)
    /** The fraction a finger at [x] points to: what is under the finger is what is set. */
    fun fractionAt(x: Float): Float = if (!x.isFinite()) 0f else ((x - left - inset) / travel).coerceIn(0f, 1f)
    /** How far [x] is past an end (negative before the start, positive after the end, 0 on the track). */
    fun overshoot(x: Float): Float = when {
        !x.isFinite() -> 0f
        x > right - inset -> x - (right - inset)
        x < left + inset -> x - (left + inset)
        else -> 0f
    }
}

/** The maths of the press expansion, kept pure so it can be tested without a screen. */
object SliderPress {
    /** The seek bar of the player: 5 dp at rest, 12 dp under the finger. */
    val Scrub = SliderPressSpec(rest = 5f, held = 12f, grow = 4f, maxStretch = 10f)
    /** The volume of the player: 6 dp at rest, 12 dp under the finger. */
    val Volume = SliderPressSpec(rest = 6f, held = 12f, grow = 4f, maxStretch = 12f)
    /** The shared glass slider (settings, equalizer…), whose glass thumb also swells. */
    val Glass = SliderPressSpec(rest = 6f, held = 10f, grow = 3f, maxStretch = 10f)

    /** Under reduced motion the track only thickens a little, at once: no stretch, no reach, no rubber band. */
    const val REDUCED_THICKENING = 2f
    /** How much thinner the track gets at the most stretched point of its rubber band (it looks pulled). */
    const val SQUEEZE = .18f
    /** A press that lasts this long without moving or scrolling counts as a hold (a quick tap does not flash). */
    const val HOLD_DELAY_MS = 90L

    /** What the expansion springs towards. */
    fun target(held: Boolean): Float = if (held) 1f else 0f

    /** Swelling is quick and barely bounces; letting go settles back with a small bounce, as on iOS. */
    fun expansionSpec(held: Boolean, reduced: Boolean): AnimationSpec<Float> = when {
        reduced -> snap()
        held -> spring(dampingRatio = .72f, stiffness = 620f)
        else -> spring(dampingRatio = .52f, stiffness = 420f)
    }

    /** The rubber band snaps back with a lively bounce once the finger lets go or comes back on the track. */
    fun stretchSpec(reduced: Boolean): AnimationSpec<Float> =
        if (reduced) snap() else spring(dampingRatio = .45f, stiffness = 700f)

    /** The rubber band for a finger [overPx] past an end: it follows less and less, never beyond [maxPx]. */
    fun stretchFor(overPx: Float, maxPx: Float): Float =
        if (!overPx.isFinite() || maxPx <= 0f) 0f else RubberBand.displayed(overPx, maxPx).coerceIn(-maxPx, maxPx)

    /** Thickness in dp for an expansion (the spring may overshoot a little either side). */
    fun thicknessDp(spec: SliderPressSpec, expansion: Float, reduced: Boolean = false): Float {
        val e = if (expansion.isFinite()) expansion.coerceIn(-.2f, 1.3f) else 0f
        val held = if (reduced) spec.rest + minOf(REDUCED_THICKENING, spec.held - spec.rest) else spec.held
        return spec.rest + (held - spec.rest) * e
    }

    /**
     * The track for a slider whose ends lie at [start] and [end] px at rest. [expansion] is 0 at rest and 1
     * held; [stretchPx] the rubber band (negative: pulled past the start). [inset] is where the value's
     * travel starts within the track (half a thumb on a slider that has one).
     */
    fun track(spec: SliderPressSpec, density: Density, start: Float, end: Float, expansion: Float,
              stretchPx: Float = 0f, reduced: Boolean = false, inset: Float = 0f): SliderTrack {
        val px = density.density
        val e = if (expansion.isFinite()) expansion.coerceIn(-.2f, 1.3f) else 0f
        val reach = if (reduced) 0f else spec.grow * px * e
        val maxPx = spec.maxStretch * px
        val s = if (reduced || !stretchPx.isFinite()) 0f else stretchPx.coerceIn(-maxPx, maxPx)
        val squeeze = 1f - SQUEEZE * (if (maxPx > 0f) abs(s) / maxPx else 0f)
        return SliderTrack(
            left = start - reach + minOf(s, 0f),
            right = end + reach + maxOf(s, 0f),
            thickness = thicknessDp(spec, e, reduced) * px * squeeze,
            inset = inset
        )
    }

    /** How far a label or icon beside the track moves out to make room, in dp (positive: outwards). */
    fun labelShiftDp(spec: SliderPressSpec, expansion: Float, reduced: Boolean = false): Float =
        if (reduced) 0f else spec.grow * (if (expansion.isFinite()) expansion.coerceIn(-.2f, 1.3f) else 0f)

    /** How much a label or icon beside the track grows while held (and a little more under the rubber band). */
    fun labelScale(expansion: Float, stretchShare: Float = 0f, reduced: Boolean = false): Float =
        if (reduced) 1f else 1f + .06f * (if (expansion.isFinite()) expansion.coerceIn(-.2f, 1.3f) else 0f) +
            .12f * (if (stretchShare.isFinite()) stretchShare.coerceIn(0f, 1f) else 0f)
}

/** The live state of a slider's press: whether it is held, and its two springs (expansion, rubber band). */
@Stable
class SliderPressState internal constructor(private val scope: CoroutineScope) {
    var held by mutableStateOf(false)
        private set
    private val expansionAnim = Animatable(0f)
    private val stretchAnim = Animatable(0f)
    /** 0 at rest, 1 held (springs between them; read it while drawing to avoid recomposing). */
    val expansion: Float get() = expansionAnim.value
    /** The rubber band shown past an end, in px (negative past the start). */
    val stretch: Float get() = stretchAnim.value

    fun press() {
        if (held) return
        held = true
        val reduced = Motion.reduced
        scope.launch { expansionAnim.animateTo(SliderPress.target(true), SliderPress.expansionSpec(true, reduced)) }
    }

    fun release() {
        val reduced = Motion.reduced
        if (held) {
            held = false
            scope.launch { expansionAnim.animateTo(SliderPress.target(false), SliderPress.expansionSpec(false, reduced)) }
        }
        if (stretchAnim.value != 0f || stretchAnim.isRunning) scope.launch { stretchAnim.animateTo(0f, SliderPress.stretchSpec(reduced)) }
    }

    /** The finger is [overPx] past an end (0 on the track): the rubber band follows it at once, or springs home. */
    fun pull(overPx: Float, maxPx: Float) {
        if (Motion.reduced) { if (stretchAnim.value != 0f) scope.launch { stretchAnim.snapTo(0f) }; return }
        val next = SliderPress.stretchFor(overPx, maxPx)
        if (next == 0f) {
            if (stretchAnim.value != 0f && stretchAnim.targetValue != 0f) scope.launch { stretchAnim.animateTo(0f, SliderPress.stretchSpec(false)) }
        } else scope.launch { stretchAnim.snapTo(next) }
    }
}

@Composable
fun rememberSliderPress(): SliderPressState {
    val scope = rememberCoroutineScope()
    return remember(scope) { SliderPressState(scope) }
}

/**
 * Watches the finger on a slider: a hold (or a drag along it) presses it; lifting the finger, or the
 * page scrolling away under it, lets it go. It never consumes anything, so the slider's own tap and
 * drag handlers keep working as before. [vertical] is for a slider dragged up and down.
 */
fun Modifier.sliderPress(state: SliderPressState, enabled: Boolean = true, vertical: Boolean = false): Modifier =
    if (!enabled) this else pointerInput(state, vertical) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = down.position
            fun along(o: Offset) = if (vertical) abs(o.y) else abs(o.x)
            fun across(o: Offset) = if (vertical) abs(o.x) else abs(o.y)
            // A quick tap does not flash; a scroll of the page does not press; a drag along the slider does at once.
            val decided: Boolean? = withTimeoutOrNull(SliderPress.HOLD_DELAY_MS) {
                var verdict: Boolean? = null
                while (verdict == null) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    val moved = (change?.position ?: origin) - origin
                    verdict = when {
                        change == null || !change.pressed -> false
                        across(moved) > slop && across(moved) > along(moved) -> false
                        along(moved) > slop -> true
                        else -> null
                    }
                }
                verdict
            }
            if (decided == false) return@awaitEachGesture
            state.press()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val moved = change.position - origin
                    // The page took the gesture over (it scrolls across the slider): let go.
                    if (change.isConsumed && across(moved) > slop && across(moved) > along(moved) * 1.5f) break
                }
            } finally {
                state.release()
            }
        }
    }

/**
 * Draws a pressable track: a capsule rail, a capsule fill up to [fraction] with its two ends rounded,
 * and while it is held a soft glass sheen over the enlarged rail and a small light where the fill ends.
 */
fun DrawScope.drawPressTrack(
    track: SliderTrack,
    fraction: Float,
    trackColor: Color,
    fillColor: Color,
    expansion: Float,
    highlight: Boolean = true,
    direction: LayoutDirection = LayoutDirection.Ltr,
    showEmptyFill: Boolean = false
) {
    val t = track.thickness.coerceAtLeast(1f)
    val y = size.height / 2f
    val top = y - t / 2f
    val radius = CornerRadius(t / 2f, t / 2f)
    val e = expansion.coerceIn(0f, 1f)
    drawRoundRect(trackColor, Offset(track.left, top), Size(track.length, t), radius)
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val endLtr = track.xOf(f)
    val filled = f > .001f || showEmptyFill
    // The fill is a capsule of its own inside the rail: rounded at both ends, at least as long as it is thick.
    val fillLeft: Float
    val fillRight: Float
    if (direction == LayoutDirection.Rtl) {
        fillRight = track.right
        fillLeft = minOf(track.right - (endLtr - track.left), track.right - t)
    } else {
        fillLeft = track.left
        fillRight = maxOf(endLtr, track.left + t)
    }
    if (filled) drawRoundRect(fillColor, Offset(fillLeft, top), Size(fillRight - fillLeft, t), radius)
    // A line of light along the top of the fill: the bar reads as glass, brighter while held.
    if (highlight && filled && fillRight - fillLeft > t * 1.2f) drawLine(
        Color.White.copy(alpha = .22f + .16f * e),
        Offset(fillLeft + t * .3f, y - t * .22f), Offset(fillRight - t * .3f, y - t * .22f),
        strokeWidth = (t * .16f).coerceAtLeast(1f), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    if (e > .01f) {
        // The sheen of the enlarged glass: light along its upper half, fading towards the middle.
        drawRoundRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = .26f * e), .55f to Color.Transparent,
                startY = top, endY = top + t),
            Offset(track.left, top), Size(track.length, t), radius)
        // And a small glow where the fill ends, under the finger.
        if (filled) {
            val tip = if (direction == LayoutDirection.Rtl) fillLeft + t / 2f else fillRight - t / 2f
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .30f * e), Color.Transparent),
                center = Offset(tip, y), radius = t * 1.4f), radius = t * 1.4f, center = Offset(tip, y))
        }
    }
}
