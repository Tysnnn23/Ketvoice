package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.MixContext
import com.ketvoice.app.automix.MixFacts
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixSettings
import com.ketvoice.app.automix.MusicalKey
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.TransitionPlanner
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What « Maximum » feeds the shared planner, and that the planner without it plans as before (the phone's case). */
class PcPlanningTest {
    private val duration = 200_000L
    private fun outro(bpm: Float = 124f) = SegmentAnalysis(startMs = 150_000L, lengthMs = 50_000L, bpm = bpm, beatAtMs = 150_000.0,
        confidence = .9f, barOffset = 0, loudnessDb = -9f, soundEndMs = 199_000L, riseMs = 150_000L, fallMs = 192_000L,
        key = 9, keyStrength = .8f, phraseAtMs = 150_000.0, activity = .5f)
    private fun intro(bpm: Float = 125f) = SegmentAnalysis(startMs = 0L, lengthMs = 45_000L, bpm = bpm, beatAtMs = 0.0, confidence = .9f,
        barOffset = 0, loudnessDb = -9f, soundStartMs = 0L, riseMs = 8_000L, fallMs = 45_000L, key = 9, keyStrength = .8f, phraseAtMs = 0.0, activity = .5f)

    private fun facts(sections: List<SongStructure.Section> = emptyList(), key: Int = MusicalKey.NONE, confidence: Float = 0f,
                      energy: FloatArray = FloatArray(200) { .5f }) =
        TrackFacts(duration, doubleArrayOf(), sections, key, confidence, energy, null)

    @Test fun withoutFactsNothingChanges() {
        val a = outro()
        assertEquals(a, PcInputs.analysis(a, null))
        assertTrue(PcInputs.energyStep(null, facts(), a, intro()).isNaN())
        val plain = TransitionPlanner.plan(MixFacts(duration, a), MixFacts(duration, intro()), MixContext(), MixSettings())
        val same = TransitionPlanner.plan(MixFacts(duration, PcInputs.analysis(a, null)), MixFacts(duration, PcInputs.analysis(intro(), null)),
            MixContext(energyStep = Float.NaN, avoid = emptySet()), MixSettings())
        assertEquals(plain, same)
    }

    @Test fun sectionsBecomeTheLandingPlaces() {
        val chorusEnd = 176_129.0
        val a = PcInputs.analysis(outro(), facts(listOf(SongStructure.Section(0, 60_000, SongStructure.Label.Intro),
            SongStructure.Section(60_000, chorusEnd.toLong(), SongStructure.Label.Chorus),
            SongStructure.Section(chorusEnd.toLong(), duration, SongStructure.Label.Outro))))!!
        assertEquals(listOf(60_000.0, chorusEnd.toLong().toDouble()), a.sectionStartsMs)
        assertEquals(listOf(chorusEnd.toLong().toDouble()), a.phraseStarts(170_000.0, 190_000.0))
        // Where no section starts, the phrase grid is used as before.
        assertEquals(outro().phraseStarts(150_000.0, 170_000.0), a.phraseStarts(150_000.0, 170_000.0))
    }

    @Test fun neuralKeyTakesOverOnlyWhenSure() {
        assertEquals(2, PcInputs.analysis(outro(), facts(key = 2, confidence = .6f))!!.key)
        assertEquals(9, PcInputs.analysis(outro(), facts(key = 2, confidence = .1f))!!.key)
        assertEquals(9, PcInputs.analysis(outro(), facts(key = MusicalKey.NONE, confidence = .9f))!!.key)
    }

    @Test fun energyStepComparesTheEndingWithTheStart() {
        val quietEnd = facts(energy = FloatArray(200) { if (it > 170) .2f else .7f })
        val loudStart = facts(energy = FloatArray(200) { if (it < 30) .8f else .5f })
        val step = PcInputs.energyStep(quietEnd, loudStart, outro(), intro())
        assertTrue(abs(step - .6f) < .05f, "step $step")
    }

    @Test fun avoidedKindsAreLeftOutOfAutomaticChoices() {
        val out = MixFacts(duration, outro())
        val into = MixFacts(duration, intro())
        val usual = TransitionPlanner.plan(out, into, MixContext(), MixSettings())
        assertEquals(MixKind.Beatmatch, usual.kind)
        val other = TransitionPlanner.plan(out, into, MixContext(avoid = setOf(MixKind.Beatmatch)), MixSettings())
        assertTrue(other.mixes)
        assertNotEquals(MixKind.Beatmatch, other.kind)
        // A style the listener picked by hand is never overridden.
        val asked = TransitionPlanner.plan(out, into, MixContext(avoid = setOf(MixKind.Beatmatch)), MixSettings(style = com.ketvoice.app.automix.MixStyle.Tempo))
        assertEquals(MixKind.Beatmatch, asked.kind)
    }

    @Test fun avoidedFiltersAndWashesBecomePlainFades() {
        // Tempos too far apart: the crossing is a filter crossing, or a dissolve when the title has no beat.
        val out = MixFacts(duration, outro(124f))
        val into = MixFacts(duration, intro(90f))
        val ways = setOf(MixKind.Echo, MixKind.Rise, MixKind.Beatmatch)
        val usual = TransitionPlanner.plan(out, into, MixContext(avoid = ways), MixSettings())
        assertTrue(usual.kind == MixKind.Filter || usual.kind == MixKind.Wash, "${usual.kind} ${usual.reason}")
        val avoided = TransitionPlanner.plan(out, into, MixContext(avoid = ways + usual.kind), MixSettings())
        assertEquals(MixKind.Crossfade, avoided.kind)
        assertEquals(usual.overlapMs, avoided.overlapMs)
        // Asked for by hand, « Filtres » stays.
        assertEquals(MixKind.Filter, TransitionPlanner.plan(out, into, MixContext(avoid = ways + MixKind.Filter),
            MixSettings(style = com.ketvoice.app.automix.MixStyle.Filters)).kind)
    }

    /** « Ketmix apprend »: three 👎 on blends in time and the desktop's next plan is another kind; with learning off, nothing changes. */
    @Test fun learnedTasteReachesThePlannerOnlyWhenOn() {
        val out = MixFacts(duration, outro())
        val into = MixFacts(duration, intro())
        val a = com.ketvoice.app.data.Track("a", "Titre a", "Artiste a", "", durationMs = duration)
        val b = com.ketvoice.app.data.Track("b", "Titre b", "Artiste b", "", durationMs = duration)
        val state = com.ketvoice.app.player.PlayerUiState(queue = listOf(a, b), index = 0, isPlaying = true)
        fun plan() = DesktopAutomix.planFor(out, into, DesktopAutomix.contextFor(state, a, b, 1f), null, 1f, beats = true)
        KetmixFeedback.resetForTest()
        DesktopAutomix.resetForTest()
        try {
            KetmixPcSettings.learns.value = true
            val first = plan()
            assertEquals(MixKind.Beatmatch, first.kind)
            repeat(3) { KetmixFeedback.planned(first, a, b); KetmixFeedback.rate(false) }
            assertEquals(setOf(MixKind.Beatmatch), DesktopAutomix.contextFor(state, a, b, 1f).avoid)
            val learnt = plan()
            assertTrue(learnt.mixes)
            assertNotEquals(MixKind.Beatmatch, learnt.kind)
            // Off: what was learnt is kept but not used.
            KetmixPcSettings.learns.value = false
            assertEquals(emptySet(), DesktopAutomix.contextFor(state, a, b, 1f).avoid)
            assertEquals(MixKind.Beatmatch, plan().kind)
            // And a rating given while off counts for nothing.
            KetmixFeedback.planned(first, a, b); KetmixFeedback.rate(true)
            assertNull(KetmixFeedback.transition.value?.rating)
        } finally { KetmixPcSettings.learns.value = false; KetmixFeedback.resetForTest(); DesktopAutomix.resetForTest() }
    }

    @Test fun soundScoresPreferCloseTitles() {
        val near = SoundQueue.score(.95f, .01f, .05f)!!
        val far = SoundQueue.score(.4f, .01f, .05f)!!
        assertTrue(near < far)
        assertNull(SoundQueue.score(null, null, Float.NaN))
        assertEquals(2, SoundQueue.pick(listOf(far, far, near)))
        assertEquals(0, SoundQueue.pick(listOf(near, far, near - .01f)), "a tiny gain does not move the queue")
        assertEquals(0, SoundQueue.pick(listOf(null, null)))
        val a = SoundPrint.unit(floatArrayOf(1f, 1f, 0f)); val b = SoundPrint.unit(floatArrayOf(1f, 0f, 0f))
        assertEquals(.7071f, SoundPrint.cosine(a, b)!!, 1e-3f)
    }

    @Test fun skeyOrderMapsToTheSharedKeyNumbers() {
        assertEquals(9, NeuralKey.fromSkey(0))    // A major
        assertEquals(0, NeuralKey.fromSkey(3))    // C major
        assertEquals(3, NeuralKey.fromSkey(6))    // D# major
        assertEquals(12 + 11, NeuralKey.fromSkey(12)) // B minor
        assertEquals(12 + 9, NeuralKey.fromSkey(22))  // A minor
        assertEquals(12 + 10, NeuralKey.fromSkey(23)) // Bb minor
        assertEquals((0..23).toSet(), (0..23).map(NeuralKey::fromSkey).toSet())
    }
}

class SongStructureTest {
    private val fps = 50f
    private val mels = 128
    private val barMs = 2_000.0

    /** A song: [plan] gives each bar's part; each part has its own spectral shape and level. */
    private fun song(plan: String): Triple<FloatArray, List<Double>, Long> {
        val framesPerBar = (barMs / 1000 * fps).toInt()
        val data = FloatArray(plan.length * framesPerBar * mels)
        val rng = java.util.Random(4)
        for ((bar, part) in plan.withIndex()) for (f in 0 until framesPerBar) for (m in 0 until mels) {
            val shape = when (part) {
                'i' -> if (m < 30) 1.2f else .2f
                'v' -> if (m in 20..70) 2.5f else .8f
                'c' -> if (m in 50..110) 4.2f else 2.6f
                'b' -> if (m > 90) 3f else .5f
                else -> if (m < 30) 1.0f else .15f
            }
            data[((bar * framesPerBar) + f) * mels + m] = shape + rng.nextFloat() * .2f
        }
        return Triple(data, plan.indices.map { it * barMs }, (plan.length * barMs).toLong())
    }

    @Test fun findsTheSectionsOnBarsAndNamesTheChorus() {
        val plan = "iiii" + "vvvvvvvv" + "cccccccc" + "vvvvvvvv" + "cccccccc" + "bbbbbbbb" + "cccccccc" + "oooo"
        val (data, bars, duration) = song(plan)
        val sections = SongStructure.sections(data, mels, fps, bars, duration)
        val truth = listOf(4, 12, 20, 28, 36, 44, 52).map { it * barMs.toLong() }
        val found = sections.drop(1).map { it.startMs }
        for (t in truth) assertTrue(found.any { abs(it - t) <= barMs.toLong() }, "boundary at $t ms missing in $found")
        assertTrue(sections.count { it.label == SongStructure.Label.Chorus } >= 3, sections.toString())
        assertEquals(SongStructure.Label.Intro, sections.first().label)
        assertEquals(SongStructure.Label.Outro, sections.last().label)
        assertEquals(duration, sections.last().endMs)
    }

    @Test fun tooShortToTell() {
        val (data, bars, duration) = song("vvvvcc")
        assertEquals(emptyList(), SongStructure.sections(data, mels, fps, bars, duration))
    }

    @Test fun energyFollowsLoudnessOnAScaleSharedByTitles() {
        val (quiet, _, _) = song("iiii")
        val (loud, _, _) = song("cccc")
        val a = EnergyCurve.of(quiet, mels, fps)
        val b = EnergyCurve.of(loud, mels, fps)
        assertEquals(8, a.size)
        assertTrue(b.average() > a.average() + .3, "${a.average()} vs ${b.average()}")
        assertTrue(EnergyCurve.around(null, 0, 1000).isNaN())
    }

    @Test fun factsSurviveTheirFile() {
        val dir = kotlin.io.path.createTempDirectory("facts").toFile()
        TrackFactsStore.folder = dir
        try {
            val f = TrackFacts(180_000L, doubleArrayOf(0.0, 2000.0), listOf(SongStructure.Section(0, 2000, SongStructure.Label.Chorus)),
                21, .7f, floatArrayOf(.1f, .9f), SoundPrint.unit(floatArrayOf(1f, 2f, 3f)))
            TrackFactsStore.put("id-1", f)
            TrackFactsStore.clearCache()
            val back = TrackFactsStore.get("id-1", 179_000L)!!
            assertEquals(21, back.key); assertEquals(f.sections, back.sections)
            assertTrue(back.sound!!.contentEquals(f.sound!!) && back.energy.contentEquals(f.energy))
            assertNull(TrackFactsStore.get("id-1", 120_000L), "another version of the title")
        } finally { TrackFactsStore.folder = null; TrackFactsStore.clearCache(); dir.deleteRecursively() }
    }
}

class LiveMixTest {
    @Test fun deckWindowCarriesBeatsBarsAndPhase() {
        val a = SegmentAnalysis(startMs = 0, lengthMs = 60_000, bpm = 120f, beatAtMs = 0.0, confidence = .9f, barOffset = 0)
        val d = LiveMix.deck("Titre", 10_250L, a, null, .7f, 1f)
        assertEquals(LiveMix.POINTS, d.peaks.size)
        // 12 s at 120 BPM: 24 beats, 6 of them first beats of bars.
        assertEquals(24, d.beats.size + d.bars.size)
        assertEquals(6, d.bars.size)
        assertEquals(.5f, d.phase, 1e-3f)
        assertTrue(d.bars.all { it in 0f..1f })
    }

    @Test fun crossingProgressFollowsTheOutgoingTitle() {
        val plan = com.ketvoice.app.automix.MixPlan(MixKind.Beatmatch, outMs = 100_000, inMs = 0, overlapMs = 8_000)
        assertEquals(0f, LiveMix.mixProgress(plan, 99_000))
        assertEquals(.5f, LiveMix.mixProgress(plan, 104_000))
        assertEquals(1f, LiveMix.mixProgress(plan, 120_000))
    }
}
