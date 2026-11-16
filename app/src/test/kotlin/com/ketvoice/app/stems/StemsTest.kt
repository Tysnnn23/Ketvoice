package com.ketvoice.app.stems

import com.ketvoice.app.automix.Curve
import com.ketvoice.app.automix.DeckAutomation
import com.ketvoice.app.automix.DeckDsp
import com.ketvoice.app.automix.PcmCapture
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class StemsTest {
    private fun sine(hz: Double, rate: Int, n: Int, amp: Float = .5f, phase: Double = 0.0) =
        FloatArray(n) { (amp * sin(2 * PI * hz * it / rate + phase)).toFloat() }

    private fun rms(a: FloatArray, from: Int, until: Int): Double {
        var s = 0.0; for (i in from until until) s += a[i].toDouble() * a[i]; return sqrt(s / (until - from))
    }

    @Test fun fftMatchesTheDefinition() {
        val n = 64
        val fft = Fft(n)
        val x = FloatArray(n) { sin(it * .7).toFloat() + .3f * cos(it * 2.1).toFloat() }
        val re = x.copyOf(); val im = FloatArray(n)
        fft.transform(re, im)
        for (k in 0 until n) {
            var r = 0.0; var i = 0.0
            for (t in 0 until n) { r += x[t] * cos(2 * PI * k * t / n); i -= x[t] * sin(2 * PI * k * t / n) }
            assertEquals(r, re[k].toDouble(), 1e-3); assertEquals(i, im[k].toDouble(), 1e-3)
        }
        fft.transform(re, im, inverse = true)
        for (t in 0 until n) assertEquals(x[t], re[t] / n, 1e-4f)
    }

    @Test fun stereoStftOfEachChannelIsItsOwnSpectrum() {
        val left = sine(1_000.0, 44_100, 8192); val right = sine(3_000.0, 44_100, 8192, .25f)
        val stft = StereoStft()
        val lr = FloatArray(StemDsp.BINS); val li = FloatArray(StemDsp.BINS); val rr = FloatArray(StemDsp.BINS); val ri = FloatArray(StemDsp.BINS)
        stft.forward(left, right, 1000, lr, li, rr, ri)
        fun peak(re: FloatArray, im: FloatArray) = (0 until StemDsp.BINS).maxByOrNull { re[it] * re[it] + im[it] * im[it] }!!
        assertEquals(Math.round(1_000.0 * 4096 / 44_100).toInt(), peak(lr, li))
        assertEquals(Math.round(3_000.0 * 4096 / 44_100).toInt(), peak(rr, ri))
    }

    /** A mask that keeps everything gives the signal back, sample for sample, inside the covered part. */
    @Test fun fullMaskReconstructsTheSignal() {
        val n = 44_100 * 3
        val left = FloatArray(n) { (.4 * sin(2 * PI * 440 * it / 44_100) + .2 * sin(2 * PI * 9_000 * it / 44_100)).toFloat() }
        val right = sine(660.0, 44_100, n, .3f)
        val frames = StemDsp.frames(n)
        val full = StemMask(0L, frames, ByteArray(frames * StemDsp.NET_BINS / 2) { 0xFF.toByte() })
        val result = Separator.instrumental(left, right, full, 0)
        assertTrue(result.until - result.from > n / 2)
        var worst = 0f
        for (i in result.from until result.until) worst = maxOf(worst, abs(result.left[i] - left[i]), abs(result.right[i] - right[i]))
        assertTrue("worst error $worst", worst < 1e-4f)
    }

    /** With a network that hears as much voice as music, half of each cell stays below 11 kHz, all of it above. */
    @Test fun maskFollowsTheNetworkAndKeepsTheHighs() {
        val n = 44_100 * 4
        val low = sine(500.0, 44_100, n, .4f); val high = sine(15_000.0, 44_100, n, .1f)
        val mix = FloatArray(n) { low[it] + high[it] }
        val equal = object : StemNet {
            override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) {
                input.copyInto(vocals); input.copyInto(accompaniment)
            }
        }
        val mask = Separator.mask(mix, mix, 0L, equal)!!
        assertEquals(StemDsp.frames(n), mask.frames)
        val r = Separator.instrumental(mix, mix, mask, 0)
        val kept = StemMask.quantize(.5f).let { (it / 15f) * (it / 15f) }
        // The instrumental of the low tone is the kept share of it; the high tone passes untouched.
        val bandLow = FloatArray(n) { low[it] * kept + high[it] }
        var err = 0.0; var ref = 0.0
        for (i in r.from until r.until) { err += (r.left[i] - bandLow[i]).toDouble().let { it * it }; ref += bandLow[i].toDouble() * bandLow[i] }
        assertTrue("error ${sqrt(err / ref)}", sqrt(err / ref) < .02)
    }

    @Test fun maskQuantisationIsFineNearSilence() {
        assertEquals(0, StemMask.quantize(0f))
        assertEquals(15, StemMask.quantize(1f))
        val packed = ByteArray(StemDsp.NET_BINS / 2)
        val m = StemMask(0L, 1, packed)
        assertEquals(0f, m.value(0, 5), 0f)
        assertTrue((1f / 15) * (1f / 15) < .005f)
    }

    @Test fun resamplerKeepsATone() {
        val input = sine(1_000.0, 48_000, 48_000)
        val out = Resampler.resample(input, 48_000, 44_100)
        assertEquals(44_100, out.size)
        val expected = sine(1_000.0, 44_100, 44_100)
        var worst = 0f
        for (i in 200 until out.size - 200) worst = maxOf(worst, abs(out[i] - expected[i]))
        assertTrue("worst $worst", worst < 2e-3f)
        val back = Resampler.resample(out, 44_100, 48_000)
        for (i in 400 until 47_000) assertEquals(input[i], back[i], 4e-3f)
    }

    @Test fun storeKeepsTheSeparation() {
        val frames = 37
        val packed = ByteArray(frames * StemDsp.NET_BINS / 2) { (it * 31).toByte() }
        val mask = StemMask(123_456L, frames, packed)
        val back = StemStore.decode(StemStore.encode(mask))
        assertEquals(123_456L, back.startUs); assertEquals(frames, back.frames)
        assertArrayEquals(packed, back.packed)
        val dir = kotlin.io.path.createTempDirectory("stems").toFile()
        try {
            StemStore.init(dir)
            assertFalse(StemStore.has("t", 200_000, true))
            StemStore.save("t", 200_000, true, mask)
            assertTrue(StemStore.has("t", 200_000, true))
            assertFalse(StemStore.has("t", 200_000, false))
            assertEquals(1, StemStore.usage().first)
            StemStore.clear()
            assertEquals(0, StemStore.usage().first)
        } finally { dir.deleteRecursively() }
    }

    @Test fun engineComputesOnceThenReuses() {
        val dir = kotlin.io.path.createTempDirectory("stems").toFile()
        var runs = 0
        StemEngine.testNet = object : StemNet {
            override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) {
                runs++; vocals.fill(0f); input.copyInto(accompaniment)
            }
        }
        try {
            StemStore.init(dir)
            val rate = 48_000
            val frames = rate * 6
            val pcm = ShortArray(frames * 2) { (sin(2 * PI * 300 * (it / 2) / rate) * 12_000).toInt().toShort() }
            assertNull(StemEngine.instrumental("x", 180_000, true, pcm, rate, 0L, compute = false))
            val first = StemEngine.instrumental("x", 180_000, true, pcm, rate, 0L, compute = true)!!
            val afterFirst = runs
            assertTrue(afterFirst > 0)
            // The same part read again a little later: the stored separation is lined up with it, the network does not run.
            val shift = 240
            val later = pcm.copyOfRange(shift * 2, pcm.size)
            val second = StemEngine.instrumental("x", 180_000, true, later, rate, shift * 1_000_000L / rate, compute = false)!!
            assertEquals(afterFirst, runs)
            // An all-music network keeps the music: the instrumental is the sound itself.
            val f = first.from + 2_000
            assertEquals(pcm[2 * f].toFloat(), first.pcm[2 * f].toFloat(), 400f)
            val g = second.frameAt(f * 1_000_000L / rate)
            assertEquals(first.pcm[2 * f].toFloat(), second.pcm[2 * g].toFloat(), 400f)
        } finally { StemEngine.testNet = null; dir.deleteRecursively() }
    }

    @Test fun audioCoversOnlyWhatIsTrusted() {
        val a = StemAudio(10_000_000L, 48_000, ShortArray(48_000 * 2 * 10), 4_800, 48_000 * 9)
        assertEquals(-1, a.frameAt(9_000_000L))
        assertEquals(-1, a.frameAt(10_050_000L))
        assertEquals(48_000, a.frameAt(11_000_000L))
        assertTrue(a.covers(11_000_000L, 18_000_000L))
        assertFalse(a.covers(11_000_000L, 19_500_000L))
    }

    /** On a deck, the voice amount fades from the title to its instrumental. */
    @Test fun deckFadesToTheInstrumental() {
        val rate = 48_000
        val frames = rate
        val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) { input.putShort(10_000); input.putShort(10_000) }
        input.flip()
        val silent = StemAudio(0L, rate, ShortArray(frames * 4), 0, frames * 2)
        fun play(voice: Float): ShortArray {
            val dsp = DeckDsp(rate, 2)
            val auto = DeckAutomation(Curve.flat(1f), Curve.flat(0f), vocalCut = Curve(longArrayOf(0, 1), floatArrayOf(voice, voice)))
            val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            dsp.process(input.duplicate().order(ByteOrder.LITTLE_ENDIAN), out, 0L, auto, silent)
            out.flip(); return ShortArray(out.remaining() / 2) { out.short }
        }
        assertEquals(0, play(1f)[2000].toInt())
        assertEquals(5_000f, play(.5f)[2000].toFloat(), 30f)
    }

    @Test fun captureRecordsFromWhereItWasAsked() {
        val c = PcmCapture(1_000_000L, 7_000_000L)
        val rate = 48_000
        fun buf(frames: Int, value: Short) = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames * 2) { putShort(value) }; flip()
        }
        c.pcm(buf(4800, 1), 0L, rate, 2) // before the part: ignored
        var at = 1_000_000L
        while (!c.done) { c.pcm(buf(4800, 7), at, rate, 2); at += 100_000L }
        assertEquals(1_000_000L, c.startUs)
        val pcm = c.pcm()!!
        assertEquals(7.toShort(), pcm[0])
        assertTrue(pcm.size >= rate * 2 * 6)
    }

    /**
     * The whole separation against sherpa-onnx's Spleeter on a real song, with the network's
     * outputs computed by ONNX Runtime (tools/stems/reference.py). Local only: the song and the
     * model outputs are not part of the repository.
     */
    @Test fun matchesTheReferenceImplementation() {
        val dir = System.getenv("KETVOICE_STEM_REF")?.let(::File)
        assumeTrue(dir != null && File(dir, "mix.f32").isFile)
        fun floats(name: String): FloatArray {
            val bytes = File(dir, name).readBytes()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(b.remaining()).also { b.get(it) }
        }
        val mix = floats("mix.f32")
        val n = mix.size / 2
        val left = FloatArray(n) { mix[2 * it] }; val right = FloatArray(n) { mix[2 * it + 1] }
        val inputs = floats("input.f32"); val refVocals = floats("vocals.f32"); val refAccompaniment = floats("accompaniment.f32")
        val block = 2 * StemDsp.NET_FRAMES * StemDsp.NET_BINS
        var split = 0
        var worstInput = 0.0
        val replay = object : StemNet {
            override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) {
                val base = split * block
                var e = 0.0; var r = 0.0
                for (i in 0 until block) { val d = input[i] - inputs[base + i]; e += d * d; r += inputs[base + i].toDouble() * inputs[base + i] }
                worstInput = maxOf(worstInput, sqrt(e / r))
                refVocals.copyInto(vocals, 0, base, base + block)
                refAccompaniment.copyInto(accompaniment, 0, base, base + block)
                split++
            }
        }
        val started = System.nanoTime()
        val mask = Separator.mask(left, right, 0L, replay)!!
        val result = Separator.instrumental(left, right, mask, 0)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("network input differs: $worstInput", worstInput < 1e-3)
        println("sung passages (s): " + VoiceActivity.passages(mask)!!.joinToString { "%.1f-%.1f".format(it.first / 1000.0, it.last / 1000.0) })
        val voice = Separator.stem(left, right, mask, 0, vocals = true)
        var voiceErr = 0.0; var voiceRef = 0.0
        val sherpaVocals = floats("sherpa-vocals.f32")
        var err = 0.0; var ref = 0.0
        for (i in maxOf(result.from, 8192) until minOf(result.until, sherpaVocals.size / 2 - 8192)) for (c in 0..1) {
            val expected = mix[2 * i + c] - sherpaVocals[2 * i + c]
            val got = if (c == 0) result.left[i] else result.right[i]
            err += (got - expected).toDouble().let { it * it }; ref += expected.toDouble() * expected
        }
        for (i in maxOf(voice.from, 8192) until minOf(voice.until, sherpaVocals.size / 2 - 8192)) {
            val expected = sherpaVocals[2 * i]
            voiceErr += (voice.left[i] - expected).toDouble().let { it * it }; voiceRef += expected.toDouble() * expected
        }
        println("voice alone vs sherpa-onnx: %.1f dB".format(10 * log10(voiceRef / voiceErr)))
        val snr = 10 * log10(ref / err)
        println("separation of ${n / 44_100} s in $ms ms (without the network), instrumental vs sherpa-onnx: %.1f dB".format(snr))
        assertTrue("instrumental too far from the reference: $snr dB", snr > 18)
    }
}

