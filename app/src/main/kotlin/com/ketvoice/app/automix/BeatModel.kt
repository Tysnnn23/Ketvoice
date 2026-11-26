package com.ketvoice.app.automix

import android.content.Context
import com.ketvoice.app.LiteTuning
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.stems.ModelPack
import com.ketvoice.app.stems.Resampler
import com.ketvoice.app.stems.StemModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * The beat model as published by the beat workflow (tools/beat/expected.sha256). Since 7.0.0 the mobile and Quest
 * APKs carry this very file (app/build.gradle.kts fetches and checks it at build time); Lite downloads it.
 */
internal const val BEAT_BYTES = 18_575_568L
internal const val BEAT_SHA = "74cc5ee960dbdbf75cef978e8e24a5308ef26f459f70494285f249a82a9c67d7"

/** Runs the network on one chunk of [frames] frames of [BeatSpectrogram.MELS] values; returns the beat and the downbeat logits. */
internal interface BeatRunner : AutoCloseable {
    /** How many frames the network takes at once (8.4: read from the model, so that a 30 s export is cut as such). */
    val frames: Int get() = BeatPost.BEAT_FRAMES
    fun run(spect: FloatArray): Pair<FloatArray, FloatArray>
    override fun close() {}
}

/** Beat This! small (CPJKU, ISMIR 2024, MIT; 2.1 M parameters) converted to LiteRT with a fixed input [1, frames, 128] (500 in `ketmix-beat-v1`). */
internal class LiteRtBeatNet private constructor(private val interpreter: org.tensorflow.lite.Interpreter) : BeatRunner {
    constructor(file: File, threads: Int = 2) : this(org.tensorflow.lite.Interpreter(file, options(threads)))
    /** The model memory-mapped from the APK's assets (7.0.0); [model] must stay mapped while this runs. */
    constructor(model: ByteBuffer, threads: Int = 2) : this(org.tensorflow.lite.Interpreter(model, options(threads)))
    private val order = BeatModel.pick((0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).name() })
    override val frames: Int = interpreter.getInputTensor(0).shape().getOrNull(1)?.takeIf { it > 2 * BeatPost.BORDER } ?: BeatPost.BEAT_FRAMES
    private val input = ByteBuffer.allocateDirect(4 * frames * BeatSpectrogram.MELS).order(ByteOrder.nativeOrder())

    override fun run(spect: FloatArray): Pair<FloatArray, FloatArray> {
        input.clear(); input.asFloatBuffer().put(spect); input.rewind()
        val outputs = HashMap<Int, Any>()
        val buffers = (0 until interpreter.outputTensorCount).map {
            ByteBuffer.allocateDirect(interpreter.getOutputTensor(it).numBytes()).order(ByteOrder.nativeOrder()).also { b -> outputs[it] = b }
        }
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        return floats(buffers[order.first]) to floats(buffers[order.second])
    }

    private fun floats(buffer: ByteBuffer): FloatArray {
        buffer.rewind()
        val out = FloatArray(buffer.remaining() / 4)
        buffer.asFloatBuffer().get(out)
        check(out.size == frames) { "unexpected output size ${out.size}" }
        return out
    }

    override fun close() = interpreter.close()

    private companion object {
        fun options(threads: Int) = org.tensorflow.lite.Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true)
    }
}

/** The mono sound of a stretch as it is decoded, at the decoder's own rate, kept for the model (the audio is never stored). */
internal class BeatPcm(private val rate: Int, private val channels: Int, private val firstUs: Long) {
    private var data = FloatArray(rate * 20)
    var size = 0
        private set
    private val cap = rate * CAP_SECONDS

    fun accept16(buffer: ByteBuffer) {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = max(1, channels)
        while (view.remaining() >= 2 * count && size < cap) {
            var sum = 0
            for (c in 0 until count) sum += view.short.toInt()
            if (size == data.size) data = data.copyOf(min(cap, data.size * 2))
            data[size++] = sum / (32768f * count)
        }
    }

    /** The model's window of the stretch at 22 050 Hz: its first or its last [MAX_SECONDS]; and where in the title it starts, in ms. */
    fun window(tail: Boolean): Pair<FloatArray, Double>? {
        val wanted = (MAX_SECONDS * rate).toInt()
        val from = if (tail) max(0, size - wanted) else 0
        val to = min(size, from + wanted)
        if (to - from < MIN_SECONDS * rate) return null
        val mono = Resampler.resample(data.copyOfRange(from, to), rate, BeatSpectrogram.RATE)
        return mono to (firstUs / 1000.0 + from * 1000.0 / rate)
    }

    companion object {
        const val CAP_SECONDS = 90
        /** Six chunks of the 10 s network at most, two of a 30 s one (see [BeatPost.starts]). */
        const val MAX_SECONDS = 45.0
        const val MIN_SECONDS = 8
    }
}

