package com.ketvoice.app.player

import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.LyricsTranslation
import com.ketvoice.app.data.MusicApi.LyricLine
import com.ketvoice.app.data.MusicApi.LyricWord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The sung line outside the app (6.1): which line, until when, how often it is sent, and when it is wanted. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveLyricsTest {
    private val lines = listOf(
        LyricLine(5_000, "First line"),
        LyricLine(9_000, "Second line"),
        LyricLine(13_000, ""),
        LyricLine(15_000, "After the blank"),
    )

    @Test fun theIntroShowsARestThenTheFirstLine() {
        val m = LyricTimeline.at(lines, 1_000)!!
        assertEquals(-1, m.index)
        assertEquals(LyricTimeline.REST, m.current)
        assertEquals("First line", m.next)
        assertEquals(5_000L, m.endsAt)
    }

    @Test fun theSungLineAndTheNextOne() {
        val m = LyricTimeline.at(lines, 5_000)!!
        assertEquals(0, m.index); assertEquals("First line", m.current); assertEquals("Second line", m.next); assertEquals(9_000L, m.endsAt)
        val m2 = LyricTimeline.at(lines, 12_999)!!
        assertEquals(1, m2.index); assertNull("a blank line is not announced", m2.next)
        val blank = LyricTimeline.at(lines, 13_500)!!
        assertEquals(2, blank.index); assertEquals(LyricTimeline.REST, blank.current); assertEquals("After the blank", blank.next)
    }

    @Test fun theLastLineStaysWhenItsEndIsUnknown() {
        val m = LyricTimeline.at(lines, 200_000)!!
        assertEquals(3, m.index); assertNull(m.next); assertNull("nothing left to wait for", m.endsAt)
    }

    @Test fun aLongBreakAfterATimedLastWordShowsARest() {
        val timed = listOf(
            LyricLine(1_000, "Sung", words = listOf(LyricWord(1_000, "Sung", 3_000))),
            LyricLine(30_000, "After the solo"),
        )
        val during = LyricTimeline.at(timed, 4_000)!!
        assertEquals("Sung", during.current)
        assertEquals("the line holds two seconds after its last word", 5_000L, during.endsAt)
        val solo = LyricTimeline.at(timed, 5_000)!!
        assertEquals(-1, solo.index); assertEquals(LyricTimeline.REST, solo.current); assertEquals("After the solo", solo.next)
        assertEquals(30_000L, solo.endsAt)
        // A short gap is no break.
        val close = listOf(LyricLine(1_000, "A", words = listOf(LyricWord(1_000, "A", 3_000))), LyricLine(6_000, "B"))
        assertEquals("A", LyricTimeline.at(close, 5_500)!!.current)
        assertEquals(6_000L, LyricTimeline.at(close, 5_500)!!.endsAt)
        // The end of the song: the rest after the last word, and nothing more scheduled.
        val end = LyricTimeline.at(timed.take(1), 9_000)!!
        assertEquals(LyricTimeline.REST, end.current); assertNull(end.endsAt)
    }

    @Test fun untimedLyricsShowNothing() {
        assertNull(LyricTimeline.at(listOf(LyricLine(-1, "Plain")), 1_000))
        assertNull(LyricTimeline.at(emptyList(), 1_000))
    }

    @Test fun theWaitFollowsTheSpeed() {
        assertEquals(1_000L + LyricTimeline.MARGIN_MS, LyricTimeline.wallDelay(6_000, 5_000, 1f))
        assertEquals(500L + LyricTimeline.MARGIN_MS, LyricTimeline.wallDelay(6_000, 5_000, 2f))
        assertEquals("a broken speed counts as normal", 1_000L + LyricTimeline.MARGIN_MS, LyricTimeline.wallDelay(6_000, 5_000, Float.NaN))
        assertEquals(LyricTimeline.MIN_WAIT_MS, LyricTimeline.wallDelay(5_000, 5_000, 1f))
    }

    private fun state(id: String = "t1", timed: Boolean = true) = LyricsRepo.State(lines = lines, isTimed = timed, trackId = id)
    private fun head(pos: Long, id: String = "t1", play: Boolean = true, playing: Boolean = play) =
        Playhead(id, pos, play, playing, 1f, "Song", "Artist")

    @Test fun theVoiceStageDelayAndTheOffsetMoveTheLine() {
        // The voice stage plays the sound 93 ms late: at 9 050 ms of the stream, "Second line" is not heard yet.
        val (late, wait) = LiveLyrics.lineFor(head(9_050), state(), 0, 93, null)
        assertEquals("First line", late!!.current)
        assertEquals(43L + LyricTimeline.MARGIN_MS, wait)
        assertEquals("Second line", LiveLyrics.lineFor(head(9_100), state(), 0, 93, null).first!!.current)
        // The listener's own offset (lyrics ahead by half a second).
        assertEquals("Second line", LiveLyrics.lineFor(head(8_600), state(), 500, 0, null).first!!.current)
        assertEquals("Song", late.title); assertEquals("Artist", late.artist); assertEquals("t1", late.trackId)
    }

    @Test fun nothingWhenPausedForAnotherTitleOrUntimed() {
        assertNull(LiveLyrics.lineFor(head(6_000, play = false), state(), 0, 0, null).first)
        assertNull("lyrics still of the previous title", LiveLyrics.lineFor(head(6_000, id = "t2"), state(), 0, 0, null).first)
        assertNull(LiveLyrics.lineFor(head(6_000), state(timed = false), 0, 0, null).first)
        assertNull(LiveLyrics.lineFor(null, state(), 0, 0, null).first)
        // Buffering: the line shows, nothing is scheduled until the sound moves again.
        val (line, wait) = LiveLyrics.lineFor(head(6_000, playing = false), state(), 0, 0, null)
        assertEquals("First line", line!!.current); assertNull(wait)
    }

    @Test fun theTranslationOfTheSungLine() {
        val tr = LyricsTranslation.Live("t1", mapOf(0 to "Première ligne", 1 to "Deuxième ligne"))
        assertEquals("Première ligne", LiveLyrics.lineFor(head(6_000), state(), 0, 0, tr).first!!.translation)
        assertNull("another title's translation", LiveLyrics.lineFor(head(6_000), state(), 0, 0, tr.copy(trackId = "t2")).first!!.translation)
        assertNull("no translation of the rest", LiveLyrics.lineFor(head(1_000), state(), 0, 0, tr).first!!.translation)
    }

    @Test fun theThrottleSpacesPosts() {
        val t = PostThrottle(1_000)
        assertEquals(0L, t.waitAt(50))
        t.posted(100)
        assertEquals(700L, t.waitAt(400))
        assertEquals(0L, t.waitAt(1_100))
        assertEquals(0L, t.waitAt(5_000))
        t.reset(); assertEquals(0L, t.waitAt(101))
    }

    @Test fun theGateFollowsTheSettingTheDeviceAndAndroid() {
        assertTrue(LockscreenLyricsGate.wanted(setting = true, quest = false, allowed = true))
        assertFalse(LockscreenLyricsGate.wanted(setting = false, quest = false, allowed = true))
        assertFalse("no lock screen on Quest", LockscreenLyricsGate.wanted(setting = true, quest = true, allowed = true))
        assertFalse("notifications refused", LockscreenLyricsGate.wanted(setting = true, quest = false, allowed = false))
        assertFalse(LiveLyrics.Demand(notification = false).any)
        assertTrue(LiveLyrics.Demand(notification = true).any)
    }

    /** A deck moving in the test's virtual time. */
    private class FakeDeck(private val scope: TestScope) {
        var base = 0L; var since = 0L; var playing = true; var id = "t1"
        fun position() = if (playing) base + (scope.testScheduler.currentTime - since) else base
        fun seek(to: Long) { base = to; since = scope.testScheduler.currentTime }
        fun pause() { base = position(); since = scope.testScheduler.currentTime; playing = false }
        fun play() { since = scope.testScheduler.currentTime; playing = true }
        fun head() = Playhead(id, position(), playing, playing, 1f, "Song", "Artist")
    }

    @Test fun theEngineWakesOnlyAtLineChanges() = runTest {
        val deck = FakeDeck(this)
        var screen = true
        var demand = LiveLyrics.Demand(notification = true)
        val inputs = MutableStateFlow(LyricsInputs(state(), emptyMap(), 0, null, 0))
        val engine = LiveLyricsEngine(backgroundScope, deck::head, { screen }, { demand }, inputs)
        engine.start(); runCurrent()
        assertEquals(LyricTimeline.REST, LiveLyrics.state.value.line!!.current)
        assertTrue(LiveLyrics.state.value.notify)
        advanceTimeBy(5_100)
        assertEquals("First line", LiveLyrics.state.value.line!!.current)
        assertEquals("one wake-up for the first line", 1, engine.wakeups)
        advanceTimeBy(4_000)
        assertEquals("Second line", LiveLyrics.state.value.line!!.current)
        assertEquals(2, engine.wakeups)

        // Paused: the line goes, and nothing wakes however long the pause.
        deck.pause(); engine.poke(); runCurrent()
        assertNull(LiveLyrics.state.value.line)
        val beforePause = engine.wakeups
        advanceTimeBy(600_000)
        assertEquals(beforePause, engine.wakeups)

        // A seek while playing: the line of the new place at once.
        deck.play(); deck.seek(15_500); engine.poke(); runCurrent()
        assertEquals("After the blank", LiveLyrics.state.value.line!!.current)
        // The last line has no known end: nothing more is scheduled.
        val atEnd = engine.wakeups
        advanceTimeBy(120_000)
        assertEquals(atEnd, engine.wakeups)

        // Screen off: nothing scheduled; screen on again: the line is brought up to date.
        deck.seek(5_100); engine.poke(); runCurrent()
        assertEquals("First line", LiveLyrics.state.value.line!!.current)
        screen = false; engine.poke(); runCurrent()
        val dark = engine.wakeups
        advanceTimeBy(20_000)
        assertEquals("no wake-up with the screen off", dark, engine.wakeups)
        screen = true; engine.poke(); runCurrent()
        assertEquals("After the blank", LiveLyrics.state.value.line!!.current)

        // A new title whose lyrics are still loading: nothing shown until they arrive.
        deck.id = "t2"; deck.seek(0); engine.poke(); runCurrent()
        assertNull(LiveLyrics.state.value.line)
        inputs.value = inputs.value.copy(lyrics = state(id = "t2")); runCurrent()
        assertEquals(LyricTimeline.REST, LiveLyrics.state.value.line!!.current)

        // Nobody wants the line (setting off): nothing, and no work.
        demand = LiveLyrics.Demand(notification = false)
        inputs.value = inputs.value.copy(demandEpoch = 1); runCurrent()
        assertNull(LiveLyrics.state.value.line)
        val off = engine.wakeups
        advanceTimeBy(60_000)
        assertEquals(off, engine.wakeups)
        engine.stop()
    }
}
