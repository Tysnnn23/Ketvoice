package com.ketvoice.app.automix

import com.ketvoice.app.data.MusicApi
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TransitionPlannerTest {
    private val duration = 200_000L
    /** Last 45 s of a 200 s title at [bpm], beats on the second, with an outro from [fall]. */
    private fun outro(bpm: Float = 120f, fall: Long = 185_000, end: Long = 199_000, fade: Boolean = false, hard: Boolean = false,
                      confidence: Float = .9f, bar: Int = -1, loudness: Float = -14f) =
        SegmentAnalysis(155_000, 45_000, bpm, 155_000.0, confidence, bar, loudness, 155_000, end, 155_000,
            if (hard) end else fall, fade, hard)
    private fun intro(bpm: Float = 120f, start: Long = 300, rise: Long = 8_000, confidence: Float = .9f, bar: Int = -1,
                      loudness: Float = -14f) =
        SegmentAnalysis(0, 40_000, bpm, 500.0, confidence, bar, loudness, start, 40_000, rise, 40_000)
    private fun plan(a: SegmentAnalysis?, b: SegmentAnalysis?, context: MixContext = MixContext(), intensity: Int = 1,
                     sungUntil: Long? = null, sungFrom: Long? = null, outDuration: Long = duration, inDuration: Long = duration) =
        TransitionPlanner.plan(MixFacts(outDuration, a, vocalEndMs = sungUntil), MixFacts(inDuration, b, vocalStartMs = sungFrom),
            context, MixSettings(intensity))
    private fun onBeat(position: Long, origin: Double, bpm: Float): Boolean {
        val beats = (position - origin) / (60_000.0 / bpm)
        return abs(beats - Math.round(beats)) * (60_000.0 / bpm) < 2.0
    }

    @Test fun someListeningIsNeverMixed() {
        assertEquals("Albums are left alone only when the listener says so", MixKind.None,
            plan(outro(), intro(), MixContext(sameAlbumInOrder = true, albumsMixed = false)).kind)
        assertEquals("...and mixed by default", MixKind.Beatmatch, plan(outro(), intro(), MixContext(sameAlbumInOrder = true), sungUntil = 170_000).kind)
        assertEquals(MixKind.None, plan(outro(), intro(), MixContext(repeatOne = true)).kind)
        assertEquals(MixKind.None, plan(outro(), intro(), MixContext(stopAfterCurrent = true)).kind)
        assertEquals(MixKind.None, plan(outro(), intro(), MixContext(exactAudio = true)).kind)
        assertEquals("An interlude is left alone", MixKind.None, plan(outro(), intro(), inDuration = 25_000).kind)
        assertEquals(MixKind.None, plan(outro(), intro(), outDuration = 20_000).kind)
        assertFalse(plan(outro(), intro(), MixContext(repeatOne = true)).mixes)
    }

    @Test fun closeTemposAreMixedOnTheBeat() {
        val plan = plan(outro(120f), intro(124f), sungUntil = 170_000)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertEquals("The incoming title follows the outgoing tempo", 120f / 124f, plan.rate, 1e-4f)
        assertEquals("32 beats at 120 BPM", 16_000L, plan.overlapMs)
        assertTrue("The mix starts on a beat of the outgoing title", onBeat(plan.outMs, 155_000.0, 120f))
        assertTrue("The incoming title enters on its first beat", onBeat(plan.inMs, 500.0, 124f))
        assertEquals(500L, plan.inMs)
        assertTrue("Never before the singing is over", plan.outMs >= 170_300)
        assertTrue("Both are heard until the outgoing title really ends", plan.outMs + plan.overlapMs <= 199_000)
        assertTrue("The bass changes hands on a beat in the middle", onBeat(plan.swapMs, 155_000.0, 120f) &&
            abs(plan.swapMs - (plan.outMs + plan.overlapMs / 2)) <= 500)
        assertEquals(plan.inMs + (16_000 * plan.rate).toLong().toDouble(), plan.inEndMs.toDouble(), 2.0)
    }

    @Test fun aTempoReadTwiceAsFastIsTheSamePulse() {
        val plan = plan(outro(140f), intro(70f), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertEquals(1f, plan.rate, 1e-4f)
        // 140 BPM is a bar pulse already; 32 beats of it.
        assertEquals((32 * 60_000f / 140f).toLong().toDouble(), plan.overlapMs.toDouble(), 2.0)
        val fast = plan(outro(174f), intro(87f), sungUntil = 160_000)
        assertEquals("174 BPM is counted on its 87 BPM pulse", (32 * 60_000f / 87f).toLong().toDouble(), fast.overlapMs.toDouble(), 2.0)
    }

    @Test fun tempoRangeFollowsTheIntensity() {
        assertEquals("7 % apart: balanced mixes it", MixKind.Beatmatch, plan(outro(120f), intro(128.4f), sungUntil = 170_000).kind)
        assertEquals("discreet does not", MixKind.Crossfade, plan(outro(120f), intro(128.4f), intensity = 0, sungUntil = 170_000).kind)
        assertEquals("11 % apart: only DJ", MixKind.Crossfade, plan(outro(120f), intro(133.5f), sungUntil = 170_000).kind)
        assertEquals(MixKind.Beatmatch, plan(outro(120f), intro(133.5f), intensity = 2, sungUntil = 170_000).kind)
        assertEquals("20 % apart: nobody", MixKind.Crossfade, plan(outro(120f), intro(150f), intensity = 2, sungUntil = 170_000).kind)
    }

    @Test fun theOverlapShrinksToTheRoomLeftAfterTheSinging() {
        // Singing until 12 s before the end: 32 beats (16 s) do not fit, 16 beats (8 s) do.
        val plan = plan(outro(120f), intro(120f), sungUntil = 187_000)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertEquals(8_000L, plan.overlapMs)
        assertTrue(plan.outMs >= 187_300)
        // Singing until the very end and a soft ending: a short crossfade, never a beat mix over the voice.
        val late = plan(outro(120f), intro(120f), sungUntil = 198_000)
        assertEquals(MixKind.Crossfade, late.kind)
        assertEquals(2_500L, late.overlapMs)
    }

    @Test fun howMuchOfAnInstrumentalEndingIsSkippedDependsOnTheIntensity() {
        val discreet = plan(outro(120f), intro(120f), intensity = 0, sungUntil = 160_000)
        val balanced = plan(outro(120f), intro(120f), intensity = 1, sungUntil = 160_000)
        val dj = plan(outro(120f), intro(120f), intensity = 2, sungUntil = 160_000)
        assertEquals("Discreet plays the ending to its last bar", 199_000L, discreet.outMs + discreet.overlapMs)
        assertEquals("16 beats at most when discreet", 8_000L, discreet.overlapMs)
        assertTrue("Balanced skips up to 25 s of an instrumental ending", balanced.outMs + balanced.overlapMs in 173_000..176_500)
        assertTrue("DJ mixes as soon as the singing is over", dj.outMs in 160_300..161_000)
        assertEquals("DJ mixes over 64 beats", 32_000L, dj.overlapMs)
    }

    @Test fun withoutLyricsTheMixWaitsForTheOutro() {
        val plan = plan(outro(120f, fall = 181_000), intro(120f), intensity = 2)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertTrue("From where the level drops (${plan.outMs})", plan.outMs in 181_000..181_600)
        // An incoming title that sings at once is held back to a shorter overlap, then to a crossfade.
        val early = plan(outro(120f, fall = 181_000), intro(120f), sungFrom = 5_000)
        assertEquals(MixKind.Beatmatch, early.kind)
        assertEquals("8 beats: the voice arrives when the other title is nearly gone", 4_000L, early.overlapMs)
        assertEquals(MixKind.Crossfade, plan(outro(120f, fall = 181_000), intro(120f), sungFrom = 1_200).kind)
    }

    @Test fun aLastNoteAtFullLevelIsNeverCovered() {
        val plan = plan(outro(120f, hard = true), intro(150f))
        assertEquals(MixKind.Trim, plan.kind)
        assertTrue(plan.overlapMs <= 300)
        assertTrue("The next title starts as the last note ends", abs(plan.outMs + plan.overlapMs / 2 - 199_000) <= 10)
        assertEquals(260L, plan.inMs)
        // Same tempo but singing until a hard ending: still no mix over it.
        assertEquals(MixKind.Trim, plan(outro(120f, hard = true), intro(120f), sungUntil = 198_500).kind)
    }

    @Test fun aFadeOutIsCrossedWhileItIsStillHeard() {
        val plan = plan(outro(0f, fall = 187_000, end = 199_000, fade = true, confidence = 0f), intro(0f, confidence = 0f))
        assertEquals(MixKind.Crossfade, plan.kind)
        assertEquals("The incoming title is fully in before the last, silent quarter of the fade", 196_000L, plan.outMs + plan.overlapMs)
        assertEquals("The fade is only 12 s long: the crossfade keeps to it", 8_000L, plan.overlapMs)
        assertEquals(300L, plan.inMs)
        assertEquals("no steady beat", plan.reason)
    }

    @Test fun twoVoicesDoNotOverlapForLong() {
        val calm = plan(outro(0f, confidence = 0f), intro(0f, confidence = 0f), intensity = 2, sungUntil = 180_000, sungFrom = 600)
        assertEquals("The ending is instrumental: the long fade stays", 12_000L, calm.overlapMs)
        val clash = plan(outro(0f, confidence = 0f), intro(0f, confidence = 0f), intensity = 2, sungUntil = 198_000, sungFrom = 600)
        assertEquals(2_500L, clash.overlapMs)
    }

    @Test fun aLouderIncomingTitleIsHeldBackNotBoosted() {
        assertEquals(.5f, plan(outro(loudness = -20f), intro(loudness = -8f), sungUntil = 170_000).gain, 1e-3f)
        assertEquals(1f, plan(outro(loudness = -8f), intro(loudness = -20f), sungUntil = 170_000).gain, 0f)
        assertEquals(.708f, plan(outro(loudness = -14f), intro(loudness = -11f), sungUntil = 170_000).gain, 2e-3f)
    }

    @Test fun barsMeetWhenBothAreKnown() {
        // Outgoing bars start on beats 2, 6, 10… of its grid; the incoming title enters on the third beat of a bar.
        val plan = plan(outro(120f, bar = 2), intro(120f, bar = 2), sungUntil = 170_000)
        val outBeat = Math.round((plan.outMs - 155_000.0) / 500.0)
        assertEquals("Outgoing beat is the same beat of its bar as the incoming one", ((0 - 2) % 4 + 4) % 4, (((outBeat - 2) % 4) + 4).toInt() % 4)
    }

    @Test fun withoutAnalysisAShortCrossfadeStillWorks() {
        val plan = plan(null, null)
        assertEquals(MixKind.Crossfade, plan.kind)
        assertEquals(5_333L, plan.overlapMs)
        assertEquals(0L, plan.inMs)
        assertEquals(duration - 250, plan.outMs + plan.overlapMs)
        assertEquals(MixKind.Crossfade, plan(outro(), null).kind)
    }

    @Test fun skipSilenceKeepsToCrossfades() {
        assertEquals(MixKind.Crossfade, plan(outro(120f), intro(120f), MixContext(skipSilence = true), sungUntil = 170_000).kind)
    }

    @Test fun singingIsReadFromSynchronisedLyrics() {
        val lines = listOf(MusicApi.LyricLine(-1, "Untimed"), MusicApi.LyricLine(4_000, "♪"), MusicApi.LyricLine(12_000, "First words"),
            MusicApi.LyricLine(150_000, "the very last line here", listOf(MusicApi.LyricWord(150_000, "the"), MusicApi.LyricWord(152_400, "here", 153_100))),
            MusicApi.LyricLine(160_000, "…"))
        assertEquals(12_000L, Vocals.startMs(lines))
        assertEquals(153_100L, Vocals.endMs(lines, 200_000))
        val plain = listOf(MusicApi.LyricLine(100_000, "four words in it"))
        assertEquals(100_000L + 1_500 + 4 * 320, Vocals.endMs(plain, 200_000))
        assertEquals("Never past the end", 101_000L, Vocals.endMs(plain, 101_000))
        assertNull(Vocals.startMs(listOf(MusicApi.LyricLine(-1, "plain text"))))
        assertNull(Vocals.endMs(emptyList(), 1))
    }

    @Test fun anAlbumWithNoGapBetweenItsTitlesIsLeftAlone() {
        val abrupt = outro(120f, hard = true, end = 199_900)
        val immediate = intro(120f, start = 20, rise = 200)
        val joined = plan(abrupt, immediate, MixContext(sameAlbumInOrder = true))
        assertEquals(MixKind.None, joined.kind); assertEquals("gapless album", joined.reason)
        // The same two titles in a playlist, not as neighbours of an album, are mixed as any others.
        assertNotEquals(MixKind.None, plan(abrupt, immediate, MixContext(sameAlbumInOrder = false), sungUntil = 190_000).kind)
        // An album whose titles are separated by a little silence is mixed.
        assertNotEquals(MixKind.None, plan(outro(120f, hard = true, end = 197_000), intro(), MixContext(sameAlbumInOrder = true)).kind)
    }

    @Test fun withoutLyricsALongMixNeverStartsBeforeTheOutro() {
        // 64 beats (32 s) would start in the body of the title: the planner goes down to a count that fits the outro.
        val plan = plan(outro(120f, fall = 181_000), intro(120f), intensity = 2)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertTrue("starts in the outro (${plan.outMs})", plan.outMs >= 181_000)
        assertEquals(16_000L, plan.overlapMs)
    }
}