internal object BeatModel {
    val pack = ModelPack("https://github.com/Tysnnn23/Ketvoice/releases/download/ketmix-beat-v1/",
        listOf(ModelPack.Part("ketmix-beat-small0.tflite", BEAT_BYTES, BEAT_SHA)), "beat/model")

    /** Where the mobile and Quest APKs carry the model (stored uncompressed, see app/build.gradle.kts). */
    const val ASSET = "beat/ketmix-beat-small0.tflite"

    /** True when this APK carries the model: no download then, a switch in the settings instead (7.0.0). */
    @Volatile var bundled = false
        private set
    @Volatile private var assets: android.content.res.AssetManager? = null
    @Volatile private var mapped: ByteBuffer? = null

    fun init(context: Context) {
        pack.init(context.filesDir)
        assets = context.assets
        bundled = runCatching { context.assets.openFd(ASSET).use { it.length == BEAT_BYTES } }.getOrDefault(false)
        // A copy downloaded before 7.0.0 is no longer needed: the APK has the same file.
        if (bundled) pack.parts.forEach { part -> pack.file(part)?.takeIf { it.isFile }?.delete() }
    }

    /** The model may run now: carried by the APK and switched on, or downloaded; on Lite and on modest phones only while charging. */
    fun usable(): Boolean = present(bundled, AppSettings.ketmixBeatModel.value, pack.ready) &&
        allowed(LiteTuning.lite, LiteTuning.modest, StemModel.charging())

    internal fun present(bundled: Boolean, switchedOn: Boolean, downloaded: Boolean) = if (bundled) switchedOn else downloaded

    /** The bundled model, mapped once from the APK (no copy in memory or on disk). */
    private fun asset(): ByteBuffer? = mapped ?: synchronized(this) {
        mapped ?: runCatching {
            assets?.openFd(ASSET)?.use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).channel.use { channel ->
                    channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }.getOrNull()?.also { mapped = it }
    }

    internal fun allowed(lite: Boolean, modest: Boolean, charging: Boolean) = !(lite || modest) || charging

    fun remove() {
        pack.delete()
    }

    /** Indices of the beat and the downbeat among the network's outputs: by their names, otherwise in that order. */
    internal fun pick(names: List<String>): Pair<Int, Int> {
        val down = names.indexOfFirst { it.contains("downbeat", ignoreCase = true) }
        val beat = names.indexOfFirst { it.contains("beat", ignoreCase = true) && !it.contains("downbeat", ignoreCase = true) }
        return if (down >= 0 && beat >= 0) beat to down else 0 to 1
    }

    /** Test seam: where the network comes from. */
    @Volatile internal var open: () -> BeatRunner? = {
        if (bundled) asset()?.let { LiteRtBeatNet(it) }
        else pack.file(pack.parts.single())?.takeIf { it.isFile }?.let { LiteRtBeatNet(it) }
    }

    /** The log-mel frames the model read ([BeatSpectrogram]) and where in the title the first one is, in ms (9.0, for [SongSections]). */
    class Mels(val frames: FloatArray, val originMs: Double)

    /**
     * [dsp] with the model's beats and bars where it is sure of them; [dsp] as it was (marked as tried) otherwise. Never throws.
     * [mels] is handed the frames the model read, when it ran.
     */
    fun refine(dsp: SegmentAnalysis, pcm: BeatPcm, tail: Boolean, mels: ((Mels) -> Unit)? = null): SegmentAnalysis {
        val read = runCatching { pcm.window(tail)?.let { (samples, originMs) -> read(dsp, samples, originMs, mels) } }.getOrNull()
        return BeatMapping.merge(dsp, read)
    }

    private fun read(dsp: SegmentAnalysis, samples: FloatArray, originMs: Double, mels: ((Mels) -> Unit)?): SegmentAnalysis? =
        open()?.use { net ->
            val spect = BeatSpectrogram.compute(samples)
            mels?.invoke(Mels(spect, originMs))
            BeatMapping.segment(predictSpect(net, spect), dsp, originMs)
        }

    internal fun predict(net: BeatRunner, samples: FloatArray): BeatPost.Times = predictSpect(net, BeatSpectrogram.compute(samples))

    private fun predictSpect(net: BeatRunner, spect: FloatArray): BeatPost.Times {
        val frames = spect.size / BeatSpectrogram.MELS
        val window = BeatPost.Window(net.frames)
        val starts = BeatPost.starts(frames, window)
        val outputs = starts.map { net.run(BeatPost.chunk(spect, frames, it, window)) }
        return BeatPost.peaks(BeatPost.aggregate(outputs.map { it.first }, starts, frames, window),
            BeatPost.aggregate(outputs.map { it.second }, starts, frames, window))
    }
}
