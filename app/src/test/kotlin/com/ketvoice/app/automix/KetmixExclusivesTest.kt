package com.ketvoice.app.automix

import com.ketvoice.app.data.MusicApi
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Ketmix 6.9 exclusives on made-up analyses: « fondu sur les paroles » ([LyricTimes]), the « boucle » blend
 * ([LoopBlend], [LoopFx]) and variety from one transition to the next ([MixVariety]).
 */
class KetmixExclusivesTest {
    private val duration = 200_000L
    private fun outro(bpm: Float = 120f, fall: Long = 185_000, end: Long = 199_000, fade: Boolean = false, hard: Boolean = false,
                      bar: Int = -1, confidence: Float = .9f, key: Int = MusicalKey.UNREAD) =
        SegmentAnalysis(155_000, 45_000, bpm, 155_000.0, confidence, bar, -14f, 155_000, end, 155_000,
            if (hard) end else fall, fade, hard, key, if (key >= 0) .8f else 0f)
    private fun intro(bpm: Float = 120f, bar: Int = -1, key: Int = MusicalKey.UNREAD) =
        SegmentAnalysis(0, 40_000, bpm, 500.0, .9f, bar, -14f, 300, 40_000, 8_000, 40_000, key = key, keyStrength = if (key >= 0) .8f else 0f)
    private fun plan(a: SegmentAnalysis, b: SegmentAnalysis, sungUntil: Long? = null, sungFrom: Long? = null,
                     outLines: List<LongRange> = emptyList(), inLines: List<LongRange> = emptyList(),
                     context: MixContext = MixContext(), settings: MixSettings = MixSettings(1)) =
        TransitionPlanner.plan(MixFacts(duration, a, vocalEndMs = sungUntil, sungLines = outLines),
            MixFacts(duration, b, vocalStartMs = sungFrom, sungLines = inLines), context, settings)

    // « Fondu sur les paroles »

    @Test fun linesEndAtTheirWordsAtARestOrAtTheNextLine() {
        val lines = listOf(MusicApi.LyricLine(-1, "untimed"), MusicApi.LyricLine(10_000, "first line of it"),
            MusicApi.LyricLine(12_000, "second words"), MusicApi.LyricLine(30_000, "held to a rest"), MusicApi.LyricLine(39_500, "♪"),
            MusicApi.LyricLine(50_000, "timed", listOf(MusicApi.LyricWord(50_000, "ti"), MusicApi.LyricWord(50_400, "med", 51_200))),
            MusicApi.LyricLine(60_000, "the last one"))
        assertEquals(listOf(10_000L..12_000L, 12_000L..14_140L, 30_000L..39_500L, 50_000L..51_200L, 60_000L..62_460L),
            LyricTimes.lines(lines, duration))
        assertEquals("a held last line ends at the rest that follows it, not at a guess", 39_500L,
            Vocals.endMs(lines.subList(0, 5), duration))
        assertEquals(listOf(1L..5L), LyricTimes.merge(emptyList(), listOf(1L..5L)))
        assertEquals(listOf(2L..3L), LyricTimes.merge(listOf(2L..3L), listOf(1L..5L)))
    }

    @Test fun theOutgoingVoiceStepsBackBetweenLinesNeverHalfwayIntoOne() {
        val bare = plan(outro(120f), intro(123f), sungUntil = 170_000)
        assertEquals(MixKind.Beatmatch, bare.kind)
        assertTrue("the blend starts under the last sung words (${bare.outMs})", bare.outMs < 170_000)
        // The last line is sung across the start of the blend.
        val lines = listOf(150_000L..160_000L, bare.outMs - 1_500L..170_000L)
        val sung = plan(outro(120f), intro(123f), sungUntil = 170_000, outLines = lines)
        assertEquals(bare.outMs, sung.outMs)
        assertEquals("from the end of that line", 170_000L, sung.voiceDownMs)
        val cut = DeckAutomation.outgoing(sung).vocalCut
        assertEquals("the line is sung whole", 0f, cut.at(169_900_000L), 1e-6f)
        assertEquals("before it, the plan without lines stepped it back already", true,
            DeckAutomation.outgoing(bare).vocalCut.at(169_900_000L) > .3f)
        // Between two lines it steps back at once.
        assertEquals(165_500L, LyricTimes.voiceDownFrom(listOf(150_000L..165_000L, 166_000L..170_000L), 165_500L))
        assertEquals("a line just begun is not cut into either", 166_050L, LyricTimes.voiceDownFrom(listOf(166_000L..170_000L), 166_050L))
    }

