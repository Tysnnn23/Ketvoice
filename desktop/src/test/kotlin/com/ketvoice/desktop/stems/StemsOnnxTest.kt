package com.ketvoice.app.stems

import com.ketvoice.app.automix.Curve
import com.ketvoice.app.automix.DeckAutomation
import com.ketvoice.desktop.audio.AudioFixtures
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.Deck
import com.ketvoice.desktop.audio.DesktopMixer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop's separation: the shared pipeline (`Separator`, `UvrSeparator`, `StemEngine`'s rules) with the networks
 * run by ONNX Runtime. Tiny stand-in models with the real ones' names and shapes (tools/stems/tiny_onnx.py) check the
 * plumbing against the same networks written in Kotlin; `KETVOICE_STEMS_ONNX=<folder>` runs the real models.
 */
class StemsOnnxTest {
    private fun resource(name: String) = File(javaClass.getResource("/stems/$name")!!.toURI())

    /** About a voice (a sung vibrato with harmonics, in the middle) over a bass and a hi-hat (on the sides). */
    private fun clip(seconds: Double, rate: Int = StemDsp.RATE): Pair<FloatArray, FloatArray> {
        val n = (seconds * rate).toInt()
        val l = FloatArray(n); val r = FloatArray(n)
        val noise = java.util.Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val f = 330.0 * (1 + .01 * sin(2 * PI * 5.5 * t))
            var voice = 0.0
            for (h in 1..6) voice += sin(2 * PI * f * h * t) / h
            val bass = .5 * sin(2 * PI * 55.0 * t)
            val hat = if ((t * 4) % 1.0 < .03) .2 * (noise.nextFloat() - .5) * exp(-((t * 4) % 1.0) * 60) else 0.0
            l[i] = (.18 * voice + .3 * bass + hat).toFloat(); r[i] = (.18 * voice + .25 * bass - hat).toFloat()
        }
        return l to r
    }

    /** What the tiny Spleeter stand-ins compute, in Kotlin. */
    private object KotlinTiny : StemNet {
        override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) {
            val bins = StemDsp.NET_BINS
            for (row in 0 until input.size / bins) {
                val o = row * bins
                var max = Float.NEGATIVE_INFINITY
                for (k in 0 until bins) max = maxOf(max, input[o + k])
                var sum = 0.0
                for (k in 0 until bins) sum += exp((input[o + k] - max).toDouble())
                for (k in 0 until bins) {
                    val s = exp((input[o + k] - max).toDouble()) / sum
                    vocals[o + k] = (input[o + k] * s * 300.0).toFloat()
                    accompaniment[o + k] = input[o + k] * .5f
                }
            }
        }
    }

    @Test fun spleeterThroughOnnxMatchesTheSharedPipeline() {
        val (l, r) = clip(8.0)
        val net = OnnxStemNet(resource("tiny-vocals.onnx"), resource("tiny-accompaniment.onnx"), 2)
        val onnx = net.use { Separator.mask(l, r, 0L, it)!! }
        val kotlin = Separator.mask(l, r, 0L, KotlinTiny)!!
        assertEquals(kotlin.frames, onnx.frames)
        var same = 0; var worst = 0f; var moved = 0
        for (t in 0 until onnx.frames) for (k in 0 until StemDsp.NET_BINS) {
            val a = onnx.value(t, k); val b = kotlin.value(t, k)
            if (a == b) same++
            worst = maxOf(worst, abs(a - b))
            if (b < .99f) moved++
        }
        val cells = onnx.frames * StemDsp.NET_BINS
        // The networks really move the mask (it is not all instrumental), and both runs find the same one.
        assertTrue(moved > cells / 100, "mask hardly moved: $moved of $cells")
        assertTrue(same > cells * 998L / 1000, "only $same of $cells cells agree")
        assertTrue(worst < .15f, "worst difference $worst")
        for (t in 0 until onnx.frames) assertTrue(abs(onnx.voiceShare!![t] - kotlin.voiceShare!![t]) <= 1)
    }

    @Test fun uvrThroughOnnxMatchesTheSharedPipeline() {
        val (l, r) = clip(4.0)
        val net = OnnxUvrNet(resource("tiny-uvr.onnx"), 2)
        assertEquals(768, net.dimF); assertEquals(64, net.dimT); assertEquals(1536, net.nFft)
        val swapped = object : UvrNet {
            override val nFft = 1536; override val dimF = 768; override val dimT = 64
            override fun run(input: FloatArray, output: FloatArray) {
                val half = input.size / 2
                for (i in 0 until half) { output[i] = input[half + i] * .5f; output[half + i] = input[i] * .5f }
            }
        }
        val (ol, or) = net.use { UvrSeparator(it).voice(l, r)!! }
        val (kl, kr) = UvrSeparator(swapped).voice(l, r)!!
        var worst = 0f
        for (i in ol.indices) worst = maxOf(worst, abs(ol[i] - kl[i]), abs(or[i] - kr[i]))
        assertTrue(worst < 1e-4f, "ONNX and Kotlin differ by $worst")
        // The stand-in swaps the channels and halves them: what comes out is half of the other channel, in the
        // range the network covers (the shared STFT, framing and overlap-add are exact there).
        var err = 0.0; var ref = 0.0
        for (i in 4_000 until ol.size - 4_000) { err += (ol[i] - r[i] * .5f).let { it * it }; ref += (r[i] * .5f).let { it * it } }
        assertTrue(err / ref < 1e-3, "reconstruction error ${err / ref}")
    }

    @Test fun pinsMatchTheWorkflowAndItsSources() {
        val pins = File("../tools/stems/expected-onnx.sha256").readLines().filter { it.isNotBlank() }
            .associate { it.substringAfter("  ") to it.substringBefore("  ") }
        val parts = StemModel.fast.parts + StemModel.hq.parts
        assertEquals(pins.keys, parts.map { it.name }.toSet())
        for (p in parts) assertEquals(pins[p.name], p.sha256)
        val workflow = File("../.github/workflows/publish-stem-models-onnx.yml").readText()
        assertTrue("TAG: ketmix-stems-onnx-v1" in workflow)
        for (p in parts) { val source = p.source!!; assertTrue(p.sha256 in workflow, p.name); assertTrue(source in workflow || source.substringAfterLast('/') in workflow, source) }
        // The phone's LiteRT tags are never touched.
        assertTrue("ketmix-stems-v1" !in workflow.replace("ketmix-stems-onnx-v1", "") && "ketmix-stems-v2" !in workflow)
    }

    @Test fun downloadKeepsOnlyThePinnedBytesAndFallsBackToTheSource() {
        val tiny = resource("tiny-uvr.onnx")
        val sha = MessageDigest.getInstance("SHA-256").digest(tiny.readBytes()).joinToString("") { "%02x".format(it) }
        val dir = kotlin.io.path.createTempDirectory("stem-models").toFile()
        try {
            val pack = ModelPack("https://release.example/", listOf(ModelPack.Part("m.onnx", tiny.length(), sha, "https://source.example/m.onnx")), "m")
            pack.init(dir)
            // The release answers with other bytes, the source with the right ones: only those are kept.
            val asked = mutableListOf<String>()
            pack.open = { url -> asked += url; if (url.startsWith("https://release")) ByteArray(tiny.length().toInt()).inputStream() else tiny.inputStream() }
            pack.download(); runBlocking { pack.join() }
            assertEquals(ModelPack.State.Ready, pack.state.value)
            assertEquals(listOf("https://release.example/m.onnx", "https://source.example/m.onnx"), asked)
            assertEquals(sha, MessageDigest.getInstance("SHA-256").digest(File(dir, "m/m.onnx").readBytes()).joinToString("") { "%02x".format(it) })
            // Nowhere has them: nothing is kept.
            val other = ModelPack("https://release.example/", listOf(ModelPack.Part("n.onnx", tiny.length(), sha, "https://source.example/n.onnx")), "n")
            other.init(dir); other.open = { null }
            other.download(); runBlocking { other.join() }
            assertTrue(other.state.value is ModelPack.State.Failed)
            assertTrue(File(dir, "n").listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    /** The mixer's voice stage: level 1 is the record (one window late), level 0 its instrumental (here: nothing). */
    @Test fun voiceStageInTheMixerFollowsTheLevel() {
        val rate = 48_000
        val wav = AudioFixtures.wav("voice-stage-tone", rate, AudioFixtures.pcm(rate, 3000, 440.0, .4))
        fun render(level: Float): FloatArray {
            val mixer = DesktopMixer(rate)
            // A whole separation that calls every cell voice.
            val frames = 3 * StemDsp.RATE / StemDsp.HOP + 4
            val allVoice = StemMask(0L, frames, ByteArray(frames * StemDsp.NET_BINS / 2), ByteArray(frames), ByteArray(frames))
            mixer.voiceMask = { allVoice }
            mixer.current = Deck(AudioSource.LocalFile(wav.path))
            VoiceLevel.level = level
            val out = FloatArray(rate * 2 * 2)
            val block = DesktopMixer.Block()
            var at = 0
            val buf = FloatArray(480 * 2)
            while (at < rate * 2) {
                mixer.render(buf, 480, block) { d, n -> d.awaitData(n, 2_000); true }
                System.arraycopy(buf, 0, out, at * 2, 960); at += 480
            }
            assertEquals(StemDsp.N_FFT, mixer.voiceDelayFrames)
            mixer.current?.close()
            return out
        }
        try {
            val whole = render(1f)
            val none = render(0f)
            fun rms(x: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { (x[2 * it] * x[2 * it]).toDouble() } / (to - from))
            val from = rate / 2; val to = rate * 3 / 2
            assertEquals(.4 / sqrt(2.0), rms(whole, from, to), .02)
            assertTrue(rms(none, from, to) < .004, "voice left: ${rms(none, from, to)}")
            // One window late: the first 4096 frames are the stage filling up.
            assertTrue(rms(whole, 0, StemDsp.N_FFT - StemDsp.HOP) < .001)
        } finally { VoiceLevel.level = 1f }
    }

    /** A transition's voice move takes the separated instrumental of the deck's own part ([Lane.stem]). */
    @Test fun laneCutsTheVoiceWithItsStem() {
        val rate = 48_000
        val wav = AudioFixtures.wav("lane-stem-tone", rate, AudioFixtures.pcm(rate, 2000, 300.0, .3))
        fun play(withStem: Boolean): FloatArray {
            val deck = Deck(AudioSource.LocalFile(wav.path))
            deck.awaitData(rate, 3_000)
            deck.lane.automation = DeckAutomation(Curve.flat(1f), Curve.flat(0f), vocalCut = Curve(longArrayOf(0, 1), floatArrayOf(1f, 1f)))
            if (withStem) deck.lane.stem = StemAudio(0L, rate, ShortArray(rate * 2 * 2), 0, rate * 2)
            val out = FloatArray(rate)
            var at = 0
            while (at < rate / 2) at += deck.lane.read(out, at, rate / 2 - at).also { if (it == 0) deck.awaitData(1024, 1_000) }
            deck.close()
            return out
        }
        val band = play(false); val separated = play(true)
        val peak = { x: FloatArray -> x.drop(20_000).maxOf { abs(it) } }
        // Without the separation the band cut only steps the voice down; with it the instrumental (silence here) plays.
        assertTrue(peak(band) > .02f)
        assertEquals(0f, peak(separated), 1e-4f)
    }

    /** Opt-in: `KETVOICE_STEMS_ONNX=<folder with the pinned ONNX files>` runs the real Spleeter and UVR on a generated clip. */
    @Test fun realModelsSeparateAGeneratedClip() {
        val dir = System.getenv("KETVOICE_STEMS_ONNX")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val (l, r) = clip(12.0)
        fun bandEnergy(x: FloatArray, hz: Double): Double {
            // Goertzel at one frequency, over the middle of the clip.
            val w = 2 * PI * hz / StemDsp.RATE; var s1 = 0.0; var s2 = 0.0
            for (i in x.size / 4 until x.size * 3 / 4) { val s = x[i] + 2 * Math.cos(w) * s1 - s2; s2 = s1; s1 = s }
            return s1 * s1 + s2 * s2 - 2 * Math.cos(w) * s1 * s2
        }
        fun check(name: String, mask: StemMask) {
            val music = Separator.stem(l, r, mask, 0, vocals = false); val voice = Separator.stem(l, r, mask, 0, vocals = true)
            val bassKept = bandEnergy(music.left, 55.0) / bandEnergy(l, 55.0)
            val voiceOut = bandEnergy(voice.left, 330.0) / bandEnergy(l, 330.0)
            println("$name: bass kept in the instrumental ${"%.2f".format(bassKept)}, voice fundamental in the voice ${"%.2f".format(voiceOut)}")
            // A generated « voice » is a steady tone to these networks (they hear it as an instrument): only the
            // instrumental side is asserted; what they do with real singing is not checked here.
            assertTrue(bassKept > .5, "$name lost the bass: $bassKept")
            assertTrue(music.left.all { it.isFinite() } && voice.left.all { it.isFinite() })
        }
        val files = (StemModel.fast.parts + StemModel.hq.parts).associateWith { File(dir, it.name) }
        for ((part, f) in files) if (f.isFile) {
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(part.sha256, sha, part.name)
        }
        val v = files.getValue(StemModel.fast.parts[0]); val a = files.getValue(StemModel.fast.parts[1])
        if (v.isFile && a.isFile) OnnxStemNet(v, a, 4).use { net ->
            val t0 = System.nanoTime(); val mask = Separator.mask(l, r, 0L, net)!!
            println("Spleeter: 12 s in ${(System.nanoTime() - t0) / 1_000_000} ms"); check("Spleeter", mask)
        }
        val u = files.getValue(StemModel.hq.parts[0])
        if (u.isFile) OnnxUvrNet(u, 4).use { net ->
            assertEquals(3072, net.dimF); assertEquals(256, net.dimT)
            val t0 = System.nanoTime(); val (vl, vr) = UvrSeparator(net).voice(l, r)!!
            println("UVR: 12 s in ${(System.nanoTime() - t0) / 1_000_000} ms"); check("UVR", Separator.maskFromVoice(l, r, vl, vr, 0L)!!)
        }
    }
}
