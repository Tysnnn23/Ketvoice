package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

/** « Éditeur de paroles » (9.0): timing by taps, nudges, undo, word mode, and LRC / enhanced LRC in and out. */
class LyricsDraftTest {
    private val text = """
        [Verse 1]
        Hello darkness my old friend

        I've come to talk with you again
        Because a vision
    """.trimIndent()

    @Test fun pastedTextBecomesLinesWithoutBlanksOrSectionLabels() {
        val draft = LyricsDraft.fromText(text)
        assertEquals(listOf("Hello darkness my old friend", "I've come to talk with you again", "Because a vision"), draft.lines.map { it.text })
        assertEquals(listOf("Hello ", "darkness ", "my ", "old ", "friend"), draft.lines[0].words.map { it.text })
        assertTrue(draft.lines.all { it.timeMs < 0 })
        assertEquals(0, draft.line)
    }

    @Test fun eachTapTimesTheNextLineAndUndoGoesBack() {
        var d = LyricsDraft.fromText(text)
        d = d.tap(12_340).tap(15_000)
        assertEquals(listOf(12_340L, 15_000L, -1L), d.lines.map { it.timeMs })
        assertEquals("the first word starts with its line", 12_340L, d.lines[0].words[0].startMs)
        assertEquals(2, d.line)
        d = d.tap(18_000)
        assertTrue(d.done)
        assertSame("a tap past the end changes nothing", d, d.tap(20_000))
        d = d.undo()
        assertEquals(2, d.line)
        assertEquals(-1L, d.lines[2].timeMs)
        d = d.undo().undo()
        assertEquals(0, d.line)
        assertFalse(d.canUndo)
        assertEquals(LyricsDraft.fromText(text).lines, d.lines)
    }

    @Test fun aNudgeMovesTheLastTimedLineAndItsWords() {
        var d = LyricsDraft.fromText(text).withMode(TapMode.Word)
        repeat(5) { d = d.tap(10_000L + it * 400) }
        d = d.withMode(TapMode.Line).tap(14_000)
        // Line mode: the last line timed is line 1; ±50 ms moves it.
        d = d.nudge(50).nudge(50).nudge(-50)
        assertEquals(14_050L, d.lines[1].timeMs)
        assertEquals(10_000L, d.lines[0].timeMs)
        // Selecting line 1 again and nudging moves line 0, the one before the cursor, with all its words.
        d = d.select(1).nudge(-100)
        assertEquals(9_900L, d.lines[0].timeMs)
        assertEquals(listOf(9_900L, 10_300L, 10_700L, 11_100L, 11_500L), d.lines[0].words.map { it.startMs })
        assertEquals("a nudge never goes before the start", 0L, LyricsDraft.fromText("a").tap(20).nudge(-50).lines[0].timeMs)
        d = d.undo()
        assertEquals(10_000L, d.lines[0].timeMs)
    }

    @Test fun wordModeTimesEachWordThenMovesToTheNextLine() {
        var d = LyricsDraft.fromText("one two three\nfour five").withMode(TapMode.Word)
        d = d.tap(1_000)
        assertEquals(1_000L, d.lines[0].timeMs)
        assertEquals(0 to 1, d.line to d.word)
        d = d.tap(1_500).tap(2_100)
        assertEquals(1 to 0, d.line to d.word)
        assertTrue(d.lines[0].wordsTimed)
        assertEquals(listOf(1_000L, 1_500L, 2_100L), d.lines[0].words.map { it.startMs })
        // Word mode nudges the last word alone.
        d = d.nudge(50)
        assertEquals(listOf(1_000L, 1_500L, 2_150L), d.lines[0].words.map { it.startMs })
        assertEquals(1_000L, d.lines[0].timeMs)
        d = d.tap(3_000)
        d = d.nudge(-50)
        assertEquals("nudging a line's first word moves the line", 2_950L, d.lines[1].timeMs)
        val shown = d.toLyricLines()
        assertEquals(2, shown.size)
        assertEquals("a word ends where the next starts", 1_500L, shown[0].words[0].endMs)
        assertEquals("…and the last where the next line starts", 2_950L, shown[0].words[2].endMs)
        assertTrue("a line with one word timed is shown line by line", shown[1].words.isEmpty())
    }

    @Test fun aLineTimedAgainInLineModeKeepsItsWordsInStep() {
        var d = LyricsDraft.fromText("a b c").withMode(TapMode.Word).tap(1_000).tap(1_200).tap(1_400)
        d = d.withMode(TapMode.Line).select(0).tap(2_000)
        assertEquals(listOf(2_000L, 2_200L, 2_400L), d.lines[0].words.map { it.startMs })
    }

