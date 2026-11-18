package com.ketvoice.app.automix

import com.ketvoice.app.stems.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The input of Beat This! (CPJKU, ISMIR 2024, MIT), the same as its `LogMelSpect`: 22 050 Hz mono, a
 * 1024-point periodic Hann window, hop 441 (50 frames a second), centred with reflection, magnitude divided
 * by the window's energy, 128 slaney mel bands from 30 to 11 000 Hz, then `log1p(1000 x)`.
 * Pure Kotlin; checked against a numpy re-implementation (tools/beat/reference.py).
 */
internal object BeatSpectrogram {
    const val RATE = 22_050
    const val N_FFT = 1024
    const val HOP = 441
    const val MELS = 128
    private const val F_MIN = 30.0
    private const val F_MAX = 11_000.0
    private const val BINS = N_FFT / 2 + 1

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    private val windowNorm = sqrt(window.sumOf { it.toDouble() * it }).toFloat()

    /** Slaney mel scale, as librosa and torchaudio (`mel_scale="slaney"`). */
    private fun hzToMel(f: Double) = if (f >= 1000.0) 15.0 + ln(f / 1000.0) / (ln(6.4) / 27.0) else f / (200.0 / 3)
    private fun melToHz(m: Double) = if (m >= 15.0) 1000.0 * exp((ln(6.4) / 27.0) * (m - 15.0)) else m * (200.0 / 3)

    /** Triangular filters of the slaney area norm: one row of [BINS] weights per mel band. */
    private val filters: Array<FloatArray> by lazy {
        val points = DoubleArray(MELS + 2) { melToHz(hzToMel(F_MIN) + (hzToMel(F_MAX) - hzToMel(F_MIN)) * it / (MELS + 1)) }
        Array(MELS) { m -> filterRow(points, m) }
    }

    private fun filterRow(points: DoubleArray, m: Int): FloatArray {
        val area = 2.0 / (points[m + 2] - points[m])
        return FloatArray(BINS) { bin ->
            val f = bin * (RATE / 2.0) / (BINS - 1)
            val down = (f - points[m]) / (points[m + 1] - points[m])
            val up = (points[m + 2] - f) / (points[m + 2] - points[m + 1])
            (max(0.0, min(down, up)) * area).toFloat()
        }
    }

    fun frames(samples: Int): Int = 1 + samples / HOP

    /** Frames of [MELS] values each, one after the other, for [samples] (mono, [RATE] Hz). */
    fun compute(samples: FloatArray): FloatArray {
        val count = frames(samples.size)
        val out = FloatArray(count * MELS)
        val fft = Fft(N_FFT)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val magnitude = FloatArray(BINS)
        for (t in 0 until count) {
            frame(samples, t, re, im)
            fft.transform(re, im)
            for (k in 0 until BINS) magnitude[k] = sqrt(re[k] * re[k] + im[k] * im[k]) / windowNorm
            mel(magnitude, out, t * MELS)
        }
        return out
    }

    /** One windowed frame, centred on t * HOP, reflected at both ends of the signal. */
    private fun frame(x: FloatArray, t: Int, re: FloatArray, im: FloatArray) {
        val start = t * HOP - N_FFT / 2
        for (i in 0 until N_FFT) re[i] = at(x, start + i) * window[i]
        java.util.Arrays.fill(im, 0f)
    }

    private fun at(x: FloatArray, index: Int): Float {
        if (x.size < 2) return 0f
        val period = 2 * (x.size - 1)
        val i = ((index % period) + period) % period
        return x[if (i < x.size) i else period - i]
    }

    private fun mel(magnitude: FloatArray, out: FloatArray, offset: Int) {
        for (m in 0 until MELS) {
            val row = filters[m]
            var sum = 0f
            for (k in 0 until BINS) { val w = row[k]; if (w != 0f) sum += w * magnitude[k] }
            out[offset + m] = ln(1.0 + 1000.0 * sum).toFloat()
        }
    }
}
