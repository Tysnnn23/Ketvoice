package com.ketvoice.app.automix

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min

/**
 * What Beat This! (CPJKU, MIT) does around its network, on frames of 20 ms: how a title longer than one
 * chunk of the network is cut and put back together (`split_piece`, `aggregate_prediction` of its
 * `inference.py`, "keep_first"), and its "minimal" post-processor (`postprocessor.py`): maxima within
 * 7 frames, kept above a logit of 0, adjacent peaks merged, downbeats moved to the nearest beat.
 *
 * 8.4: each maximum is placed between frames by a parabola through it and its two neighbours ([refine]), so that
 * beats are no longer rounded to the 20 ms grid of the frames; the length of a chunk is the network's own ([Window]).
 */
internal object BeatPost {
    /**
     * Frames the published network takes at once (10 s, `ketmix-beat-v1`): its input is fixed at [1, 500, 128] and its
     * reshapes carry that length as constants, so it cannot be resized. Its attention grows with the square of this.
     */
    const val BEAT_FRAMES = 500
    /** Frames of each chunk's two ends that are left to the neighbouring chunks (Beat This! drops 6; the middle is better known with more context). */
    const val BORDER = 60
    const val FPS = 50.0
    private const val POOL = 3
    /** 30 s, the length Beat This! was trained on: what a network exported at that length takes at once. */
    const val TRAINED_FRAMES = 1500

    /**
     * How a piece is cut for a network that takes [frames] frames at once: [border] frames at each end of a chunk are
     * left to its neighbours. 10 s chunks keep 1.2 s at each end, 30 s ones 3 s (a tenth), so that every beat kept is
     * read with at least that much music on both sides.
     */
    class Window(val frames: Int, val border: Int = borderOf(frames)) {
        init { require(border >= 0 && frames > 2 * border) { "window $frames, border $border" } }
        val stride: Int get() = frames - 2 * border
    }

    fun borderOf(frames: Int): Int = if (frames <= BEAT_FRAMES) min(BORDER, frames / 4) else max(BORDER, frames / 10)

    /** The window of the published network. */
    val DEFAULT = Window(BEAT_FRAMES)

    /** Start frames of the chunks (may be negative: the first chunk is padded by the border on the left). */
    fun starts(frames: Int, window: Window = DEFAULT): IntArray {
        val border = window.border
        val list = ArrayList<Int>()
        var s = -border
        while (s < frames - border) { list += s; s += window.stride }
        if (list.isEmpty()) list += -border
        if (frames > window.stride) list[list.size - 1] = frames - (window.frames - border)
        return list.toIntArray()
    }

    /** The window's frames from [start] (zeros outside the piece) of a spectrogram of [MELS] values per frame. */
    fun chunk(spect: FloatArray, frames: Int, start: Int, window: Window = DEFAULT): FloatArray {
        val mels = BeatSpectrogram.MELS
        val out = FloatArray(window.frames * mels)
        val from = max(0, start)
        val to = min(frames, start + window.frames)
        if (to > from) System.arraycopy(spect, from * mels, out, (from - start) * mels, (to - from) * mels)
        return out
    }

    /** Logits of a whole piece from those of its chunks; earlier chunks win where they overlap. */
    fun aggregate(chunks: List<FloatArray>, starts: IntArray, frames: Int, window: Window = DEFAULT): FloatArray {
        val out = FloatArray(frames) { -1000f }
        for (i in chunks.indices.reversed()) {
            for (j in window.border until window.frames - window.border) {
                val at = starts[i] + j
                if (at in 0 until frames) out[at] = chunks[i][j]
            }
        }
        return out
    }

    class Times(val beats: DoubleArray, val downbeats: DoubleArray)

    /**
     * Beat and downbeat times in seconds from the two logit tracks. [subFrame] false keeps each peak on its frame,
     * as up to 8.3 (kept for comparison).
     */
    fun peaks(beat: FloatArray, downbeat: FloatArray, subFrame: Boolean = true): Times {
        val beats = merge(maxima(beat, subFrame))
        val downs = merge(maxima(downbeat, subFrame))
        val snapped = downs.map { d -> beats.minByOrNull { abs(it - d) } ?: d }.distinct().sorted()
        return Times(beats.map { it / FPS }.toDoubleArray(), snapped.map { it / FPS }.toDoubleArray())
    }

    /** Frames that are the maximum within +/- [POOL] (a tie counts) and above zero; placed between frames when [subFrame]. */
    private fun maxima(x: FloatArray, subFrame: Boolean): List<Double> {
        val found = ArrayList<Double>()
        for (i in x.indices) {
            if (x[i] <= 0f) continue
            var top = true
            for (j in max(0, i - POOL)..min(x.size - 1, i + POOL)) if (x[j] > x[i]) { top = false; break }
            if (top) found += i + if (subFrame) refine(x, i) else 0.0
        }
        return found
    }

    /**
     * Where the peak at frame [i] really lies, in frames from [i] (-0.5..0.5): the top of the parabola through the log
     * of the activation (log-sigmoid of the logits) at [i] and its two neighbours, i.e. of the Gaussian through those
     * three values (on the published model and synthetic drums it scattered beats 3 % less than a parabola on the logits).
     * 0 at either end of the piece, beside a frame no chunk covered, or on a flat top; two equal neighbouring maxima
     * both land half-way between them, where [merge] keeps one.
     */
    internal fun refine(x: FloatArray, i: Int): Double {
        if (i <= 0 || i >= x.size - 1) return 0.0
        if (x[i - 1] <= UNCOVERED || x[i + 1] <= UNCOVERED) return 0.0
        val left = logSigmoid(x[i - 1]); val top = logSigmoid(x[i]); val right = logSigmoid(x[i + 1])
        val curve = left - 2 * top + right
        if (curve >= 0.0) return 0.0
        return (.5 * (left - right) / curve).coerceIn(-.5, .5)
    }

    /** ln(1 / (1 + e^-v)), without overflow either way. */
    private fun logSigmoid(v: Float): Double {
        val x = v.toDouble()
        return if (x >= 0.0) -ln1p(exp(-x)) else x - ln1p(exp(x))
    }

    /** Logits at or under this were left by [aggregate] where no chunk spoke. */
    private const val UNCOVERED = -999f

    /** Groups of peaks no more than one frame apart become their mean (`deduplicate_peaks`, width 1). */
    private fun merge(peaks: List<Double>): List<Double> {
        val out = ArrayList<Double>()
        var mean = 0.0
        var count = 0
        for (p in peaks) {
            if (count > 0 && p - mean <= 1.0) { count++; mean += (p - mean) / count } else {
                if (count > 0) out += mean
                mean = p; count = 1
            }
        }
        if (count > 0) out += mean
        return out
    }
}
