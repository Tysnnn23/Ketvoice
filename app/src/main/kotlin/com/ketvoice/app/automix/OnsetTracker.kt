package com.ketvoice.app.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Listens to PCM as it is decoded and keeps, about 86 times a second, how much new
 * sound appeared (spectral flux), how much of it was bass, and the level. That short
 * description is all the tempo, beat and intro/outro analysis needs; the audio itself
 * is never kept.
 */
internal class OnsetTracker(sampleRate: Int, private val channels: Int) {
    private val decimation = max(1, (sampleRate / 11_025f).roundToInt())
    /** Rate of the mono signal the analysis works on. */
    val rate: Float = sampleRate.toFloat() / decimation
    /** Envelope frames per second. */
    val fps: Float = rate / HOP

    private val frame = FloatArray(WINDOW)
    private var filled = 0
    private var pending = 0f
    private var pendingCount = 0
    private val previous = FloatArray(BANDS)
    private var previousBass = 0f
    /** First bin after each band: 0–100, –200, –400, –800, –1600, –3200 Hz and above. */
    private val bandEnds = IntArray(BANDS) { band ->
        if (band == BANDS - 1) WINDOW / 2 else ((100f * (1 shl band)) / (rate / WINDOW)).roundToInt().coerceIn(band + 2, WINDOW / 2)
    }
    private val real = FloatArray(WINDOW)
    private val imaginary = FloatArray(WINDOW)
    private var hopEnergy = 0.0

    private var flux = FloatArray(4096)
    private var low = FloatArray(4096)
    private var level = FloatArray(4096)
    var frames = 0
        private set

    /** Interleaved little-endian 16-bit PCM; the buffer's position is left untouched. */
    fun accept16(buffer: ByteBuffer) {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = max(1, channels)
        while (view.remaining() >= 2 * count) {
            var sum = 0
            for (c in 0 until count) sum += view.short.toInt()
            sample(sum / (32768f * count))
        }
    }

    fun acceptMono(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) sample(samples[i])
    }

    private fun sample(value: Float) {
        // Box-car decimation: enough of a low-pass for an onset envelope.
        pending += value; pendingCount++
        if (pendingCount < decimation) return
        val v = pending / decimation
        pending = 0f; pendingCount = 0
        frame[filled++] = v
        if (filled > WINDOW - HOP) hopEnergy += v * v
        if (filled == WINDOW) {
            emit()
            System.arraycopy(frame, HOP, frame, 0, WINDOW - HOP)
            filled = WINDOW - HOP
            hopEnergy = 0.0
        }
    }

    private fun emit() {
        for (i in 0 until WINDOW) { real[i] = frame[i] * HANN[i]; imaginary[i] = 0f }
        Fft.transform(real, imaginary)
        // Rises are counted per band, not per bin: a kick (two bands) then weighs as much as
        // a hi-hat (two bands), instead of being drowned by the hat's hundreds of bins.
        var total = 0f
        var bassPower = 0f
        var bin = 1
        for (band in 0 until BANDS) {
            val end = bandEnds[band]
            var power = 0f
            while (bin < end) { power += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]; bin++ }
            if (band < LOW_BANDS) bassPower += power
            val compressed = ln(1f + 100f * sqrt(power) / (WINDOW / 4f))
            val rise = compressed - previous[band]
            if (rise > 0f) total += rise
            previous[band] = compressed
        }
        // The bass keeps its real size: that is what tells a first beat from the others.
        val bass = sqrt(bassPower) / (WINDOW / 4f)
        val bassRise = max(0f, bass - previousBass)
        previousBass = bass
        if (frames == flux.size) {
            flux = flux.copyOf(frames * 2); low = low.copyOf(frames * 2); level = level.copyOf(frames * 2)
        }
        flux[frames] = total; low[frames] = bassRise; level[frames] = (hopEnergy / HOP).toFloat()
        frames++
    }

    /** Seconds, from the first sample given, at which the onset reported by [frame] happened. */
    fun timeOf(frame: Float): Float = (frame * HOP + ONSET_OFFSET) / rate

    fun snapshot(): OnsetEnvelope = OnsetEnvelope(flux.copyOf(frames), low.copyOf(frames), level.copyOf(frames), fps, ONSET_OFFSET / rate)

    companion object {
        const val WINDOW = 512
        const val HOP = 128
        private const val BANDS = 7
        private const val LOW_BANDS = 2
        /** A new sound weighs most in the flux when it is three quarters into the Hann window. */
        private const val ONSET_OFFSET = WINDOW * .75f
        private val HANN = FloatArray(WINDOW) { (.5 - .5 * cos(2.0 * PI * it / (WINDOW - 1))).toFloat() }
    }
}

/** What [OnsetTracker] kept of a stretch of audio. Frame `n` is at `offsetSeconds + n / fps`. */
internal class OnsetEnvelope(val flux: FloatArray, val low: FloatArray, val level: FloatArray, val fps: Float,
                             val offsetSeconds: Float) {
    val size: Int get() = flux.size
    val seconds: Float get() = size / fps
    fun timeOf(frame: Float): Float = offsetSeconds + frame / fps
    fun frameAt(seconds: Float): Float = (seconds - offsetSeconds) * fps
}

/** In-place radix-2 FFT, sized for the 512-sample analysis window. */
internal object Fft {
    fun transform(real: FloatArray, imaginary: FloatArray) {
        val n = real.size
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepR = cos(angle).toFloat(); val stepI = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wr = 1f; var wi = 0f
                for (k in 0 until length / 2) {
                    val a = start + k; val b = a + length / 2
                    val tr = real[b] * wr - imaginary[b] * wi
                    val ti = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti
                    real[a] += tr; imaginary[a] += ti
                    val next = wr * stepR - wi * stepI
                    wi = wr * stepI + wi * stepR; wr = next
                }
                start += length
            }
            length = length shl 1
        }
    }
}
