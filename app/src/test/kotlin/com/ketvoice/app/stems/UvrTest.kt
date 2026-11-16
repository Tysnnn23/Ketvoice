package com.ketvoice.app.stems

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

class UvrTest {
    @Test fun mixedFftMatchesTheDefinition() {
        for (n in intArrayOf(12, 48, 96)) {
            val fft = MixedFft(n)
            val x = FloatArray(n) { sin(it * .37).toFloat() + .2f * cos(it * 1.9).toFloat() }
            val y = FloatArray(n) { cos(it * .11).toFloat() }
            val re = x.copyOf(); val im = y.copyOf()
            fft.transform(re, im)
            for (k in 0 until n) {
                var r = 0.0; var i = 0.0
                for (t in 0 until n) {
                    val a = -2 * PI * k * t / n
                    r += x[t] * cos(a) - y[t] * sin(a); i += x[t] * sin(a) + y[t] * cos(a)
                }
                assertEquals("n=$n k=$k", r, re[k].toDouble(), 1e-3); assertEquals(i, im[k].toDouble(), 1e-3)
            }
            fft.transform(re, im, inverse = true)
            for (t in 0 until n) { assertEquals(x[t], re[t] / n, 1e-4f); assertEquals(y[t], im[t] / n, 1e-4f) }
        }
    }

    /** A network that hands the title back as its voice gives the title back: the chunks, margins and segments are seamless. */
    @Test fun identityNetworkGivesTheSignalBack() {
        val net = object : UvrNet {
            override val nFft = 1536; override val dimF = 768; override val dimT = 64
            override fun run(input: FloatArray, output: FloatArray) { input.copyInto(output) }
        }
        val n = 44_100 * 40
        val left = FloatArray(n) { (.3 * sin(2 * PI * 440 * it / 44_100) + .1 * sin(2 * PI * 97 * it / 44_100)).toFloat() }
        val right = FloatArray(n) { (.2 * sin(2 * PI * 660 * it / 44_100)).toFloat() }
        val (l, r) = UvrSeparator(net).voice(left, right)!!
        var worst = 0f
        for (i in 0 until n) worst = maxOf(worst, abs(l[i] - left[i]), abs(r[i] - right[i]))
        // The top bin is left out by the network's band: a little error on a pure tone only.
        assertTrue("worst $worst", worst < 2e-3f)
    }

    /**
     * The whole high-quality path on a MUSDB18-HQ excerpt (local only, see tools/stems/uvr_reference.py):
     * the network's outputs computed by ONNX Runtime are replayed; the voice must match sherpa-onnx's and the
     * stored separation must keep most of the model's quality against the true stems.
     */
    @Test fun matchesTheReferenceAndTheTrueStems() {
        val dir = System.getenv("KETVOICE_UVR_REF")?.let(::File)
        assumeTrue(dir != null && File(dir, "meta.txt").isFile)
        val (nFft, dimF, dimT) = File(dir, "meta.txt").readText().trim().split(" ").map { it.toInt() }
        fun floats(name: String): FloatArray {
            val b = ByteBuffer.wrap(File(dir, name).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(b.remaining()).also { b.get(it) }
        }
        val inputs = floats("uvr-input.f32"); val outputs = floats("uvr-output.f32")
        val size = 4 * dimF * dimT
        var segment = 0; var worstInput = 0.0
        val replay = object : UvrNet {
            override val nFft = nFft; override val dimF = dimF; override val dimT = dimT
            override fun run(input: FloatArray, output: FloatArray) {
                val base = segment * size
                var e = 0.0; var r = 0.0
                for (i in 0 until size) { val d = input[i] - inputs[base + i]; e += d * d; r += inputs[base + i].toDouble() * inputs[base + i] }
                worstInput = maxOf(worstInput, kotlin.math.sqrt(e / r))
                outputs.copyInto(output, 0, base, base + size)
                segment++
            }
        }
        val mix = floats("mix.f32"); val n = mix.size / 2
        val left = FloatArray(n) { mix[2 * it] }; val right = FloatArray(n) { mix[2 * it + 1] }
        val started = System.nanoTime()
        val (vl, vr) = UvrSeparator(replay).voice(left, right)!!
        val mask = Separator.maskFromVoice(left, right, vl, vr, 0L)!!
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("network input differs: $worstInput", worstInput < 1e-3)
        assertEquals(StemMask.HIGH, mask.quality)
        fun sdr(ref: FloatArray, est: (Int) -> Float, stride: Int = 2, ch: Int = 0, from: Int = 16_384, until: Int = n - 16_384): Double {
            var s = 0.0; var e = 0.0
            for (i in from until until) { val r = ref[stride * i + ch].toDouble(); val d = r - est(i); s += r * r; e += d * d }
            return 10 * log10(s / e)
        }
        val sherpa = floats("sherpa-voice.f32")
        val vsSherpa = sdr(sherpa, { vl[it] })
        val music = Separator.instrumental(left, right, mask, 0)
        val voice = Separator.stem(left, right, mask, 0, vocals = true)
        val trueAcc = floats("true-acc.f32"); val trueVoc = floats("true-voc.f32")
        val from = maxOf(music.from, 16_384); val until = minOf(music.until, n - 16_384)
        val direct = sdr(trueVoc, { vl[it] }, from = from, until = until)
        val stored = sdr(trueVoc, { voice.left[it] }, from = from, until = until)
        val instrumental = sdr(trueAcc, { music.left[it] }, from = from, until = until)
        println("UVR voice vs sherpa-onnx %.1f dB; vs true voice: model %.2f dB, stored %.2f dB; instrumental %.2f dB; %d ms without the network"
            .format(vsSherpa, direct, stored, instrumental, ms))
        assertTrue(vsSherpa > 60)
        assertTrue(stored > direct - 1.5)
    }
}
