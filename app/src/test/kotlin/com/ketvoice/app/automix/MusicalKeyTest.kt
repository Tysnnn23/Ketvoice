package com.ketvoice.app.automix

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class MusicalKeyTest {
    private val rate = 11_025

    /** Chords (MIDI notes) of one second each, played over and over, every note with two overtones. */
    private fun chords(progression: List<IntArray>, seconds: Int): FloatArray {
        val out = FloatArray(rate * seconds)
        for (i in out.indices) {
            val chord = progression[(i / rate) % progression.size]
            val t = i.toDouble() / rate
            var v = 0.0
            for (note in chord) {
                val hz = 440.0 * 2.0.pow((note - 69) / 12.0)
                v += sin(2 * PI * hz * t) + .5 * sin(2 * PI * 2 * hz * t) + .3 * sin(2 * PI * 3 * hz * t)
            }
            out[i] = (v * .08).toFloat()
        }
        return out
    }
    private fun keyOf(samples: FloatArray): Pair<Int, Float> {
        val tracker = ChromaTracker(rate.toFloat())
        for (v in samples) tracker.sample(v)
        return MusicalKey.estimate(tracker.snapshot())
    }
    private fun shifted(progression: List<IntArray>, semitones: Int) = progression.map { chord -> IntArray(chord.size) { chord[it] + semitones } }

    // C, F, G, C and A minor, D minor, E, A minor: the plainest way to say "C major" and "A minor".
    private val major = listOf(intArrayOf(48, 52, 55, 60), intArrayOf(53, 57, 60), intArrayOf(55, 59, 62), intArrayOf(48, 52, 55, 60))
    private val minor = listOf(intArrayOf(45, 48, 52, 57), intArrayOf(50, 53, 57), intArrayOf(52, 56, 59), intArrayOf(45, 48, 52, 57))

    @Test fun theKeyOfPlainChordsIsFound() {
        val (c, strength) = keyOf(chords(major, 24))
        assertEquals("C major", 0, c)
        assertTrue("clearly ($strength)", strength >= .7f)
        assertEquals("8B", MusicalKey.code(c))
        assertEquals("A minor", 12 + 9, keyOf(chords(minor, 24)).first)
        // The same music a tone higher, or a fourth lower, is found there.
        assertEquals("D major", 2, keyOf(chords(shifted(major, 2), 24)).first)
        assertEquals("G major", 7, keyOf(chords(shifted(major, -5), 24)).first)
        assertEquals("F sharp minor", 12 + 6, keyOf(chords(shifted(minor, -3), 24)).first)
    }

    @Test fun musicWithoutNotesHasNoKey() {
        val random = java.util.Random(4)
        val noise = FloatArray(rate * 20) { (random.nextFloat() * 2 - 1) * .3f }
        assertEquals(MusicalKey.NONE, keyOf(noise).first)
        assertEquals("Too little to tell", MusicalKey.NONE, keyOf(chords(major, 3)).first)
        assertEquals(MusicalKey.NONE, MusicalKey.estimate(null).first)
        assertEquals(MusicalKey.NONE, keyOf(FloatArray(rate * 20)).first)
    }

    @Test fun theWheelSaysWhichKeysGoTogether() {
        // The twelve major keys and their relative minors sit where DJs expect them.
        val majors = mapOf(0 to "8B", 7 to "9B", 2 to "10B", 9 to "11B", 4 to "12B", 11 to "1B", 6 to "2B", 1 to "3B", 8 to "4B", 3 to "5B", 10 to "6B", 5 to "7B")
        for ((key, code) in majors) assertEquals(code, MusicalKey.code(key))
        val minors = mapOf(9 to "8A", 4 to "9A", 11 to "10A", 6 to "11A", 1 to "12A", 8 to "1A", 3 to "2A", 10 to "3A", 5 to "4A", 0 to "5A", 7 to "6A", 2 to "7A")
        for ((tonic, code) in minors) assertEquals(code, MusicalKey.code(12 + tonic))
        assertEquals("", MusicalKey.code(MusicalKey.NONE)); assertEquals("", MusicalKey.code(MusicalKey.UNREAD))

        assertEquals(MusicalKey.Relation.Same, MusicalKey.relation(0, 0))
        assertEquals("C and G", MusicalKey.Relation.Close, MusicalKey.relation(0, 7))
        assertEquals("C and F", MusicalKey.Relation.Close, MusicalKey.relation(0, 5))
        assertEquals("C and A minor", MusicalKey.Relation.Close, MusicalKey.relation(0, 12 + 9))
        assertEquals("B and F sharp, across the wheel's seam", MusicalKey.Relation.Close, MusicalKey.relation(11, 6))
        assertEquals("C and F sharp", MusicalKey.Relation.Clash, MusicalKey.relation(0, 6))
        assertEquals("C and D", MusicalKey.Relation.Clash, MusicalKey.relation(0, 2))
        assertEquals("C and E minor: a neighbour in the other mode is not counted", MusicalKey.Relation.Clash, MusicalKey.relation(0, 12 + 4))
        assertEquals(MusicalKey.Relation.Unknown, MusicalKey.relation(MusicalKey.NONE, 3))
        assertEquals(MusicalKey.Relation.Unknown, MusicalKey.relation(3, MusicalKey.UNREAD))
        for (a in 0..23) for (b in 0..23) assertEquals(MusicalKey.relation(a, b), MusicalKey.relation(b, a))
    }

    @Test fun theAnalysisReadsTheKeyOnlyWhenAskedTo() {
        val samples = chords(major, 24)
        val without = BeatAnalysis.analyze(OnsetTracker(44_100, 1).apply { acceptMono(FloatArray(samples.size * 4) { samples[it / 4] }) }.snapshot(), 0)
        assertEquals(MusicalKey.UNREAD, without.key)
        val with = BeatAnalysis.analyze(OnsetTracker(44_100, 1, withKey = true).apply { acceptMono(FloatArray(samples.size * 4) { samples[it / 4] }) }.snapshot(), 0)
        assertEquals(0, with.key)
        assertTrue(with.keyStrength >= .6f)
        // Kept and found again.
        val kept = AnalysisStore.decode(JSONObject(AnalysisStore.encode(with).toString()))!!
        assertEquals(with.key, kept.key); assertEquals(with.keyStrength, kept.keyStrength, 1e-4f)
        // What an earlier version kept has no key: it reads as "not looked for".
        val old = AnalysisStore.encode(with).apply { remove("k"); remove("ks") }
        assertEquals(MusicalKey.UNREAD, AnalysisStore.decode(old)!!.key)
    }

    @Test fun eachAnalysisSettingAsksForMore() {
        assertEquals(AnalysisDepth.Light, AnalysisDepth.of(0)); assertEquals(AnalysisDepth.Balanced, AnalysisDepth.of(1))
        assertEquals(AnalysisDepth.Maximum, AnalysisDepth.of(2)); assertEquals(AnalysisDepth.Balanced, AnalysisDepth.of(7))
        assertTrue(AnalysisDepth.Light.outroMs < AnalysisDepth.Balanced.outroMs && AnalysisDepth.Balanced.outroMs < AnalysisDepth.Maximum.outroMs)
        assertTrue(AnalysisDepth.Light.introMs < AnalysisDepth.Balanced.introMs && AnalysisDepth.Balanced.introMs < AnalysisDepth.Maximum.introMs)
        assertFalse(AnalysisDepth.Light.key); assertTrue(AnalysisDepth.Balanced.key); assertTrue(AnalysisDepth.Maximum.thorough)
        val light = SegmentAnalysis(170_000, 30_000)
        val balanced = SegmentAnalysis(155_000, 45_000, key = 3)
        val maximum = SegmentAnalysis(125_000, 75_000, key = MusicalKey.NONE)
        // A stretch read by a lighter setting is not enough for a finer one; the reverse is.
        assertTrue(AnalysisDepth.Light.satisfiedBy(light, AnalysisDepth.Light.outroMs, 200_000))
        assertFalse(AnalysisDepth.Balanced.satisfiedBy(light, AnalysisDepth.Balanced.outroMs, 200_000))
        assertTrue(AnalysisDepth.Balanced.satisfiedBy(balanced, AnalysisDepth.Balanced.outroMs, 200_000))
        assertFalse(AnalysisDepth.Maximum.satisfiedBy(balanced, AnalysisDepth.Maximum.outroMs, 200_000))
        assertTrue("no clear key was still a reading", AnalysisDepth.Maximum.satisfiedBy(maximum, AnalysisDepth.Maximum.outroMs, 200_000))
        assertTrue(AnalysisDepth.Light.satisfiedBy(maximum, AnalysisDepth.Light.outroMs, 200_000))
        // A title shorter than the stretch asked for was read whole.
        assertTrue(AnalysisDepth.Maximum.satisfiedBy(SegmentAnalysis(0, 50_000, key = 1), AnalysisDepth.Maximum.outroMs, 50_000))
    }
}
