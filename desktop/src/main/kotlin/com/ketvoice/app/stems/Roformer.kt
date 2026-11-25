package com.ketvoice.app.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos

/**
 * The studio voice network (Windows 2.0, « Maximum »): Kimberley Jensen's Mel-Band RoFormer as
 * `tools/stems/export_roformer.py` exports it. [spectrum] and [out] hold `2050 × 801 × 2` values: by frequency bin
 * with both channels interleaved (bin 0 left, bin 0 right, …), then frame, then real/imaginary.
 * [run] returns false when [cancelled] stopped it midway.
 */
internal interface RoformerNet : AutoCloseable {
    fun run(spectrum: FloatArray, out: FloatArray, cancelled: () -> Boolean): Boolean
    override fun close() {}
}

internal class OnnxRoformerNet(file: File, threads: Int, gpu: Boolean) : RoformerNet {
    private val session = OrtAccel.session(file, threads, gpu)
    private val shape = longArrayOf(1, 2L * RoformerSeparator.BINS, RoformerSeparator.FRAMES.toLong(), 2)

    override fun run(spectrum: FloatArray, out: FloatArray, cancelled: () -> Boolean): Boolean {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.RunOptions().use { options ->
            // A chunk takes seconds on a processor: the listener coming back stops it at once (ONNX Runtime's terminate flag).
            val watch = Thread { while (!Thread.interrupted()) { if (cancelled()) { runCatching { options.setTerminate(true) }; return@Thread }; try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread } } }
                .apply { isDaemon = true; start() }
            try {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(spectrum), shape).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor), options).use { result ->
                        val buffer = (result.get(0) as OnnxTensor).floatBuffer
                        check(buffer.remaining() == out.size) { "unexpected output size ${buffer.remaining()}" }
                        buffer.get(out)
                    }
                }
                return true
            } catch (e: ai.onnxruntime.OrtException) {
                if (cancelled()) return false
                throw e
            } finally { watch.interrupt() }
        }
    }

    override fun close() = session.close()
}

/**
 * The voice of a whole title by [RoformerNet], as the reference inference does it (Music-Source-Separation-Training's
 * `demix`, generic mode, mirrored in `export_roformer.py`'s `separate`): 8 s chunks every 4 s over the title padded by
 * reflection, each blended with linear fades of 0.8 s; the centred, reflect-padded STFT (Hann 2048, hop 441) and its
 * inverse are computed here.
 */
internal class RoformerSeparator(private val net: RoformerNet) {
    private val fft = Fft(N_FFT)
    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    private val re = FloatArray(N_FFT); private val im = FloatArray(N_FFT)
    private val spectrum = FloatArray(2 * BINS * FRAMES * 2)
    private val voiceSpectrum = FloatArray(spectrum.size)

    /** The voice of [left]/[right] (44.1 kHz) as two signals of the same length; null when cancelled. */
    fun voice(left: FloatArray, right: FloatArray, cancelled: () -> Boolean = { false }): Pair<FloatArray, FloatArray>? {
        val n = left.size
        val border = CHUNK - STEP
        val padded = n > 2 * border
        val mixL = if (padded) reflectPad(left, border) else left
        val mixR = if (padded) reflectPad(right, border) else right
        val total = mixL.size
        val resultL = FloatArray(total); val resultR = FloatArray(total); val counter = FloatArray(total)
        val fades = fades()
        val partL = FloatArray(CHUNK); val partR = FloatArray(CHUNK)
        val outL = FloatArray(CHUNK); val outR = FloatArray(CHUNK)
        var i = 0
        while (i < total) {
            if (cancelled()) return null
            val length = minOf(CHUNK, total - i)
            chunk(mixL, i, length, partL); chunk(mixR, i, length, partR)
            stft(partL, partR)
            if (!net.run(spectrum, voiceSpectrum, cancelled)) return null
            istft(voiceSpectrum, 0, outL); istft(voiceSpectrum, 1, outR)
            val first = i == 0; val last = i + STEP >= total
            for (j in 0 until length) {
                val w = when { first && j < FADE -> 1f; last && j >= CHUNK - FADE -> 1f; else -> fades[j] }
                resultL[i + j] += outL[j] * w; resultR[i + j] += outR[j] * w; counter[i + j] += w
            }
            i += STEP
        }
        val from = if (padded) border else 0
        val voiceL = FloatArray(n) { resultL[from + it] / counter[from + it] }
        val voiceR = FloatArray(n) { resultR[from + it] / counter[from + it] }
        return voiceL to voiceR
    }

