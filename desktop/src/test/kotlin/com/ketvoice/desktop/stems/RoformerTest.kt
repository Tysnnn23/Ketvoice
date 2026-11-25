package com.ketvoice.desktop.stems

import com.ketvoice.app.stems.OnnxRoformerNet
import com.ketvoice.app.stems.RoformerSeparator
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The studio voice pipeline (STFT, the export's (f s) t c layout, chunks and fades, inverse STFT) around a stand-in
 * network that keeps the left channel's bins under 2.15 kHz and silences the rest (`tools/stems/tiny_roformer.py`).
 * The real network's agreement with the reference (135.6 dB on a sung clip) is checked by `export_roformer.py`.
 */
class RoformerTest {
    private fun resource(name: String) = File(javaClass.getResource("/stems/$name")!!.toURI())
    private val rate = RoformerSeparator.RATE

    private fun tone(n: Int, vararg hz: Double) = FloatArray(n) { i -> hz.sumOf { .25 * sin(2 * PI * it * i / rate) }.toFloat() }

    private fun rms(x: FloatArray, from: Int, until: Int) = sqrt((from until until).sumOf { (x[it] * x[it]).toDouble() } / (until - from))

    @Test fun layoutChannelsAndBinsSurviveTheRoundTrip() {
        val n = rate * 21 // 21 s: padded, several overlapping chunks and a short last one
        val left = tone(n, 440.0, 8_000.0)
        val right = tone(n, 440.0)
        val (vl, vr) = OnnxRoformerNet(resource("tiny-roformer.onnx"), 2, gpu = false).use { RoformerSeparator(it).voice(left, right)!! }
        assertEquals(n, vl.size); assertEquals(n, vr.size)
        val low = tone(n, 440.0)
        val inner = rate until n - rate
        var err = 0.0
        for (i in inner) err = maxOf(err, abs((vl[i] - low[i]).toDouble()))
        assertTrue(err < 2e-3, "left keeps its 440 Hz and loses its 8 kHz (max error $err)")
        assertTrue(rms(vr, 0, n) < 1e-4, "the right channel is silenced: ${rms(vr, 0, n)}")
        assertTrue(rms(vl, 0, n) > .15)
    }

    @Test fun reflectionMatchesNumpy() {
        // numpy.pad([0, 1, 2, 3], 2, mode="reflect") == [2, 1, 0, 1, 2, 3, 2, 1]
        assertEquals(listOf(2, 1, 0, 1, 2, 3, 2, 1), (-2 until 6).map { RoformerSeparator.reflectIndex(it, 4) })
        val fades = RoformerSeparator.fades()
        assertEquals(0f, fades[0]); assertEquals(1f, fades[RoformerSeparator.FADE - 1], 1e-6f)
        assertEquals(1f, fades[RoformerSeparator.CHUNK / 2]); assertEquals(0f, fades[RoformerSeparator.CHUNK - 1])
    }

    @Test fun stopsWhenAsked() {
        val n = rate * 10
        val result = OnnxRoformerNet(resource("tiny-roformer.onnx"), 2, gpu = false).use { RoformerSeparator(it).voice(tone(n, 440.0), tone(n, 440.0)) { true } }
        assertNull(result)
    }
}
