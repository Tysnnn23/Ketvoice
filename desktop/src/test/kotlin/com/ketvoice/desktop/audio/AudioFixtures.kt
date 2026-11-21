package com.ketvoice.desktop.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Test signals written in Kotlin, encoded by the FFmpeg executable bundled with the JavaCPP natives. */
object AudioFixtures {
    val dir: File by lazy { File("build/audio-fixtures").apply { mkdirs() } }

    private val ffmpeg: String by lazy { org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }

    /** Encoder arguments and file extension per codec. */
    val codecs = linkedMapOf(
        "aac" to (listOf("-c:a", "aac", "-b:a", "160k") to "m4a"),
        "opus" to (listOf("-c:a", "libopus", "-b:a", "128k") to "webm"),
        "flac" to (listOf("-c:a", "flac") to "flac"),
        "mp3" to (listOf("-c:a", "libmp3lame", "-b:a", "192k") to "mp3"),
        "alac" to (listOf("-c:a", "alac") to "m4a"),
        "vorbis" to (listOf("-c:a", "vorbis", "-strict", "-2", "-q:a", "6") to "ogg"),
    )

    /** A sine of [hz] lasting [ms], or [signal] when given (sample index → value). */
    fun pcm(rate: Int, ms: Int, hz: Double = 440.0, amplitude: Double = .5, signal: ((Int) -> Double)? = null): ShortArray {
        val n = rate * ms / 1000
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            val v = signal?.invoke(i) ?: (amplitude * sin(2 * PI * hz * i / rate))
            val s = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            out[i * 2] = s; out[i * 2 + 1] = s
        }
        return out
    }

    fun wav(name: String, rate: Int, samples: ShortArray): File {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) { bytes[i * 2] = samples[i].toByte(); bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte() }
        return File(dir, "$name.wav").also { CaptureSink.writeWav(it, bytes, rate, 2) }
    }

    /** [wav] encoded with [codec]; cached across runs by name. */
    fun encode(name: String, codec: String, wav: File): File {
        val (args, ext) = codecs.getValue(codec)
        val out = File(dir, "$name-$codec.$ext")
        if (out.exists() && out.lastModified() >= wav.lastModified()) return out
        val cmd = listOf(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", wav.path) + args + out.path
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0 && out.length() > 0) { "ffmpeg $codec failed: $log" }
        return out
    }

    fun tone(codec: String, hz: Int = 440, ms: Int = 1000, rate: Int = 44_100): File {
        val name = "tone$hz-${ms}ms-$rate"
        val wav = File(dir, "$name.wav").takeIf { it.exists() } ?: wav(name, rate, pcm(rate, ms, hz.toDouble()))
        return encode(name, codec, wav)
    }

    /** Silence with 1 kHz bursts of 5 ms every 500 ms (at 0.5 s, 1.0 s… before [ms]). */
    fun clicks(codec: String, ms: Int = 3000, rate: Int = 48_000): File {
        val name = "clicks-${ms}ms-$rate"
        val wav = File(dir, "$name.wav").takeIf { it.exists() } ?: wav(name, rate, pcm(rate, ms, signal = { i ->
            val t = i % (rate / 2)
            if (i >= rate / 2 && t < rate / 200) .8 * sin(2 * PI * 1000.0 * t / rate) else 0.0
        }))
        return encode(name, codec, wav)
    }

    /** Left channel of interleaved samples. */
    fun left(s: ShortArray) = ShortArray(s.size / 2) { s[it * 2] }

    /** Zero crossings → frequency over [from, to) samples. */
    fun frequency(x: ShortArray, rate: Int, from: Int, to: Int): Double {
        var crossings = 0
        for (i in from + 1 until to) if ((x[i - 1] < 0) != (x[i] < 0)) crossings++
        return crossings / 2.0 / ((to - from).toDouble() / rate)
    }

    fun firstLoud(x: ShortArray, threshold: Int = 1000, from: Int = 0): Int { for (i in from until x.size) if (abs(x[i].toInt()) > threshold) return i; return -1 }
    fun lastLoud(x: ShortArray, threshold: Int = 1000): Int { for (i in x.indices.reversed()) if (abs(x[i].toInt()) > threshold) return i; return -1 }
    fun maxStep(x: ShortArray, from: Int = 1, to: Int = x.size): Int { var m = 0; for (i in maxOf(1, from) until to) m = maxOf(m, abs(x[i] - x[i - 1])); return m }

    /** Longest run of near-silent samples inside [from, to). */
    fun longestQuiet(x: ShortArray, from: Int, to: Int, threshold: Int = 60): Int {
        var best = 0; var run = 0
        for (i in from until to) { if (abs(x[i].toInt()) <= threshold) { run++; best = maxOf(best, run) } else run = 0 }
        return best
    }

    /** Collects an engine's events from now on. */
    class Recorder(engine: AudioEngine) {
        val events: MutableList<AudioEvent> = Collections.synchronizedList(ArrayList())
        private val job: Job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { events += it }
        }
        fun await(timeoutMs: Long = 20_000, test: (AudioEvent) -> Boolean): AudioEvent {
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                synchronized(events) { events.firstOrNull(test) }?.let { return it }
                Thread.sleep(5)
            }
            error("event not seen in ${timeoutMs}ms; got ${synchronized(events) { events.toList() }}")
        }
        fun stop() = job.cancel()
    }
}