    /** One chunk of [CHUNK] samples from [at]; a short last one is completed by reflection, or by silence when under half. */
    private fun chunk(x: FloatArray, at: Int, length: Int, into: FloatArray) {
        System.arraycopy(x, at, into, 0, length)
        val reflect = length > CHUNK / 2
        for (j in length until CHUNK) into[j] = if (reflect) into[reflectIndex(j, length)] else 0f
    }

    /** The centred STFT of both channels into [spectrum] ((f s) t c). */
    private fun stft(left: FloatArray, right: FloatArray) {
        for ((s, x) in listOf(left, right).withIndex()) {
            for (t in 0 until FRAMES) {
                val base = t * HOP - N_FFT / 2
                for (j in 0 until N_FFT) { re[j] = x[reflectIndex(base + j, CHUNK)] * window[j]; im[j] = 0f }
                fft.transform(re, im)
                for (k in 0 until BINS) {
                    val at = ((k * 2 + s) * FRAMES + t) * 2
                    spectrum[at] = re[k]; spectrum[at + 1] = im[k]
                }
            }
        }
    }

    /** The inverse of [stft] for channel [s] of [spec]: windowed overlap-add divided by the window's energy. */
    private fun istft(spec: FloatArray, s: Int, out: FloatArray) {
        val length = N_FFT + (FRAMES - 1) * HOP
        val sum = FloatArray(length); val den = FloatArray(length)
        for (t in 0 until FRAMES) {
            for (k in 0 until BINS) { val at = ((k * 2 + s) * FRAMES + t) * 2; re[k] = spec[at]; im[k] = spec[at + 1] }
            for (k in 1 until N_FFT / 2) { re[N_FFT - k] = re[k]; im[N_FFT - k] = -im[k] }
            fft.transform(re, im, inverse = true)
            val base = t * HOP
            for (j in 0 until N_FFT) { sum[base + j] += re[j] / N_FFT * window[j]; den[base + j] += window[j] * window[j] }
        }
        for (j in 0 until CHUNK) { val p = j + N_FFT / 2; out[j] = if (den[p] > 1e-11f) sum[p] / den[p] else 0f }
    }

    companion object {
        const val RATE = 44_100
        const val CHUNK = 352_800
        const val N_FFT = 2048
        const val HOP = 441
        const val FRAMES = CHUNK / HOP + 1
        const val BINS = N_FFT / 2 + 1
        const val STEP = CHUNK / 2
        const val FADE = CHUNK / 10

        /** numpy's `reflect` (the edge sample is not repeated) of index [j] in a signal of [n] samples. */
        internal fun reflectIndex(j: Int, n: Int): Int {
            if (n == 1) return 0
            val period = 2 * (n - 1)
            var k = j % period
            if (k < 0) k += period
            return if (k < n) k else period - k
        }

        internal fun reflectPad(x: FloatArray, pad: Int): FloatArray = FloatArray(x.size + 2 * pad) { x[reflectIndex(it - pad, x.size)] }

        /** The reference's window: linear fades of [FADE] samples (numpy linspace, both ends included). */
        internal fun fades(): FloatArray = FloatArray(CHUNK) { j ->
            when {
                j < FADE -> j / (FADE - 1f)
                j >= CHUNK - FADE -> (CHUNK - 1 - j) / (FADE - 1f)
                else -> 1f
            }
        }
    }
}
