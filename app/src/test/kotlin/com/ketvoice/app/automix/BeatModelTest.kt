package com.ketvoice.app.automix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BeatModelTest {
    // ----------------------------------------------------------------- front end

    @Test fun logMelMatchesTheNumpyReimplementationOfBeatThis() {
        val lines = javaClass.getResourceAsStream("/beat-logmel-reference.txt")!!.bufferedReader().readLines()
        val (frames, mels) = lines[0].split(" ").map { it.toInt() }
        val x = FloatArray(11_025) { i ->
            val t = i.toDouble() / BeatSpectrogram.RATE
            (0.3 * sin(2 * PI * 440 * t) + 0.2 * sin(2 * PI * 3000 * t) + 0.1 * sin(2 * PI * 7000 * t)).toFloat() + if (i == 5000) .5f else 0f
        }
        val got = BeatSpectrogram.compute(x)
        assertEquals(frames, BeatSpectrogram.frames(x.size))
        assertEquals(frames * mels, got.size)
        var worst = 0f
        for (t in 0 until frames) {
            val want = lines[1 + t].split(" ").map { it.toFloat() }
            for (m in 0 until mels) worst = maxOf(worst, abs(want[m] - got[t * mels + m]))
        }
        assertTrue("worst difference $worst", worst < 2e-3f)
    }

    // ----------------------------------------------------------------- chunks and peaks

    @Test fun chunksCoverThePieceAndTheLastEndsWithIt() {
        val frames = 2_000
        val starts = BeatPost.starts(frames)
        assertEquals(-BeatPost.BORDER, starts.first())
        assertEquals(frames - (BeatPost.BEAT_FRAMES - BeatPost.BORDER), starts.last())
        val seen = BooleanArray(frames)
        for (s in starts) for (j in BeatPost.BORDER until BeatPost.BEAT_FRAMES - BeatPost.BORDER) (s + j).takeIf { it in 0 until frames }?.let { seen[it] = true }
        assertTrue(seen.all { it })
        assertEquals(1, BeatPost.starts(300).size)
    }

    @Test fun aggregateKeepsTheFirstChunkWhereTheyOverlap() {
        val frames = 900
        val starts = BeatPost.starts(frames)
        val chunks = starts.indices.map { i -> FloatArray(BeatPost.BEAT_FRAMES) { (i + 1).toFloat() } }
        val whole = BeatPost.aggregate(chunks, starts, frames)
        assertEquals(1f, whole[0], 0f)
        assertEquals(starts.size.toFloat(), whole[frames - 1], 0f)
        assertTrue(whole.all { it > 0f })
    }

    @Test fun minimalPostProcessingPicksMaximaAboveZeroAndMergesNeighbours() {
        val beat = FloatArray(100) { -5f }
        beat[10] = 3f; beat[11] = 3f          // adjacent equal peaks: one beat at 10.5
        beat[30] = 2f; beat[33] = 1f          // 33 is under 30's neighbourhood max within 3 frames: dropped
        beat[60] = -1f                        // a maximum below zero: not a beat
        beat[80] = 4f
        val down = FloatArray(100) { -5f }
        down[31] = 2f                         // moved to the nearest beat, 30
        down[79] = 2f                         // and 80
        val t = BeatPost.peaks(beat, down)
        assertArrayEquals(doubleArrayOf(10.5 / 50, 30.0 / 50, 80.0 / 50), t.beats, 1e-9)
        assertArrayEquals(doubleArrayOf(30.0 / 50, 80.0 / 50), t.downbeats, 1e-9)
    }

    // ----------------------------------------------------------------- mapping and merge

    private fun times(bpm: Double, first: Double = 1.0, beats: Int = 40, barAt: Int = 2, jitter: Double = 0.0): BeatPost.Times {
        val p = 60.0 / bpm
        val b = DoubleArray(beats) { first + it * p + if (it % 2 == 0) jitter else -jitter }
        return BeatPost.Times(b, b.filterIndexed { i, _ -> i >= barAt && (i - barAt) % 4 == 0 }.toDoubleArray())
    }

    private val dsp = SegmentAnalysis(155_000, 45_000, 120f, 155_000.0, .8f, 1, phraseAtMs = 155_500.0)

    @Test fun regularBeatsGiveTempoPhaseAndBars() {
        val a = BeatMapping.segment(times(120.0), dsp, 155_000.0)!!
        assertEquals(120f, a.bpm, .05f)
        assertTrue(a.confidence > .9f)
        assertEquals(SegmentAnalysis.BEAT_THIS, a.analyser)
        // the beat grid is on 156_000 + k * 500; the bars begin at beat 2, i.e. 157_000 + k * 2000
        assertEquals(0.0, ((a.beatAtMs - 156_000.0) % 500.0 + 500.0) % 500.0, 1.0)
        val barStart = a.beatAtMs + a.barOffset * a.beatMs
        assertEquals(0.0, ((barStart - 157_000.0) % 2_000.0 + 2_000.0) % 2_000.0, 1.0)
    }

    @Test fun irregularBeatsAndThreeBeatBarsAreNotTrusted() {
        assertTrue(BeatMapping.segment(times(120.0, jitter = .1), dsp, 0.0)!!.confidence < .6f)
        assertEquals(null, BeatMapping.segment(BeatPost.Times(doubleArrayOf(1.0, 1.5, 2.0), doubleArrayOf()), dsp, 0.0))
        val waltz = times(120.0)
        val three = BeatPost.Times(waltz.beats, waltz.beats.filterIndexed { i, _ -> i % 3 == 0 }.toDoubleArray())
        assertEquals(-1, BeatMapping.segment(three, dsp, 0.0)!!.barOffset)
    }

    @Test fun halfAndDoubleTempoAreBroughtToTheSignalAnalysisOctave() {
        assertEquals(120f, BeatMapping.segment(times(240.0, barAt = 0, beats = 80), dsp, 0.0)!!.bpm, .1f)
        assertEquals(120f, BeatMapping.segment(times(60.0, beats = 24), dsp, 0.0)!!.bpm, .1f)
        // With nothing to compare to, a tempo outside Ketmix's range is folded into it.
        assertEquals(100f, BeatMapping.segment(times(200.0, beats = 80), SegmentAnalysis(0, 40_000), 0.0)!!.bpm, .1f)
    }

    @Test fun theModelIsUsedWhenSureAndTheSignalAnalysisKeptOtherwise() {
        val sure = BeatMapping.segment(times(120.0), dsp, 155_000.0)!!
        val merged = BeatMapping.merge(dsp, sure)
        assertEquals(SegmentAnalysis.BEAT_THIS, merged.analyser)
        assertEquals(sure.barOffset, merged.barOffset)
        assertEquals(dsp.soundEndMs, merged.soundEndMs)
        val unsure = sure.copy(confidence = .4f)
        assertEquals(dsp.copy(analyser = SegmentAnalysis.BEAT_TRIED), BeatMapping.merge(dsp, unsure))
        assertEquals(dsp.copy(analyser = SegmentAnalysis.BEAT_TRIED), BeatMapping.merge(dsp, null))
        // a sure signal analysis that disagrees with a model that is only fairly sure stays
        val other = sure.copy(bpm = 140f, confidence = .7f)
        assertEquals(SegmentAnalysis.BEAT_TRIED, BeatMapping.merge(dsp.copy(confidence = .9f), other).analyser)
        // without a beat of its own the signal analysis takes the model's
        assertEquals(SegmentAnalysis.BEAT_THIS, BeatMapping.merge(SegmentAnalysis(155_000, 45_000), sure).analyser)
    }

    @Test fun phraseStartsMoveToTheModelsBars() {
        val sure = BeatMapping.segment(times(120.0), dsp, 155_000.0)!!
        val merged = BeatMapping.merge(dsp, sure)
        val bar = sure.beatMs * 4.0
        val first = sure.beatAtMs + sure.barOffset * sure.beatMs
        assertEquals(0.0, ((merged.phraseAtMs - first) / bar).let { it - Math.round(it) }, 1e-6)
    }

    // ----------------------------------------------------------------- the whole path with a fake network

    private class Fake(val frames: Int) : BeatRunner {
        private val starts = BeatPost.starts(frames)
        private var call = 0
        override fun run(spect: FloatArray): Pair<FloatArray, FloatArray> {
            val s = starts[call++]
            val beat = FloatArray(BeatPost.BEAT_FRAMES) { if ((s + it) % 25 == 5) 4f else -4f }
            val down = FloatArray(BeatPost.BEAT_FRAMES) { if ((s + it) % 100 == 5) 4f else -4f }
            return beat to down
        }
    }

    private fun silence(seconds: Int, rate: Int = 44_100): BeatPcm {
        val pcm = BeatPcm(rate, 1, 10_000_000L)
        val b = ByteBuffer.allocate(rate * 2 * seconds).order(ByteOrder.LITTLE_ENDIAN)
        pcm.accept16(b)
        return pcm
    }

    @Test fun aFakeNetworkRunsThroughChunksPeaksAndMapping() {
        val pcm = silence(30)
        val (samples, origin) = pcm.window(false)!!
        assertEquals(10_000.0, origin, 1e-6)
        val frames = BeatSpectrogram.frames(samples.size)
        val old = BeatModel.open
        try {
            BeatModel.open = { Fake(frames) }
            val got = BeatModel.refine(SegmentAnalysis(10_000, 30_000), pcm, tail = false)
            assertEquals(SegmentAnalysis.BEAT_THIS, got.analyser)
            assertEquals(120f, got.bpm, .2f)
            val phase = ((got.beatAtMs - 10_100.0) % 500.0 + 500.0) % 500.0
            assertTrue("phase $phase", minOf(phase, 500.0 - phase) < 25.0)
            assertTrue(got.barOffset in 0..3)
            BeatModel.open = { null }
            assertEquals(SegmentAnalysis.BEAT_TRIED, BeatModel.refine(SegmentAnalysis(10_000, 30_000), pcm, tail = false).analyser)
            BeatModel.open = { throw IllegalStateException("no model") }
            assertEquals(SegmentAnalysis.BEAT_TRIED, BeatModel.refine(SegmentAnalysis(10_000, 30_000), pcm, tail = false).analyser)
        } finally { BeatModel.open = old }
    }

    @Test fun theTailWindowStartsLaterInTheTitle() {
        val pcm = silence(80)
        val (samples, origin) = pcm.window(true)!!
        assertEquals(BeatPcm.MAX_SECONDS, samples.size.toDouble() / BeatSpectrogram.RATE, .01)
        assertEquals(10_000.0 + (80 - BeatPcm.MAX_SECONDS) * 1000.0, origin, 2.0)
    }

    // ----------------------------------------------------------------- pin, outputs, rules

    @Test fun pinMatchesTheWorkflowDigest() {
        val expected = File("../tools/beat/expected.sha256").takeIf { it.isFile } ?: File("tools/beat/expected.sha256")
        val (sha, name) = expected.readLines().first { it.isNotBlank() }.split("  ")
        assertEquals(name, BeatModel.pack.parts.single().name)
        assertEquals(sha, BeatModel.pack.parts.single().sha256)
        assertEquals(sha, BEAT_SHA)
    }

    @Test fun outputsAreFoundByNameThenByOrder() {
        assertEquals(1 to 0, BeatModel.pick(listOf("downbeat", "beat")))
        assertEquals(0 to 1, BeatModel.pick(listOf("beat", "downbeat")))
        assertEquals(0 to 1, BeatModel.pick(listOf("Identity", "Identity_1")))
        assertEquals(0 to 1, BeatModel.pick(listOf("StatefulPartitionedCall:0", "StatefulPartitionedCall:1")))
    }

    @Test fun liteAndModestPhonesRunTheModelOnlyWhileCharging() {
        assertTrue(BeatModel.allowed(lite = false, modest = false, charging = false))
        assertTrue(!BeatModel.allowed(lite = true, modest = false, charging = false))
        assertTrue(!BeatModel.allowed(lite = false, modest = true, charging = false))
        assertTrue(BeatModel.allowed(lite = true, modest = true, charging = true))
    }

    @Test fun theStoreKeepsWhoReadTheBeats() {
        val a = dsp.copy(analyser = SegmentAnalysis.BEAT_THIS)
        assertEquals(SegmentAnalysis.BEAT_THIS, AnalysisStore.decode(AnalysisStore.encode(a))!!.analyser)
        val old = AnalysisStore.encode(a); old.remove("an")
        assertEquals(0, AnalysisStore.decode(old)!!.analyser)
    }

    // ----------------------------------------------------------------- the planner

    @Test fun aDifferentBarOffsetFromTheModelChangesThePlannedEntry() {
        fun out(bar: Int) = SegmentAnalysis(155_000, 45_000, 120f, 155_000.0, .9f, bar, -14f, 155_000, 199_000, 155_000, 185_000)
        val intro = SegmentAnalysis(0, 40_000, 120f, 500.0, .9f, 0, -14f, 300, 40_000, 8_000, 40_000)
        fun plan(o: SegmentAnalysis) = TransitionPlanner.plan(MixFacts(200_000, o), MixFacts(200_000, intro), MixContext(), MixSettings(1))
        val signal = plan(out(0))
        val model = plan(out(2))
        assertNotEquals("the exit moves with the bars the model found", signal.outMs, model.outMs)
        assertEquals("and stays on the model's bars", 0L, (model.outMs - 155_000L - 2 * 500L) % 2_000L)
    }
}

