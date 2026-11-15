package com.ketvoice.app.automix

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic material with a known tempo, beat positions and shape. */
internal object Synth {
    fun silence(rate: Int, seconds: Float) = FloatArray((rate * seconds).toInt())

    /** A kick on every beat (louder on the first of four), a hat between beats, a held pad. */
    fun groove(rate: Int, seconds: Float, bpm: Float, firstBeatAt: Float = 0f, accentBar: Boolean = true, seed: Int = 7,
               gain: Float = 1f): FloatArray {
        val out = FloatArray((rate * seconds).toInt())
        val random = Random(seed)
        val beat = 60f / bpm
        var k = 0
        while (true) {
            val time = firstBeatAt + k * beat
            val start = (time * rate).toInt()
            if (start >= out.size) break
            val strength = if (accentBar && k % 4 == 0) 1f else .62f
            for (i in 0 until (rate * .16f).toInt()) {
                val at = start + i; if (at >= out.size || at < 0) break
                val t = i / rate.toFloat()
                out[at] += strength * .7f * sin(2 * PI * (55.0 + 60.0 * exp(-t * 40.0)) * t).toFloat() * exp(-t * 22f)
            }
            val hat = ((time + beat / 2) * rate).toInt()
            for (i in 0 until (rate * .03f).toInt()) {
                val at = hat + i; if (at >= out.size || at < 0) break
                out[at] += .16f * (random.nextFloat() * 2 - 1) * exp(-i / (rate * .008f))
            }
            k++
        }
        for (i in out.indices) {
            val t = i / rate.toFloat()
            out[i] = gain * (out[i] + .07f * sin(2 * PI * 220.0 * t).toFloat() + .05f * sin(2 * PI * 330.0 * t).toFloat())
        }
        return out
    }

    /** Notes at irregular moments over a held chord: music, but no pulse to mix on. */
    fun rubato(rate: Int, seconds: Float, seed: Int = 3): FloatArray {
        val random = Random(seed)
        val out = FloatArray((rate * seconds).toInt()) { i ->
            val t = i / rate.toFloat()
            .12f * sin(2 * PI * 196.0 * t).toFloat() + .004f * (random.nextFloat() * 2 - 1)
        }
        var time = .3f
        while (time < seconds) {
            val start = (time * rate).toInt()
            val pitch = 220.0 * (1 + random.nextInt(8)) / 2.0
            for (i in 0 until (rate * .5f).toInt()) {
                val at = start + i; if (at >= out.size) break
                val t = i / rate.toFloat()
                out[at] += .3f * sin(2 * PI * pitch * t).toFloat() * exp(-t * 6f)
            }
            time += .23f + random.nextFloat() * .9f
        }
        return out
    }

    fun scaled(samples: FloatArray, from: Float, to: Float): FloatArray =
        FloatArray(samples.size) { samples[it] * (from + (to - from) * it / samples.size.coerceAtLeast(1)) }

    fun envelope(samples: FloatArray, rate: Int): OnsetEnvelope =
        OnsetTracker(rate, 1).apply { acceptMono(samples) }.snapshot()

    fun pcm16(samples: FloatArray, channels: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2 * channels).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) repeat(channels) { buffer.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        buffer.flip()
        return buffer
    }
}

class BeatAnalysisTest {
    private fun beatError(analysis: SegmentAnalysis, trueBeatMs: Double) = BeatAnalysis.nearestBeatError(analysis, trueBeatMs)

    @Test fun aSteadyGrooveGivesItsTempoAndItsBeats() {
        for ((bpm, first) in listOf(120f to .25f, 128f to .1f, 93.5f to .4f, 140f to 0f, 174f to .2f, 72f to .3f)) {
            val rate = 44_100
            val analysis = BeatAnalysis.analyze(Synth.envelope(Synth.groove(rate, 32f, bpm, first), rate), startMs = 0)
            // The same pulse counted twice as fast or as slow is the same tempo to mix on.
            val octave = listOf(1f, 2f, .5f).minByOrNull { kotlin.math.abs(analysis.bpm - bpm * it) }!!
            assertEquals("tempo of $bpm", bpm * octave, analysis.bpm, bpm * octave * .004f)
            assertTrue("confident on $bpm (${analysis.confidence})", analysis.confidence > .6f)
            assertTrue("beats of $bpm land on the kicks (${beatError(analysis, first * 1000.0)} ms)", beatError(analysis, first * 1000.0) < 14.0)
            // Thirty seconds later the grid is still on the kicks: the period is exact enough to mix on.
            val later = (first + 60f / bpm * (30f / (60f / bpm)).toInt()) * 1000.0
            assertTrue("no drift on $bpm (${beatError(analysis, later)} ms)", beatError(analysis, later) < 20.0)
        }
    }

