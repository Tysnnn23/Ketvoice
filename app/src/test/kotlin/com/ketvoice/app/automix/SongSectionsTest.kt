package com.ketvoice.app.automix

import com.ketvoice.app.automix.SongStructure.Label
import com.ketvoice.app.automix.SongStructure.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SongSectionsTest {
    private val barMs = 2_000.0

    /** A stretch at 120 BPM from [startMs], bars on the beat grid, read by [OnsetTracker]'s band energies as [plan] says. */
    private fun stretch(plan: String, startMs: Long = 0L): Pair<OnsetEnvelope, SegmentAnalysis> {
        val fps = 22f
        val perBar = (barMs / 1000 * fps).toInt()
        val bands = TimbreBands.BANDS
        val data = FloatArray(plan.length * perBar * bands)
        val rng = java.util.Random(7)
        for ((bar, part) in plan.withIndex()) for (f in 0 until perBar) for (m in 0 until bands) {
            val shape = when (part) {
                'i' -> if (m < 4) 1.2f else .2f
                'v' -> if (m in 3..8) 2.5f else .8f
                'c' -> if (m in 7..14) 4.2f else 2.6f
                'b' -> if (m > 11) 3f else .5f
                else -> if (m < 4) 1.0f else .15f
            }
            data[(bar * perBar + f) * bands + m] = shape + rng.nextFloat() * .2f
        }
        val length = (plan.length * barMs).toLong()
        val envelope = OnsetEnvelope(FloatArray(8), FloatArray(8), FloatArray(8), 86f, 0f, timbre = data, timbreFps = fps)
        val a = SegmentAnalysis(startMs, length, 120f, startMs.toDouble(), .9f, 0, -14f)
        return envelope to a
    }

    @Test fun theTrackerKeepsTheColourOfTheSoundOnlyWhenAsked() {
        val rate = 44_100
        val pcm = ByteBuffer.allocate(rate * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate * 2) {
            // A low tone the first second, a high one the next.
            val hz = if (i < rate) 80.0 else 3_000.0
            val v = (sin(2 * PI * hz * i / rate) * 12_000).toInt().toShort()
            pcm.putShort(v); pcm.putShort(v)
        }
        pcm.flip()
        val plain = OnsetTracker(rate, 2).apply { accept16(pcm) }.snapshot()
        assertNull("no band energies unless sections are wanted", plain.timbre)
        val read = OnsetTracker(rate, 2, withTimbre = true).apply { accept16(pcm) }.snapshot()
        val timbre = read.timbre!!
        val frames = timbre.size / TimbreBands.BANDS
        assertEquals(read.size / TimbreBands.FRAMES, frames)
        assertEquals(read.fps / TimbreBands.FRAMES, read.timbreFps, 1e-3f)
        fun loudest(frame: Int) = (0 until TimbreBands.BANDS).maxByOrNull { timbre[frame * TimbreBands.BANDS + it] }!!
        val low = loudest(frames / 4)
        val high = loudest(frames * 3 / 4)
        assertTrue("the low tone lies in a low band ($low), the high one in a high band ($high)", low < 4 && high > 10)
    }

    @Test fun anEndingIsCutIntoItsSectionsOnItsBars() {
        val (envelope, a) = stretch("vvvvvvvv" + "cccccccc" + "vvvvvvvv" + "cccccccc" + "oooooooo", startMs = 100_000L)
        val sections = SongSections.of(envelope, a, ending = true)!!
        val found = sections.drop(1).map { it.startMs }
        for (t in listOf(8, 16, 24, 32).map { 100_000L + it * barMs.toLong() })
            assertTrue("boundary at $t missing in $found", found.any { abs(it - t) <= barMs.toLong() })
        assertEquals(100_000L, sections.first().startMs)
        assertEquals("an ending starts on no intro", Label.Verse, sections.first().label)
        assertEquals(Label.Outro, sections.last().label)
        assertTrue(sections.toString(), sections.count { it.label == Label.Chorus } >= 2)
    }

    @Test fun aStartHasAnIntroAndNoOutro() {
        val (envelope, a) = stretch("iiiiiiii" + "vvvvvvvv" + "cccccccc" + "vvvvvvvv")
        val sections = SongSections.of(envelope, a, ending = false)!!
        assertEquals(Label.Intro, sections.first().label)
        assertTrue("the stretch stops, not the title", sections.last().label != Label.Outro)
        assertEquals(8 * barMs, SongSections.entry(a.copy(sections = sections))!!.toDouble(), barMs)
    }

    @Test fun theBeatModelsFramesAreUsedWhenTheyCoverTheStretch() {
        val plan = "vvvvvvvv" + "cccccccc" + "vvvvvvvv" + "cccccccc" + "oooooooo"
        val fps = BeatSpectrogram.RATE.toFloat() / BeatSpectrogram.HOP
        val mels = BeatSpectrogram.MELS
        val perBar = (barMs / 1000 * fps).toInt()
        val data = FloatArray(plan.length * perBar * mels)
        val rng = java.util.Random(3)
        for ((bar, part) in plan.withIndex()) for (f in 0 until perBar) for (m in 0 until mels) {
            val shape = when (part) { 'v' -> if (m in 20..70) 2.5f else .8f; 'c' -> if (m in 50..110) 4.2f else 2.6f; else -> if (m < 30) 1f else .15f }
            data[(bar * perBar + f) * mels + m] = shape + rng.nextFloat() * .2f
        }
        val a = SegmentAnalysis(100_000, (plan.length * barMs).toLong(), 120f, 100_000.0, .9f, 0, -14f)
        val sections = SongSections.of(BeatModel.Mels(data, 100_000.0), a, ending = true)!!
        val found = sections.drop(1).map { it.startMs }
        for (t in listOf(8, 16, 24, 32).map { 100_000L + it * barMs.toLong() })
            assertTrue("boundary at $t missing in $found", found.any { abs(it - t) <= barMs.toLong() })
        assertEquals(Label.Outro, sections.last().label)
        assertEquals(a.startMs + a.lengthMs, sections.last().endMs)
        assertNull("frames that start too late leave it to the band energies", SongSections.of(BeatModel.Mels(data, 110_000.0), a, ending = true))
    }

    @Test fun nothingIsGuessedWithoutBarsOrBandEnergies() {
        val (envelope, a) = stretch("vvvvvvvvcccccccc")
        assertNull(SongSections.of(envelope, a.copy(barOffset = -1), ending = true))
        val plain = OnsetEnvelope(FloatArray(8), FloatArray(8), FloatArray(8), 86f, 0f)
        assertNull(SongSections.of(plain, a, ending = true))
        val (short, b) = stretch("vvvvcc")
        assertEquals(emptyList<Section>(), SongSections.of(short, b, ending = true))
    }

    @Test fun onlyBeyondLightAndNeverOnLite() {
        assertFalse(SongSections.wanted(AnalysisDepth.Light, lite = false))
        assertTrue(SongSections.wanted(AnalysisDepth.Balanced, lite = false))
        assertTrue(SongSections.wanted(AnalysisDepth.Maximum, lite = false))
        assertFalse(SongSections.wanted(AnalysisDepth.Maximum, lite = true))
    }

    private val ending = listOf(Section(155_000, 161_000, Label.Verse), Section(161_000, 165_000, Label.Chorus),
        Section(165_000, 200_000, Label.Outro))

    @Test fun aTitleIsLeftWhereASectionStartsButNeverAsAChorusBegins() {
        val a = SegmentAnalysis(155_000, 45_000, 120f, 155_000.0, .9f, 0, -14f, phraseAtMs = 155_000.0, sections = ending)
        assertEquals(listOf(165_000.0), a.phraseStarts(158_000.0, 170_000.0))
        // No section start within reach: the phrase grid, without the phrase that opens the chorus.
        val grid = a.copy(sections = listOf(Section(155_000, 171_000, Label.Verse), Section(171_000, 200_000, Label.Chorus)))
        assertEquals(listOf(187_000.0), grid.phraseStarts(169_000.0, 190_000.0))
        assertEquals(listOf(171_000.0, 187_000.0), grid.copy(sections = null).phraseStarts(169_000.0, 190_000.0))
        assertTrue(SongSections.opensChorus(grid, 171_500.0))
        assertFalse(SongSections.opensChorus(grid, 174_000.0))
    }

    @Test fun theBlendStartsAsTheOutroBegins() {
        val a = SegmentAnalysis(155_000, 45_000, 120f, 155_000.0, .9f, 0, -14f, 155_000, 199_000, 155_000, 160_000)
        val b = SegmentAnalysis(0, 40_000, 120f, 500.0, .9f, 0, -14f, 300, 40_000, 8_000, 40_000)
        fun plan(out: SegmentAnalysis) = TransitionPlanner.plan(MixFacts(200_000, out), MixFacts(200_000, b), MixContext(), MixSettings(1))
        val before = plan(a)
        val after = plan(a.copy(sections = ending))
        assertEquals(MixKind.Beatmatch, after.kind)
        assertTrue("on the outro's first bar (${after.outMs}, was ${before.outMs})", abs(after.outMs - 165_000) <= barMs)
        assertTrue(after.onPhrase)
        assertEquals("the incoming title still enters on its first downbeat", before.inMs, after.inMs)
    }

    @Test fun aDropLandsWhereTheNextIntroEnds() {
        val b = SegmentAnalysis(0, 40_000, 120f, 500.0, .9f, 0, -14f, 300, 40_000, 4_500, 40_000)
        val drop = StyleCuts.dropAt(b, MixFacts(200_000, b))
        val sections = listOf(Section(0, 8_500, Label.Intro), Section(8_500, 24_500, Label.Chorus), Section(24_500, 40_000, Label.Verse))
        assertEquals("the level alone: where it rises", 4_500L, drop)
        assertEquals("with sections: where the intro gives way", 8_500L, StyleCuts.dropAt(b.copy(sections = sections), MixFacts(200_000, b)))
        assertNull("no sections, no change", SongSections.entry(b))
    }

    @Test fun sectionsAreKeptAndOldReadingsHaveNone() {
        val a = SegmentAnalysis(155_000, 45_000, 120f, 155_000.0, .9f, 0, -14f, sections = ending)
        assertEquals(ending, AnalysisStore.decode(AnalysisStore.encode(a))!!.sections)
        val empty = a.copy(sections = emptyList())
        assertEquals(emptyList<Section>(), AnalysisStore.decode(AnalysisStore.encode(empty))!!.sections)
        val old = AnalysisStore.encode(a).apply { remove("sv") }
        assertNull("a reading kept before 8.6 has no sections", AnalysisStore.decode(old)!!.sections)
        val other = AnalysisStore.encode(a).apply { put("sv", 99) }
        assertNull(AnalysisStore.decode(other)!!.sections)
        val broken = AnalysisStore.encode(a).apply { put("sc", org.json.JSONArray().put(1).put(2)) }
        assertNull(AnalysisStore.decode(broken)!!.sections)
        assertNotNull(AnalysisStore.decode(old))
    }

    @Test fun aReadingWithoutSectionsIsNotReadAgainForThem() {
        val a = SegmentAnalysis(155_000, 75_000, 120f, 155_000.0, .9f, 0, -14f, key = 3, analyser = 1)
        assertTrue(AnalysisDepth.Maximum.satisfiedBy(a, AnalysisDepth.Maximum.outroMs, 230_000))
    }
}
