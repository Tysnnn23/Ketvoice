package com.ketvoice.app.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The signal processing around the separation network (5.10.0): the same short-time Fourier
 * transform as the Spleeter models were trained with (4096-point periodic Hann window, hop of
 * 1024, no centring, 44.1 kHz), its inverse by overlap-add, and a windowed-sinc resampler.
 * Pure Kotlin, so that all of it is tested on the host.
 */
internal object StemDsp {
    const val RATE = 44_100
    const val N_FFT = 4096
    const val HOP = 1024
    /** Bins of a frame ([N_FFT] / 2 + 1). */
    const val BINS = N_FFT / 2 + 1
    /** Bins the network sees (up to 11 kHz); above them the sound is left as it is. */
    const val NET_BINS = 1024
    /** Frames the network reads at once. */
    const val NET_FRAMES = 512

    /** Periodic Hann window, as torch.hann_window and kaldi-native-fbank's "hann". */
    val window: FloatArray = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }

    fun frames(samples: Int): Int = if (samples < N_FFT) 0 else 1 + (samples - N_FFT) / HOP
}

/** An in-place iterative radix-2 complex FFT of a fixed size. */
internal class Fft(val size: Int) {
    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    init { require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" } }

    /** Forward transform (e^-i) of [re] + i[im]; [inverse] uses e^+i and does not scale. */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1f else -1f
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]; val wi = sign * sinTable[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr; im[l] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}

/**
 * Short-time transforms of a stereo pair. Both channels go through one complex FFT (left as
 * the real part, right as the imaginary part) and are told apart by symmetry, which halves the work.
 */
internal class StereoStft {
    private val fft = Fft(StemDsp.N_FFT)
    private val re = FloatArray(StemDsp.N_FFT)
    private val im = FloatArray(StemDsp.N_FFT)

    /**
     * The spectrum of the frame starting at [offset] of [left]/[right] (zeros outside them), into
     * [lr]/[li] and [rr]/[ri], each [StemDsp.BINS] long.
     */
    fun forward(left: FloatArray, right: FloatArray, offset: Int,
                lr: FloatArray, li: FloatArray, rr: FloatArray, ri: FloatArray) {
        val n = StemDsp.N_FFT
        val w = StemDsp.window
        for (i in 0 until n) {
            val at = offset + i
            val inside = at >= 0 && at < left.size
            re[i] = if (inside) left[at] * w[i] else 0f
            im[i] = if (inside) right[at] * w[i] else 0f
        }
        fft.transform(re, im)
        for (k in 0 until StemDsp.BINS) {
            val m = (n - k) and (n - 1)
            val zr = re[k]; val zi = im[k]; val cr = re[m]; val ci = -im[m]
            // L = (Z[k] + conj Z[N-k]) / 2, R = (Z[k] - conj Z[N-k]) / 2i
            lr[k] = (zr + cr) * .5f; li[k] = (zi + ci) * .5f
            rr[k] = (zi - ci) * .5f; ri[k] = -(zr - cr) * .5f
        }
    }

    /**
     * Adds the windowed inverse of two half spectra at [offset] of [left]/[right] (overlap-add).
     * Divide by [OverlapAdd.FULL_OVERLAP] afterwards, where all frames overlap.
     */
    fun inverseAdd(lr: FloatArray, li: FloatArray, rr: FloatArray, ri: FloatArray,
                   left: FloatArray, right: FloatArray, offset: Int) {
        val n = StemDsp.N_FFT
        // Z = L + iR on the full circle, each side Hermitian.
        for (k in 0 until StemDsp.BINS) {
            re[k] = lr[k] - ri[k]; im[k] = li[k] + rr[k]
            if (k in 1 until n / 2) {
                val m = n - k
                // conj(L[k]) + i conj(R[k])
                re[m] = lr[k] + ri[k]; im[m] = -li[k] + rr[k]
            }
        }
        fft.transform(re, im, inverse = true)
        val w = StemDsp.window
        val scale = 1f / n
        for (i in 0 until n) {
            val at = offset + i
            if (at < 0 || at >= left.size) continue
            left[at] += re[i] * scale * w[i]
            right[at] += im[i] * scale * w[i]
        }
    }
}

internal object OverlapAdd {
    /** The squared windows of the frames covering a sample, added (1.5 for a Hann window at a quarter hop). */
    val FULL_OVERLAP: Float = (0 until StemDsp.HOP).maxOf { i ->
        (0 until StemDsp.N_FFT / StemDsp.HOP).sumOf { k -> StemDsp.window[i + k * StemDsp.HOP].toDouble().let { it * it } }
    }.toFloat()
}

/** Band-limited resampling with a Kaiser-windowed sinc, for whole buffers (not in real time). */
internal object Resampler {
    private const val HALF_TAPS = 16
    /** Kernel points per input sample, linearly interpolated between them. */
    private const val PHASES = 256
    private const val BETA = 8.0
    private val tables = java.util.concurrent.ConcurrentHashMap<Long, Kernel>()

