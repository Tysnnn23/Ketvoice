package com.ketvoice.desktop.lyrics

import com.ketvoice.app.data.MusicApi
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The word sync's arithmetic against Whisper's own: the fixture's expected times were computed by
 * `whisper.timing` (softmax, std_mean, median_filter, dtw) in tools/align/export_whisper.py `--fixtures`, on a
 * cross-attention built from the same formula as [synthetic].
 */
class AlignMathTest {
    private val heads = 3
    private val tokens = 12

    /** qk(h, t, f) = -((f - (6 + 13 (t - 4)))² / 50) + 0.5 sin(0.37 h + 1.3 t + 0.11 f), in float32. */
    private fun synthetic(): FloatArray = FloatArray(heads * tokens * AlignMath.AUDIO_FRAMES) { i ->
        val f = i % AlignMath.AUDIO_FRAMES; val t = (i / AlignMath.AUDIO_FRAMES) % tokens; val h = i / (AlignMath.AUDIO_FRAMES * tokens)
        val centre = 6.0 + 13.0 * (t - 4.0)
        (-((f - centre) * (f - centre)) / 50.0 + 0.5 * sin(0.37 * h + 1.3 * t + 0.11 * f)).toFloat()
    }

    @Test fun postProcessingMatchesWhispersTiming() {
        val times = AlignMath.wordTimes(synthetic(), heads, tokens, 320, listOf(2, 1, 2, 1, 1))
        val starts = listOf(0.0, 0.26, 0.5, 1.02, 1.3)
        val ends = listOf(0.26, 0.5, 1.02, 1.3, 1.54)
        times.forEachIndexed { i, (s, e) ->
            assertTrue(abs(s - starts[i]) < 1e-6 && abs(e - ends[i]) < 1e-6, "word $i: $s..$e, whisper ${starts[i]}..${ends[i]}")
        }
    }

    @Test fun medianFilterMirrorsTheEnds() {
        val x = floatArrayOf(5f, 1f, 9f, 3f, 7f, 2f, 8f, 4f)
        // torch: F.pad(reflect) then unfold(7).sort()[3]
        assertEquals(listOf(3f, 5f, 3f, 5f, 4f, 7f, 4f, 7f), AlignMath.median(x, 7).toList())
        val short = floatArrayOf(1f, 2f, 3f)
        assertEquals(short.toList(), AlignMath.median(short, 7).toList())
    }

    @Test fun dtwFollowsTheCheapestDiagonal() {
        val x = arrayOf(doubleArrayOf(0.0, 9.0, 9.0, 9.0), doubleArrayOf(9.0, 0.0, 0.0, 9.0), doubleArrayOf(9.0, 9.0, 9.0, 0.0))
        val (rows, cols) = AlignMath.dtw(x)
        assertEquals(listOf(0, 1, 1, 2), rows.toList())
        assertEquals(listOf(0, 1, 2, 3), cols.toList())
    }

    @Test fun wordsAreRunsAndCharactersForChineseAndJapanese() {
        assertEquals(listOf("Don't ", "stop ", "me"), AlignMath.pieces("Don't stop me"))
        assertEquals(listOf("夜", "に", "駆", "け", "る"), AlignMath.pieces("夜に駆ける"))
        assertEquals(listOf("愛", "し", "て", "る ", "baby"), AlignMath.pieces("愛してる baby"))
        assertEquals(" Don't", AlignMath.spoken("Don't ", 0))
        assertEquals("に", AlignMath.spoken("に", 1))
    }

    @Test fun windowsGroupLinesUnder29SecondsAndLeaveInstrumentalsOut() {
        val lines = (0 until 12).map { MusicApi.LyricLine(10_000L + it * 4_000L, "one two three four") } + MusicApi.LyricLine(120_000, "last line here")
        val w = AlignMath.windows(lines, 180_000) { 10 }
        assertTrue(w.all { it.endMs - it.startMs <= AlignMath.MAX_WINDOW_MS }, "$w")
        assertEquals(lines.indices.toList(), w.flatMap { it.lines })
        // The last line after a long break: a second per word and three more, not up to the end of the song.
        val last = w.last()
        assertEquals(listOf(12), last.lines)
        assertEquals(120_000L - AlignMath.LEAD_MS, last.startMs)
        assertEquals(126_000L, last.endMs)
    }

    @Test fun windowsRespectTheTokenBudget() {
        val lines = (0 until 6).map { MusicApi.LyricLine(it * 2_000L, "a b") }
        val w = AlignMath.windows(lines, 60_000) { 100 }
        assertTrue(w.all { it.lines.size <= 2 }, "$w")
    }

    @Test fun aTimedLineKeepsItsStartAndStaysInOrder() {
        val line = MusicApi.LyricLine(10_000, "one two three")
        val found = listOf(AlignMath.Timed(9_000, 10_400, .8), AlignMath.Timed(10_300, 10_900, .7), AlignMath.Timed(10_200, 13_500, .6))
        val out = AlignMath.timedLine(line, found, 12_000)!!
        assertEquals(10_000L, out.words[0].startMs)
        assertEquals(10_300L, out.words[1].startMs)
        // Out of order in the attention: pushed after the word before it.
        assertEquals(10_340L, out.words[2].startMs)
        assertEquals(10_300L, out.words[0].endMs)
        assertEquals(12_000L, out.words[2].endMs)
        assertEquals("one two three", out.words.joinToString("") { it.text })
    }

    @Test fun unheardLinesKeepLineTiming() {
        val line = MusicApi.LyricLine(0, "one two")
        assertNull(AlignMath.timedLine(line, listOf(AlignMath.Timed(0, 100, .01), AlignMath.Timed(100, 200, .02)), 1_000))
        assertNull(AlignMath.timedLine(MusicApi.LyricLine(0, "alone"), listOf(AlignMath.Timed(0, 100, .9)), 1_000))
    }

    @Test fun aSongWhoseTextIsNotSungIsRejected() {
        assertTrue(AlignMath.acceptable(listOf(.6, .4, .3), 8, 10))
        assertTrue(!AlignMath.acceptable(listOf(.05, .04), 8, 10))
        assertTrue(!AlignMath.acceptable(listOf(.6), 3, 10))
    }

    @Test fun theScriptNarrowsTheLanguage() {
        assertEquals(listOf("ja"), AlignMath.allowedLanguages(listOf("夜に駆ける")))
        assertEquals(listOf("ko"), AlignMath.allowedLanguages(listOf("사랑해요 너를")))
        assertTrue("ru" in AlignMath.allowedLanguages(listOf("Смотри на меня")))
        val latin = AlignMath.allowedLanguages(listOf("Je t'aime à la folie"))
        assertTrue("fr" in latin && "en" in latin && "ja" !in latin && "ru" !in latin)
        val logits = FloatArray(51_865)
        logits[WhisperTokens.languageToken("fr")!!] = 5f; logits[WhisperTokens.languageToken("ja")!!] = 9f
        assertEquals("fr", AlignMath.pickLanguage(logits, latin))
    }
}
