package com.ketvoice.app.ui.player

import com.ketvoice.app.data.MusicApi.LyricLine
import com.ketvoice.app.data.MusicApi.LyricWord
import org.junit.Assert.*
import org.junit.Test

class LyricsPresentationTest {
    @Test fun separatesTimedBacksWithoutChangingOriginalOrLeadTiming() {
        val line = LyricLine(100, "Hello (yeah) world", listOf(
            LyricWord(100, "Hello ", 800), LyricWord(300, "(yeah) ", 1200), LyricWord(1400, "world", 2400)))
        val display = LyricsPresentation.split(line)
        assertEquals("Hello world", display.lead.text)
        assertEquals(listOf(100L, 1400L), display.lead.words.map { it.startMs })
        assertEquals(display.lead.text, display.lead.words.joinToString("") { it.text })
        assertEquals("yeah", display.backs.single().text)
        assertEquals(300L, display.backs.single().words.single().startMs)
        assertEquals("Hello (yeah) world", line.text)
    }
    @Test fun nestedMultipleAndFullWidthParenthesesAreNotDuplicated() {
        val result = LyricsPresentation.split(LyricLine(0, "Main (one (two)) lyric （three）"))
        assertEquals("Main lyric", result.lead.text)
        assertEquals(listOf("one two", "three"), result.backs.map { it.text })
        assertTrue(result.lead.words.isEmpty())
    }
    @Test fun unmatchedParenthesisIsNotSilentlyDeleted() {
        val line = LyricLine(0, "Keep (unfinished")
        assertEquals(line, LyricsPresentation.split(line).lead)
    }
    @Test fun onlyBackLineIsKeptOnceBelow() {
        val result = LyricsPresentation.split(LyricLine(0, "(Oh, yeah!)"))
        assertEquals("", result.lead.text)
        assertEquals("Oh, yeah!", result.backs.single().text)
    }
    @Test fun separateBackingWindowsDoNotLightUpTogether() {
        val a = LyricLine(0,"one",listOf(LyricWord(100,"one",500)))
        val b = LyricLine(0,"two",listOf(LyricWord(900,"two",1400)))
        assertTrue(LyricsPresentation.backIsActive(a,300));assertFalse(LyricsPresentation.backIsActive(b,300))
        assertFalse(LyricsPresentation.backIsActive(a,1000));assertTrue(LyricsPresentation.backIsActive(b,1000))
    }
    @Test fun noTimingIsInventedForPlainLyrics() {
        val result=LyricsPresentation.split(LyricLine(-1,"Go (go) now"))
        assertTrue(result.lead.words.isEmpty());assertTrue(result.backs.single().words.isEmpty())
    }
    @Test fun removingABackDoesNotLeaveASpaceBeforePunctuation() {
        val line=LyricLine(0,"Hello (yeah), world",listOf(
            LyricWord(0,"Hello ",500),LyricWord(200,"(yeah), ",700),LyricWord(900,"world",1300)))
        val result=LyricsPresentation.split(line)
        assertEquals("Hello, world",result.lead.text)
        assertEquals(listOf(0L,900L),result.lead.words.map { it.startMs })
        assertEquals(result.lead.text,result.lead.words.joinToString("") { it.text })
    }
    @Test fun alignedWordsRebuildForeignLinesInsteadOfLightingWrongGlyphs() {
        val line = LyricLine(0, "你好 世界", listOf(
            LyricWord(0, "你好", 400), LyricWord(500, "世界", 900)))
        val aligned = LyricsPresentation.aligned(line)
        assertEquals(line.text, aligned.joinToString("") { it.text })
        assertEquals(listOf(0L, 500L), aligned.map { it.startMs })
        assertEquals("你好 ", aligned.first().text)
    }
    @Test fun impossibleWordMappingFallsBackToTheWholeLine() {
        val line = LyricLine(0, "Paroles affichées", listOf(
            LyricWord(100, "unrelated", 900), LyricWord(1000, "tokens", 1800)))
        val aligned = LyricsPresentation.aligned(line)
        assertEquals(1, aligned.size)
        assertEquals(line.text, aligned.single().text)
        assertEquals(100L, aligned.single().startMs)
        assertEquals(1800L, aligned.single().endMs)
    }
    @Test fun alreadyExactWordTimingsAreUntouched() {
        val words = listOf(LyricWord(0, "Go ", 500), LyricWord(600, "now", 1200))
        assertEquals(words, LyricsPresentation.aligned(LyricLine(0, "Go now", words)))
    }
    @Test fun sustainOnlyUsesTheRealSungWindowAndHonorsReducedMotion() {
        assertEquals(0f,LyricsPresentation.sustain(1600,0,1200,false))
        assertEquals(0f,LyricsPresentation.sustain(300,0,600,false))
        assertEquals(0f,LyricsPresentation.sustain(700,0,1500,true))
        assertEquals(0f,LyricsPresentation.sustain(0,0,1500,false))
        assertTrue(LyricsPresentation.sustain(1_200,0,1_500,false)>.95f)
        assertTrue(LyricsPresentation.sustain(1_480,0,1_500,false)>.95f)
        // Short syllables never glow; a held note does.
        assertEquals(0f,LyricsPresentation.sustain(300,0,600,false))
        assertTrue(LyricsPresentation.sustain(900,0,1_100,false)>0f)
        assertTrue(LyricsPresentation.sustain(1_080,0,1_100,false)>.9f)
        assertEquals(0f,LyricsPresentation.sustain(300,0,800,false))
        assertEquals(0f,LyricsPresentation.sustain(400,0,900,false))
        assertTrue(LyricsPresentation.sustain(850,0,900,false)>0f)
    }

