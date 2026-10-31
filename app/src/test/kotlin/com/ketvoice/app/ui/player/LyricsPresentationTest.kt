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
    @Test fun sustainOnlyUsesTheRealSungWindowAndHonorsReducedMotion() {
        assertEquals(0f,LyricsPresentation.sustain(1600,0,1200,false))
        assertEquals(0f,LyricsPresentation.sustain(300,0,600,false))
        assertEquals(0f,LyricsPresentation.sustain(700,0,1500,true))
        assertEquals(0f,LyricsPresentation.sustain(0,0,1500,false))
        assertTrue(LyricsPresentation.sustain(700,0,1500,false)>.9f)
        assertTrue(LyricsPresentation.sustain(1480,0,1500,false)<.1f)
    }
}
