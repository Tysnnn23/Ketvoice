package com.ketvoice.app.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer

private fun onnxSession(file: File, threads: Int): OrtSession =
    OrtEnvironment.getEnvironment().createSession(file.path, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })

private fun OrtSession.runOne(input: FloatArray, shape: LongArray, into: FloatArray) {
    val env = OrtEnvironment.getEnvironment()
    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
        run(mapOf(inputNames.first() to tensor)).use { result ->
            val out = (result.get(0) as OnnxTensor).floatBuffer
            check(out.remaining() == into.size) { "unexpected output size ${out.remaining()}" }
            out.get(into)
        }
    }
}

/**
 * Spleeter 2 stems as sherpa-onnx exports it: two networks, input `x` and output `y` of
 * `[2 channels, splits, 512 frames, 1024 bins]` magnitudes; one split here, which is [StemNet]'s layout.
 */
internal class OnnxStemNet(vocals: File, accompaniment: File, threads: Int) : StemNet {
    private val vocalNet = onnxSession(vocals, threads)
    private val accompanimentNet = onnxSession(accompaniment, threads)
    private val shape = longArrayOf(2, 1, StemDsp.NET_FRAMES.toLong(), StemDsp.NET_BINS.toLong())

    override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) {
        vocalNet.runOne(input, shape, vocals)
        accompanimentNet.runOne(input, shape, accompaniment)
    }

    override fun close() { vocalNet.close(); accompanimentNet.close() }
}

/** UVR MDX-Net as sherpa-onnx exports it: `input` and `output` of `[1, 4, dimF, dimT]`, channels first. */
internal class OnnxUvrNet(file: File, threads: Int) : UvrNet {
    private val session = onnxSession(file, threads)
    private val dims = (session.inputInfo.values.first().info as TensorInfo).shape
    override val dimF: Int = dims.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: 3072
    override val dimT: Int = dims.getOrNull(3)?.takeIf { it > 0 }?.toInt() ?: 256
    override val nFft: Int = dimF * 2
    private val shape = longArrayOf(1, 4, dimF.toLong(), dimT.toLong())

    override fun run(input: FloatArray, output: FloatArray) = session.runOne(input, shape, output)
    override fun close() = session.close()
}

/**
 * The desktop counterpart of the phone's `StemEngine` (same members, same rules): separations are read from
 * [StemStore] when a part was already separated, otherwise the network computes them once and they are stored.
 * The networks run through ONNX Runtime on the processor. Never on the audio thread.
 */
internal object StemEngine {
    /** At most this much of a title's intro or ending is separated for a mix. */
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
        return runCatching { OnnxStemNet(v, a, threads()) }.onFailure {
            android.util.Log.w("KetvoiceStems", "model failed to load: $it")
        }.getOrNull()?.also { net = it }
    }

    @Synchronized private fun highQuality(): UvrNet? {
        testUvr?.let { return it }
        uvr?.let { return it }
        if (!StemModel.hq.ready) return null
        val f = StemModel.hq.file(StemModel.hq.parts[0]) ?: return null
        return runCatching { OnnxUvrNet(f, threads()) }.onFailure {
            android.util.Log.w("KetvoiceStems", "high-quality model failed to load: $it")
        }.getOrNull()?.also { uvr = it }
    }

    @Synchronized fun close() { net?.close(); net = null; uvr?.close(); uvr = null }

    /** Which model separates right now, from the setting and the mains power; null when none is there. */
    fun wanted(): Int? = testUvr?.let { StemMask.HIGH } ?: testNet?.let { StemMask.FAST }
        ?: StemModel.charging().let { StemModel.quality(com.ketvoice.app.data.AppSettings.ketmixStemsQuality.value, it) }

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

    /** Separates this part now if it never was, or was by the quick model while the better one is wanted. */
    fun prepare(trackId: String, durationMs: Long, intro: Boolean, pcm: ShortArray, rate: Int, startUs: Long,
                cancelled: () -> Boolean = { false }): Boolean {
        val stored = StemStore.quality(trackId, durationMs, intro)
        val quality = wanted() ?: return stored != null
        if (stored != null && stored >= quality) return true
        return store(trackId, durationMs, intro, pcm, rate, startUs, quality, cancelled) || stored != null
    }

    /** Separates this part with the model of [quality] and stores it, whatever is stored already. */
    fun store(trackId: String, durationMs: Long, intro: Boolean, pcm: ShortArray, rate: Int, startUs: Long,
              quality: Int, cancelled: () -> Boolean = { false }): Boolean {
        if (pcm.size < 4 || rate <= 0) return false
        val left = Resampler.resample(pcm, 0, 2, rate, StemDsp.RATE)
        val right = Resampler.resample(pcm, 1, 2, rate, StemDsp.RATE)
        if (cancelled()) return false
        val found = separate(left, right, startUs, quality, cancelled) ?: return false
        StemStore.save(trackId, durationMs, intro, found)
        return true
    }

    /** The model a mix waiting for its result uses: the quick one when it is there, the better one otherwise. */
    fun forMix(): Int? = if (StemModel.fast.ready || testNet != null) StemMask.FAST else wanted()

    /** Whether this part still needs the work of [prepare]. */
    fun needs(trackId: String, durationMs: Long, intro: Boolean): Boolean {
        val quality = wanted() ?: return false
        val stored = StemStore.quality(trackId, durationMs, intro) ?: return true
        return stored < quality
    }

    /**
     * The instrumental of [pcm] (interleaved 16-bit stereo at [rate], its first frame at [startUs] in the title),
     * and with [withVocals] its voice alone too. Null when this part was never separated and [compute] is false,
     * or when the model is missing.
     */
    fun instrumental(trackId: String, durationMs: Long, intro: Boolean, pcm: ShortArray, rate: Int, startUs: Long,
                     compute: Boolean, withVocals: Boolean = false, cancelled: () -> Boolean = { false }): StemAudio? {
        if (pcm.size < 4 || rate <= 0) return null
        val frames = pcm.size / 2
        val left = Resampler.resample(pcm, 0, 2, rate, StemDsp.RATE)
        val right = Resampler.resample(pcm, 1, 2, rate, StemDsp.RATE)
        if (cancelled()) return null
        val mask = StemStore.load(trackId, durationMs, intro) ?: run {
            if (!compute) return null
            val quality = forMix() ?: return null
            val found = separate(left, right, startUs, quality, cancelled) ?: return null
            StemStore.save(trackId, durationMs, intro, found)
            found
        }
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
