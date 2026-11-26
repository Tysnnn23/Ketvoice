package com.ketvoice.app.automix

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.stems.Resampler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * Ketmix's rhythm model on the desktop: Beat This! small (CPJKU, ISMIR 2024, MIT), the very network the
 * Android app runs through LiteRT, here as its ONNX export through ONNX Runtime (CPU). It is downloaded on
 * request and checked against its pinned SHA-256; the analysis is the shared one ([BeatSpectrogram],
 * [BeatPost], [BeatMapping]), so phone and desktop find the same beats.
 */
internal const val BEAT_ONNX = "ketmix-beat-small0.onnx"
internal const val BEAT_ONNX_BYTES = 10_401_044L
/** `small0.onnx` as `.github/workflows/publish-beat-model.yml` pins it (the source of the phone's LiteRT file). */
internal const val BEAT_ONNX_SHA = "c462b064a4033050ca6a5354bf866ff4610ab1fd8f1dec1e494b20b22d870aea"

/** Runs the network on one chunk of [frames] frames of [BeatSpectrogram.MELS] values; returns the beat and the downbeat logits. */
internal interface BeatRunner : AutoCloseable {
    /** How many frames the network takes at once (8.4: read from the model, so that a 30 s export is cut as such). */
    val frames: Int get() = BeatPost.BEAT_FRAMES
    fun run(spect: FloatArray): Pair<FloatArray, FloatArray>
    override fun close() {}
}

/** The ONNX export, input `[1, frames, 128]`, outputs beat and downbeat logits `[1, frames]`. */
internal class OnnxBeatNet(file: File, full: Boolean = false) : BeatRunner {
    private val env = OrtEnvironment.getEnvironment()
    /** Windows 2.0: the full model runs on the graphics card when it can, with half the cores otherwise. */
    private val session = if (full) com.ketvoice.app.stems.OrtAccel.session(file, com.ketvoice.app.stems.OrtAccel.threads(background = true))
        else env.createSession(file.path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
    private val input = session.inputNames.first()
    private val outputs = session.outputNames.toList()
    private val order = BeatModel.pick(outputs)
    /** The input's frame count when the export fixes it (`ketmix-beat-v1`: 500), else the 10 s window. */
    override val frames: Int = runCatching {
        (session.inputInfo.getValue(input).info as ai.onnxruntime.TensorInfo).shape.getOrNull(1)?.toInt()
    }.getOrNull()?.takeIf { it > 2 * BeatPost.BORDER } ?: BeatPost.BEAT_FRAMES

    override fun run(spect: FloatArray): Pair<FloatArray, FloatArray> {
        val shape = longArrayOf(1, (spect.size / BeatSpectrogram.MELS).toLong(), BeatSpectrogram.MELS.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(spect), shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                fun floats(i: Int): FloatArray {
                    val t = result.get(outputs[i]).get() as OnnxTensor
                    val out = FloatArray(t.floatBuffer.remaining()).also { t.floatBuffer.get(it) }
                    check(out.size == frames) { "unexpected output size ${out.size}" }
                    return out
                }
                return floats(order.first) to floats(order.second)
            }
        }
    }

    override fun close() = session.close()
}

/** The mono sound of a stretch as it is decoded, at the decoder's own rate, kept for the model (as on Android). */
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
        const val MAX_SECONDS = 45.0
        const val MIN_SECONDS = 8
    }
}

internal object BeatModel {
    /** Where it is published: beside the phone's LiteRT file on the `ketmix-beat-v1` prerelease, then its source. */
    val urls = listOf(
        "https://github.com/Tysnnn23/Ketvoice/releases/download/ketmix-beat-v1/$BEAT_ONNX",
        "https://huggingface.co/ashudesai/songbird-models/resolve/main/small0.onnx",
    )

    /** Overridable for tests; the app's data folder otherwise. */
    @Volatile internal var folder: File? = null
    private fun dir(): File = folder ?: File(android.content.Context.app.filesDir, "beat")
    fun file(): File = File(dir(), BEAT_ONNX)

    /** On disk and checked (the check runs when it is downloaded; the size is enough afterwards). */
    val ready: Boolean get() = runCatching { file().length() == BEAT_ONNX_BYTES }.getOrDefault(false)

    /** The model may run now: switched on in the settings, and downloaded (the small one, or the full one of « Maximum »). */
    fun usable(): Boolean = AppSettings.ketmixBeatModel.value && (ready || fullFile() != null)

    /** Indices of the beat and the downbeat among the network's outputs: by their names, otherwise in that order. */
    internal fun pick(names: List<String>): Pair<Int, Int> {
        val down = names.indexOfFirst { it.contains("downbeat", ignoreCase = true) }
        val beat = names.indexOfFirst { it.contains("beat", ignoreCase = true) && !it.contains("downbeat", ignoreCase = true) }
        return if (down >= 0 && beat >= 0) beat to down else 0 to 1
    }

    /**
     * Downloads the model ([progress] 0..1) and keeps it only when its SHA-256 is the pinned one. Blocking;
     * true when it is ready. [fetch] opens a URL (test seam).
     */
    fun download(progress: (Float) -> Unit = {}, fetch: (String) -> java.io.InputStream? = ::open): Boolean {
        if (ready) return true
        val target = file()
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "$BEAT_ONNX.part")
        for (url in urls) {
            val ok = runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                (fetch(url) ?: return@runCatching false).use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > BEAT_ONNX_BYTES) return@runCatching false
                            digest.update(buf, 0, n); out.write(buf, 0, n)
                            progress(read.toFloat() / BEAT_ONNX_BYTES)
                        }
                    }
                }
                read == BEAT_ONNX_BYTES && digest.digest().joinToString("") { "%02x".format(it) } == BEAT_ONNX_SHA
            }.getOrDefault(false)
            if (ok) {
                java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return true
            }
            part.delete()
        }
        return false
    }

    fun remove() { runCatching { file().delete() } }

    private fun open(url: String): java.io.InputStream? {
        val response = okhttp3.OkHttpClient.Builder().followRedirects(true).build()
            .newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Ketvoice").build()).execute()
        if (!response.isSuccessful) { response.close(); return null }
        return response.body?.byteStream()
    }

    /** Windows 2.0 « Maximum »: Beat This! final0 when the tier has it (set by the app from `PcModels`), else null. */
    @Volatile internal var fullFile: () -> File? = { null }

    /** Test seam: where the network comes from (the full model first when the tier has it). */
    @Volatile internal var openNet: () -> BeatRunner? = {
        fullFile()?.let { runCatching { OnnxBeatNet(it, full = true) }.getOrNull() } ?: file().takeIf { ready }?.let { OnnxBeatNet(it) }
    }

    /** The log-mel frames the model read ([BeatSpectrogram]) and where in the title the first one is, in ms (for [SongSections]). */
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
        openNet()?.use { net ->
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
