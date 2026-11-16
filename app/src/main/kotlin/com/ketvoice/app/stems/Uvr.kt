package com.ketvoice.app.stems

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A complex FFT of size 2^k or 3·2^k (6.0.0: the high-quality model reads 6144-point spectra).
 * A size divisible by three is split into three interleaved halves of a power of two.
 */
internal class MixedFft(val size: Int) {
    private val third = if (size % 3 == 0) size / 3 else 0
    private val radix2 = if (third == 0) Fft(size) else Fft(third)
    private val cosN = FloatArray(size) { cos(2.0 * PI * it / size).toFloat() }
    private val sinN = FloatArray(size) { sin(2.0 * PI * it / size).toFloat() }
    private val parts = if (third == 0) emptyArray() else Array(6) { FloatArray(third) }

    init { require(third == 0 || third and (third - 1) == 0) { "size must be 2^k or 3*2^k" } }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        if (third == 0) { radix2.transform(re, im, inverse); return }
        val m = third
        val ar = parts[0]; val ai = parts[1]; val br = parts[2]; val bi = parts[3]; val cr = parts[4]; val ci = parts[5]
        for (n in 0 until m) {
            ar[n] = re[3 * n]; ai[n] = im[3 * n]
            br[n] = re[3 * n + 1]; bi[n] = im[3 * n + 1]
            cr[n] = re[3 * n + 2]; ci[n] = im[3 * n + 2]
        }
        radix2.transform(ar, ai, inverse); radix2.transform(br, bi, inverse); radix2.transform(cr, ci, inverse)
        val sign = if (inverse) 1f else -1f
        for (k in 0 until size) {
            val j = k % m
            val w1 = k; val w2 = (2 * k) % size
            val c1 = cosN[w1]; val s1 = sign * sinN[w1]
            val c2 = cosN[w2]; val s2 = sign * sinN[w2]
            val xr = ar[j] + (br[j] * c1 - bi[j] * s1) + (cr[j] * c2 - ci[j] * s2)
            val xi = ai[j] + (br[j] * s1 + bi[j] * c1) + (cr[j] * s2 + ci[j] * c2)
            re[k] = xr; im[k] = xi
        }
    }

}

/** The high-quality separation network (UVR MDX-Net): the complex spectrum of the voice from that of the title. */
internal interface UvrNet : AutoCloseable {
    val nFft: Int
    val dimF: Int
    val dimT: Int
    /**
     * [input] and [output] hold `4 × dimF × dimT` values: real and imaginary parts of the left channel,
     * then of the right one, each by frequency then frame.
     */
    fun run(input: FloatArray, output: FloatArray)
    override fun close() {}
}

/**
 * The voice of a title by UVR MDX-Net, exactly as sherpa-onnx computes it (chunks of 15 s with 1 s
 * of margin, segments of [UvrNet.dimT] frames, centred reflect-padded STFT with a hop of 1024).
 */
internal class UvrSeparator(private val net: UvrNet) {
    private val nFft = net.nFft
    private val hop = 1024
    private val bins = nFft / 2 + 1
    private val trim = nFft / 2
    private val segment = hop * (net.dimT - 1)
    private val gen = segment - 2 * trim
    private val window = FloatArray(nFft) { (0.5 - 0.5 * cos(2.0 * PI * it / nFft)).toFloat() }
    private val fft = MixedFft(nFft)
    private val re = FloatArray(nFft); private val im = FloatArray(nFft)

    /** The voice of [left]/[right] (44.1 kHz), as two signals of the same length. Null when cancelled. */
    fun voice(left: FloatArray, right: FloatArray, cancelled: () -> Boolean = { false }): Pair<FloatArray, FloatArray>? {
        val n = left.size
        val outL = FloatArray(n); val outR = FloatArray(n)
        var start = 0
        while (start < n) {
            val from = maxOf(0, start - MARGIN)
            val until = minOf(start + CHUNK + MARGIN, n)
            val first = start == 0; val last = until == n
            val chunk = chunk(left, right, from, until, cancelled) ?: return null
            val pad = chunk.third
            val lo = if (first) 0 else MARGIN
            val hi = chunk.first.size - pad - (if (last) 0 else MARGIN)
            var at = from + lo
            for (i in lo until hi) { if (at < n) { outL[at] = chunk.first[i]; outR[at] = chunk.second[i] }; at++ }
            if (last) break
            start += CHUNK
        }
        return outL to outR
    }

