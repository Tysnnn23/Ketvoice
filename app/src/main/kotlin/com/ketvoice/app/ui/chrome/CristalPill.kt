/*
 * The selection's spring constants, squash sensitivity and hand-over timings follow the motion of the
 * liquid_glass_easy package (pub.dev, 4.3.4, Copyright (c) 2025 Ahmed Gamil, MIT License). This file is
 * Ketvoice's own implementation of that motion.
 */
package com.ketvoice.app.ui.chrome

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * The Cristal tab bar's selection (8.4), in tab steps (0 the first tab). A tap lifts the pill at once on two
 * under-damped springs, one per axis (it swells past the bar and rebounds), and it stays up while a third spring
 * carries it to its tab; from 92 % of the way it lets the lift go, so it deflates where it lands, dipping under its
 * resting size and back. A finger holding it keeps the lift open. Its drawn position, differentiated twice over the
 * last 0.3 s, squashes it (width × (1 + d), height × (1 − d), |d| ≤ 12 %); a trip keeps one sense of squash, keyed
 * to where it is going. A fast, critically damped spring puts the glass on almost as soon as it is touched, and the
 * glass is handed back to the flat fill from the landing. Pure: the frame clock is [CristalPillMotion]'s.
 */
internal class CristalPillPhysics(initial: Int) {
    var position = initial.toFloat(); private set
    var liftX = 0f; private set
    var liftY = 0f; private set
    /** 0..1: how much of the glass material is on. */
    var material = 0f; private set
    /** The squash: width × (1 + this), height × (1 − this). */
    var deviation = 0f; private set
    /** 1 lifted glass .. 0 flat. */
    var glassPresence = 0f; private set
    /** Nothing left but the flat fill at rest: the plain pill draws the same picture. */
    var isFlat = true; private set
    var index = initial; private set
    /** One tab step in dp; the squash is measured in dp/s². */
    var stepDp = 0f
    var reduced = false

    private val travel = Spring1(initial.toDouble())
    private var travelGoal = initial.toDouble()
    private var travelStart = initial.toDouble()
    private var travelling = false
    private val width = Spring1(0.0)
    private val height = Spring1(0.0)
    private val glass = Spring1(0.0)
    private var up = false
    private var holding = false
    private var finger = 0.0
    private var follow = 0.0
    private var heading = 0.0
    private var headingEased = 0.0
    private var handover = 1.0
    private var squash = 0.0
    private val squashModel = SquashModel()

    /** A tab was chosen: off it goes, lifted. Choosing the tab it is on does nothing. */
    fun select(next: Int) {
        if (next == index) return
        if (reduced) { snap(next); return }
        index = next
        travelling = true
        up = true
        travelStart = travel.x
        travelGoal = next.toDouble()
        heading = headingOf(travelGoal - travelStart)
        isFlat = false
    }

    /** Placed on [target] at rest: no travel, no lift, no glass. */
    fun snap(target: Int) {
        index = target
        travel.reset(target.toDouble()); travelGoal = travel.x; travelStart = travel.x
        travelling = false; holding = false; up = false
        width.reset(0.0); height.reset(0.0); glass.reset(0.0)
        handover = 1.0; squash = 0.0; heading = 0.0; headingEased = 0.0
        squashModel.stop()
        publish(); isFlat = true
    }

    /** A finger picks the pill up where it is. */
    fun grab() {
        holding = true
        travelling = false
        up = !reduced
        heading = 0.0
        follow = travel.x; finger = travel.x
        travel.v = 0.0
        isFlat = false
    }

    /** The finger is over [at] tab steps. */
    fun hold(at: Float) { if (holding) finger = at.toDouble() }

    /** The finger let go: the pill settles on [next], still lifted, and comes down as a tap's does. */
    fun letGo(next: Int) {
        if (!holding) return
        val from = follow
        holding = false
        index = next
        if (reduced) { snap(next); return }
        travelling = true
        travel.reset(from)
        travelStart = from
        travelGoal = next.toDouble()
        heading = headingOf(travelGoal - travelStart)
    }

    /** Whether there is anything to move: the loop sleeps otherwise. */
    val active: Boolean get() = !isFlat