    @Test fun noExitOnTheBeatCutsASungLine() {
        val lines = listOf(160_000L..176_000L, 177_000L..181_400L)
        val sungUntil = Vocals.endMs(listOf(MusicApi.LyricLine(160_000, "a"), MusicApi.LyricLine(177_000, "b c"),
            MusicApi.LyricLine(181_400, "")), duration)
        assertEquals(181_400L, sungUntil)
        for (style in listOf(MixStyle.Echo, MixStyle.Rise)) {
            val p = plan(outro(120f, bar = 0), intro(120f, bar = 0), sungUntil = sungUntil, outLines = lines, settings = MixSettings(1, style))
            assertTrue("$style mixes", p.mixes)
            assertTrue("$style leaves after the last line (${p.outMs})", p.outMs >= 181_400L)
            assertNull(LyricTimes.inside(lines, p.outMs))
        }
    }

    @Test fun anEntryNeverCutsIntoTheFirstLine() {
        // The incoming title sings from 0.3 s: its first beat (0.5 s) falls 200 ms into that line.
        val inLines = listOf(300L..4_000L, 4_500L..8_000L)
        val bare = plan(outro(120f), intro(120f), sungUntil = 150_000)
        val sung = plan(outro(120f), intro(120f), sungUntil = 150_000, sungFrom = 300, inLines = inLines)
        assertEquals(MixKind.Beatmatch, sung.kind)
        assertEquals("just before the line", 300L - LyricTimes.ENTRY_LEAD_MS, sung.inMs)
        assertNull(LyricTimes.inside(inLines, sung.inMs))
        assertEquals("both titles stay in time: the outgoing position moves with it", bare.outMs - bare.inMs.toDouble(),
            sung.outMs - sung.inMs / sung.rate.toDouble(), 1.0)
        assertTrue(sung.outMs + sung.overlapMs <= duration)
        // A line that starts long before the entry is not gone back to; nor is an entry put under the outgoing voice.
        val p = MixPlan(MixKind.Filter, outMs = 170_000, inMs = 500, overlapMs = 8_000)
        assertEquals(5_000L, LyricTimes.entry(p.copy(inMs = 5_000), listOf(1_000L..9_000L), null).inMs)
        assertEquals(500L, LyricTimes.entry(p, inLines, sungUntilMs = 170_100).inMs)
        assertEquals(180L, LyricTimes.entry(p, inLines, sungUntilMs = 169_000).inMs)
        assertEquals("a drop stays on its downbeat", 500L, LyricTimes.entry(p.copy(kind = MixKind.Rise), inLines, null).inMs)
    }

    @Test fun twoSungLinesAreNeverHeardOverEachOther() {
        val outLines = listOf(150_000L..165_000L, 166_000L..171_000L)
        for (sungFrom in listOf(300L, 2_000L, 6_000L, 20_000L)) for (bpm in listOf(123f, 150f)) for (fade in listOf(false, true)) {
            val inLines = listOf(sungFrom..sungFrom + 3_000L, sungFrom + 3_500L..sungFrom + 9_000L)
            val p = plan(outro(120f, fade = fade, fall = if (fade) 187_000 else 185_000), intro(bpm), sungUntil = 171_000, sungFrom = sungFrom,
                outLines = outLines, inLines = inLines)
            assertTrue("$sungFrom $bpm $fade mixes", p.mixes)
            assertFalse("$sungFrom $bpm $fade: ${p.kind} at ${p.outMs}", LyricTimes.voicesMeet(p, outLines, inLines))
        }
        val clash = MixPlan(MixKind.Filter, outMs = 160_000, inMs = 0, overlapMs = 10_000)
        assertTrue(LyricTimes.voicesMeet(clash, outLines, listOf(1_000L..3_000L)))
    }

    // « Boucle »

    private val fading = outro(120f, fall = 187_000, fade = true, bar = 0)

