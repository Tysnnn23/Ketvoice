package com.ketvoice.desktop.audio

import com.ketvoice.desktop.audio.AudioFixtures.Recorder
import com.ketvoice.desktop.audio.AudioFixtures.clicks
import com.ketvoice.desktop.audio.AudioFixtures.firstLoud
import com.ketvoice.desktop.audio.AudioFixtures.frequency
import com.ketvoice.desktop.audio.AudioFixtures.lastLoud
import com.ketvoice.desktop.audio.AudioFixtures.left
import com.ketvoice.desktop.audio.AudioFixtures.longestQuiet
import com.ketvoice.desktop.audio.AudioFixtures.maxStep
import com.ketvoice.desktop.audio.AudioFixtures.tone
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FfmpegAudioEngineTest {
    private val rate = FfmpegAudioEngine.RATE

    /** Plays [file] to its end into a capture; returns the left channel. */
    private fun capture(file: File, startMs: Long = 0, setup: (FfmpegAudioEngine, CaptureSink) -> Unit = { _, _ -> }): Pair<ShortArray, List<AudioEvent>> {
        val sink = CaptureSink()
        val engine = FfmpegAudioEngine(sink)
        val rec = Recorder(engine)
        try {
            setup(engine, sink)
            engine.load(AudioSource.LocalFile(file.path), startMs)
            rec.await { it is AudioEvent.Ended || it is AudioEvent.Error }
            assertTrue(rec.events.none { it is AudioEvent.Error }, "errors: ${rec.events}")
            return left(sink.samples()) to rec.events.toList()
        } finally { rec.stop(); engine.release() }
    }

    @Test fun decodesEveryCodecAtTheRightLengthAndPitch() {
        val report = StringBuilder()
        for (codec in AudioFixtures.codecs.keys) {
            val (x, events) = capture(tone(codec))
            val ms = (lastLoud(x) - firstLoud(x)) * 1000.0 / rate
            val hz = frequency(x, rate, rate / 10, rate * 9 / 10)
            val format = events.filterIsInstance<AudioEvent.Format>().single()
            report.append("$codec: ${"%.1f".format(ms)} ms, ${"%.1f".format(hz)} Hz, $format\n")
            assertTrue(abs(ms - 1000) <= 20, "$codec lasts $ms ms\n$report")
            assertTrue(abs(hz - 440) <= 440 * .02, "$codec at $hz Hz\n$report")
            assertEquals(codec, format.codec, report.toString())
            assertEquals(if (codec == "opus") 48_000 else 44_100, format.sampleRate, report.toString())
            assertEquals(2, format.channels, report.toString())
            assertEquals(codec == "flac" || codec == "alac", format.lossless, report.toString())
            if (format.lossless) assertEquals(16, format.bitDepth, report.toString()) else assertTrue(format.bitrate > 0, report.toString())
        }
        println(report)
    }

    @Test fun volumeChangesAndPauseDoNotClick() {
        val file = tone("flac", ms = 2000)
        val (x, _) = capture(file) { engine, sink ->
            sink.onWrite = { frames ->
                when (frames) {
                    in 24_000L until 24_480L -> engine.volume = .2f
                    in 48_000L until 48_480L -> engine.volume = 1f
                    in 60_000L until 60_480L -> {
                        engine.pause()
                        Thread { Thread.sleep(300); engine.play() }.start()
                    }
                }
            }
        }
        // A 440 Hz sine at half scale moves at most ~950 per sample; a cut in the gain would jump by thousands.
        val step = maxStep(x)
        assertTrue(step < 1_300, "largest step $step")
        // The pause faded down to silence, and the whole tone was still played after the resume.
        // (The capture records nothing while paused: the dip is the fade-out meeting the fade-in.)
        assertTrue(longestQuiet(x, 60_000, x.size, threshold = 1000) > 200, "the pause did not fade down")
        val loud = x.count { abs(it.toInt()) > 1000 }
        assertTrue(lastLoud(x) - firstLoud(x) > 2 * rate - rate / 50, "too short: ${x.size}, loud $loud")
    }

    @Test fun preloadedNextStartsWithNoGapAndNoOverlap() {
        for (codec in listOf("flac", "aac", "mp3")) {
            val a = tone(codec, 440, 1000, 48_000)
            val b = tone(codec, 660, 1000, 48_000)
            val sink = CaptureSink()
            val engine = FfmpegAudioEngine(sink)
            val rec = Recorder(engine)
            try {
                engine.load(AudioSource.LocalFile(a.path))
                engine.preloadNext(AudioSource.LocalFile(b.path))
                rec.await { it is AudioEvent.Ended }
                assertTrue(rec.events.any { it == AudioEvent.Advanced(AudioSource.LocalFile(b.path)) }, "$codec: ${rec.events}")
                val x = left(sink.samples())
                val start = firstLoud(x, 200); val end = lastLoud(x, 200)
                val total = (end - start) * 1000.0 / rate
                val gap = longestQuiet(x, start, end) * 1000.0 / rate
                println("$codec gapless: total ${"%.2f".format(total)} ms, longest quiet ${"%.2f".format(gap)} ms")
                assertTrue(gap <= 1.0, "$codec gap $gap ms")
                val tolerance = if (codec == "flac") 1.0 else 20.0
                assertTrue(abs(total - 2000) <= tolerance, "$codec total $total ms (overlap or gap)")
                // Pitch on each side of the joint.
                val hzA = frequency(x, rate, start + rate / 10, start + rate * 9 / 10)
                val hzB = frequency(x, rate, start + rate * 11 / 10, start + rate * 19 / 10)
                assertTrue(abs(hzA - 440) < 9 && abs(hzB - 660) < 13, "$codec: $hzA / $hzB")
            } finally { rec.stop(); engine.release() }
        }
    }

    @Test fun startsAndSeeksWhereAsked() {
        for (codec in listOf("flac", "aac", "mp3", "opus")) {
            val file = clicks(codec)
            val tolerance = if (codec == "flac") 1.0 else 20.0
            // Decoder alone: the burst at 1.5 s is 250 ms after a seek to 1.25 s.
            FfmpegDecoder(FileByteSource(file)).use { d ->
                d.seek(1250)
                val out = FloatArray(rate * 2 * 2)
                var n = 0
                while (n < rate) { val r = d.read(out, n * 2, rate * 2 - n); if (r <= 0) break; n += r }
                val x = ShortArray(n) { (out[it * 2] * 32767).toInt().toShort() }
                val at = firstLoud(x, 3000) * 1000.0 / rate
                assertTrue(abs(at - 250) <= tolerance, "$codec decoder seek: burst at $at ms")
            }
            // Engine: a start position, then a seek while playing.
            val (x, _) = capture(file, startMs = 1250)
            val at = firstLoud(x, 3000) * 1000.0 / rate
            assertTrue(abs(at - 250) <= tolerance, "$codec start: burst at $at ms")

            var seekAt = -1L
            val (y, _) = capture(file) { engine, sink ->
                sink.onWrite = { frames -> if (seekAt < 0 && frames >= rate / 5) { seekAt = frames; engine.seekTo(2_250) } }
            }
            // After the seek (and its 5 ms de-click fade-out), the next burst is 2.5 s, 250 ms later.
            val next = firstLoud(y, 3000, from = seekAt.toInt())
            val ms = (next - seekAt) * 1000.0 / rate - Gain.DECLICK_MS
            assertTrue(abs(ms - 250) <= tolerance + 10, "$codec seekTo: burst $ms ms after the seek")
        }
    }

    @Test fun positionFollowsWhatWasPlayed() {
        val sink = CaptureSink()
        val engine = FfmpegAudioEngine(sink)
        val rec = Recorder(engine)
        try {
            engine.load(AudioSource.LocalFile(tone("flac", ms = 2000).path), startMs = 500, play = false)
            assertEquals(500, engine.positionMs())
            rec.await { it is AudioEvent.Format }
            assertEquals(2000, engine.durationMs(), "duration")
            engine.seekTo(1500)
            assertEquals(1500, engine.positionMs())
            Thread.sleep(100)
            assertEquals(1500, engine.positionMs(), "paused: stays")
            engine.play()
            rec.await { it is AudioEvent.Ended }
            assertEquals(2000, engine.positionMs())
            assertTrue(!engine.isPlaying())
            assertEquals(rate / 2.0, sink.frames.toDouble(), 480.0, "only the last 500 ms were played")
        } finally { rec.stop(); engine.release() }
    }

    /** A sound card stand-in: plays 48 frames per ms of wall time and holds 170 ms. */
    private class PacedSink : PcmSink {
        override val realtime = true
        private var start = 0L
        @Volatile private var written = 0L
        private fun played() = (System.nanoTime() - start) * 48 / 1_000_000
        override fun open(sampleRate: Int, channels: Int) { start = System.nanoTime() }
        override fun write(pcm: ByteArray, length: Int) {
            // An idle device keeps its clock: what was not written in time is a gap, not a backlog.
            if (played() > written) start = System.nanoTime() - written * 1_000_000 / 48
            written += length / 4
            while (written - played() > 8_160) Thread.sleep(1)
        }
        override fun queuedFrames() = (written - played()).coerceIn(0, 8_160).toInt()
        override fun close() {}
    }

    @Test fun realTimePositionIsWhatWasHeard() {
        val engine = FfmpegAudioEngine(PacedSink())
        val rec = Recorder(engine)
        try {
            val t0 = System.nanoTime()
            engine.load(AudioSource.LocalFile(tone("flac", ms = 2000).path))
            val seen = ArrayList<Pair<Long, Long>>()
            while (System.nanoTime() - t0 < 900_000_000) { seen += (System.nanoTime() - t0) / 1_000_000 to engine.positionMs(); Thread.sleep(20) }
            assertTrue(seen.zipWithNext().all { (a, b) -> b.second >= a.second }, "went backwards: $seen")
            val (wall, pos) = seen.last()
            assertTrue(abs(wall - pos) < 120, "at $wall ms of wall time the position is $pos")
            engine.pause()
            Thread.sleep(400)
            val paused = engine.positionMs()
            Thread.sleep(200)
            assertEquals(paused, engine.positionMs(), "paused")
            assertTrue(paused - pos in 150..450, "the fade-out is heard after the pause: $pos -> $paused")
            engine.seekTo(1700); engine.play()
            rec.await(5_000) { it is AudioEvent.Ended }
            assertEquals(2000, engine.positionMs())
        } finally { rec.stop(); engine.release() }
    }

    @Test fun equalizerBoostsItsBand() {
        val file = tone("flac", hz = 62, ms = 1000, rate = 48_000)
        fun rms(gains: FloatArray?): Double {
            val (x, _) = capture(file) { e, _ -> e.setEqualizer(gains) }
            val mid = x.copyOfRange(rate / 4, rate * 3 / 4)
            return Math.sqrt(mid.sumOf { it.toDouble() * it } / mid.size)
        }
        val flat = rms(null)
        val boosted = rms(FloatArray(10) { if (it == 1) 4f else 0f })
        val db = 20 * Math.log10(boosted / flat)
        assertTrue(abs(db - 4) < .7, "62 Hz band at +4 dB gave $db dB")
    }
}