class StemModelPinTest {
    /** The app checks the downloaded model against the digests the publishing workflow verifies. */
    @org.junit.Test fun digestsMatchThePublishedModel() {
        val expected = File("../tools/stems/expected.sha256").takeIf { it.isFile } ?: File("tools/stems/expected.sha256")
        val pins = expected.readLines().filter { it.isNotBlank() }.associate { it.substringAfter("  ") to it.substringBefore("  ") }
        assertEquals(pins.keys, StemModel.parts.map { it.name }.toSet())
        for (part in StemModel.parts) assertEquals(pins[part.name], part.sha256)
    }
}

class VoiceActivityTest {
    private fun mask(frames: Int, sung: (Int) -> Boolean, startUs: Long = 0L): StemMask {
        val share = ByteArray(frames) { if (sung(it)) 200.toByte() else 20.toByte() }
        val level = ByteArray(frames) { if (sung(it)) 180.toByte() else 90.toByte() }
        return StemMask(startUs, frames, ByteArray(frames * StemDsp.NET_BINS / 2), share, level)
    }
    private fun frame(ms: Long) = (ms * StemDsp.RATE / 1000 / StemDsp.HOP).toInt()

    @Test fun findsWhereTheVoiceStartsAndStops() {
        val m = mask(frame(40_000), { it in frame(12_000) until frame(30_000) })
        assertEquals(12_000f, VoiceActivity.startMs(m)!!.toFloat(), 60f)
        assertEquals(30_000f, VoiceActivity.endMs(m)!!.toFloat(), 60f)
    }