class BeatSettingsRowTest {
    private val source = (File("src/main/kotlin/com/ketvoice/app/ui/settings/SettingsScreen.kt").takeIf { it.isFile }
        ?: File("app/src/main/kotlin/com/ketvoice/app/ui/settings/SettingsScreen.kt")).readText()

    @Test fun theBeatModelRowSitsWithTheVoiceSeparationModels() {
        val rows = Regex("ModelRow\\(([\\w.]+),").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(listOf("com.ketvoice.app.stems.StemModel.fast", "com.ketvoice.app.stems.StemModel.hq", "com.ketvoice.app.automix.BeatModel.pack"), rows)
        assertTrue(source.contains("\"Calage précis du rythme (Ketmix)\""))
        assertTrue(source.contains("Un petit modèle d'IA trouve les temps et les mesures pour des mélanges mieux calés"))
    }

    @Test fun theRowHasItsTranslations() {
        val file = File("src/main/assets/translations.json").takeIf { it.isFile } ?: File("app/src/main/assets/translations.json")
        val catalog = org.json.JSONObject(file.readText())
        val languages = listOf("en", "hi", "ja", "zh", "ru", "it", "es", "de", "pt", "ko")
        for (text in listOf("Calage précis du rythme (Ketmix)", "Un petit modèle d'IA trouve les temps et les mesures pour des mélanges mieux calés : {0}")) {
            for (language in languages) assertTrue("$language misses « $text »", catalog.getJSONObject(language).has(text))
        }
    }
}