    @Test fun lrcIsWrittenInCentiseconds() {
        assertEquals("00:12.34", LyricsExport.stamp(12_340))
        assertEquals("01:05.01", LyricsExport.stamp(65_006))
        assertEquals("103:00.00", LyricsExport.stamp(6_180_000))
        val d = LyricsDraft.fromText(text).tap(12_340).tap(15_000)
        val lrc = LyricsExport.lrc(d, "Song [live]", "Artist", "")
        assertEquals("[ti:Song  live]\n[ar:Artist]\n[re:Ketvoice]\n[00:12.34]Hello darkness my old friend\n[00:15.00]I've come to talk with you again\n", lrc)
    }

    @Test fun lrcAndEnhancedLrcReadBackToTheSameTimes() {
        var d = LyricsDraft.fromText(text).withMode(TapMode.Word)
        listOf(10_000L, 10_400L, 10_800L, 11_200L, 11_600L).forEach { d = d.tap(it) }
        d = d.withMode(TapMode.Line).tap(14_000).tap(18_500)
        val enhanced = LyricsExport.enhanced(d, "T", "A", "B")
        assertTrue(enhanced, "[00:10.00]<00:10.00>Hello <00:10.40>darkness <00:10.80>my <00:11.20>old <00:11.60>friend<00:14.00>\n" in enhanced)
        assertTrue("[00:14.00]I've come to talk with you again\n" in enhanced)
        val back = LyricsExport.import(enhanced)
        assertEquals(d.lines.map { it.text }, back.lines.map { it.text })
        assertEquals(d.lines.map { it.timeMs }, back.lines.map { it.timeMs })
        assertEquals(d.lines[0].words.map { it.startMs }, back.lines[0].words.map { it.startMs })
        assertEquals(d.lines[0].words.map { it.text }, back.lines[0].words.map { it.text })
        assertTrue(back.done)
        val plain = LyricsExport.import(LyricsExport.lrc(d))
        assertEquals(d.lines.map { it.timeMs }, plain.lines.map { it.timeMs })
        assertEquals("plain LRC keeps no word times", listOf(10_000L, -1L), plain.lines[0].words.take(2).map { it.startMs })
        // What the title's own file shows is exactly what was timed.
        val shown = CustomLyrics.parse(enhanced).second
        assertEquals(listOf(10_000L, 14_000L, 18_500L), shown.map { it.timeMs })
        assertEquals(5, shown[0].words.size)
    }

    @Test fun theLastWordOfTheLastLineEndsASecondLater() {
        val d = LyricsDraft.fromText("bye now").withMode(TapMode.Word).tap(5_000).tap(5_500)
        assertTrue(LyricsExport.enhanced(d).endsWith("[00:05.00]<00:05.00>bye <00:05.50>now<00:06.50>\n"))
    }

    @Test fun newTextKeepsTheTimesOfUnchangedLines() {
        val d = LyricsDraft.fromText("one\ntwo\nthree").tap(1_000).tap(2_000).tap(3_000)
        val edited = d.retext("one\ntwo!\nthree")
        assertEquals(listOf(1_000L, -1L, 3_000L), edited.lines.map { it.timeMs })
        assertEquals("the cursor goes to the first line without a time", 1, edited.line)
        assertTrue(edited.canUndo)
    }

    @Test fun existingLyricsAreTheStartingPoint() {
        val lines = listOf(MusicApi.LyricLine(1_000, "first line"), MusicApi.LyricLine(-1, "second"),
            MusicApi.LyricLine(3_000, "word line", listOf(MusicApi.LyricWord(3_000, "word "), MusicApi.LyricWord(3_400, "line"))))
        val d = LyricsDraft.fromLines(lines)
        assertEquals(listOf(1_000L, -1L, 3_000L), d.lines.map { it.timeMs })
        assertEquals(1, d.line)
        assertTrue(d.lines[2].wordsTimed)
        assertEquals(1_000L, d.lines[0].words[0].startMs)
    }

    @Test fun undoIsBounded() {
        var d = LyricsDraft.fromText((1..300).joinToString("\n") { "line $it" })
        repeat(300) { d = d.tap(it * 1_000L) }
        var steps = 0
        while (d.canUndo) { d = d.undo(); steps++ }
        assertEquals(LyricsDraft.HISTORY, steps)
    }
}