    private fun chunk(left: FloatArray, right: FloatArray, from: Int, until: Int, cancelled: () -> Boolean): Triple<FloatArray, FloatArray, Int>? {
        val n = until - from
        val pad = gen - n % gen
        val total = trim + n + pad + trim
        val l = FloatArray(total); val r = FloatArray(total)
        System.arraycopy(left, from, l, trim, n); System.arraycopy(right, from, r, trim, n)
        val outL = FloatArray(n + pad); val outR = FloatArray(n + pad)
        val input = FloatArray(4 * net.dimF * net.dimT); val output = FloatArray(input.size)
        val specL = Array(2) { FloatArray(net.dimT * bins) }; val specR = Array(2) { FloatArray(net.dimT * bins) }
        var i = 0; var written = 0
        while (i < n + pad) {
            if (cancelled()) return null
            stft(l, i, specL); stft(r, i, specR)
            val plane = net.dimF * net.dimT
            for (f in 0 until net.dimF) for (t in 0 until net.dimT) {
                val at = f * net.dimT + t; val s = t * bins + f
                input[at] = specL[0][s]; input[plane + at] = specL[1][s]
                input[2 * plane + at] = specR[0][s]; input[3 * plane + at] = specR[1][s]
            }
            net.run(input, output)
            for (spec in arrayOf(specL, specR)) { spec[0].fill(0f); spec[1].fill(0f) }
            for (f in 0 until net.dimF) for (t in 0 until net.dimT) {
                val at = f * net.dimT + t; val s = t * bins + f
                specL[0][s] = output[at]; specL[1][s] = output[plane + at]
                specR[0][s] = output[2 * plane + at]; specR[1][s] = output[3 * plane + at]
            }
            istft(specL, outL, written); istft(specR, outR, written)
            written += gen; i += gen
        }
        return Triple(outL, outR, pad)
    }

    /** Centred, reflect-padded STFT of [segment] samples of [x] from [offset], into real and imaginary planes (frame-major). */
    private fun stft(x: FloatArray, offset: Int, out: Array<FloatArray>) {
        val half = nFft / 2
        fun sample(j: Int): Float { // j in the padded signal of length segment + nFft
            val k = j - half
            val i = when { k < 0 -> -k; k >= segment -> 2 * (segment - 1) - k; else -> k }
            return x[offset + i]
        }
        for (t in 0 until net.dimT) {
            val base = t * hop
            for (j in 0 until nFft) { re[j] = sample(base + j) * window[j]; im[j] = 0f }
            fft.transform(re, im)
            for (k in 0 until bins) { out[0][t * bins + k] = re[k]; out[1][t * bins + k] = im[k] }
        }
    }

    /** Inverse of [stft], keeping the [gen] samples in the middle of the segment, written at [at] of [out]. */
    private fun istft(spec: Array<FloatArray>, out: FloatArray, at: Int) {
        val length = nFft + (net.dimT - 1) * hop
        val sum = FloatArray(length); val den = FloatArray(length)
        for (t in 0 until net.dimT) {
            for (k in 0 until bins) { re[k] = spec[0][t * bins + k]; im[k] = spec[1][t * bins + k] }
            for (k in 1 until nFft / 2) { re[nFft - k] = re[k]; im[nFft - k] = -im[k] }
            fft.transform(re, im, inverse = true)
            val base = t * hop
            for (j in 0 until nFft) { sum[base + j] += re[j] / nFft * window[j]; den[base + j] += window[j] * window[j] }
        }
        // Centred: the first and last half windows are the padding; then the trim on both sides.
        val first = nFft / 2 + trim
        for (j in 0 until gen) {
            val s = first + j
            out[at + j] = if (den[s] > 0f) sum[s] / den[s] else 0f
        }
    }

    companion object {
        const val MARGIN = 44_100
        const val CHUNK = 15 * 44_100
    }
}

/** UVR MDX-Net run by LiteRT on the phone's processor (XNNPACK). */
internal class LiteRtUvrNet(file: File, threads: Int) : UvrNet {
    private val interpreter = org.tensorflow.lite.Interpreter(file, org.tensorflow.lite.Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true))
    private val inShape = interpreter.getInputTensor(0).shape()
    private val outShape = interpreter.getOutputTensor(0).shape()
    override val dimF: Int = inShape[2]
    override val dimT: Int = inShape[3]
    override val nFft: Int = dimF * 2
    /** The converted model gives its output channels last ([1, F, T, 4]) or first ([1, 4, F, T]). */
    private val channelsLast = outShape.size == 4 && outShape[3] == 4
    private val inBuffer = ByteBuffer.allocateDirect(4 * 4 * dimF * dimT).order(ByteOrder.nativeOrder())
    private val outBuffer = ByteBuffer.allocateDirect(4 * 4 * dimF * dimT).order(ByteOrder.nativeOrder())
    private val raw = FloatArray(4 * dimF * dimT)

    override fun run(input: FloatArray, output: FloatArray) {
        inBuffer.clear(); inBuffer.asFloatBuffer().put(input); inBuffer.rewind()
        outBuffer.clear()
        interpreter.run(inBuffer, outBuffer)
        outBuffer.rewind()
        if (!channelsLast) { outBuffer.asFloatBuffer().get(output); return }
        outBuffer.asFloatBuffer().get(raw)
        val plane = dimF * dimT
        for (f in 0 until dimF) for (t in 0 until dimT) {
            val src = (f * dimT + t) * 4
            for (c in 0 until 4) output[c * plane + f * dimT + t] = raw[src + c]
        }
    }

    override fun close() = interpreter.close()
}
