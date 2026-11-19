package com.ketvoice.app.automix

import com.ketvoice.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SetExportTest {
    /** A steady tone, its phase tied to the position in the title (as a real file read from a seek point). */
    private class Tone(private val hz: Double, private val durationMs: Long, fromMs: Long) : SetSource {
        private var frame = SetRender.frames(fromMs)
        private val end = SetRender.frames(durationMs)
        override fun read(out: ShortArray, at: Int, frames: Int): Int {
            val n = minOf(frames.toLong(), end - frame).toInt().coerceAtLeast(0)
            for (k in 0 until n) {
                val v = (AMP * sin(2 * PI * hz * (frame + k) / SetRender.RATE)).toInt().toShort()
                out[(at + k) * 2] = v; out[(at + k) * 2 + 1] = v
            }
            frame += n
            return n
        }
    }

    private class Collect : SetSink {
        val samples = ArrayList<Short>()
        var finished = false
        var aborted = false
        override fun write(pcm: ShortArray, frames: Int) { for (k in 0 until frames * 2) samples += pcm[k] }
        override fun finish() { finished = true }
        override fun abort() { aborted = true }
    }

    private fun title(hz: Double, ms: Long = 20_000) = SetTitle(ms) { from -> Tone(hz, ms, from) }
    private val fade = MixPlan(MixKind.Crossfade, outMs = 15_000, inMs = 1_000, overlapMs = 4_000)

    @Test fun `the set lasts what its titles play, overlaps counted once`() {
        val titles = listOf(title(440.0), title(554.0), title(659.0))
        val sink = Collect()
        assertTrue(SetRender.render(titles, listOf(fade, fade, null), sink))
        val expected = SetRender.frames(15_000) + SetRender.frames(14_000) + SetRender.frames(19_000)
        assertEquals(expected, SetRender.lengthFrames(titles, listOf(fade, fade, null)))
        assertEquals(expected * 2, sink.samples.size.toLong())
        assertTrue(sink.finished)
    }

    @Test fun `no click and no hole at the joins`() {
        val sink = Collect()
        SetRender.render(listOf(title(440.0), title(554.0), title(659.0)), listOf(fade, fade, null), sink)
        val left = sink.samples.filterIndexed { i, _ -> i % 2 == 0 }
        // Two tones at most, each moving at most AMP * 2 pi f / rate per sample: a cut would jump far more.
        val bound = AMP * 2 * PI * (554.0 + 659.0) / SetRender.RATE
        val worst = (1 until left.size).maxOf { abs(left[it] - left[it - 1]) }
        assertTrue("largest step $worst, bound $bound", worst <= bound * 1.1)
        val window = SetRender.RATE / 20
        val quietest = (0 until left.size / window).minOf { w ->
            sqrt((0 until window).sumOf { val v = left[w * window + it].toDouble(); v * v } / window)
        }
        assertTrue("quietest 50 ms: $quietest", quietest > AMP * .25)
    }

    @Test fun `titles that do not mix follow each other gaplessly`() {
        val sink = Collect()
        SetRender.render(listOf(title(440.0, 5_000), title(440.0, 5_000)), listOf(null, null), sink)
        assertEquals(SetRender.frames(10_000) * 2, sink.samples.size.toLong())
    }

    @Test fun `a cancelled export stops and drops its file`() {
        val sink = Collect()
        var blocks = 0
        assertFalse(SetRender.render(listOf(title(440.0), title(554.0)), listOf(fade, null), sink, cancelled = { ++blocks > 10 }))
        assertTrue(sink.aborted); assertFalse(sink.finished)
    }

    @Test fun `streamed titles are never exported`() {
        val set = listOf(Track("a", "A", "X", "Y"), Track("b", "B", "X", "Y"), Track("c", "C", "X", "Y"))
        assertNull(SetExport.check(set) { if (it.id == "a") "/music/a.m4a" else null })
        val (kept, skipped) = SetExport.check(set) { if (it.id != "b") "/music/${it.id}.m4a" else null }!!
        assertEquals(listOf("a", "c"), kept.map { it.id }); assertEquals(1, skipped)
    }

    @Test fun `the resampler keeps the length of the sound`() {
        val from = 48_000
        val resampler = StreamResampler(from)
        val tone = ShortArray(from) { (AMP * sin(2 * PI * 440 * it / from)).toInt().toShort() }
        resampler.push(tone, from, 1)
        val out = ShortArray(SetRender.RATE * 2 + 200)
        val made = resampler.pull(out, 0, SetRender.RATE + 100, ended = true)
        assertTrue("made $made", abs(made - SetRender.RATE) <= 2)
    }

    @Test fun `a WAV file holds the set with its header`() {
        val file = File.createTempFile("set", ".wav")
        try {
            val sink = WavSink(file)
            SetRender.render(listOf(title(440.0, 2_000), title(554.0, 2_000)), listOf(null, null), sink)
            assertEquals(44 + SetRender.frames(4_000) * 4, file.length())
            assertEquals("RIFF", String(file.readBytes().copyOfRange(0, 4)))
        } finally { file.delete() }
    }

    private companion object { const val AMP = 8_000.0 }
}
