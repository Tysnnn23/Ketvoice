package com.ketvoice.desktop.audio

import com.ketvoice.app.automix.MixBus
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.desktop.audio.AudioFixtures.Recorder
import com.ketvoice.desktop.audio.AudioFixtures.frequency
import com.ketvoice.desktop.audio.AudioFixtures.left
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ketmix on the desktop mixer, measured on what the engine really renders: two generated FLAC titles
 * (a 440 Hz and a 660 Hz tone) mixed by a plan, captured sample for sample. The gain curve of each title
 * is read with a Goertzel filter per 20 ms; a click is a jump of the second difference far above what the
 * tones themselves produce.
 */
class KetmixRenderTest {
    private val rate = FfmpegAudioEngine.RATE

    private fun title(name: String, hz: Double, ms: Int): File =
        AudioFixtures.encode(name, "flac", AudioFixtures.wav(name, rate, AudioFixtures.pcm(rate, ms, hz)))

    /** Amplitude of [hz] in x[from, from + n), under a Hann window (the other tone leaks below 0.003). */
    private fun level(x: ShortArray, hz: Double, from: Int, n: Int = rate / 25): Double {
        val w = 2 * PI * hz / rate
        var re = 0.0; var im = 0.0
        for (i in 0 until n) {
            val v = x.getOrElse(from + i) { 0 } / 32768.0 * (.5 - .5 * cos(2 * PI * i / n))
            re += v * cos(w * i); im += v * sin(w * i)
        }
        return 4 * sqrt(re * re + im * im) / n
    }

    /** Largest |x[i] - 2x[i-1] + x[i-2]|: about A·(2πf/fs)² for a clean tone, thousands for a click. */
    private fun worstBend(x: ShortArray, from: Int = 2, to: Int = x.size): Int {
        var m = 0
        for (i in maxOf(2, from) until to) m = maxOf(m, abs(x[i] - 2 * x[i - 1] + x[i - 2]))
        return m
    }

    private class Run(val x: ShortArray, val events: List<AudioEvent>, val speeds: List<Pair<Long, Float>>)

