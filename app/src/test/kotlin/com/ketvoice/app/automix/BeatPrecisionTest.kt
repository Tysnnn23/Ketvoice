package com.ketvoice.app.automix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 8.4 « Temps plus précis »: beats placed between the model's 20 ms frames ([BeatPost.refine]) and pieces cut in the
 * network's own window length ([BeatPost.Window]).
 */
class BeatPrecisionTest {
    // ----------------------------------------------------------------- sub-frame peaks

    @Test fun aGaussianPeakGivesBackItsTopExactly() {
        for (sure in doubleArrayOf(.6, .9, .97)) for (top in doubleArrayOf(100.0, 100.37, 100.5, 99.62, 100.11)) {
            // The activation of a peak between frames, as logits: its log is a parabola through any three frames.
            val x = FloatArray(200) { i -> (sure * exp(-(i - top) * (i - top) / 2.4)).coerceAtLeast(1e-6).let { ln(it / (1 - it)) }.toFloat() }
            val peak = x.indices.maxByOrNull { x[it] }!!
            assertEquals("top $top at $sure", top, peak + BeatPost.refine(x, peak), 1e-3)
        }
    }

    @Test fun refinementStaysWithinHalfAFrameAndKeepsItsLimits() {
        val x = floatArrayOf(-5f, 1f, 3f, 2.9f, -5f, 2f, 2f, -1000f, 4f, -2f)
        assertTrue(BeatPost.refine(x, 2) in 0.0..0.5)
        assertEquals("a flat top of two lands half-way", .5, BeatPost.refine(x, 5), 1e-9)
        assertEquals("a neighbour no chunk covered", 0.0, BeatPost.refine(x, 8), 0.0)
        assertEquals("first frame", 0.0, BeatPost.refine(x, 0), 0.0)
        assertEquals("last frame", 0.0, BeatPost.refine(x, x.size - 1), 0.0)
        assertEquals("a dip is no peak", 0.0, BeatPost.refine(floatArrayOf(1f, 0f, 1f), 1), 0.0)
    }

    /** Logits of a network that is sure of a beat over about ±2 frames around each of [beats] (seconds). */
    private fun activation(beats: DoubleArray, frames: Int, width: Double = 1.1): FloatArray = FloatArray(frames) { i ->
        val near = beats.minByOrNull { abs(it * BeatPost.FPS - i) }!! * BeatPost.FPS
        val p = (.97 * exp(-(i - near) * (i - near) / (2 * width * width))).coerceIn(1e-4, 1 - 1e-4)
        ln(p / (1 - p)).toFloat()
    }

    private fun meanError(found: DoubleArray, truth: DoubleArray) = found.map { f -> truth.minOf { abs(it - f) } }.average()

    @Test fun beatsBetweenFramesAreFoundWhereTheyAreNotOnTheFrameGrid() {
        var before = 0.0; var after = 0.0; var phaseBefore = 0.0; var phaseAfter = 0.0
        val tempos = doubleArrayOf(97.3, 104.9, 112.7, 123.4, 128.0, 133.3, 141.9, 151.7)
        for ((k, bpm) in tempos.withIndex()) {
            val first = .113 + .037 * k
            val truth = DoubleArray(((30.0 - first) / (60.0 / bpm)).toInt()) { first + it * 60.0 / bpm }
            val logits = activation(truth, 1_500)
            val old = BeatPost.peaks(logits, logits, subFrame = false)
            val new = BeatPost.peaks(logits, logits)
            assertEquals(truth.size, new.beats.size)
            before += meanError(old.beats, truth); after += meanError(new.beats, truth)
            // The grid Ketmix keeps (BeatMapping.fit): its anchor against the true beat nearest to it.
            fun phase(beats: DoubleArray) = BeatMapping.fit(beats)!!.anchor.let { a -> truth.minOf { abs(it - a) } }
            phaseBefore += phase(old.beats); phaseAfter += phase(new.beats)
        }
        val n = tempos.size
        val summary = "mean beat error %.2f -> %.2f ms, grid phase %.3f -> %.3f ms".format(
            before / n * 1000, after / n * 1000, phaseBefore / n * 1000, phaseAfter / n * 1000)
        println("sub-frame refinement on synthetic activations: $summary")
        assertTrue(summary, before / n > .004)
        assertTrue(summary, after / n < .0005)
        assertTrue(summary, phaseAfter < phaseBefore)
    }