    @Test fun thresholdIs820msOfContinuousAudioTimeNotDeclaredDuration() {
        // A declared 2 s word must stay dark until 820 ms have really elapsed.
        assertEquals(0f,LyricsPresentation.sustain(819,0,2_000,false))
        assertEquals(0f,LyricsPresentation.sustain(500,0,1_000,false)) // 500 ms syllables stay clean
        assertTrue(LyricsPresentation.sustain(850,0,2_000,false)>0f)
        assertEquals(0f,LyricsPresentation.sustain(1_000,0,800,false)) // declared under threshold is hidden
    }

    @Test fun glowRampsInAround300msAndOutAround400ms() {
        val atJustAfterThreshold = LyricsPresentation.sustain(830,0,3_000,false)
        val halfIn = LyricsPresentation.sustain(970,0,3_000,false)
        val full = LyricsPresentation.sustain(1_200,0,3_000,false)
        assertTrue(atJustAfterThreshold < halfIn && halfIn < full)
        assertTrue(full > .98f)
        val outStart = LyricsPresentation.sustain(3_060,0,3_000,false)
        val outMid = LyricsPresentation.sustain(3_200,0,3_000,false)
        assertEquals(0f,LyricsPresentation.sustain(3_400,0,3_000,false))
        assertTrue(outMid < outStart)
    }

    @Test fun glowStaysLitForTheWholeRealHeldDurationWithSubtleBreathing() {
        val start = LyricsPresentation.sustain(2_000,0,5_000,false)
        val middle = LyricsPresentation.sustain(3_500,0,5_000,false)
        val late = LyricsPresentation.sustain(4_800,0,5_000,false)
        assertTrue(start > .9f && middle > .9f && late > .9f)
        val breath = listOf(2_000L, 2_650L, 3_300L, 3_950L).map { LyricsPresentation.breath(it, 0) }
        assertTrue(breath.all { it in .94f..1.0f })
        assertEquals(.97f, LyricsPresentation.breath(0, 0), .0001f)
        assertTrue(breath.distinct().size > 2)
    }

    @Test fun diffusionGrowsWithHoldAndIsStableAtTheEnd() {
        assertTrue(LyricsPresentation.diffusion(900,0,5_000) < LyricsPresentation.diffusion(2_000,0,5_000))
        assertEquals(1f, LyricsPresentation.diffusion(5_500,0,5_000), .0001f)
    }
}