    /** Advances by [dt] seconds (clock [now], seconds); false once everything has settled. */
    fun step(dt: Double, now: Double): Boolean {
        val travelSettled = stepTravel(dt)
        if (holding) follow += if (reduced) finger - follow else (finger - follow) * (1 - exp(-dt / FOLLOW_TAU))
        if (!holding && (travelSettled || progress() >= HANDOVER_START)) up = false
        val goal = if (up) 1.0 else 0.0
        width.settleStep(goal, dt, LIFT_STIFFNESS, LIFT_DAMPING_X)
        height.settleStep(goal, dt, LIFT_STIFFNESS, LIFT_DAMPING_Y)
        glass.settleStep(goal, dt, MATERIAL_STIFFNESS, MATERIAL_DAMPING)
        val liftSettled = !up && width.x == 0.0 && height.x == 0.0 && glass.x == 0.0
        if (travelling && travelSettled && liftSettled && !holding) travelling = false
        stepSquash(dt, now)
        val handTo = if (up) 0.0 else 1.0
        handover += (handTo - handover) * (1 - exp(-dt / if (handTo > handover) HANDOVER_TAU else GLASS_RETURN_TAU))
        if (abs(handTo - handover) < .002) handover = handTo
        val settled = !travelling && !holding && liftSettled && abs(squash) < .0005 && handover >= 1.0
        if (settled) { squashModel.stop(); squash = 0.0; heading = 0.0; headingEased = 0.0 }
        publish()
        isFlat = settled
        return !settled
    }

    private fun stepTravel(dt: Double): Boolean {
        if (!travelling) return true
        travel.step(travelGoal, dt, TRAVEL_STIFFNESS, TRAVEL_DAMPING)
        val settled = abs(travel.x - travelGoal) < .003 && abs(travel.v) < .05
        if (settled) travel.reset(travelGoal)
        return settled
    }

    private fun stepSquash(dt: Double, now: Double) {
        if (stepDp <= 0f) return
        if (!squashModel.tracking) squashModel.start()
        squash = squashModel.track((if (holding) follow else travel.x) * stepDp, now, dt)
        if (headingEased == 0.0) headingEased = heading
        else if (headingEased != heading) {
            headingEased += (heading - headingEased) * (1 - exp(-dt / SIGN_TAU))
            if (abs(heading - headingEased) < .01) headingEased = heading
        }
        // The force's size, the sense of the trip.
        val key = headingEased
        if (key != 0.0) squash = squash * (1 - abs(key)) - key * abs(squash)
    }

    private fun progress(): Double {
        val span = abs(travelGoal - travelStart)
        return if (span < 1e-6) 1.0 else (1 - abs(travelGoal - travel.x) / span).coerceIn(0.0, 1.0)
    }

    private fun headingOf(span: Double): Double = if (abs(span) < 1e-6) 0.0 else sign(span)

    private fun publish() {
        position = (if (holding) follow else travel.x).toFloat()
        liftX = width.x.toFloat(); liftY = height.x.toFloat()
        material = glass.x.coerceIn(0.0, 1.0).toFloat()
        deviation = squash.toFloat()
        glassPresence = (1 - handover).coerceIn(0.0, 1.0).toFloat()
    }

    /** The pill's size now, between its [rest] size and its [lifted] one, squashed. */
    fun size(rest: Size, lifted: Size): Size = sizeOf(rest, lifted, liftX, liftY, deviation)

    companion object {
        const val TRAVEL_STIFFNESS = 280.0
        const val TRAVEL_DAMPING = 31.4
        /** Damping ratios 0.6 across and 0.7 down: the width overshoots a shade further. */
        const val LIFT_STIFFNESS = 250.0
        const val LIFT_DAMPING_X = 19.0
        const val LIFT_DAMPING_Y = 22.1
        /** Critically damped and four times as stiff as the lift. */
        const val MATERIAL_STIFFNESS = 1000.0
        const val MATERIAL_DAMPING = 63.3
        const val HANDOVER_START = .92
        const val HANDOVER_TAU = .09
        const val GLASS_RETURN_TAU = .05
        const val FOLLOW_TAU = .05
        const val SIGN_TAU = .25
        const val SUBSTEP = 1.0 / 240.0
        /** How much taller than the whole bar (its insets included) the lifted pill stands, in dp. */
        const val GROW_DP = 9f

        fun sizeOf(rest: Size, lifted: Size, liftX: Float, liftY: Float, deviation: Float): Size {
            val w = rest.width + (lifted.width - rest.width) * liftX
            val h = rest.height + (lifted.height - rest.height) * liftY
            return Size(w * (1 + deviation), h * (1 - deviation))
        }

        /** The lifted size: as tall as the row and both insets plus [GROW_DP], keeping the tab cell's proportions. */
        fun liftedSize(rest: Size, insetTopPx: Float, insetBottomPx: Float, growPx: Float): Size {
            if (rest.height <= 0f) return rest
            val h = rest.height + insetTopPx + insetBottomPx + growPx
            return Size(h * rest.width / rest.height, h)
        }

        /** The rim light and the contact shadow: gone once the hand-over is 55 % through. */
        fun rim(presence: Float): Float = ((presence - .45f) / .55f).coerceIn(0f, 1f)
    }
}

/** One damped spring, integrated by semi-implicit Euler in 1/240 s steps (a long frame cannot blow it up). */
private class Spring1(var x: Double) {
    var v = 0.0
    fun reset(at: Double) { x = at; v = 0.0 }