    @Test fun downbeatsStillLandOnTheRefinedBeats() {
        val truth = DoubleArray(40) { .2137 + it * .5 }
        val beats = activation(truth, 1_200)
        val downs = activation(truth.filterIndexed { i, _ -> i % 4 == 1 }.toDoubleArray(), 1_200)
        val t = BeatPost.peaks(beats, downs)
        assertEquals(10, t.downbeats.size)
        for (d in t.downbeats) assertTrue(t.beats.any { it == d })
        assertEquals(.7137, t.downbeats.first(), .001)
    }

    // ----------------------------------------------------------------- windows

    @Test fun theWindowsBorderFollowsItsLength() {
        assertEquals(60, BeatPost.DEFAULT.border)
        assertEquals(BeatPost.BEAT_FRAMES, BeatPost.DEFAULT.frames)
        val trained = BeatPost.Window(BeatPost.TRAINED_FRAMES)
        assertEquals("3 s at each end of a 30 s chunk", 150, trained.border)
        assertEquals(1_200, trained.stride)
        assertEquals(25, BeatPost.Window(100).border)
        assertTrue(runCatching { BeatPost.Window(100, 50) }.isFailure)
    }

    @Test fun piecesAreCutInTheNetworksOwnLength() {
        for (window in listOf(BeatPost.DEFAULT, BeatPost.Window(BeatPost.TRAINED_FRAMES))) {
            for (frames in intArrayOf(300, 900, 1_500, 2_250, 4_000)) {
                val starts = BeatPost.starts(frames, window)
                assertEquals(-window.border, starts.first())
                if (frames > window.stride) assertEquals(frames - (window.frames - window.border), starts.last())
                val seen = BooleanArray(frames)
                for (s in starts) for (j in window.border until window.frames - window.border) (s + j).takeIf { it in 0 until frames }?.let { seen[it] = true }
                assertTrue("${window.frames}/$frames", seen.all { it })
            }
        }
        // 45 s (BeatPcm.MAX_SECONDS): six chunks of 10 s, two of 30 s.
        assertEquals(6, BeatPost.starts(2_250).size)
        assertEquals(2, BeatPost.starts(2_250, BeatPost.Window(BeatPost.TRAINED_FRAMES)).size)
        // The old constants give the old cut.
        assertArrayEquals(BeatPost.starts(2_000), BeatPost.starts(2_000, BeatPost.Window(500, 60)))
    }

    /** A network of [frames] frames that hears a beat every 25 frames (120 BPM), a bar every 100. */
    private class Fake(override val frames: Int, piece: Int) : BeatRunner {
        private val starts = BeatPost.starts(piece, BeatPost.Window(frames))
        private var call = 0
        val calls get() = call
        override fun run(spect: FloatArray): Pair<FloatArray, FloatArray> {
            assertEquals(frames * BeatSpectrogram.MELS, spect.size)
            val s = starts[call++]
            val beat = FloatArray(frames) { if ((s + it) % 25 == 5) 4f else -4f }
            val down = FloatArray(frames) { if ((s + it) % 100 == 5) 4f else -4f }
            return beat to down
        }
    }

    @Test fun aThirtySecondNetworkIsRunInThirtySecondChunks() {
        val samples = FloatArray(BeatSpectrogram.RATE * 45)
        val frames = BeatSpectrogram.frames(samples.size)
        for (length in intArrayOf(BeatPost.BEAT_FRAMES, BeatPost.TRAINED_FRAMES)) {
            val net = Fake(length, frames)
            val t = BeatModel.predict(net, samples)
            assertEquals(BeatPost.starts(frames, BeatPost.Window(length)).size, net.calls)
            assertEquals(.5, t.beats[1] - t.beats[0], 1e-9)
            assertEquals(.1, t.beats.first(), 1e-9)
            assertEquals(2.0, t.downbeats[1] - t.downbeats[0], 1e-9)
        }
    }
}
