package com.ketvoice.desktop.audio

import com.ketvoice.desktop.audio.AudioFixtures.Recorder
import com.ketvoice.desktop.audio.AudioFixtures.firstLoud
import com.ketvoice.desktop.audio.AudioFixtures.lastLoud
import com.ketvoice.desktop.audio.AudioFixtures.left
import com.ketvoice.desktop.audio.AudioFixtures.longestQuiet
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * « Lecture sans blanc » (true gapless) and « Mode exclusif » bit-perfect (Windows 2.0), through the real engine:
 * one continuous signal cut in two files comes back whole, sample for sample, and at a title's own rate nothing
 * changes a single bit between the file and the output.
 */
class GaplessExclusiveTest {

    /** 32-bit output like the exclusive sink's, keeping every open's format and every sample. */
    private class WideSink : PcmSink {
        val opened = ArrayList<Pair<Int, Int>>()
        private val bytes = java.io.ByteArrayOutputStream()
        override val realtime = false
        override val bytesPerSample = 4
        override fun open(sampleRate: Int, channels: Int) { opened += sampleRate to 16 }
        override fun openFormat(sampleRate: Int, channels: Int, bits: Int) { opened += sampleRate to bits }
        override fun write(pcm: ByteArray, length: Int) { synchronized(bytes) { bytes.write(pcm, 0, length) } }
        override fun queuedFrames() = 0
        override fun close() {}
        fun samples(): IntArray {
            val b = synchronized(bytes) { bytes.toByteArray() }
            return IntArray(b.size / 4) { (b[it * 4].toInt() and 0xFF) or ((b[it * 4 + 1].toInt() and 0xFF) shl 8) or
                ((b[it * 4 + 2].toInt() and 0xFF) shl 16) or (b[it * 4 + 3].toInt() shl 24) }
        }
    }

    /** A sine that never restarts, in [rate] Hz stereo 16-bit, cut at [cut] frames into two FLAC files. */
    private fun split(name: String, rate: Int, frames: Int, cut: Int): Triple<ShortArray, File, File> {
        val whole = AudioFixtures.pcm(rate, frames * 1000 / rate, signal = { i -> .5 * sin(2 * PI * 441.3 * i / rate) + .2 * sin(2 * PI * 97.0 * i / rate) })
        val a = AudioFixtures.encode("$name-a", "flac", AudioFixtures.wav("$name-a", rate, whole.copyOfRange(0, cut * 2)))
        val b = AudioFixtures.encode("$name-b", "flac", AudioFixtures.wav("$name-b", rate, whole.copyOfRange(cut * 2, whole.size)))
        return Triple(whole, a, b)
    }

    @Test fun trueGaplessGivesBackOneContinuousSignalSampleForSample() {
        val rate = FfmpegAudioEngine.RATE
        val (whole, a, b) = split("gapless48", rate, 96_000, cut = 48_123)
        val sink = CaptureSink()
        val engine = FfmpegAudioEngine(sink).apply { trueGapless = true }
        val rec = Recorder(engine)
        try {
            engine.load(AudioSource.LocalFile(a.path))
            engine.preloadNext(AudioSource.LocalFile(b.path))
            rec.await { it is AudioEvent.Ended }
            assertTrue(rec.events.any { it is AudioEvent.Advanced }, "${rec.events}")
            val got = sink.samples()
            val frames = whole.size / 2
            assertTrue(got.size / 2 >= frames, "${got.size / 2} frames for $frames")
            var worst = 0
            // From past the 5 ms fade-in at the start: every sample, the joint at frame 48 123 included.
            for (i in 1_000 * 2 until whole.size) worst = maxOf(worst, abs(got[i] - whole[i]))
            println("true gapless 48 kHz FLAC: worst difference $worst LSB over ${frames - 1000} frames")
            assertTrue(worst <= 1, "worst difference $worst LSB")
            assertTrue(got.size / 2 - frames <= 0, "${got.size / 2 - frames} frames after the end")
        } finally { rec.stop(); engine.release() }
    }

    @Test fun trueGaplessEndsAacOnItsStatedLength() {
        val a = AudioFixtures.tone("aac", 440, 1000, 48_000)
        val b = AudioFixtures.tone("aac", 660, 1000, 48_000)
        fun total(exact: Boolean): Double {
            val sink = CaptureSink()
            val engine = FfmpegAudioEngine(sink).apply { trueGapless = exact }
            val rec = Recorder(engine)
            try {
                engine.load(AudioSource.LocalFile(a.path))
                engine.preloadNext(AudioSource.LocalFile(b.path))
                rec.await { it is AudioEvent.Ended }
                val x = left(sink.samples())
                val start = firstLoud(x, 200); val end = lastLoud(x, 200)
                assertTrue(longestQuiet(x, start, end) * 1000.0 / 48_000 <= 1.0)
                return (end - start) * 1000.0 / 48_000
            } finally { rec.stop(); engine.release() }
        }
        val before = total(false)
        val after = total(true)
        println("AAC two titles of 1000 ms: ${"%.2f".format(before)} ms before, ${"%.2f".format(after)} ms with true gapless")
        assertTrue(abs(after - 2000) <= 1.0, "true gapless AAC total $after ms")
        assertTrue(abs(after - 2000) <= abs(before - 2000) + 0.05)
    }

    @Test fun nativeRateIsBitPerfectAndFollowsEachTitlesRate() {
        val (whole44, a, _) = split("bitperfect44", 44_100, 44_100, cut = 30_000)
        val (whole48, c, _) = split("bitperfect48", 48_000, 48_000, cut = 20_000)
        val sink = WideSink()
        val engine = FfmpegAudioEngine(sink)
        val rec = Recorder(engine)
        try {
            engine.setNativeRate(true)
            engine.load(AudioSource.LocalFile(a.path))
            engine.preloadNext(AudioSource.LocalFile(c.path))
            rec.await { it is AudioEvent.Ended }
            assertEquals(listOf(44_100 to 16, 48_000 to 16), sink.opened.takeLast(2), "opens: ${sink.opened}")
            val got = sink.samples()
            val first = 30_000 * 2
            // Past the fade-in, every sample of the 44.1 kHz title comes out as it is in the file, shifted into 32 bits.
            for (i in 1_000 * 2 until first) assertEquals(whole44[i].toInt() shl 16, got[i], "44.1 kHz sample $i")
            // The handover reopened the output at 48 kHz: the next title follows at once, bit for bit too.
            for (i in 0 until 20_000 * 2) assertEquals(whole48[i].toInt() shl 16, got[first + i], "48 kHz sample $i")
        } finally { rec.stop(); engine.release() }
    }
}
