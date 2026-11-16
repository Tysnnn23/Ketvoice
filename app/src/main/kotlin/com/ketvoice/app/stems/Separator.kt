package com.ketvoice.app.stems

import kotlin.math.sqrt

/**
 * The separation network: Deezer's Spleeter (2 stems), as two models that each estimate the
 * magnitude of one stem from the magnitude of the mix.
 */
internal interface StemNet : AutoCloseable {
    /**
     * [input] holds `2 × [StemDsp.NET_FRAMES] × [StemDsp.NET_BINS]` magnitudes (channel, frame,
     * bin). Writes the estimated vocal and accompaniment magnitudes, in the same layout.
     */
    fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray)
    override fun close() {}
}

/**
 * Which part of each time–frequency cell of a title belongs to its instrumental: one value per
 * frame and bin below [StemDsp.NET_BINS], shared by both channels, stored on 4 bits with a square-root
 * law (fine steps near 0, where a voice is taken out). Above the network's bins everything is
 * instrumental. Its first frame starts at [startUs] in the title, on the 44.1 kHz grid.
 */
internal class StemMask(val startUs: Long, val frames: Int, val packed: ByteArray) {
    init { require(packed.size == frames * StemDsp.NET_BINS / 2) }

    fun value(frame: Int, bin: Int): Float {
        val b = packed[(frame * StemDsp.NET_BINS + bin) shr 1].toInt()
        val q = if (bin and 1 == 0) b and 15 else (b shr 4) and 15
        return LEVELS[q]
    }

    companion object {
        private val LEVELS = FloatArray(16) { val r = it / 15f; r * r }

        fun quantize(value: Float): Int = (sqrt(value.coerceIn(0f, 1f)) * 15f + .5f).toInt().coerceIn(0, 15)
    }
}

internal object Separator {
    private const val EPS = 1e-10f

    /**
     * Runs the network over a stereo signal at 44.1 kHz and returns where its instrumental is.
     * [startUs] is the position in the title of the first sample. [cancelled] is checked between
     * blocks of the network.
     */
    fun mask(left: FloatArray, right: FloatArray, startUs: Long, net: StemNet, cancelled: () -> Boolean = { false }): StemMask? {
        val frames = StemDsp.frames(left.size)
        if (frames <= 0) return null
        val packed = ByteArray(frames * StemDsp.NET_BINS / 2)
        val stft = StereoStft()
        val lr = FloatArray(StemDsp.BINS); val li = FloatArray(StemDsp.BINS)
        val rr = FloatArray(StemDsp.BINS); val ri = FloatArray(StemDsp.BINS)
        val block = StemDsp.NET_FRAMES * StemDsp.NET_BINS
        val input = FloatArray(2 * block)
        val vocals = FloatArray(2 * block)
        val accompaniment = FloatArray(2 * block)
        var first = 0
        while (first < frames) {
            if (cancelled()) return null
            input.fill(0f)
            val count = minOf(StemDsp.NET_FRAMES, frames - first)
            for (f in 0 until count) {
                stft.forward(left, right, (first + f) * StemDsp.HOP, lr, li, rr, ri)
                val row = f * StemDsp.NET_BINS
                for (k in 0 until StemDsp.NET_BINS) {
                    input[row + k] = sqrt(lr[k] * lr[k] + li[k] * li[k])
                    input[block + row + k] = sqrt(rr[k] * rr[k] + ri[k] * ri[k])
                }
            }
            net.run(input, vocals, accompaniment)
            for (f in 0 until count) {
                val row = f * StemDsp.NET_BINS
                val frame = first + f
                for (k in 0 until StemDsp.NET_BINS) {
                    var share = 0f
                    for (c in 0..1) {
                        val v = vocals[c * block + row + k]; val a = accompaniment[c * block + row + k]
                        share += (a * a + EPS / 2) / (v * v + a * a + EPS)
                    }
                    val q = StemMask.quantize(share * .5f)
                    val at = (frame * StemDsp.NET_BINS + k) shr 1
                    packed[at] = (if (k and 1 == 0) (packed[at].toInt() and 0xF0) or q else (packed[at].toInt() and 0x0F) or (q shl 4)).toByte()
                }
            }
            first += count
        }
        return StemMask(startUs, frames, packed)
    }

    /**
     * The instrumental of [left]/[right] (44.1 kHz) by [mask]. [offset] is where the mask's first
     * frame starts in these samples (negative when the signal starts after it). Samples the mask
     * does not cover are silent, outside [Result.from]..[Result.until].
     */
    fun instrumental(left: FloatArray, right: FloatArray, mask: StemMask, offset: Int): Result {
        val outL = FloatArray(left.size); val outR = FloatArray(right.size)
        val stft = StereoStft()
        val lr = FloatArray(StemDsp.BINS); val li = FloatArray(StemDsp.BINS)
        val rr = FloatArray(StemDsp.BINS); val ri = FloatArray(StemDsp.BINS)
        var used = 0
        var firstFrame = -1
        for (t in 0 until mask.frames) {
            val at = offset + t * StemDsp.HOP
            if (at + StemDsp.N_FFT <= 0 || at >= left.size) continue
            if (firstFrame < 0) firstFrame = t
            stft.forward(left, right, at, lr, li, rr, ri)
            for (k in 0 until StemDsp.NET_BINS) {
                val m = mask.value(t, k)
                lr[k] *= m; li[k] *= m; rr[k] *= m; ri[k] *= m
            }
            stft.inverseAdd(lr, li, rr, ri, outL, outR, at)
            used = t - firstFrame + 1
        }
        if (firstFrame < 0) return Result(outL, outR, 0, 0)
        val firstAt = offset + firstFrame * StemDsp.HOP
        // Near the first and last frames the windows only partly overlap: the sound there is not trusted
        // and left silent. In between, the squared windows always add up to the same value.
        val from = (firstAt + StemDsp.N_FFT).coerceIn(0, left.size)
        val until = (firstAt + (used - 1) * StemDsp.HOP).coerceIn(from, left.size)
        val scale = 1f / OverlapAdd.FULL_OVERLAP
        for (i in left.indices) {
            if (i in from until until) { outL[i] *= scale; outR[i] *= scale } else { outL[i] = 0f; outR[i] = 0f }
        }
        return Result(outL, outR, from, until)
    }

    /** An instrumental, trustworthy from sample [from] (inclusive) to [until] (exclusive). */
    class Result(val left: FloatArray, val right: FloatArray, val from: Int, val until: Int)
}
