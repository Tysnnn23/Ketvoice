package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.SongStructure
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.ketvoice.app.automix.BeatModel
import com.ketvoice.app.automix.BeatSpectrogram
import com.ketvoice.app.automix.MusicalKey
import com.ketvoice.app.stems.OrtAccel
import com.ketvoice.app.stems.Resampler
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * What « Maximum » knows of a whole title (Windows 2.0), computed once while the PC is idle ([LibraryPrep]) and kept
 * in `ketmix/facts`: its beats and bars (Beat This! final0), its sections ([SongStructure]), its key (S-KEY) and
 * energy ([EnergyCurve]) and its sound embedding (CLAP). Any part may be missing when its model is.
 */
class TrackFacts(
    val durationMs: Long,
    val barsMs: DoubleArray,
    val sections: List<SongStructure.Section>,
    /** [MusicalKey] encoding (0..11 major, 12..23 minor, tonic from C), [MusicalKey.NONE] when unknown. */
    val key: Int,
    val keyConfidence: Float,
    val energy: FloatArray,
    /** Unit-length CLAP embedding, null without the model. */
    val sound: FloatArray?,
) {
    companion object {
        const val VERSION = 1
    }
}

/** One small file per title: `ketmix/facts/<sha1 of the id>.kvf`. */
object TrackFactsStore {
    @Volatile internal var folder: File? = null
    private val cache = object : LinkedHashMap<String, TrackFacts?>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackFacts?>?) = size > 400
    }

    private fun dir(): File = folder ?: File(android.content.Context.app.filesDir, "ketmix/facts")
    private fun file(id: String): File = File(dir(), java.security.MessageDigest.getInstance("SHA-1")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) } + ".kvf")

    /** The facts of [id] when they were computed for this length (±2 s: another version of a title has other facts). */
    @Synchronized fun get(id: String, durationMs: Long): TrackFacts? {
        val facts = if (cache.containsKey(id)) cache[id] else runCatching { read(file(id)) }.getOrNull().also { cache[id] = it }
        return facts?.takeIf { durationMs <= 0 || kotlin.math.abs(it.durationMs - durationMs) <= 2_000L }
    }

    @Synchronized fun has(id: String): Boolean = get(id, 0) != null

    @Synchronized fun put(id: String, facts: TrackFacts) {
        val f = file(id)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { write(it, facts) }
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        cache[id] = facts
    }

    /** Every title with facts and an embedding (for similarity), read lazily. */
    fun sound(id: String, durationMs: Long): FloatArray? = get(id, durationMs)?.sound

    internal fun write(out: DataOutputStream, f: TrackFacts) {
        out.writeInt(TrackFacts.VERSION); out.writeLong(f.durationMs)
        out.writeInt(f.barsMs.size); f.barsMs.forEach(out::writeDouble)
        out.writeInt(f.sections.size); f.sections.forEach { out.writeLong(it.startMs); out.writeLong(it.endMs); out.writeByte(it.label.ordinal) }
        out.writeInt(f.key); out.writeFloat(f.keyConfidence)
        out.writeInt(f.energy.size); f.energy.forEach(out::writeFloat)
        out.writeInt(f.sound?.size ?: 0); f.sound?.forEach(out::writeFloat)
    }

    internal fun read(file: File): TrackFacts? {
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != TrackFacts.VERSION) return null
            val duration = input.readLong()
            val bars = DoubleArray(input.readInt().coerceIn(0, 100_000)) { input.readDouble() }
            val sections = List(input.readInt().coerceIn(0, 1_000)) {
                SongStructure.Section(input.readLong(), input.readLong(), SongStructure.Label.values()[input.readByte().toInt().coerceIn(0, 4)])
            }
            val key = input.readInt(); val confidence = input.readFloat()
            val energy = FloatArray(input.readInt().coerceIn(0, 100_000)) { input.readFloat() }
            val sound = input.readInt().coerceIn(0, 4096).takeIf { it > 0 }?.let { n -> FloatArray(n) { input.readFloat() } }
            return TrackFacts(duration, bars, sections, key, confidence, energy, sound)
        }
    }

    /** Test seam. */
    @Synchronized internal fun clearCache() = cache.clear()
}

/** S-KEY (Deezer, MIT) through ONNX Runtime: the key of a whole title. */
object NeuralKey {
    const val RATE = 22_050
    /** At most this much of a title is read (S-KEY averages over time; the middle eight minutes say it all). */
    private const val MAX_SECONDS = 480

