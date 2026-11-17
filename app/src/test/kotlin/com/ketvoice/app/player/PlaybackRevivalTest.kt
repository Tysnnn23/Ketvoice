package com.ketvoice.app.player

import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.player.ResumeDecision.Verdict
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** When playback that Android ended is brought back by itself (6.1), and what it comes back to. */
class ResumeDecisionTest {
    private fun decide(reason: Int? = ApplicationExitInfo.REASON_LOW_MEMORY, since: Long = 40_000L, attempts: Int = 0,
                       enabled: Boolean = true, wasPlaying: Boolean = true, playingNow: Boolean = false) =
        ResumeDecision.decide(enabled, wasPlaying, playingNow, reason, since, attempts)

    @Test fun androidEndingPlayingMusicBringsItBack() {
        for (reason in listOf(ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_FREEZER,
                ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_UNKNOWN, null))
            assertEquals("reason $reason", Verdict.Resume, decide(reason))
    }

    @Test fun theListenerStoppingItIsNeverUndone() {
        assertEquals(Verdict.ByUser, decide(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("force-stop", Verdict.ByUser, decide(ApplicationExitInfo.REASON_USER_STOPPED))
        assertEquals("paused, or swiped away with nothing playing", Verdict.NotPlaying, decide(wasPlaying = false))
        assertEquals(Verdict.Off, decide(enabled = false))
    }

    @Test fun crashesAndUpdatesAreNotResumed() {
        for (reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_PERMISSION_CHANGE))
            assertEquals("reason $reason", Verdict.NotSystem, decide(reason))
    }

    @Test fun onlyWithinMinutesAndAFewTimes() {
        assertEquals(Verdict.Resume, decide(since = ResumeDecision.WINDOW_MS))
        assertEquals(Verdict.TooLate, decide(since = ResumeDecision.WINDOW_MS + 1))
        assertEquals("a clock that went back", Verdict.TooLate, decide(since = -5_000L))
        assertEquals(Verdict.Resume, decide(attempts = ResumeDecision.MAX_ATTEMPTS - 1))
        assertEquals(Verdict.TooManyAttempts, decide(attempts = ResumeDecision.MAX_ATTEMPTS))
        assertEquals(Verdict.AlreadyPlaying, decide(playingNow = true))
        assertEquals("a phone switched off and on is not a kill", Verdict.Rebooted,
            ResumeDecision.decide(true, true, false, null, 60_000L, 0, rebooted = true))
    }

    @Test fun theListenersWishComesBeforeTheWindow() {
        // A pause long ago and a stop by the listener both say why, not "too late".
        assertEquals(Verdict.NotPlaying, decide(wasPlaying = false, since = 3_600_000L))
        assertEquals(Verdict.ByUser, decide(ApplicationExitInfo.REASON_USER_STOPPED, since = 3_600_000L, attempts = 9))
    }

    @Test fun theMarkOfTheSameTitleGivesThePlace() {
        assertEquals(93_000L, ResumeDecision.resumePoint("t1", 93_000L, "t1", 80_000L))
        // The saved session moved on (a mix handed over): its own place is used.
        assertEquals(12_000L, ResumeDecision.resumePoint("t1", 93_000L, "t2", 12_000L))
        assertEquals(40_000L, ResumeDecision.resumePoint(null, 0L, "t2", 40_000L))
        assertEquals(0L, ResumeDecision.resumePoint("t1", -50L, "t1", 10L))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RevivalStoreTest {
    private val store get() = RevivalStore(ApplicationProvider.getApplicationContext())

    @Test fun thePlaceIsKeptAsTheMusicPlays() {
        val s = store
        s.playing("qa:song", 15_000L, now = 1_000L)
        s.playing("qa:song", 20_000L, now = 6_000L)
        val mark = store.read()
        assertTrue(mark.playing)
        assertEquals("qa:song", mark.trackId)
        assertEquals(20_000L, mark.positionMs)
        assertEquals(6_000L, mark.at)
    }

    @Test fun aPauseEndsWhatCouldBeResumed() {
        val s = store
        s.playing("qa:song", 15_000L, now = 1_000L)
        s.attempted(markAt = 1_000L, now = 2_000L)
        s.stopped(now = 3_000L)
        val mark = store.read()
        assertFalse(mark.playing)
        assertEquals("a pause starts a new run of attempts", 0, mark.attempts)
        assertEquals("the place stays for the session", 15_000L, mark.positionMs)
    }

    @Test fun attemptsCountUntilPlaybackHolds() {
        val s = store
        s.playing("qa:song", 1_000L, now = 0L)
        s.attempted(markAt = 0L, now = 10_000L)
        s.attempted(markAt = 0L, now = 20_000L)
        s.playing("qa:song", 9_000L, now = 30_000L)
        assertEquals("a resume that is soon ended again counts", 2, store.read().attempts)
        s.playing("qa:song", 9_000L, now = 20_000L + RevivalStore.STEADY_MS + 1)
        assertEquals("ten minutes of music start a new run", 0, store.read().attempts)
    }

    @Test fun anEndIsLookedAtOnce() {
        val s = store
        s.playing("qa:song", 1_000L, now = 500L)
        s.handled(500L)
        val mark = store.read()
        assertEquals(mark.at, mark.handled)
    }
}

/** The ways back read why Android ended the app and write what they decided to the playback journal, once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackRevivalCheckTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun ended(reason: Int, at: Long) {
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        org.robolectric.Shadows.shadowOf(am).addApplicationExitInfo(
            org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(app.packageName).setReason(reason).setTimestamp(at).build())
    }

    @Test fun aForceStopIsNeverUndoneAndIsLookedAtOnce() {
        com.ketvoice.app.data.PlaybackJournal.init(app)
        val now = System.currentTimeMillis()
        RevivalStore(app).playing("qa:song", 30_000L, now - 20_000L)
        ended(ApplicationExitInfo.REASON_USER_STOPPED, now - 10_000L)
        assertFalse(kotlinx.coroutines.runBlocking { PlaybackRevival.check(app, PlaybackRevival.WAY_APP) })
        val lines = com.ketvoice.app.data.PlaybackJournal.snapshot()
        assertTrue(lines.last(), lines.last().contains("force-stopped") && lines.last().contains(Verdict.ByUser.note))
        assertEquals(RevivalStore(app).read().at, RevivalStore(app).read().handled)
        // Another way back finds it looked at already: nothing more in the journal.
        assertFalse(kotlinx.coroutines.runBlocking { PlaybackRevival.check(app, "alarm") })
        assertEquals(lines.size, com.ketvoice.app.data.PlaybackJournal.snapshot().size)
    }

    @Test fun pausedMusicIsLeftAlone() {
        com.ketvoice.app.data.PlaybackJournal.init(app)
        val now = System.currentTimeMillis()
        RevivalStore(app).playing("qa:song", 30_000L, now - 20_000L)
        RevivalStore(app).stopped(now - 15_000L)
        ended(ApplicationExitInfo.REASON_LOW_MEMORY, now - 10_000L)
        val before = com.ketvoice.app.data.PlaybackJournal.snapshot().size
        assertFalse(kotlinx.coroutines.runBlocking { PlaybackRevival.check(app, "alarm") })
        assertEquals(before, com.ketvoice.app.data.PlaybackJournal.snapshot().size)
    }

    @Test fun theSwitchTurnsItOff() {
        com.ketvoice.app.data.PlaybackJournal.init(app)
        val settings = app.getSharedPreferences("ketvoice_settings", android.content.Context.MODE_PRIVATE)
        settings.edit().putBoolean("autoResume", false).commit()
        try {
            val now = System.currentTimeMillis()
            RevivalStore(app).playing("qa:song", 30_000L, now - 20_000L)
            ended(ApplicationExitInfo.REASON_LOW_MEMORY, now - 10_000L)
            assertFalse(kotlinx.coroutines.runBlocking { PlaybackRevival.check(app, "alarm") })
            val last = com.ketvoice.app.data.PlaybackJournal.snapshot().last()
            assertTrue(last, last.contains("low memory") && last.contains(Verdict.Off.note))
        } finally {
            settings.edit().remove("autoResume").commit()
        }
    }
}
