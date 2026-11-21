package com.ketvoice.app.ui.chrome

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.ketvoice.app.glass.GlassParams
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 8.3 « Pastille physique »: the selection of the tab bar is a small body moved by springs integrated by hand, one
 * small step at a time, rather than eased from tab to tab. Positions are in tab slots (0..2).
 *
 *  - travel: a spring pulls it to its tab, damped just under critical, so it arrives without a visible rebound;
 *  - lift: while it travels (or a finger holds it) it rises on an under-damped spring — it overshoots a little, then
 *    settles — and the lifted pill is a few dp taller than the bar, bending the bar's own glass like a lens;
 *    near its tab it sinks back and hands over to the flat pill;
 *  - squash and stretch: its acceleration stretches it along the bar and thins it, by at most [DEFORM_CAP].
 *
 * Under reduced motion it moves straight to its tab: no lift, no squash.
 */
internal class PillPhysics(start: Float = 0f) {
    var x = start; private set
    var velocity = 0f; private set
    var lift = 0f; private set
    var liftVelocity = 0f; private set
    /** 0..[DEFORM_CAP]: how much the pill is stretched along the bar right now. */
    var deform = 0f; private set

    fun snapTo(target: Float) {
        x = target; velocity = 0f; lift = 0f; liftVelocity = 0f; deform = 0f
    }

    /** Moves on by [dtSeconds] towards [target]; true while it is still moving. */
    fun step(dtSeconds: Float, target: Float, held: Boolean, reduced: Boolean): Boolean {
        val goal = if (target.isFinite()) target else x
        if (reduced) { snapTo(goal); return false }
        var left = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0f, MAX_FRAME_S) else 0f
        while (left > 1e-6f) {
            val h = minOf(left, SUBSTEP_S)
            substep(h, goal, held)
            left -= h
        }
        if (!held && atRest(goal)) { snapTo(goal); return false }
        return true
    }

    private fun substep(h: Float, target: Float, held: Boolean) {
        // Semi-implicit Euler: the speed first, then the place from the new speed (stable at these stiffnesses).
        val accel = TRAVEL_STIFFNESS * (target - x) - TRAVEL_DRAG * velocity
        velocity += accel * h
        x += velocity * h
        val travelling = held || abs(target - x) > NEAR_SLOTS || abs(velocity) > SLOW_SLOTS_PER_S
        val liftAccel = LIFT_STIFFNESS * ((if (travelling) 1f else 0f) - lift) - LIFT_DRAG * liftVelocity
        liftVelocity += liftAccel * h
        lift += liftVelocity * h
        // The stretch follows the acceleration, eased over a few hundredths of a second so it never pops.
        val wanted = DEFORM_CAP * tanh(abs(accel) / ACCEL_REF)
        deform += (wanted - deform) * (1f - exp(-h / DEFORM_EASE_S))
    }

    fun atRest(target: Float): Boolean = abs(target - x) < REST_SLOTS && abs(velocity) < REST_SPEED &&
        abs(lift) < REST_LIFT && abs(liftVelocity) < REST_SPEED && deform < REST_LIFT

    /** The lift as drawn: never below the flat pill, never past [MAX_LIFT]. */
    val shownLift: Float get() = lift.coerceIn(0f, MAX_LIFT)
    val stretchX: Float get() = 1f + deform
    /** Thinned as it stretches, so it keeps about the same area. */
    val stretchY: Float get() = 1f / (1f + deform)

    companion object {
        const val TRAVEL_STIFFNESS = 380f
        const val TRAVEL_DAMPING = .86f
        val TRAVEL_DRAG = 2f * TRAVEL_DAMPING * sqrt(TRAVEL_STIFFNESS)
        const val LIFT_STIFFNESS = 520f
        /** Under-damped: one overshoot of about 16 % (GlassMotion.overshoot), then it settles. */
        const val LIFT_DAMPING = .5f
        val LIFT_DRAG = 2f * LIFT_DAMPING * sqrt(LIFT_STIFFNESS)
        const val DEFORM_CAP = .12f
        /** The acceleration (slots/s²) at which the stretch reaches three quarters of its cap. */
        const val ACCEL_REF = 90f
        const val DEFORM_EASE_S = .045f
        const val MAX_LIFT = 1.25f
        const val SUBSTEP_S = 1f / 240f
        /** A longer frame (the app was paused) is not played out whole. */
        const val MAX_FRAME_S = .1f
        const val NEAR_SLOTS = .02f
        const val SLOW_SLOTS_PER_S = .25f
        const val REST_SLOTS = .001f
        const val REST_SPEED = .01f
        const val REST_LIFT = .002f
    }
}

/** How the lifted pill is drawn (8.3). */
internal object TabPill {
    /** At a lift of 1 the pill is this much taller than at rest: 4 dp taller than the bar. */
    const val LIFT_HEIGHT_DP = 14f
    const val LIFT_WIDTH_DP = 12f
    /** Below this lift the lens over the bar's glass is not drawn at all. */
    const val LENS_FROM = .04f

    /** How much of the lens shows over the flat pill: it takes over as the pill rises and hands back as it sinks. */
    fun lensAlpha(lift: Float): Float {
        val t = (((if (lift.isFinite()) lift else 0f) - .1f) / .6f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The lens: the selection's glass held clear, refracting more, with a brighter rim (the held lens of 6.0). */
    fun lensParams(dark: Boolean): GlassParams = GlassParams.selection(dark = dark).let { base ->
        base.copy(opticalPressure = 1f, lens = 1f, blurPx = 0f, tintAlpha = base.tintAlpha * .2f,
            refractionHeightDp = base.refractionHeightDp + 4f, refractionAmountDp = base.refractionAmountDp + 9f,
            highlightAlpha = base.highlightAlpha + .2f)
    }
}

/**
 * [PillPhysics] driven by the frame clock. [target], [held] and [reduced] are set from composition; the values the
 * pill is drawn with are states read only while laying out and drawing, so its motion never composes the bar again.
 */
@Stable
internal class PillMotion(start: Float) {
    private val physics = PillPhysics(start)
    var target by mutableFloatStateOf(start)
    var held by mutableStateOf(false)
    var reduced by mutableStateOf(false)
    private val xState = mutableFloatStateOf(start)
    private val liftState = mutableFloatStateOf(0f)
    private val deformState = mutableFloatStateOf(0f)

    val x: Float get() = xState.floatValue
    val lift: Float get() = liftState.floatValue
    val stretchX: Float get() = 1f + deformState.floatValue
    val stretchY: Float get() = 1f / (1f + deformState.floatValue)

    /** Runs for as long as the bar is shown; idle (no frame asked for) while the pill rests. */
    suspend fun run() {
        while (true) {
            snapshotFlow { Triple(target, held, reduced) }.first { (t, h, _) -> h || !physics.atRest(t) }
            var last = -1L
            var moving = true
            while (moving) {
                moving = withFrameNanos { now ->
                    val dt = if (last < 0L) 1f / 60f else (now - last) / 1e9f
                    last = now
                    physics.step(dt, target, held, reduced).also { publish() }
                }
            }
        }
    }

    private fun publish() {
        xState.floatValue = physics.x
        liftState.floatValue = physics.shownLift
        deformState.floatValue = physics.deform
    }
}