    private fun mix(a: File, b: File, plan: MixPlan, preRollMs: Long): Run {
        MixBus.clear()
        val speeds = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Float>>())
        lateinit var engine: FfmpegAudioEngine
        val sink = CaptureSink { frames -> if (frames % (rate / 10) < FfmpegAudioEngine.BLOCK) speeds += frames to engine.speed() }
        engine = FfmpegAudioEngine(sink)
        val rec = Recorder(engine)
        try {
            engine.load(AudioSource.LocalFile(a.path), play = false)
            engine.preloadNext(AudioSource.LocalFile(b.path))
            var set: Boolean? = null
            engine.mixIntoNext(plan, preRollMs) { set = it }
            engine.play()
            rec.await(60_000) { it is AudioEvent.Ended || it is AudioEvent.Error }
            assertEquals(true, set, "the mix was not set")
            assertTrue(rec.events.none { it is AudioEvent.Error }, "errors: ${rec.events}")
            assertTrue(rec.events.any { it == AudioEvent.Advanced(AudioSource.LocalFile(b.path)) }, "no takeover: ${rec.events}")
            return Run(left(sink.samples()), rec.events.toList(), speeds.toList())
        } finally { rec.stop(); engine.release() }
    }

    @Test fun crossfadeFollowsThePlanWithoutClicks() {
        val a = title("ketmix-a440", 440.0, 12_000)
        val b = title("ketmix-b660", 660.0, 12_000)
        val plan = MixPlan(MixKind.Crossfade, outMs = 6_000, inMs = 1_000, overlapMs = 4_000)
        val run = mix(a, b, plan, preRollMs = 400)
        val x = run.x
        val ms = { t: Int -> t * rate / 1000 }
        val curve = (0 until x.size / ms(20)).map { level(x, 440.0, it * ms(20)) to level(x, 660.0, it * ms(20)) }
        println("t s   440    660")
        for (i in curve.indices step 25) println("%5.2f %.3f  %.3f".format(i * .02, curve[i].first, curve[i].second))
        // Before the overlap only the outgoing title, at its full level; after it only the incoming one.
        for (i in 10 until 5_500 / 20) assertTrue(curve[i].first > .47 && curve[i].second < .01, "before the mix at ${i * 20} ms: ${curve[i]}")
        for (i in 10_400 / 20 until 16_000 / 20) assertTrue(curve[i].first < .01 && curve[i].second > .47, "after the mix at ${i * 20} ms: ${curve[i]}")
        // During it, one falls while the other rises, and the two cross once.
        val during = 6_000 / 20 until 10_000 / 20
        for (i in during.drop(1)) {
            assertTrue(curve[i].first <= curve[i - 1].first + .01, "440 rises again at ${i * 20} ms")
            assertTrue(curve[i].second >= curve[i - 1].second - .01, "660 falls again at ${i * 20} ms")
        }
        assertTrue(curve[6_200 / 20].second < .25 && curve[9_800 / 20].first < .25, "the overlap is not where the plan put it")
        assertTrue(during.count { curve[it].first > curve[it].second } in 40..160, "no single crossing in the middle")
        // 6 s of the first title, then the second from its 1st second: 17 s, give or take a block.
        val totalMs = AudioFixtures.lastLoud(x, 200) * 1000.0 / rate
        assertTrue(abs(totalMs - 17_000) < 40, "rendered $totalMs ms")
        val bend = worstBend(x, 0, AudioFixtures.lastLoud(x, 200))
        println("crossfade: worst second difference $bend (a clean 660 Hz tone at -6 dBFS: about 120)")
        assertTrue(bend < 400, "click: second difference $bend")
    }

    @Test fun tempoMatchedMixKeepsThePitchAndGlidesBack() {
        val a = title("ketmix-a440", 440.0, 12_000)
        val b = title("ketmix-b660-long", 660.0, 30_000)
        val plan = MixPlan(MixKind.Beatmatch, outMs = 6_000, inMs = 1_000, overlapMs = 4_000, rate = 1.04f, swapMs = 8_000, beatMs = 500f)
        val run = mix(a, b, plan, preRollMs = 1_500)
        val x = run.x
        val s = { t: Int -> t * rate / 1000 }
        // The incoming title plays 4 % fast with its pitch kept, then glides back to its own speed.
        val fastest = run.speeds.maxOf { it.second }
        assertEquals(1.04f, fastest, .0001f, "speeds: ${run.speeds}")
        val hz = frequency(x, rate, s(11_000), s(12_000))
        println("tempo mix: 660 Hz title at speed ${run.speeds.first { it.first >= s(11_500) }.second} sounds at $hz Hz")
        assertTrue(abs(hz - 660) < 5, "pitch moved: $hz Hz")
        val last = run.speeds.last { it.first < x.size - s(500) }.second
        assertEquals(1f, last, "did not glide back: ${run.speeds.takeLast(5)}")
        // The glide (16 steps of 0.0025 every 0.5 s, after 2 s) shortens the title: at 1.04 throughout from 4.5 s
        // of output, then stepping down, about 0.7 s of title more than of output are played.
        val totalMs = AudioFixtures.lastLoud(x, 200) * 1000.0 / rate
        assertTrue(totalMs in 33_000.0..34_200.0, "rendered $totalMs ms")
        for (t in 9_000..14_000 step 500) println("tempo mix %5.1f s: 440 %.3f  660 %.3f".format(t / 1000.0, level(x, 440.0, s(t)), level(x, 660.0, s(t))))
        assertTrue(level(x, 440.0, s(13_000)) < .01 && level(x, 660.0, s(13_000)) > .45, "after the mix")
        val bend = worstBend(x, 0, AudioFixtures.lastLoud(x, 200))
        println("tempo mix: worst second difference $bend")
        assertTrue(bend < 400, "click: second difference $bend")
    }
}
