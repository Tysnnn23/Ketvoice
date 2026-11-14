package com.ketvoice.app.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** A value that changes over the positions of a title: straight lines between its points, flat outside them. */
internal class Curve(private val timesUs: LongArray, private val values: FloatArray) {
    init { require(timesUs.size == values.size && timesUs.isNotEmpty()) }
    private var hint = 0

    fun at(timeUs: Long): Float {
        val last = timesUs.size - 1
        if (timeUs <= timesUs[0]) return values[0]
        if (timeUs >= timesUs[last]) return values[last]
        // Positions are asked in order; the last segment is almost always still the right one.
        var i = hint
        if (i >= last || timesUs[i] > timeUs) i = 0
        while (i < last - 1 && timesUs[i + 1] <= timeUs) i++
        hint = i
        val span = timesUs[i + 1] - timesUs[i]
        if (span <= 0L) return values[i + 1]
        val t = (timeUs - timesUs[i]).toFloat() / span
        return values[i] + (values[i + 1] - values[i]) * t
    }

    val endUs: Long get() = timesUs.last()

    companion object {
        fun flat(value: Float) = Curve(longArrayOf(0L), floatArrayOf(value))

        /** [shape] maps progress 0..1 to a value; it is sampled finely enough to sound continuous. */
        fun shaped(fromUs: Long, toUs: Long, steps: Int = 32, shape: (Float) -> Float): Pair<LongArray, FloatArray> {
            val times = LongArray(steps + 1) { fromUs + (toUs - fromUs) * it / steps }
            val values = FloatArray(steps + 1) { shape(it.toFloat() / steps) }
            return times to values
        }

        fun join(vararg parts: Pair<LongArray, FloatArray>): Curve {
            val times = ArrayList<Long>(); val values = ArrayList<Float>()
            for ((t, v) in parts) for (i in t.indices) {
                if (times.isNotEmpty() && t[i] <= times.last()) continue
                times += t[i]; values += v[i]
            }
            return Curve(times.toLongArray(), values.toFloatArray())
        }
    }
}

/**
 * What one deck does to its sound during a transition, by position in its own title:
 * its level, and the cut-off of a high-pass filter that takes its bass away (0 = no filter).
 * Being tied to positions rather than to the clock, it stays on the music whatever the
 * audio buffering is, and simply waits while playback is paused.
 */
internal class DeckAutomation(val gain: Curve, val highPassHz: Curve) {
    val endUs: Long get() = maxOf(gain.endUs, highPassHz.endUs)

    companion object {
        /** Bass below this goes to one deck at a time. */
        const val BASS_HZ = 300f
        private const val OPEN_HZ = 28f
        /** Both decks at this level where the bass changes hands: full enough, without summing past full scale. */
        private const val MEETING = .72f
        /** Between a straight and an equal-power crossfade: no hole in the middle, no clipping either. */
        private const val POWER = 1.47f
        private const val SETTLE_US = 8_000_000L

        val MUTE = DeckAutomation(Curve.flat(0f), Curve.flat(0f))

        private fun fadeOut(x: Float) = cos(PI / 2 * x).toFloat().coerceAtLeast(0f).pow(POWER)
        private fun fadeIn(x: Float) = sin(PI / 2 * x).toFloat().coerceAtLeast(0f).pow(POWER)
        private fun ease(x: Float) = (.5 - .5 * cos(PI * x)).toFloat()
        private fun sweep(fromHz: Float, toHz: Float, x: Float) = exp(ln(fromHz) + (ln(toHz) - ln(fromHz)) * ease(x))

        /** The title that leaves. */
        fun outgoing(plan: MixPlan): DeckAutomation {
            val start = plan.outMs * 1000L
            val end = (plan.outMs + plan.overlapMs) * 1000L
            if (plan.kind != MixKind.Beatmatch || plan.swapMs < 0)
                return DeckAutomation(Curve.join(Curve.shaped(start, end, shape = ::fadeOut)), Curve.flat(0f))
            val swap = (plan.swapMs * 1000L).coerceIn(start + 1, end - 1)
            val half = (plan.beatMs * 500f).toLong().coerceIn(120_000L, 400_000L)
            val gain = Curve.join(
                Curve.shaped(start, swap, 16) { 1f - (1f - MEETING) * ease(it) },
                Curve.shaped(swap, end, 24) { MEETING * fadeOut(it) })
            val filter = Curve.join(
                longArrayOf(swap - half - 1) to floatArrayOf(0f),
                Curve.shaped(swap - half, swap + half, 16) { sweep(OPEN_HZ, BASS_HZ, it) })
            return DeckAutomation(gain, filter)
        }

        /** The title that arrives. Its positions run [MixPlan.rate] times faster than the outgoing clock. */
        fun incoming(plan: MixPlan): DeckAutomation {
            fun position(outMs: Long) = ((plan.inMs + (outMs - plan.outMs) * plan.rate.toDouble()) * 1000.0).toLong()
            val start = plan.inMs * 1000L
            val end = position(plan.outMs + plan.overlapMs)
            val settled = end + SETTLE_US
            // After the overlap the title comes back to its own level, slowly enough not to be noticed.
            val settle = Curve.shaped(end, settled, 8) { plan.gain + (1f - plan.gain) * ease(it) }
            if (plan.kind != MixKind.Beatmatch || plan.swapMs < 0)
                return DeckAutomation(Curve.join(Curve.shaped(start, end) { plan.gain * fadeIn(it) }, settle), Curve.flat(0f))
            val swap = position(plan.swapMs).coerceIn(start + 1, end - 1)
            val half = (plan.beatMs * 500f * plan.rate).toLong().coerceIn(120_000L, 400_000L)
            val gain = Curve.join(
                Curve.shaped(start, swap, 24) { plan.gain * MEETING * fadeIn(it) },
                Curve.shaped(swap, end, 16) { plan.gain * (MEETING + (1f - MEETING) * ease(it)) },
                settle)
            val filter = Curve.join(
                longArrayOf(0L) to floatArrayOf(BASS_HZ),
                Curve.shaped(swap - half, swap + half, 16) { sweep(BASS_HZ, OPEN_HZ, it) },
                longArrayOf(swap + half + 1) to floatArrayOf(0f))
            return DeckAutomation(gain, filter)
        }
    }
}

