package com.ketvoice.app.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What Beat This! (CPJKU, MIT) does around its network, on frames of 20 ms: how a title longer than one
 * chunk of the network is cut and put back together (`split_piece`, `aggregate_prediction` of its
 * `inference.py`, "keep_first"), and its "minimal" post-processor (`postprocessor.py`): maxima within
 * 7 frames, kept above a logit of 0, adjacent peaks merged, downbeats moved to the nearest beat.
 */
internal object BeatPost {
    /** Frames the converted network takes at once (10 s): its attention grows with the square of this, so it stays small on a phone. */
    const val BEAT_FRAMES = 500
    /** Frames of each chunk's two ends that are left to the neighbouring chunks (Beat This! drops 6; the middle is better known with more context). */
    const val BORDER = 60
    const val FPS = 50.0
    private const val POOL = 3

    /** Start frames of the chunks (may be negative: the first chunk is padded by [BORDER] on the left). */
    fun starts(frames: Int): IntArray {
        val stride = BEAT_FRAMES - 2 * BORDER
        val list = ArrayList<Int>()
        var s = -BORDER
        while (s < frames - BORDER) { list += s; s += stride }
        if (list.isEmpty()) list += -BORDER
        if (frames > stride) list[list.size - 1] = frames - (BEAT_FRAMES - BORDER)
        return list.toIntArray()
    }

    /** The [BEAT_FRAMES] frames from [start] (zeros outside the piece) of a spectrogram of [MELS] values per frame. */
    fun chunk(spect: FloatArray, frames: Int, start: Int): FloatArray {
        val mels = BeatSpectrogram.MELS
        val out = FloatArray(BEAT_FRAMES * mels)
        val from = max(0, start)
        val to = min(frames, start + BEAT_FRAMES)
        if (to > from) System.arraycopy(spect, from * mels, out, (from - start) * mels, (to - from) * mels)
        return out
    }

    /** Logits of a whole piece from those of its chunks; earlier chunks win where they overlap. */
    fun aggregate(chunks: List<FloatArray>, starts: IntArray, frames: Int): FloatArray {
        val out = FloatArray(frames) { -1000f }
        for (i in chunks.indices.reversed()) {
            for (j in BORDER until BEAT_FRAMES - BORDER) {
                val at = starts[i] + j
                if (at in 0 until frames) out[at] = chunks[i][j]
            }
        }
        return out
    }

    class Times(val beats: DoubleArray, val downbeats: DoubleArray)

    /** Beat and downbeat times in seconds from the two logit tracks. */
    fun peaks(beat: FloatArray, downbeat: FloatArray): Times {
        val beats = merge(maxima(beat))
        val downs = merge(maxima(downbeat))
        val snapped = downs.map { d -> beats.minByOrNull { abs(it - d) } ?: d }.distinct().sorted()
        return Times(beats.map { it / FPS }.toDoubleArray(), snapped.map { it / FPS }.toDoubleArray())
    }

    /** Frames that are the maximum within +/- [POOL] (a tie counts) and above zero. */
    private fun maxima(x: FloatArray): List<Int> {
        val found = ArrayList<Int>()
        for (i in x.indices) {
            if (x[i] <= 0f) continue
            var top = true
            for (j in max(0, i - POOL)..min(x.size - 1, i + POOL)) if (x[j] > x[i]) { top = false; break }
            if (top) found += i
        }
        return found
    }

    /** Groups of peaks no more than one frame apart become their mean (`deduplicate_peaks`, width 1). */
    private fun merge(peaks: List<Int>): List<Double> {
        val out = ArrayList<Double>()
        var mean = 0.0
        var count = 0
        for (p in peaks) {
            if (count > 0 && p - mean <= 1.0) { count++; mean += (p - mean) / count } else {
                if (count > 0) out += mean
                mean = p.toDouble(); count = 1
            }
        }
        if (count > 0) out += mean
        return out
    }
}
