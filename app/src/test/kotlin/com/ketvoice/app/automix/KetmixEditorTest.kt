package com.ketvoice.app.automix

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The Ketmix DJ editor (6.1): waves, snapping, remembered points and the planner honouring them. */
class KetmixEditorTest {
    private val rate = 44_100

    /** [seconds] of a stereo sine of [hz] at [amplitude], as 16-bit interleaved samples. */
    private fun sine(hz: Double, seconds: Double, amplitude: Double = .5): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i -> (sin(2 * PI * hz * (i / 2) / rate) * amplitude * 32767).toInt().toShort() }
    }

    private fun bytes(samples: ShortArray, from: Int = 0, frames: Int = samples.size / 2): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from * 2 until (from + frames) * 2) buffer.putShort(samples[i])
        buffer.flip()
        return buffer
    }

    @After fun forget() { MixPointStore.use(null); WaveStore.use(null) }

    // ---- Waves ----

    @Test fun aWaveHasAColumnEveryFiftyMillisecondsWithItsPeakAndLevel() {
        val builder = WaveBuilder(rate, 2, 1_000L)
        builder.accept(sine(440.0, 2.0, .5))
        val wave = builder.build()
        assertEquals(1_000L, wave.startMs)
        assertEquals("2 s at 50 ms per column", 40, wave.columns)
        assertEquals(3_000L, wave.endMs)
        val middle = wave.columns / 2
        assertEquals("peak of a sine at half scale", .5f, wave.peakAt(middle), .02f)
        assertEquals("its RMS is the peak over √2", .5f / 1.4142f, wave.level(middle), .02f)
        assertEquals(10, wave.columnAt(1_500L))
        assertEquals(-1, wave.columnAt(999L))
        assertEquals(-1, wave.columnAt(3_000L))
    }

    @Test fun bassAndTrebleAreToldApartForTheColours() {
        fun bands(hz: Double): Pair<Float, Float> {
            val wave = WaveBuilder(rate, 2, 0L).apply { accept(sine(hz, 1.0, .6)) }.build()
            val i = wave.columns - 2
            return wave.lowAt(i) / wave.level(i) to wave.highAt(i) / wave.level(i)
        }
        val (bassLow, bassHigh) = bands(60.0)
        val (hatLow, hatHigh) = bands(8_000.0)
        assertTrue("a 60 Hz tone is bass ($bassLow)", bassLow > .8f)
        assertTrue("…with no treble ($bassHigh)", bassHigh < .1f)
        assertTrue("an 8 kHz tone is mostly treble ($hatHigh)", hatHigh > .6f)
        assertTrue("…with no bass ($hatLow)", hatLow < .1f)
    }

    @Test fun silenceIsFlatAndQuietPartsKeepSomeResolution() {
        val silent = WaveBuilder(rate, 2, 0L).apply { accept(ShortArray(rate * 2)) }.build()
        assertTrue((0 until silent.columns).all { silent.peakAt(it) == 0f && silent.level(it) == 0f })
        // -40 dB still shows: the square-root scale keeps quiet parts above the first steps of a byte.
        val quiet = WaveBuilder(rate, 2, 0L).apply { accept(sine(440.0, 1.0, .01)) }.build()
        assertTrue((quiet.peak[10].toInt() and 0xFF) >= 20)
    }

    @Test fun aWaveTapFollowsTheReadingAndStartsAgainAfterAJump() {
        val tap = WaveTap(10_000_000L, 12_000_000L)
        val sound = sine(220.0, 4.0)
        // Before the stretch: ignored.
        tap.pcm(bytes(sound, 0, rate / 2), 9_000_000L, rate, 2)
        assertNull(tap.result())
        // A first reading from 9.6 s (a seek), then a jump to 10 s: the picture starts again there.
        tap.pcm(bytes(sound, 0, rate / 2), 9_600_000L, rate, 2)
        tap.pcm(bytes(sound, 0, rate), 10_000_000L, rate, 2)
        assertFalse(tap.done)
        tap.pcm(bytes(sound, rate, rate), 11_000_000L, rate, 2)
        assertTrue(tap.done)
        val wave = tap.result()!!
        assertEquals(10_000L, wave.startMs)
        assertEquals(40, wave.columns)
    }

    @Test fun aGateLetsAnAnalysisStartWhereItAlwaysStarts() {
        var first = -1L
        val gate = FromTap(5_000_000L, object : PcmTap {
            override fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int) { if (first < 0) first = positionUs }
        })
        val sound = sine(220.0, 1.0)
        gate.pcm(bytes(sound, 0, rate / 10), 4_000_000L, rate, 2)
        assertEquals(-1L, first)
        gate.pcm(bytes(sound, 0, rate / 10), 4_950_000L, rate, 2)
        assertEquals("the buffer that reaches the start is passed on", 4_950_000L, first)
    }

    @Test fun wavesAreKeptPerTitleSmallAndTheOldestGoFirst() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ketvoice-waves-${System.nanoTime()}")
        try {
            val files = WaveFiles(dir, limit = 3)
            val head = WaveBuilder(rate, 2, 0L).apply { accept(sine(100.0, 3.0)) }.build()
            val tail = WaveBuilder(rate, 2, 150_000L).apply { accept(sine(100.0, 5.0)) }.build()
            files.put("track-1", 200_400L, head)
            files.put("track-1", 200_400L, tail)
            val entry = files.read("track-1", 200_400L)!!
            assertEquals(head.columns, entry.head!!.columns)
            assertArrayEquals(head.rms, entry.head!!.rms)
            assertEquals(150_000L, entry.tail!!.startMs)
            assertArrayEquals(tail.high, entry.tail!!.high)
            assertNotNull("a second either side, as the analysis store", files.read("track-1", 201_300L))
            // A shorter reading does not replace a longer one.
            files.put("track-1", 200_400L, WaveBuilder(rate, 2, 160_000L).apply { accept(sine(100.0, 1.0)) }.build())
            assertEquals(tail.columns, files.read("track-1", 200_400L)!!.tail!!.columns)
            val size = dir.listFiles()!!.single().length()
            assertTrue("a few bytes per column ($size)", size < 20 + (head.columns + tail.columns) * 4 + 32)
            // The cap: four titles, three files; the least recently used one goes.
            fun fileOf(id: String, length: Long) = File(dir, java.security.MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
                .joinToString("") { "%02x".format(it) } + "-${length / 1000}.kwv")
            fileOf("track-1", 200_400L).setLastModified(1_000_000L)
            files.put("a", 100_000L, head); fileOf("a", 100_000L).setLastModified(3_000_000L)
            assertNotNull("reading a title marks it used", files.read("track-1", 200_400L))
            assertTrue(fileOf("track-1", 200_400L).lastModified() > 3_000_000L)
            fileOf("track-1", 200_400L).setLastModified(1_000_000L)
            files.put("b", 100_000L, head); fileOf("b", 100_000L).setLastModified(4_000_000L)
            files.put("c", 100_000L, head)
            assertEquals(3, files.count())
            assertNull(files.read("track-1", 200_400L))
            assertNotNull(files.read("a", 100_000L))
            // A damaged file is ignored, not trusted.
            assertNull(WaveFiles.decode(byteArrayOf(1, 2, 3)))
            assertNull(runCatching { WaveFiles.decode(WaveFiles.encode(WaveFiles.Entry(head, null)).copyOf(30)) }.getOrNull())
        } finally { dir.deleteRecursively() }
    }

    // ---- Snapping ----

    /** 120 BPM, beats on whole half seconds, bars from 155 s, phrases from 155 s (every 16 s). */
    private fun grid(phrases: Boolean = true, bars: Boolean = true) =
        SegmentAnalysis(155_000, 45_000, 120f, 155_000.0, .9f, if (bars) 0 else -1, -14f, 155_000, 199_000, 155_000, 185_000,
            phraseAtMs = if (phrases) 155_000.0 else -1.0)

    @Test fun pointsSnapToPhrasesThenBarsThenBeats() {
        val a = grid()
        assertEquals(GridSnap.Result(171_000L, GridSnap.Kind.Phrase), GridSnap.snap(a, 170_400L, 400L))
        assertEquals(GridSnap.Result(175_000L, GridSnap.Kind.Bar), GridSnap.snap(a, 175_300L, 400L))
        assertEquals(GridSnap.Result(176_500L, GridSnap.Kind.Beat), GridSnap.snap(a, 176_400L, 400L))
        val plain = grid(phrases = false, bars = false)
        assertEquals(GridSnap.Result(170_500L, GridSnap.Kind.Beat), GridSnap.snap(plain, 170_400L, 400L))
        // No grid, or too far from what was measured: the point stays where it was dropped.
        assertEquals(GridSnap.Result(170_421L, GridSnap.Kind.None), GridSnap.snap(null, 170_421L, 400L))
        assertEquals(GridSnap.Result(90_421L, GridSnap.Kind.None), GridSnap.snap(a, 90_421L, 400L))
        assertTrue(GridSnap.covers(a, 120_000L))
        assertEquals(GridSnap.Kind.Phrase, GridSnap.kindAt(a, 187_000L))
        assertEquals(GridSnap.Kind.Bar, GridSnap.kindAt(a, 159_000L))
        assertEquals(GridSnap.Kind.Beat, GridSnap.kindAt(a, 159_500L))
        assertEquals(GridSnap.Kind.None, GridSnap.kindAt(a, 159_700L))
        val beats = GridSnap.beats(a, 159_000L, 163_000L)
        assertEquals(9, beats.size)
        assertEquals(GridSnap.Kind.Bar, beats.first().second)
    }

    @Test fun holdingAPointMovesItFreelyAndTheArrowsStepByBeats() {
        val a = grid()
        val range = 110_000L..199_000L
        assertEquals(GridSnap.Kind.None, MixEditor.place(a, 170_421L, 400L, free = true, range).kind)
        assertEquals(170_421L, MixEditor.place(a, 170_421L, 400L, free = true, range).ms)
        assertEquals(171_000L, MixEditor.place(a, 170_421L, 400L, free = false, range).ms)
        assertEquals("kept in its range", 199_000L, MixEditor.place(null, 260_000L, 400L, free = false, range).ms)
        assertEquals(171_500L, MixEditor.step(a, 171_000L, forward = true, range))
        assertEquals(170_500L, MixEditor.step(a, 171_000L, forward = false, range))
        assertEquals("between two beats, to the next", 171_000L, MixEditor.step(a, 170_800L, forward = true, range))
        assertEquals(170_500L, MixEditor.step(a, 170_800L, forward = false, range))
        assertEquals(170_800L + MixEditor.FREE_STEP_MS, MixEditor.step(null, 170_800L, forward = true, range))
        assertEquals("a quarter beat at least", 125L, MixEditor.tolerance(a, .1f, 14f))
        assertEquals(1_400L, MixEditor.tolerance(a, 100f, 14f))
        assertEquals("3:12.4", MixEditor.clock(192_480L))
    }

    // ---- Remembered points ----

    @Test fun pointsAreRememberedPerTitleAndPerPair() {
        val backend = MapPointsBackend()
        MixPointStore.use(backend)
        MixPointStore.setExit("song-a", 200_000L, 171_000L)
        MixPointStore.setEntry("song-b", 180_000L, 8_000L)
        MixPointStore.setPair("song-a", "song-b", PairChoice(MixStyle.Echo, 9_000L))
        assertEquals(171_000L, MixPointStore.exitOf("song-a", 200_000L))
        assertEquals("another rendition a second longer keeps it", 171_000L, MixPointStore.exitOf("song-a", 201_000L))
        assertEquals("a different version does not", -1L, MixPointStore.exitOf("song-a", 230_000L))
        assertEquals(-1L, MixPointStore.entryOf("song-a", 200_000L))
        assertEquals(8_000L, MixPointStore.entryOf("song-b", 180_000L))
        assertEquals(PairChoice(MixStyle.Echo, 9_000L), MixPointStore.pair("song-a", "song-b"))
        assertNull("a pair is one way", MixPointStore.pair("song-b", "song-a"))
        // Kept as digests, never as ids in clear.
        assertTrue(backend.all().keys.none { "song" in it })
        assertTrue(backend.all().all { (k, v) -> MixPointStore.validEntry(k, v) })
        // Round trip through the backup: a fresh device gets them back; what the device has wins.
        val exported = MixPointStore.export()
        val fresh = MapPointsBackend()
        MixPointStore.use(fresh)
        MixPointStore.setExit("song-a", 200_000L, 150_000L)
        MixPointStore.mergeBackup(exported + mapOf("t:nothex" to "1,2,3", "p:" + "0".repeat(64) to "9,99"))
        assertEquals(150_000L, MixPointStore.exitOf("song-a", 200_000L))
        assertEquals(8_000L, MixPointStore.entryOf("song-b", 180_000L))
        assertEquals(MixStyle.Echo, MixPointStore.pair("song-a", "song-b")?.style)
        assertEquals("junk is left out", 3, fresh.all().size)
        // « Réinitialiser »: the transition goes back to Ketmix.
        MixPointStore.reset("song-a", "song-b")
        assertEquals(-1L, MixPointStore.exitOf("song-a", 200_000L))
        assertEquals(-1L, MixPointStore.entryOf("song-b", 180_000L))
        assertNull(MixPointStore.pair("song-a", "song-b"))
        assertTrue(fresh.all().isEmpty())
    }

    @Test fun storedValuesAreChecked() {
        assertEquals(TitlePoints(171_000, -1, 200_000), MixPointStore.decodeTitle(MixPointStore.encodeTitle(TitlePoints(171_000, -1, 200_000))))
        assertEquals(PairChoice(null, 12_000), MixPointStore.decodePair(MixPointStore.encodePair(PairChoice(null, 12_000))))
        assertEquals(PairChoice(MixStyle.Rise, -1), MixPointStore.decodePair(MixPointStore.encodePair(PairChoice(MixStyle.Rise, -1))))
        assertNull(MixPointStore.decodeTitle("-1,-1,200000"))
        assertNull(MixPointStore.decodeTitle("1,2"))
        assertNull(MixPointStore.decodeTitle("-5,2,3"))
        assertNull(MixPointStore.decodePair("9,1000"))
        assertNull(MixPointStore.decodePair("-1,-1"))
        assertFalse(MixPointStore.validEntry("x:" + "a".repeat(64), "1,2,3"))
    }

    @Test fun theEditorReadsWhatWasRemembered() {
        MixPointStore.use(MapPointsBackend())
        MixPointStore.setExit("a", 200_000L, 160_000L)
        MixPointStore.setEntry("b", 180_000L, 4_000L)
        MixPointStore.setPair("a", "b", PairChoice(MixStyle.Filters, 6_000L))
        assertEquals(MixEditor.Choice(160_000L, 4_000L, 6_000L, MixStyle.Filters), MixEditor.remembered("a", 200_000L, "b", 180_000L, pair = true))
        assertEquals("a title alone has no pair", MixEditor.Choice(160_000L, -1L, -1L, null), MixEditor.remembered("a", 200_000L, "a", 200_000L, pair = false))
    }

    // ---- The planner honours them ----

    private val duration = 200_000L
    private fun outro(bpm: Float = 120f, hard: Boolean = false, fade: Boolean = false) =
        SegmentAnalysis(155_000, 45_000, bpm, 155_000.0, .9f, 0, -14f, 155_000, 199_000, 155_000, if (hard) 199_000 else 185_000,
            fade, hard, phraseAtMs = 155_000.0)
    private fun intro(bpm: Float = 122f) = SegmentAnalysis(0, 40_000, bpm, 500.0, .9f, 0, -14f, 300, 40_000, 8_000, 40_000)
    private fun plan(exit: Long = -1, entry: Long = -1, overlap: Long = -1, style: MixStyle = MixStyle.Auto, a: SegmentAnalysis? = outro(),
                     b: SegmentAnalysis? = intro(), sungUntil: Long? = null, context: MixContext = MixContext(), inDuration: Long = duration) =
        TransitionPlanner.plan(MixFacts(duration, a, vocalEndMs = sungUntil, userExitMs = exit),
            MixFacts(inDuration, b, userEntryMs = entry), context.copy(userOverlapMs = overlap), MixSettings(1, style))
    private fun onBeat(position: Long, origin: Double, bpm: Float): Boolean {
        val beats = (position - origin) / (60_000.0 / bpm)
        return abs(beats - Math.round(beats)) * (60_000.0 / bpm) < 2.0
    }

    @Test fun withoutPointsNothingChanges() {
        val auto = plan()
        assertNotEquals("listener", auto.reason)
        assertEquals(auto, TransitionPlanner.plan(MixFacts(duration, outro()), MixFacts(duration, intro()), MixContext(), MixSettings(1)))
    }

    @Test fun theListenersExitEntryAndOverlapArePlayed() {
        val chosen = plan(exit = 171_000, entry = 16_400, overlap = 16_000)
        assertEquals("listener", chosen.reason)
        assertEquals("in time, as Ketmix would have", MixKind.Beatmatch, chosen.kind)
        assertEquals(171_000L, chosen.outMs)
        assertTrue("the entry on a beat of the incoming title (${chosen.inMs})", onBeat(chosen.inMs, 500.0, 122f))
        assertTrue(abs(chosen.inMs - 16_400L) <= 300)
        assertEquals("whole bars: 8 bars at 120 BPM", 16_000L, chosen.overlapMs)
        assertTrue("the bass still changes hands on a beat in the middle", onBeat(chosen.swapMs, 155_000.0, 120f) &&
            abs(chosen.swapMs - (chosen.outMs + chosen.overlapMs / 2)) <= 500)
        // A point dropped between two beats is put on the nearest one for a mix in time.
        assertEquals(171_500L, plan(exit = 171_420).outMs)
    }

    @Test fun aListenerExitMayComeBeforeTheLastSungWords() {
        // Ketmix alone never leaves before the singing is over…
        val auto = plan(sungUntil = 190_000)
        assertTrue("auto ${auto.outMs}", auto.outMs >= 190_300 - 8 * 500 || !auto.mixes)
        // …the listener may, explicitly: their exit is played, the outgoing voice stepped back under the blend.
        val chosen = plan(exit = 165_000, sungUntil = 190_000)
        assertEquals(165_000L, chosen.outMs)
        assertTrue(chosen.mixes)
        assertEquals(190_300L, chosen.sungUntilMs)
    }

    @Test fun pointsAreKeptInsideBothTitles() {
        // An exit past the end comes back inside, with room for the blend.
        val late = plan(exit = 260_000, overlap = 20_000, style = MixStyle.Filters)
        assertTrue(late.mixes)
        assertTrue("${late.outMs} + ${late.overlapMs}", late.outMs + late.overlapMs <= duration + 400)
        assertTrue(late.outMs <= duration - 1_000)
        // A blend longer than what is left of the title is shortened, the exit kept.
        val tight = plan(exit = 192_000, overlap = 30_000, style = MixStyle.Filters)
        assertEquals(192_000L, tight.outMs)
        assertEquals(8_400L, tight.overlapMs)
        // Overlaps stay within 1–45 s.
        assertEquals(45_000L, plan(exit = 140_000, overlap = 90_000, style = MixStyle.Filters).overlapMs)
        assertEquals(1_000L, plan(exit = 170_000, overlap = 10, style = MixStyle.Filters).overlapMs)
        // An entry that leaves too little of the incoming title is brought back.
        val entry = plan(entry = 170_000, style = MixStyle.Filters, inDuration = 180_000)
        assertTrue(entry.mixes)
        assertTrue(entry.inEndMs <= 180_000 - 5_000)
        // Never before 5 s.
        assertTrue(plan(exit = 1_000, style = MixStyle.Filters).outMs >= 5_000)
    }

    @Test fun thePairsStyleIsPlayedAndAGridIsNeededToPlayOnTheBeat() {
        val echo = plan(exit = 179_200, style = MixStyle.Echo)
        assertEquals(MixKind.Echo, echo.kind)
        assertEquals("on the beat", 179_000L, echo.outMs)
        assertEquals(2_000L, echo.overlapMs)
        val rise = plan(exit = 187_000, style = MixStyle.Rise)
        assertEquals(MixKind.Rise, rise.kind)
        assertEquals(187_000L, rise.outMs)
        assertTrue(rise.buildMs > 0)
        assertEquals(MixKind.Crossfade, plan(exit = 170_000, overlap = 6_000, style = MixStyle.Fade).kind)
        // No grid where the listener left: a blend that needs none.
        val ungridded = plan(exit = 170_000, style = MixStyle.Tempo, a = outro(bpm = 0f))
        assertEquals(MixKind.Wash, ungridded.kind)
        assertEquals(170_000L, ungridded.outMs)
        // Tempos too far apart cannot be played in time, whatever is asked.
        assertNotEquals(MixKind.Beatmatch, plan(exit = 171_000, style = MixStyle.Tempo, b = intro(150f)).kind)
    }

    @Test fun whatRulesAMixOutStillRulesItOut() {
        assertEquals(MixKind.None, plan(exit = 171_000, context = MixContext(exactAudio = true)).kind)
        assertEquals(MixKind.None, plan(exit = 171_000, context = MixContext(stopAfterCurrent = true)).kind)
        assertEquals(MixKind.None, plan(exit = 171_000, inDuration = 20_000).kind)
        // An album that runs into itself is left alone by Ketmix, but mixed where the listener chose.
        val hard = outro(hard = true).copy(soundEndMs = 199_950)
        val immediate = intro().copy(soundStartMs = 0, riseMs = 100)
        assertEquals(MixKind.None, plan(a = hard, b = immediate, context = MixContext(sameAlbumInOrder = true)).kind)
        assertTrue(plan(exit = 180_000, a = hard, b = immediate, context = MixContext(sameAlbumInOrder = true)).mixes)
    }

    @Test fun withoutAnalysisThePointsGiveAPlainFade() {
        val chosen = plan(exit = 170_000, entry = 3_000, overlap = 7_000, a = null, b = null)
        assertEquals(MixKind.Crossfade, chosen.kind)
        assertEquals(170_000L, chosen.outMs)
        assertEquals(3_000L, chosen.inMs)
        assertEquals(7_000L, chosen.overlapMs)
    }

    @Test fun theEditorDrawsWhatTheEngineWillPlay() {
        val out = MixEditor.Side("a", duration, analysis = outro())
        val into = MixEditor.Side("b", duration, analysis = intro())
        val choice = MixEditor.Choice(exitMs = 171_000, entryMs = 16_400, overlapMs = 16_000)
        val editor = MixEditor.plan(out, into, choice, 1, MixStyle.Auto)
        assertEquals(plan(exit = 171_000, entry = 16_400, overlap = 16_000), editor)
        val view = MixEditor.view(editor, wide = false)
        assertTrue(view.afterMs >= editor.overlapMs)
        assertTrue(view.anchor in .05f..0.6f)
    }
}