    @Test fun aFadeOutWithACleanConfidentBarIsLoopedUnderTheBlend() {
        val p = plan(fading, intro(120f, bar = 0), sungUntil = 180_000, sungFrom = 20_000)
        assertEquals(MixKind.Beatmatch, p.kind)
        assertTrue("a loop (${p.reason})", p.loopUs > 0)
        assertEquals("calé sur une boucle", MixWords.label(p))
        val from = p.loopFromUs / 1000.0
        assertEquals("the last 8-beat loop on a first beat before the fade", 183_000.0, from, 1e-3)
        assertEquals(4_000_000L, p.loopUs)
        assertTrue("after the last sung words", from >= 180_300)
        assertTrue("before the fade", p.loopFromUs + p.loopUs <= 187_000_000L)
        assertEquals(183_000L, p.outMs)
        assertEquals("downbeats meet", 0, BlendFit.barIndex(intro(120f, bar = 0), p.inMs.toDouble()))
        assertTrue("16 beats or more", p.overlapMs >= 8_000)
        assertTrue(p.outMs + p.overlapMs <= duration)
        assertEquals(-1L, p.sungUntilMs)
        val loop = p.barLoop()!!
        assertEquals((p.outMs + p.overlapMs) * 1000L, loop.untilUs)
        assertNull("no loop for a plain blend", MixPlan(MixKind.Beatmatch).barLoop())
    }

    @Test fun aLoopIsOnlyPlayedWhereItCannotGoWrong() {
        val b = intro(120f, bar = 0)
        fun loops(a: SegmentAnalysis = fading, sungUntil: Long? = 180_000, context: MixContext = MixContext(),
                  settings: MixSettings = MixSettings(1), into: SegmentAnalysis = b) = plan(a, into, sungUntil, 20_000, context = context, settings = settings).loopUs > 0
        assertTrue(loops())
        assertFalse("beats not sure enough", loops(outro(120f, fall = 187_000, fade = true, bar = 0, confidence = .6f)))
        assertFalse("bars not told apart", loops(outro(120f, fall = 187_000, fade = true)))
        assertFalse("voice unknown: it could be a sung bar", loops(sungUntil = null))
        assertFalse("sung into the fade", loops(sungUntil = 185_500))
        assertTrue("one clean bar before the fade is enough for a 4-beat loop", loops(sungUntil = 184_000))
        assertFalse("not a fade-out", loops(outro(120f, bar = 0)))
        assertFalse("never twice running", loops(context = MixContext(loopedLast = true)))
        assertFalse("not with the discreet setting", loops(settings = MixSettings(0)))
        assertFalse("not with keys that clash", loops(outro(120f, fall = 187_000, fade = true, bar = 0, key = 0), into = intro(120f, bar = 0, key = 6)))
        assertFalse("not a title on repeat", loops(context = MixContext(loop = true)))
        assertFalse("not out of time", loops(into = intro(150f, bar = 0)))
        // A shorter window still takes a 4-beat loop.
        val short = LoopBlend.bar(fading, 183_100, 4)!!
        assertEquals(185_000.0, short.first, 1e-6); assertEquals(2_000.0, short.second, 1e-6)
        assertNull(LoopBlend.bar(fading, 183_100, 8))
    }

    private fun ramp(frame: Long) = ((frame * 7) % 30_000 - 15_000).toShort()

