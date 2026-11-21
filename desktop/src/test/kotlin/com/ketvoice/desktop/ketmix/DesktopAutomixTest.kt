package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.MixBus
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.audio.AudioFixtures
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.CaptureSink
import com.ketvoice.desktop.audio.FfmpegAudioEngine
import com.ketvoice.desktop.audio.PcmSink
import com.ketvoice.desktop.audio.TimeStretch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAutomixTest {
    private val rate = FfmpegAudioEngine.RATE

    /** A groove: a tone with a decaying kick every beat at [bpm], so the shared analysis finds a tempo. */
    private fun groove(name: String, hz: Double, bpm: Double, ms: Int): File {
        val beat = (rate * 60 / bpm).toInt()
        val pcm = AudioFixtures.pcm(rate, ms, signal = { i ->
            val t = i % beat
            .25 * sin(2 * PI * hz * i / rate) + .45 * exp(-t / (rate * .04)) * sin(2 * PI * 55.0 * t / rate)
        })
        return AudioFixtures.encode(name, "flac", AudioFixtures.wav(name, rate, pcm))
    }

    /** A capture that plays at [speed]× real time, so the controller's ticks see the titles move. */
    private class PacedSink(private val speed: Int, val capture: CaptureSink = CaptureSink()) : PcmSink by capture {
        private var start = 0L
        private var frames = 0L
        override fun open(sampleRate: Int, channels: Int) { capture.open(sampleRate, channels); start = System.nanoTime() }
        override fun write(pcm: ByteArray, length: Int) {
            capture.write(pcm, length); frames += length / 4
            val due = start + frames * 1_000_000_000L / (48_000L * speed)
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        DesktopAutomix.resetForTest(); Player.resetForTest(); scope.cancel()
        AppSettings.automix.value = false
    }

    @Test fun endsOfTitlesAreMixedAndSkipsBlend() {
        System.setProperty("ketvoice.dataDir", File("build/ketmix-data").absolutePath)
        MixBus.clear()
        val a = groove("groove-a", 440.0, 120.0, 40_000)
        val b = groove("groove-b", 660.0, 120.0, 40_000)
        val c = groove("groove-c", 880.0, 120.0, 40_000)
        val sink = PacedSink(10)
        val engine = FfmpegAudioEngine(sink)
        AppSettings.automix.value = true
        AppSettings.ketmixAnalysis.value = 0
        AppSettings.ketmixOnSkip.value = true
        val files = mapOf("a" to a, "b" to b, "c" to c)
        try {
            Player.attach(engine, { t -> AudioSource.LocalFile(files.getValue(t.id).path) }, scope)
            DesktopAutomix.attach(engine, scope)
            val tracks = listOf("a", "b", "c").map { Track(it, "Titre $it", "Artiste $it", "", durationMs = 40_000) }
            Player.playQueue(tracks, 0)
            // The end of « a » mixes into « b »: the engine reports a mix, the badge follows, the queue moves on.
            var sawMix = false
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && Player.state.value.index != 1) {
                if (DesktopAutomix.ui.value.phase == DesktopAutomix.Phase.Mixing) sawMix = true
                Thread.sleep(20)
            }
            assertEquals(1, Player.state.value.index, "the queue did not move on")
            assertTrue(sawMix || engine.mixStatus.value.phase != FfmpegAudioEngine.MixPhase.Idle, "no mix was played")
            assertTrue(AnalysisDepth.Light.outroMs > 0)
            // Both tones at once somewhere: the titles overlapped instead of following each other.
            Thread.sleep(400)
            val x = AudioFixtures.left(sink.capture.samples())
            val both = (0 until x.size / 4_800).count { w -> tone(x, 440.0, w * 4_800) > .05 && tone(x, 660.0, w * 4_800) > .05 }
            assertTrue(both >= 5, "no overlap ($both windows of 100 ms with both titles)")
            // « Suivant » now blends into « c » instead of cutting (the paced sink plays 10 s of title a second).
            val before = sink.capture.frames
            Player.next()
            assertEquals(1, Player.state.value.index, "the skip cut instead of mixing")
            val skipUntil = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < skipUntil && Player.state.value.index != 2) Thread.sleep(20)
            assertEquals(2, Player.state.value.index)
            val y = AudioFixtures.left(sink.capture.samples())
            val blended = (before.toInt() / 4_800 until y.size / 4_800).count { w -> tone(y, 660.0, w * 4_800) > .03 && tone(y, 880.0, w * 4_800) > .03 }
            assertTrue(blended >= 3, "the skip did not blend ($blended windows)")
        } finally { engine.release() }
    }

    private fun tone(x: ShortArray, hz: Double, from: Int, n: Int = 4_800): Double {
        var re = 0.0; var im = 0.0
        val w = 2 * PI * hz / rate
        for (i in 0 until n) {
            val v = x.getOrElse(from + i) { 0 } / 32768.0 * (.5 - .5 * kotlin.math.cos(2 * PI * i / n))
            re += v * kotlin.math.cos(w * i); im += v * sin(w * i)
        }
        return 4 * kotlin.math.sqrt(re * re + im * im) / n
    }

    @Test fun stretchAtSpeedOneIsTheInputSampleForSample() {
        val input = FloatArray(48_000 * 2) { (sin(it * .013) * .4 + sin(it * .0021) * .3).toFloat() }
        var at = 0
        val s = TimeStretch({ out, off, n -> val k = minOf(n, 48_000 - at); System.arraycopy(input, at * 2, out, off * 2, k * 2); at += k; k },
            { at >= 48_000 }, 0)
        val out = FloatArray(48_000 * 2)
        var got = 0
        while (got < 40_000) { val n = s.process(out, got, minOf(480, 48_000 - got)); if (n == 0) break; got += n }
        assertTrue(got >= 40_000)
        for (i in 0 until got * 2) assertEquals(input[i], out[i], 1e-6f, "sample $i")
        assertEquals(got.toLong(), s.titleFrame)
    }

    @Test fun stretchFasterGivesFewerFramesAtThePitch() {
        val hz = 500.0
        val total = 96_000
        val input = FloatArray(total * 2) { (sin(2 * PI * hz * (it / 2) / 48_000) * .5).toFloat() }
        var at = 0
        val s = TimeStretch({ out, off, n -> val k = minOf(n, total - at); System.arraycopy(input, at * 2, out, off * 2, k * 2); at += k; k },
            { at >= total }, 0)
        s.speed = 1.05f
        val out = FloatArray(total * 2)
        var got = 0
        while (true) { val n = s.process(out, got, 480.coerceAtMost(total - got)); if (n == 0) break; got += n }
        assertTrue(abs(got - total / 1.05) < 2_000, "gave $got frames")
        var crossings = 0
        for (i in 4_801 until got - 4_800) if ((out[(i - 1) * 2] < 0) != (out[i * 2] < 0)) crossings++
        val measured = crossings / 2.0 / ((got - 9_601) / 48_000.0)
        assertTrue(abs(measured - hz) < 3, "pitch $measured Hz")
    }
}