    @Test fun theFirstBeatOfTheBarIsFoundWhenItIsAccented() {
        val rate = 44_100
        val analysis = BeatAnalysis.analyze(Synth.envelope(Synth.groove(rate, 32f, 124f, .2f), rate), 0)
        assertTrue(analysis.barOffset >= 0)
        val bar = analysis.barAtOrAfter(0.0)
        val period = 60_000.0 / 124 * 4
        val offset = ((bar - 200.0) % period + period) % period
        assertTrue("a bar starts on an accented kick ($offset)", offset < 16.0 || period - offset < 16.0)
        val flat = BeatAnalysis.analyze(Synth.envelope(Synth.groove(rate, 32f, 124f, .2f, accentBar = false), rate), 0)
        assertEquals("Four equal beats: no bar is claimed", -1, flat.barOffset)
        assertEquals(flat.beatAtOrAfter(5_000.0), flat.barAtOrAfter(5_000.0), 1e-6)
    }

    @Test fun stereoSixteenBitAtAnotherRateGivesTheSameAnswer() {
        val rate = 48_000
        val tracker = OnsetTracker(rate, 2)
        val pcm = Synth.pcm16(Synth.groove(rate, 24f, 126f, .15f), 2)
        // Fed in uneven pieces, as a decoder does.
        var offset = 0
        val sizes = intArrayOf(4096, 1876, 9000, 4)
        var turn = 0
        while (offset < pcm.limit()) {
            val size = minOf(sizes[turn++ % sizes.size], pcm.limit() - offset)
            val piece = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN); piece.position(offset); piece.limit(offset + size)
            tracker.accept16(piece.slice().order(ByteOrder.LITTLE_ENDIAN))
            offset += size
        }
        val analysis = BeatAnalysis.analyze(tracker.snapshot(), 0)
        assertEquals(1f, BeatAnalysis.foldedRatio(analysis.bpm, 126f), .004f)
        assertTrue(beatError(analysis, 150.0) < 14.0)
    }

    @Test fun musicWithoutABeatIsNotTrusted() {
        val rate = 44_100
        val analysis = BeatAnalysis.analyze(Synth.envelope(Synth.rubato(rate, 30f), rate), 0)
        assertFalse("confidence ${analysis.confidence}", analysis.hasBeat)
        assertTrue(BeatAnalysis.analyze(Synth.envelope(Synth.silence(rate, 20f), rate), 0).bpm == 0f)
        assertEquals("Too short to tell", 0f, BeatAnalysis.analyze(Synth.envelope(Synth.groove(rate, 4f, 120f), rate), 0).bpm, 0f)
    }

    @Test fun positionsAreThoseOfTheTitleNotOfTheExcerpt() {
        val rate = 44_100
        val analysis = BeatAnalysis.analyze(Synth.envelope(Synth.groove(rate, 30f, 120f, .25f), rate), startMs = 200_000)
        assertEquals(200_000, analysis.startMs)
        assertTrue(beatError(analysis, 200_250.0) < 14.0)
        assertTrue(analysis.beatAtOrAfter(210_000.0) >= 210_000.0)
        assertTrue(analysis.beatAtOrAfter(210_000.0) - 210_000.0 < 500.0)
    }

    @Test fun aQuietIntroAndLeadingSilenceAreMeasured() {
        val rate = 44_100
        val samples = Synth.silence(rate, 1.2f) + Synth.groove(rate, 6f, 120f, gain = .08f) + Synth.groove(rate, 22f, 120f)
        val analysis = BeatAnalysis.analyze(Synth.envelope(samples, rate), 0)
        assertEquals(1_200.0, analysis.soundStartMs.toDouble(), 150.0)
        assertEquals("the body starts after the quiet intro", 7_200.0, analysis.riseMs.toDouble(), 1_300.0)
        assertTrue(analysis.loudnessDb > -30f && analysis.loudnessDb < -3f)
    }

    @Test fun aFadeOutAndAHardEndingAreToldApart() {
        val rate = 44_100
        val body = Synth.groove(rate, 20f, 120f)
        val faded = body + Synth.scaled(Synth.groove(rate, 9f, 120f), 1f, 0f) + Synth.silence(rate, 2f)
        val fade = BeatAnalysis.analyze(Synth.envelope(faded, rate), 0)
        assertTrue(fade.fadeOut); assertFalse(fade.hardEnding)
        assertTrue("the outro starts in the fade (${fade.fallMs})", fade.fallMs in 19_000..27_000)
        assertTrue("trailing silence is left out (${fade.soundEndMs})", fade.soundEndMs in 26_000..29_600)

        val hard = BeatAnalysis.analyze(Synth.envelope(body + Synth.silence(rate, .8f), rate), 0)
        assertTrue(hard.hardEnding); assertFalse(hard.fadeOut)
        assertEquals(20_000.0, hard.soundEndMs.toDouble(), 350.0)

        val quiet = BeatAnalysis.analyze(Synth.envelope(body + Synth.groove(rate, 10f, 120f, gain = .12f), rate), 0)
        assertFalse("A quiet but steady outro is not a fade", quiet.fadeOut)
        assertFalse(quiet.hardEnding)
        assertEquals(20_000.0, quiet.fallMs.toDouble(), 1_500.0)
    }

    @Test fun doubleAndHalfTempoCountAsTheSamePulse() {
        assertEquals(1f, BeatAnalysis.foldedRatio(140f, 70f), 1e-4f)
        assertEquals(1f, BeatAnalysis.foldedRatio(64f, 128f), 1e-4f)
        assertEquals(1.05f, BeatAnalysis.foldedRatio(126f, 120f), 1e-4f)
        assertEquals(.9143f, BeatAnalysis.foldedRatio(128f, 70f), 1e-3f)
        assertEquals(1f, BeatAnalysis.foldedRatio(0f, 120f), 0f)
    }

    @Test fun theTransformFindsATone() {
        val n = 512
        val real = FloatArray(n) { sin(2 * PI * 37 * it / n).toFloat() }; val imaginary = FloatArray(n)
        Fft.transform(real, imaginary)
        val magnitudes = FloatArray(n / 2) { kotlin.math.sqrt(real[it] * real[it] + imaginary[it] * imaginary[it]) }
        assertEquals(37, magnitudes.indices.maxByOrNull { magnitudes[it] })
        assertEquals(n / 2f, magnitudes[37], 1f)
        assertTrue(magnitudes.filterIndexed { i, _ -> abs(i - 37) > 1 }.all { it < 1f })
    }

    @Test fun aTempoThatDoesNotHoldIsNotTrustedForLongMixes() {
        val rate = 22_050
        // Steady: the thorough reading agrees with the plain one.
        val steady = Synth.envelope(Synth.groove(rate, 40f, 120f), rate)
        val plain = BeatAnalysis.analyze(steady, 0)
        val checked = BeatAnalysis.analyze(steady, 0, thorough = true)
        assertTrue(plain.hasBeat)
        assertEquals(plain.confidence, checked.confidence, 0f)
        assertEquals(plain.bpm, checked.bpm, 0f)
        // A band that speeds up by 5 % half-way: each half has a clear tempo, but not the same one.
        val drifting = Synth.envelope(Synth.groove(rate, 20f, 120f) + Synth.groove(rate, 20f, 126f), rate)
        val loose = BeatAnalysis.analyze(drifting, 0)
        val strict = BeatAnalysis.analyze(drifting, 0, thorough = true)
        assertTrue("half the confidence at most (${loose.confidence} -> ${strict.confidence})", strict.confidence <= loose.confidence * .5f + 1e-6f)
    }
}