    fun step(goal: Double, dt: Double, k: Double, c: Double) {
        var left = dt
        while (left > 0) {
            val h = if (left > CristalPillPhysics.SUBSTEP) CristalPillPhysics.SUBSTEP else left
            v += (-k * (x - goal) - c * v) * h
            x += v * h
            left -= h
        }
    }

    /** [step], then snapped onto [goal] once it has nothing left to show. */
    fun settleStep(goal: Double, dt: Double, k: Double, c: Double) {
        step(goal, dt, k, c)
        if (abs(x - goal) < .0008 && abs(v) < .01) reset(goal)
    }
}

/**
 * Acceleration to squash: positions (dp) over the last [WINDOW] seconds, differentiated twice and averaged, scaled
 * and clamped to ±[MAX], then eased over [RESPONSE] seconds so one noisy frame cannot flick it.
 */
private class SquashModel {
    private val times = ArrayList<Double>()
    private val places = ArrayList<Double>()
    var tracking = false; private set
    private var value = 0.0

    fun start() { times.clear(); places.clear(); value = 0.0; tracking = true }
    fun stop() { tracking = false; times.clear(); places.clear(); value = 0.0 }

    fun track(x: Double, now: Double, dt: Double): Double {
        if (!tracking) return value
        times += now; places += x
        while (times.isNotEmpty() && times[0] < now - WINDOW) { times.removeAt(0); places.removeAt(0) }
        val raw = (acceleration() * SENSITIVITY).coerceIn(-MAX, MAX)
        value += (raw - value) * (dt / RESPONSE).coerceIn(0.0, 1.0)
        return value
    }

    private fun acceleration(): Double {
        if (times.size < 3) return 0.0
        var total = 0.0
        var count = 0
        var lastSpeed = Double.NaN
        var lastMid = 0.0
        for (i in 1 until times.size) {
            val span = times[i] - times[i - 1]
            if (span <= 0) continue
            val speed = (places[i] - places[i - 1]) / span
            val mid = (times[i] + times[i - 1]) / 2
            if (!lastSpeed.isNaN() && mid > lastMid) { total += (speed - lastSpeed) / (mid - lastMid); count++ }
            lastSpeed = speed; lastMid = mid
        }
        return if (count == 0) 0.0 else total / count
    }

    companion object {
        const val WINDOW = .3
        const val SENSITIVITY = .00007
        const val MAX = .12
        const val RESPONSE = .18
    }
}

/**
 * [CristalPillPhysics] on the frame clock. The published values are states read while laying out and drawing only;
 * the loop runs while the pill moves and sleeps at rest.
 */
@Stable
internal class CristalPillMotion(initial: Int) {
    private val physics = CristalPillPhysics(initial)
    /** Wakes the loop directly (no snapshot round trip), so a tap moves the pill on the very next frame. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val positionState = mutableFloatStateOf(initial.toFloat())
    private val liftXState = mutableFloatStateOf(0f)
    private val liftYState = mutableFloatStateOf(0f)
    private val materialState = mutableFloatStateOf(0f)
    private val deviationState = mutableFloatStateOf(0f)
    private val presenceState = mutableFloatStateOf(0f)
    /** Changes only when the pill lifts off and when it has landed, so composition follows it twice per trip. */
    var isFlat by mutableStateOf(true); private set

    val position: Float get() = positionState.floatValue
    val material: Float get() = materialState.floatValue
    val glassPresence: Float get() = presenceState.floatValue
    val index: Int get() = physics.index
    var stepDp: Float
        get() = physics.stepDp
        set(value) { physics.stepDp = value }
    var reduced: Boolean
        get() = physics.reduced
        set(value) { physics.reduced = value }

    fun size(rest: Size, lifted: Size): Size = CristalPillPhysics.sizeOf(rest, lifted, liftXState.floatValue,
        liftYState.floatValue, deviationState.floatValue)

    fun select(next: Int) { physics.select(next); kick() }
    fun snap(target: Int) { physics.snap(target); publish() }
    fun grab() { physics.grab(); kick() }
    fun hold(at: Float) = physics.hold(at)
    fun letGo(next: Int) { physics.letGo(next); kick() }

    private fun kick() { publish(); wake.trySend(Unit) }

    suspend fun run() {
        while (true) {
            while (!physics.active) wake.receive()
            var last = -1L
            var moving = true
            while (moving) {
                moving = withFrameNanos { now ->
                    val dt = if (last < 0L) 0.0 else (now - last) / 1e9
                    last = now
                    physics.step(dt.coerceAtMost(.1), now / 1e9).also { publish() }
                }
            }
        }
    }

    private fun publish() {
        positionState.floatValue = physics.position
        liftXState.floatValue = physics.liftX
        liftYState.floatValue = physics.liftY
        materialState.floatValue = physics.material
        deviationState.floatValue = physics.deviation
        presenceState.floatValue = physics.glassPresence
        isFlat = physics.isFlat
    }
}