    @Test fun shortBurstsAreNotSingingAndBreathsDoNotEndAPassage() {
        // A 200 ms leak at 2 s, then a voice from 10 s to 20 s with a half-second breath in it.
        val m = mask(frame(30_000), { it in frame(2_000) until frame(2_200) ||
            (it in frame(10_000) until frame(20_000) && it !in frame(14_000) until frame(14_500)) })
        val passages = VoiceActivity.passages(m)!!
        assertEquals(1, passages.size)
        assertEquals(10_000f, passages[0].first.toFloat(), 60f)
        assertEquals(20_000f, passages[0].last.toFloat(), 60f)
    }

    @Test fun anInstrumentalPartSingsNowhere() {
        val m = mask(frame(40_000), { false }, startUs = 150_000_000L)
        // Nobody sings in this ending: the voice was over before it; in an intro, it starts after it.
        assertEquals(150_000L, VoiceActivity.endMs(m))
        assertTrue(VoiceActivity.startMs(m)!! >= 150_000L + 39_000L)
    }

    @Test fun separationsOfTheFirstVersionCarryNoVoiceActivity() {
        val old = StemMask(0L, 10, ByteArray(10 * StemDsp.NET_BINS / 2))
        assertNull(VoiceActivity.passages(old))
        val back = StemStore.decode(StemStore.encode(old))
        assertNull(back.voiceShare)
        val withVoice = StemStore.decode(StemStore.encode(mask(10, { it > 4 })))
        assertEquals(200.toByte(), withVoice.voiceShare!![7])
    }