    private fun run(fx: LoopFx, rate: Int, channels: Int, loop: BarLoop, fromFrame: Long, toFrame: Long, chunk: Int = 1_237): ShortArray {
        val out = ShortArray(((toFrame - fromFrame) * channels).toInt())
        var f = fromFrame
        while (f < toFrame) {
            val n = minOf(chunk.toLong(), toFrame - f).toInt()
            val input = ByteBuffer.allocateDirect(n * 2 * channels).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) for (c in 0 until channels) input.putShort(ramp(f + i))
            input.flip()
            // Positions arrive in µs, truncated, as the processor counts them.
            val result = fx.process(input, f * 1_000_000L / rate, loop).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n * channels) out[((f - fromFrame) * channels + i).toInt()] = result.getShort(result.position() + 2 * i)
            assertFalse(input.hasRemaining() && result !== input)
            f += n
        }
        return out
    }

    @Test fun theLoopIsPlayedSampleAccuratelyOnTheGrid() {
        val rate = 44_100; val channels = 2
        // 8 beats at 127 BPM: 3.779527… s, not a whole number of frames.
        val lengthUs = (8 * 60_000_000.0 / 127).roundToLong()
        val loop = BarLoop(fromUs = 1_000_000L, lengthUs = lengthUs, untilUs = 20_000_000L)
        val fx = LoopFx(rate, channels)
        val total = 21L * rate
        val out = run(fx, rate, channels, loop, 0L, total)
        val from = 44_100L
        val length = lengthUs * rate / 1_000_000.0
        val fade = rate * 4 / 1000
        fun at(f: Long) = out[(f * channels).toInt()]
        // Before the first repeat the title plays as it is.
        for (f in 0 until from + length.roundToLong()) assertEquals("frame $f", ramp(f), at(f))
        // Each repeat starts on the frame nearest its exact place on the grid, and plays the bar from its first frame.
        for (j in 1..4) {
            val start = from + (j * length).roundToLong()
            val next = from + ((j + 1) * length).roundToLong()
            assertTrue("repeat $j is on the grid", abs(start - (from + j * length)) <= .5)
            for (f in start + fade until minOf(next, 20L * rate)) assertEquals("repeat $j frame $f", ramp(from + (f - start)), at(f))
            for (c in 0 until channels) assertEquals(at(start + fade + 3), out[((start + fade + 3) * channels + c).toInt()])
            // The seam is a crossfade between what came next and the bar's start: within their range, never a jump to anything else.
            for (k in 0 until fade) {
                val v = at(start + k).toInt()
                val prev = (start - (from + ((j - 1) * length).roundToLong()))
                val a = ramp(from + k).toInt(); val b = ramp(from + prev + k).toInt()
                assertTrue("seam $j frame $k", abs(v) <= 1.42 * maxOf(abs(a), abs(b)) + 1)
            }
            assertEquals("the seam ends on the bar itself", ramp(from + fade - 1).toDouble(), at(start + fade - 1).toDouble(), 600.0)
        }
        // Released: the title's own sound again.
        for (f in 20L * rate + fade until total) assertEquals(ramp(f), at(f))
    }

    @Test fun aBarNotHeardWholeIsNotLooped() {
        val rate = 48_000
        val loop = BarLoop(1_000_000L, 2_000_000L, 10_000_000L)
        val fx = LoopFx(rate, 1)
        // A seek lands in the middle of the bar: what is played is the title itself.
        val out = run(fx, rate, 1, loop, (1.5 * rate).toLong(), 6L * rate)
        for (i in out.indices) assertEquals(ramp((1.5 * rate).toLong() + i), out[i])
        // Without a loop, nothing is touched and the buffer is the input itself.
        val input = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        assertSame(input, LoopFx(rate, 1).process(input, 0L, null))
    }

    // Variety

    @Test fun aThirdBlendRunningGivesWayToAnExitOnTheBeat() {
        assertFalse(MixVariety.tired(emptyList(), MixKind.Beatmatch))
        assertFalse(MixVariety.tired(listOf(MixKind.Beatmatch), MixKind.Beatmatch))
        assertFalse(MixVariety.tired(listOf(MixKind.Beatmatch, MixKind.Echo), MixKind.Beatmatch))
        assertTrue(MixVariety.tired(listOf(MixKind.Echo, MixKind.Beatmatch, MixKind.Beatmatch), MixKind.Beatmatch))
        var recent = emptyList<MixKind>()
        repeat(5) { recent = MixVariety.remember(recent, MixPlan(MixKind.Beatmatch)) }
        assertEquals(MixVariety.REMEMBERED, recent.size)
        assertEquals("a pair not mixed is not counted", recent, MixVariety.remember(recent, MixPlan(MixKind.None)))

        val a = outro(120f, bar = 0); val b = intro(123f, bar = 0)
        assertEquals(MixKind.Beatmatch, plan(a, b, 170_000).kind)
        assertEquals(MixKind.Beatmatch, plan(a, b, 170_000, context = MixContext(recentKinds = listOf(MixKind.Beatmatch))).kind)
        val varied = plan(a, b, 170_000, context = MixContext(recentKinds = listOf(MixKind.Beatmatch, MixKind.Beatmatch)))
        assertTrue("an echo or a build takes a turn: ${varied.kind}", varied.kind == MixKind.Echo || varied.kind == MixKind.Rise)
        assertTrue("still after the last sung words", varied.outMs >= 170_300)
        // Only a tie-breaker: the style the listener chose stands, and without another way out the blend stays.
        assertEquals(MixKind.Beatmatch, plan(a, b, 170_000, context = MixContext(recentKinds = listOf(MixKind.Beatmatch, MixKind.Beatmatch)),
            settings = MixSettings(1, MixStyle.Tempo)).kind)
        assertEquals(MixKind.Beatmatch, plan(outro(120f, fall = 187_000, fade = true), b, 170_000,
            context = MixContext(recentKinds = listOf(MixKind.Beatmatch, MixKind.Beatmatch))).kind)
    }
}
