package com.ketvoice.desktop.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Tempo change with the pitch kept, for Ketmix's tempo match and [com.ketvoice.app.automix.TempoReturn]:
 * WSOLA (waveform-similarity overlap-add, the method of FFmpeg's `atempo`) on 48 kHz stereo float.
 * Windows of [WINDOW] frames overlap by half under a periodic Hann window (their sum is exactly 1); each
 * new window is taken within [TOLERANCE] of where the tempo puts it, where it best continues the last one.
 * At speed 1 every window lands where the last one ends and the output is the input, sample for sample,
 * so a deck can keep its stretcher after a glide back without a click.
 *
 * [read] fills up to n frames and returns how many it gave (0 = nothing yet); [ended] says no more will come.
 */
class TimeStretch(
    private val read: (FloatArray, Int, Int) -> Int,
    private val ended: () -> Boolean,
    /** Title frame of the first input frame. */
    startFrame: Long,
) {
    /** Input frames per output frame; changed between two windows. */
    @Volatile var speed = 1f

    private val hann = FloatArray(WINDOW) { (.5 - .5 * cos(2 * PI * it / WINDOW)).toFloat() }
    private var input = FloatArray(WINDOW * 8 * 2)
    private var count = 0
    /** Title frame of input[0]. */
    private var base = startFrame
    private var nominal = startFrame.toDouble()
    private var prev = Long.MIN_VALUE
    private val tail = FloatArray(HOP * 2)
    private val hop = FloatArray(HOP * 2)
    private var hopFrom = 0
    private var hopLeft = 0
    private var hopTitle = startFrame
    private var drained = false
    /** Title frame just past the last real input frame, once the input ended. */
    private var inputEnd = Long.MAX_VALUE

    /** Title frame of the next frame [process] gives. */
    val titleFrame: Long get() = if (hopLeft > 0) hopTitle + hopFrom else nominal.roundToLong()

    /** True once the input ended and everything was given. */
    val done: Boolean get() = drained && hopLeft == 0

    /** Gives up to [frames] frames into [out] at frame [offset]; fewer when the input has nothing yet. */
    fun process(out: FloatArray, offset: Int, frames: Int): Int {
        var done = 0
        while (done < frames) {
            if (hopLeft == 0 && !nextHop()) break
            val n = minOf(hopLeft, frames - done)
            System.arraycopy(hop, hopFrom * 2, out, (offset + done) * 2, n * 2)
            hopFrom += n; hopLeft -= n; done += n
        }
        return done
    }

    /** Builds the next [HOP] frames of output; false when the input cannot give them yet. */
    private fun nextHop(): Boolean {
        if (drained) return false
        val natural = if (prev == Long.MIN_VALUE) Long.MIN_VALUE else prev + HOP
        val target = nominal.roundToLong()
        val fixed = prev == Long.MIN_VALUE || (abs(speed - 1f) < 1e-4f && target == natural)
        val from = if (fixed) (if (prev == Long.MIN_VALUE) target else natural) else target - TOLERANCE
        val until = (if (fixed) from else target + TOLERANCE) + WINDOW
        val need = maxOf(until, if (natural == Long.MIN_VALUE) 0L else natural + HOP)
        if (!fill(need)) {
            if (!ended()) return false
            // The end: what is left is padded with silence once, then the stretch is over.
            if (inputEnd == Long.MAX_VALUE) inputEnd = base + count
            if (from >= inputEnd) { drained = true; return false }
            pad(need)
        }
        val q = if (fixed) from else best(maxOf(base, target - TOLERANCE), target + TOLERANCE, natural)
        val at = ((q - base) * 2).toInt()
        if (prev == Long.MIN_VALUE) {
            // The first window: its first half as it is, as though a perfectly aligned one came before.
            System.arraycopy(input, at, hop, 0, HOP * 2)
        } else {
            for (i in 0 until HOP) {
                val w = hann[i]
                hop[i * 2] = tail[i * 2] + w * input[at + i * 2]
                hop[i * 2 + 1] = tail[i * 2 + 1] + w * input[at + i * 2 + 1]
            }
        }
        for (i in 0 until HOP) {
            val w = hann[HOP + i]
            tail[i * 2] = w * input[at + (HOP + i) * 2]
            tail[i * 2 + 1] = w * input[at + (HOP + i) * 2 + 1]
        }
        hopTitle = q; hopFrom = 0; hopLeft = HOP
        prev = q
        nominal += HOP * speed.toDouble()
        // Frames no later window can reach are dropped.
        val keep = minOf(nominal.roundToLong() - TOLERANCE, prev + HOP)
        drop((keep - base).toInt().coerceIn(0, count))
        return true
    }

    /** The window start in [lo, hi] whose first half best continues [natural] (normalised correlation, every 4th frame). */
    private fun best(lo: Long, hi: Long, natural: Long): Long {
        val n = ((natural - base) * 2).toInt()
        var bestQ = (lo + hi) / 2
        var bestScore = Double.NEGATIVE_INFINITY
        var q = lo
        while (q <= hi) {
            val a = ((q - base) * 2).toInt()
            var dot = 0.0; var energy = 1e-9
            var i = 0
            while (i < HOP) {
                val x = input[a + i * 2] + input[a + i * 2 + 1]
                val y = input[n + i * 2] + input[n + i * 2 + 1]
                dot += x * y; energy += x * x
                i += 4
            }
            val score = dot / sqrt(energy)
            if (score > bestScore) { bestScore = score; bestQ = q }
            q++
        }
        return bestQ
    }

    /** Reads until the input holds frames up to [untilFrame] (exclusive); false when it has not got them yet. */
    private fun fill(untilFrame: Long): Boolean {
        val wanted = (untilFrame - base).toInt()
        if (wanted * 2 > input.size) input = input.copyOf(wanted * 2 + WINDOW * 2)
        while (count < wanted) {
            val n = read(input, count, wanted - count)
            if (n <= 0) return false
            count += n
        }
        return true
    }

    private fun pad(untilFrame: Long) {
        val wanted = (untilFrame - base).toInt()
        if (wanted * 2 > input.size) input = input.copyOf(wanted * 2)
        if (count < wanted) { java.util.Arrays.fill(input, count * 2, wanted * 2, 0f); count = wanted }
    }

    private fun drop(frames: Int) {
        if (frames <= 0) return
        System.arraycopy(input, frames * 2, input, 0, (count - frames) * 2)
        count -= frames; base += frames
    }

    companion object {
        /** 32 ms windows, half of them overlapping. */
        const val WINDOW = 1536
        const val HOP = WINDOW / 2
        /** How far from its place a window may be taken, ±8 ms. */
        const val TOLERANCE = 384
    }
}