/**
 * Applies a [DeckAutomation] to interleaved 16-bit PCM: a level that moves sample by
 * sample (no zipper noise) and a second-order high-pass whose cut-off follows the curve.
 */
internal class DeckDsp(private val sampleRate: Int, private val channels: Int) {
    private val x1 = FloatArray(channels); private val x2 = FloatArray(channels)
    private val y1 = FloatArray(channels); private val y2 = FloatArray(channels)
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var cutoff = 0f
    private var gain = Float.NaN

    fun reset() {
        x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f)
        cutoff = 0f; gain = Float.NaN
    }

    private fun tune(hz: Float) {
        cutoff = hz
        val w = 2.0 * PI * hz.coerceIn(10f, sampleRate * .45f) / sampleRate
        val alpha = sin(w) / (2.0 * .7071)
        val c = cos(w)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + c) / 2.0 / a0).toFloat(); b1 = (-(1.0 + c) / a0).toFloat(); b2 = b0
        a1 = (-2.0 * c / a0).toFloat(); a2 = ((1.0 - alpha) / a0).toFloat()
    }

    /**
     * Reads all of [input] and writes the result to [output]. [startUs] is the position in
     * the title of the first frame. Without automation the sound passes through untouched.
     */
    fun process(input: ByteBuffer, output: ByteBuffer, startUs: Long, automation: DeckAutomation?) {
        if (automation == null) {
            if (!gain.isNaN() || cutoff > 0f) reset()
            output.put(input)
            return
        }
        input.order(ByteOrder.LITTLE_ENDIAN); output.order(ByteOrder.LITTLE_ENDIAN)
        val frames = input.remaining() / (2 * channels)
        var done = 0
        while (done < frames) {
            val block = minOf(BLOCK, frames - done)
            val blockUs = startUs + done * 1_000_000L / sampleRate
            val endUs = startUs + (done + block) * 1_000_000L / sampleRate
            val target = automation.gain.at(endUs)
            if (gain.isNaN()) gain = automation.gain.at(blockUs)
            val step = (target - gain) / block
            val hz = automation.highPassHz.at((blockUs + endUs) / 2)
            val filtering = hz > 0f
            if (filtering && abs(hz - cutoff) > cutoff * .01f) tune(hz)
            if (!filtering && cutoff > 0f) { x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f); cutoff = 0f }
            for (i in 0 until block) {
                gain += step
                for (c in 0 until channels) {
                    val x = input.short.toFloat()
                    val y = if (filtering) {
                        val v = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                        x2[c] = x1[c]; x1[c] = x; y2[c] = y1[c]; y1[c] = v
                        v
                    } else x
                    output.putShort((y * gain).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            gain = target
            done += block
        }
        // A trailing partial frame is passed on as it came.
        while (input.hasRemaining()) output.put(input.get())
    }

    private companion object { const val BLOCK = 64 }
}

/** Keeps the incoming title in time with the outgoing one, the way a DJ nudges a deck. */
internal object DeckSync {
    /** Milliseconds, on the outgoing clock, by which the incoming title is ahead (+) or behind (−). */
    fun errorMs(plan: MixPlan, outPositionMs: Long, inPositionMs: Long): Double =
        (inPositionMs - plan.inMs) / plan.rate.toDouble() - (outPositionMs - plan.outMs)

    /**
     * Speed to give the incoming title. Small errors are left alone (changing speed is
     * not free); larger ones are caught up at 4 % at most, in steps that cannot be heard.
     * While the title is audible only clear errors are corrected, and more gently.
     */
    fun speed(rate: Float, errorMs: Double, audible: Boolean): Float {
        val deadBand = if (audible) 12.0 else 4.0
        if (abs(errorMs) < deadBand) return rate
        val strength = if (audible) .00035 else .0008
        val correction = (errorMs * strength).coerceIn(-.04, .04)
        val speed = rate * (1.0 - correction)
        return (Math.round(speed / STEP) * STEP).toFloat()
    }

    /** Speeds from the mixing tempo back to the title's own, one per half second. */
    fun glide(rate: Float, steps: Int = 16): List<Float> =
        if (abs(rate - 1f) < .003f) listOf(1f)
        else (1..steps).map { step -> if (step == steps) 1f else (Math.round((rate + (1f - rate) * step / steps) / STEP) * STEP).toFloat() }

    private const val STEP = .002
}