    private class Kernel(val taps: Int, val values: FloatArray)

    private fun kernel(from: Int, to: Int): Kernel = tables.getOrPut(from.toLong() shl 32 or to.toLong()) {
        val ratio = to.toDouble() / from
        // Below the lower Nyquist frequency, a little under it so that nothing folds back.
        val cutoff = minOf(1.0, ratio) * .95
        val taps = kotlin.math.ceil(HALF_TAPS / minOf(1.0, ratio)).toInt()
        val besselBeta = bessel0(BETA)
        Kernel(taps, FloatArray(taps * PHASES + 2) { index ->
            val d = index.toDouble() / PHASES
            val x = d * cutoff
            val sinc = if (x < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            val t = d / taps
            val win = if (t >= 1.0) 0.0 else bessel0(BETA * kotlin.math.sqrt(1 - t * t)) / besselBeta
            (sinc * win * cutoff).toFloat()
        })
    }

    /** One channel of interleaved 16-bit PCM, resampled to floats in -1..1. */
    fun resample(input: ShortArray, channel: Int, channels: Int, from: Int, to: Int): FloatArray {
        val frames = input.size / channels
        val mono = FloatArray(frames) { input[it * channels + channel] / 32768f }
        return if (from == to) mono else resample(mono, from, to)
    }

    /** 7.5: one row of normalised weights per output phase, for ratios that repeat after few outputs. */
    private class Phases(val taps: Int, val period: Int, val weights: FloatArray)
    private val phaseTables = java.util.concurrent.ConcurrentHashMap<Long, Phases>()
    private const val MAX_PHASES = 2048

    private fun phases(from: Int, to: Int): Phases? {
        val g = gcd(from, to)
        val period = to / g
        if (period > MAX_PHASES) return null
        return phaseTables.getOrPut(from.toLong() shl 32 or to.toLong()) {
            val k = kernel(from, to)
            val taps = k.taps
            val table = k.values
            val limit = taps * PHASES
            val ratio = to.toDouble() / from
            val weights = FloatArray(period * 2 * taps)
            for (r in 0 until period) {
                // The same position as the direct path computes for an output of this phase.
                val center = r / ratio
                val base = floor(center).toInt()
                var norm = 0f
                for (j in 0 until 2 * taps) {
                    val pos = kotlin.math.abs(center - (base - taps + 1 + j)) * PHASES
                    val p = pos.toInt()
                    if (p >= limit) continue
                    val w = table[p] + (table[p + 1] - table[p]) * (pos - p).toFloat()
                    weights[r * 2 * taps + j] = w; norm += w
                }
                if (norm > 0f) for (j in 0 until 2 * taps) weights[r * 2 * taps + j] /= norm
            }
            Phases(taps, period, weights)
        }
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input.copyOf()
        phases(from, to)?.let { return resampled(input, from, to, it) }
        return resampleDirect(input, from, to)
    }

    /** Output o sits at o * from / to in the input: its phase is (o mod period), its base an exact integer step. */
    private fun resampled(input: FloatArray, from: Int, to: Int, ph: Phases): FloatArray {
        val outSize = floor(input.size * (to.toDouble() / from)).toInt()
        val out = FloatArray(outSize)
        val taps2 = 2 * ph.taps
        val w = ph.weights
        val step = from / gcd(from, to)
        for (o in 0 until outSize) {
            val r = o % ph.period
            val base = (o / ph.period) * step + floor(r * from.toDouble() / to).toInt()
            val first = base - ph.taps + 1
            val row = r * taps2
            var acc = 0f
            if (first >= 0 && first + taps2 <= input.size) for (j in 0 until taps2) acc += input[first + j] * w[row + j]
            else for (j in 0 until taps2) { val i = first + j; if (i >= 0 && i < input.size) acc += input[i] * w[row + j] }
            out[o] = acc
        }
        return out
    }

    internal fun resampleDirect(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input.copyOf()
        val ratio = to.toDouble() / from
        val outSize = floor(input.size * ratio).toInt()
        val out = FloatArray(outSize)
        val k = kernel(from, to)
        val taps = k.taps
        val table = k.values
        val limit = taps * PHASES
        for (o in 0 until outSize) {
            val center = o / ratio
            val base = floor(center).toInt()
            var acc = 0f
            var norm = 0f
            for (i in base - taps + 1..base + taps) {
                val pos = kotlin.math.abs(center - i) * PHASES
                val p = pos.toInt()
                if (p >= limit) continue
                val f = (pos - p).toFloat()
                val w = table[p] + (table[p + 1] - table[p]) * f
                norm += w
                if (i >= 0 && i < input.size) acc += input[i] * w
            }
            out[o] = if (norm > 0f) acc / norm else 0f
        }
        return out
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0; var term = 1.0; val q = x * x / 4
        for (k in 1..24) { term *= q / (k * k); sum += term }
        return sum
    }
}