    /** S-KEY's order (0 A major … 11 G# major, 12 B minor … 23 Bb minor) to [MusicalKey]'s (tonic from C). */
    fun fromSkey(index: Int): Int = if (index < 12) (index + 9) % 12 else 12 + (index - 12 + 11) % 12

    /** The key and S-KEY's probability for it, from mono samples at [RATE]; null without the model or for under 4 s. */
    fun read(mono: FloatArray, model: File): Pair<Int, Float>? {
        if (mono.size < RATE * 4) return null
        val n = minOf(mono.size, RATE * MAX_SECONDS)
        val from = (mono.size - n) / 2
        val env = OrtEnvironment.getEnvironment()
        OrtAccel.session(model, OrtAccel.threads(background = true), gpu = false).use { session ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(mono, from, n), longArrayOf(1, n.toLong())).use { tensor ->
                session.run(mapOf("audio" to tensor)).use { result ->
                    val p = FloatArray(24).also { (result.get(0) as OnnxTensor).floatBuffer.get(it) }
                    val best = p.indices.maxByOrNull { p[it] } ?: return null
                    return fromSkey(best) to p[best]
                }
            }
        }
    }
}

/** LAION CLAP for music (audio side, int8): a title's sound as a 512-value unit vector; cosine = dot product. */
object SoundPrint {
    const val RATE = 48_000
    private const val WINDOW = 480_000

    /** Mean of the embeddings of three 10 s windows (a quarter, half and three quarters in), unit length. */
    fun of(mono48: FloatArray, model: File): FloatArray? {
        if (mono48.size < WINDOW) return null
        val env = OrtEnvironment.getEnvironment()
        val sum = FloatArray(512)
        OrtAccel.session(model, OrtAccel.threads(background = true), gpu = false).use { session ->
            for (share in listOf(.25, .5, .75)) {
                val from = ((mono48.size - WINDOW) * share).toInt()
                OnnxTensor.createTensor(env, FloatBuffer.wrap(mono48, from, WINDOW), longArrayOf(1, WINDOW.toLong())).use { tensor ->
                    session.run(mapOf("audio" to tensor)).use { result ->
                        val e = FloatArray(512).also { (result.get(0) as OnnxTensor).floatBuffer.get(it) }
                        for (k in sum.indices) sum[k] += e[k]
                    }
                }
            }
        }
        return unit(sum)
    }

    fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (n < 1e-9f) v else FloatArray(v.size) { v[it] / n }
    }

    fun cosine(a: FloatArray?, b: FloatArray?): Float? {
        if (a == null || b == null || a.size != b.size) return null
        var dot = 0f
        for (k in a.indices) dot += a[k] * b[k]
        return dot
    }
}

/** Computes [TrackFacts] from a whole title's decoded sound, with whatever « Maximum » models are on this PC. */
object FactsAnalysis {
    /** [pcm]: interleaved 16-bit stereo at [rate]. Null when cancelled or too short. */
    fun analyse(pcm: ShortArray, rate: Int, durationMs: Long, cancelled: () -> Boolean): TrackFacts? {
        if (pcm.size < rate * 2 * 20) return null
        val mono22 = mono(pcm, rate, NeuralKey.RATE)
        if (cancelled()) return null
        val mels = BeatSpectrogram.compute(mono22)
        val fps = BeatSpectrogram.RATE.toFloat() / BeatSpectrogram.HOP
        val bars = runCatching { BeatModel.openNet()?.use { net -> BeatModel.predict(net, mono22).downbeats.map { it * 1000.0 } } }
            .getOrNull().orEmpty()
        if (cancelled()) return null
        val sections = SongStructure.sections(mels, BeatSpectrogram.MELS, fps, bars, durationMs)
        val energy = EnergyCurve.of(mels, BeatSpectrogram.MELS, fps)
        val key = PcModels.file(PcModel.Key)?.let { runCatching { NeuralKey.read(mono22, it) }.getOrNull() }
        if (cancelled()) return null
        val sound = PcModels.file(PcModel.Sound)?.let { model -> runCatching { SoundPrint.of(mono(pcm, rate, SoundPrint.RATE), model) }.getOrNull() }
        return TrackFacts(durationMs, bars.toDoubleArray(), sections, key?.first ?: MusicalKey.NONE, key?.second ?: 0f, energy, sound)
    }

    private fun mono(pcm: ShortArray, rate: Int, to: Int): FloatArray {
        val l = Resampler.resample(pcm, 0, 2, rate, to)
        val r = Resampler.resample(pcm, 1, 2, rate, to)
        return FloatArray(minOf(l.size, r.size)) { (l[it] + r[it]) * .5f }
    }
}
