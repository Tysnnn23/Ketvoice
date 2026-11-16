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
    @Volatile private var uvr: UvrNet? = null
    /** Set by tests: the networks to use instead of the downloaded ones. */
    @Volatile internal var testNet: StemNet? = null
    @Volatile internal var testUvr: UvrNet? = null

    private fun threads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    @Synchronized private fun network(): StemNet? {
        testNet?.let { return it }
        net?.let { return it }
        if (!StemModel.fast.ready) return null
        val v = StemModel.fast.file(StemModel.fast.parts[0]) ?: return null
        val a = StemModel.fast.file(StemModel.fast.parts[1]) ?: return null
        return runCatching { LiteRtStemNet(v, a, threads()) }.onFailure {
            android.util.Log.w("KetvoiceStems", "model failed to load: ${it.javaClass.simpleName}")
        }.getOrNull()?.also { net = it }
    }

    @Synchronized private fun highQuality(): UvrNet? {
        testUvr?.let { return it }
        uvr?.let { return it }
        if (!StemModel.hq.ready) return null
        val f = StemModel.hq.file(StemModel.hq.parts[0]) ?: return null
        return runCatching { LiteRtUvrNet(f, threads()) }.onFailure {
            android.util.Log.w("KetvoiceStems", "high-quality model failed to load: ${it.javaClass.simpleName}")
        }.getOrNull()?.also { uvr = it }
    }

    @Synchronized fun close() { net?.close(); net = null; uvr?.close(); uvr = null }

    /** Which model separates right now, from the setting and the charger; null when none is there. */
    fun wanted(): Int? = testUvr?.let { StemMask.HIGH } ?: testNet?.let { StemMask.FAST }
        ?: StemModel.quality(com.ketvoice.app.data.AppSettings.ketmixStemsQuality.value, StemModel.charging())

    /** Separates [left]/[right] (44.1 kHz) with the model of [quality]. */
    internal fun separate(left: FloatArray, right: FloatArray, startUs: Long, quality: Int, cancelled: () -> Boolean): StemMask? {
        if (quality == StemMask.HIGH) {
            val model = highQuality() ?: return null
            return synchronized(this) {
                val (vl, vr) = UvrSeparator(model).voice(left, right, cancelled) ?: return null
                Separator.maskFromVoice(left, right, vl, vr, startUs, cancelled)
            }
        }
        val model = network() ?: return null
        return synchronized(this) { Separator.mask(left, right, startUs, model, cancelled) }
    }

    /**
     * Separates this part of a title now if it never was, or was by the quick model while the better
     * one is wanted, so that a later mix only has to apply the result (5.11.0: titles are prepared as
     * soon as the one before them starts; 6.0.0: separations are improved while the phone charges).
     * True when a separation exists afterwards.
     */
    fun prepare(trackId: String, durationMs: Long, intro: Boolean, pcm: ShortArray, rate: Int, startUs: Long,
                cancelled: () -> Boolean = { false }): Boolean {
        val stored = StemStore.quality(trackId, durationMs, intro)
        val quality = wanted() ?: return stored != null
        if (stored != null && stored >= quality) return true
        if (pcm.size < 4 || rate <= 0) return stored != null
        val left = Resampler.resample(pcm, 0, 2, rate, StemDsp.RATE)
        val right = Resampler.resample(pcm, 1, 2, rate, StemDsp.RATE)
        if (cancelled()) return stored != null
        val found = separate(left, right, startUs, quality, cancelled) ?: return stored != null
        StemStore.save(trackId, durationMs, intro, found)
        return true
    }

    /** Whether this part still needs the work of [prepare] (no separation, or one the wanted model would improve). */
    fun needs(trackId: String, durationMs: Long, intro: Boolean): Boolean {
        val quality = wanted() ?: return false
        val stored = StemStore.quality(trackId, durationMs, intro) ?: return true
        return stored < quality
    }

    /**
     * The instrumental of [pcm] (interleaved 16-bit stereo at [rate], its first frame at [startUs]
     * in the title), and with [withVocals] its voice alone too. Null when this part was never
     * separated and [compute] is false, or when the model is missing.
     */
    fun instrumental(trackId: String, durationMs: Long, intro: Boolean, pcm: ShortArray, rate: Int, startUs: Long,
                     compute: Boolean, withVocals: Boolean = false, cancelled: () -> Boolean = { false }): StemAudio? {
        if (pcm.size < 4 || rate <= 0) return null
        val frames = pcm.size / 2
        // Each channel is resampled straight from the recording: the fewest copies of a minute of sound.
        val left = Resampler.resample(pcm, 0, 2, rate, StemDsp.RATE)
        val right = Resampler.resample(pcm, 1, 2, rate, StemDsp.RATE)
        if (cancelled()) return null
        val mask = StemStore.load(trackId, durationMs, intro) ?: run {
            if (!compute) return null
            // Here a mix waits for the result: the quick model when it is there, the better one otherwise.
            val quality = if (StemModel.fast.ready || testNet != null) StemMask.FAST else wanted() ?: return null
            val found = separate(left, right, startUs, quality, cancelled) ?: return null
            StemStore.save(trackId, durationMs, intro, found)
            found
        }
        // The stored separation starts where its first reading started; this reading may start a little elsewhere.
        val offset = Math.round((mask.startUs - startUs) * StemDsp.RATE / 1_000_000.0).toInt()
        fun render(vocals: Boolean): Pair<ShortArray, IntRange>? {
            val result = Separator.stem(left, right, mask, offset, vocals)
            if (result.until - result.from < StemDsp.RATE || cancelled()) return null
            val outL = Resampler.resample(result.left, StemDsp.RATE, rate)
            val outR = Resampler.resample(result.right, StemDsp.RATE, rate)
            val n = minOf(outL.size, outR.size, frames)
            val out = ShortArray(frames * 2)
            for (i in 0 until n) {
                out[2 * i] = (outL[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                out[2 * i + 1] = (outR[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            }
            val from = (result.from.toLong() * rate / StemDsp.RATE).toInt() + 1
            val until = (result.until.toLong() * rate / StemDsp.RATE).toInt().coerceAtMost(n) - 1
            return out to (from until until)
        }
        val (music, range) = render(vocals = false) ?: return null
        if (range.last - range.first < rate) return null
        val voice = if (withVocals) render(vocals = true)?.first else null
        return StemAudio(startUs, rate, music, range.first, range.last + 1, voice)
    }
}
