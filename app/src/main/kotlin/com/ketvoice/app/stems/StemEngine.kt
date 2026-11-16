package com.ketvoice.app.stems

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The two Spleeter models run by LiteRT on the phone's processor (XNNPACK). */
internal class LiteRtStemNet(vocals: File, accompaniment: File, private val threads: Int) : StemNet {
    private fun options() = org.tensorflow.lite.Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true)
    private val vocalNet = org.tensorflow.lite.Interpreter(vocals, options())
    private val accompanimentNet = org.tensorflow.lite.Interpreter(accompaniment, options())
    private val size = 2 * StemDsp.NET_FRAMES * StemDsp.NET_BINS
    private val input = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())

    override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) {
        this.input.clear(); this.input.asFloatBuffer().put(input)
        for ((net, into) in listOf(vocalNet to vocals, accompanimentNet to accompaniment)) {
            this.input.rewind(); output.clear()
            net.run(this.input, output)
            output.rewind(); output.asFloatBuffer().get(into)
        }
    }

    override fun close() { vocalNet.close(); accompanimentNet.close() }
}

/**
 * Turns the sound of part of a title into its instrumental for a deck: the separation is read
 * from [StemStore] when this part was already separated, otherwise the network computes it once
 * and it is stored. Runs on a background thread, never on the audio thread.
 */
internal object StemEngine {
    /** At most this much of a title's intro or ending is separated: where a mix takes place, and what a phone's memory allows. */
    const val MAX_PART_MS = 50_000L

    @Volatile private var net: StemNet? = null
    /** Set by tests: the network to use instead of the downloaded one. */
    @Volatile internal var testNet: StemNet? = null

    @Synchronized private fun network(): StemNet? {
        testNet?.let { return it }
        net?.let { return it }
        if (!StemModel.ready) return null
        val v = StemModel.file(StemModel.parts[0]) ?: return null
        val a = StemModel.file(StemModel.parts[1]) ?: return null
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        return runCatching { LiteRtStemNet(v, a, threads) }.onFailure {
            android.util.Log.w("KetvoiceStems", "model failed to load: ${it.javaClass.simpleName}")
        }.getOrNull()?.also { net = it }
    }

    @Synchronized fun close() { net?.close(); net = null }

    /**
     * The instrumental of [pcm] (interleaved 16-bit stereo at [rate], its first frame at [startUs]
     * in the title). Null when this part was never separated and [compute] is false, or when the
     * model is missing.
     */
    fun instrumental(trackId: String, durationMs: Long, intro: Boolean, pcm: ShortArray, rate: Int, startUs: Long,
                     compute: Boolean, cancelled: () -> Boolean = { false }): StemAudio? {
        if (pcm.size < 4 || rate <= 0) return null
        val frames = pcm.size / 2
        // Each channel is resampled straight from the recording: the fewest copies of a minute of sound.
        val left = Resampler.resample(pcm, 0, 2, rate, StemDsp.RATE)
        val right = Resampler.resample(pcm, 1, 2, rate, StemDsp.RATE)
        if (cancelled()) return null
        val mask = StemStore.load(trackId, durationMs, intro) ?: run {
            if (!compute) return null
            val model = network() ?: return null
            val found = synchronized(this) { Separator.mask(left, right, startUs, model, cancelled) } ?: return null
            StemStore.save(trackId, durationMs, intro, found)
            found
        }
        // The stored separation starts where its first reading started; this reading may start a little elsewhere.
        val offset = Math.round((mask.startUs - startUs) * StemDsp.RATE / 1_000_000.0).toInt()
        val result = Separator.instrumental(left, right, mask, offset)
        if (result.until - result.from < StemDsp.RATE || cancelled()) return null
        val outL = Resampler.resample(result.left, StemDsp.RATE, rate)
        val outR = Resampler.resample(result.right, StemDsp.RATE, rate)
        val n = minOf(outL.size, outR.size, frames)
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            out[2 * i] = (outL[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * i + 1] = (outR[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }
        val from = (result.from.toLong() * rate / StemDsp.RATE).toInt() + 1
        val until = (result.until.toLong() * rate / StemDsp.RATE).toInt().coerceAtMost(n) - 1
        if (until - from < rate) return null
        return StemAudio(startUs, rate, out, from, until)
    }
}