    @Test fun storeFindsAPartFiledUnderANeighbouringSecond() {
        val dir = kotlin.io.path.createTempDirectory("stems").toFile()
        try {
            StemStore.init(dir)
            StemStore.save("t", 200_950, false, mask(10, { true }))
            assertTrue(StemStore.has("t", 201_020, false))
            assertFalse(StemStore.has("t", 203_000, false))
        } finally { dir.deleteRecursively() }
    }

    /** The voice alone and the instrumental add up to the title, below the network's top frequency. */
    @Test fun voiceAndInstrumentalAddUp() {
        val n = 44_100 * 3
        val left = FloatArray(n) { (.4 * kotlin.math.sin(2 * Math.PI * 440 * it / 44_100)).toFloat() }
        val net = object : StemNet {
            override fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray) {
                for (i in input.indices) { vocals[i] = input[i] * .8f; accompaniment[i] = input[i] * .6f }
            }
        }
        val mask = Separator.mask(left, left, 0L, net)!!
        val music = Separator.stem(left, left, mask, 0, vocals = false)
        val voice = Separator.stem(left, left, mask, 0, vocals = true)
        for (i in music.from + 100 until music.until - 100 step 97) assertEquals(left[i], music.left[i] + voice.left[i], 2e-3f)
    }

    @Test fun deckPlaysTheVoiceAlone() {
        val rate = 48_000
        val input = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate) { input.putShort(10_000); input.putShort(10_000) }
        input.flip()
        val stem = StemAudio(0L, rate, ShortArray(rate * 2) { 6_000 }, 0, rate, vocals = ShortArray(rate * 2) { 4_000 })
        val dsp = DeckDsp(rate, 2)
        val auto = DeckAutomation(Curve.flat(1f), Curve.flat(0f), vocalSolo = Curve(longArrayOf(0, 1), floatArrayOf(1f, 1f)))
        val out = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
        dsp.process(input, out, 0L, auto, stem)
        out.flip()
        assertEquals(4_000, ShortArray(out.remaining() / 2) { out.short }[2_000].toInt())
    }

    @Test fun libraryWaitsForPowerAndTheSwitches() {
        assertTrue(StemLibrary.allowed(ketmix = true, library = true, charging = true))
        assertFalse(StemLibrary.allowed(ketmix = true, library = true, charging = false))
        assertFalse(StemLibrary.allowed(ketmix = false, library = true, charging = true))
        assertFalse(StemLibrary.allowed(ketmix = true, library = false, charging = true))
    }
}
