package com.ketvoice.app.data

import com.ketvoice.app.data.SmartOfflinePlan.Candidate
import com.ketvoice.app.data.SmartOfflinePlan.Owned
import com.ketvoice.app.data.SmartOfflinePlan.Reason
import com.ketvoice.app.data.SmartOfflinePlan.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SmartOfflinePlanTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private fun t(id: String, minutes: Int = 4) = Track(id, "Titre $id", "Artiste", "Album", durationMs = minutes * 60_000L)
    private val mb = 1_000_000L
    private fun ranked(vararg ids: String) = ids.mapIndexed { i, id -> Candidate(t(id), 100.0 - i, Reason.Played) }
    private val tenMb: (Track) -> Long = { 10 * mb }

    // ------------------------------------------------------------------ ranking

    @Test fun aNewFavouriteAndAMuchPlayedTitleComeFirstAndOldFavouritesAreNotNew() {
        val signals = Signals(
            favorites = listOf(t("fresh") to now - 2 * day, t("old") to 0L, t("month-ago") to now - 40 * day),
            recentPlays = mapOf("hit" to 12, "once" to 1, "twice" to 2),
            known = listOf("hit", "once", "twice").associateWith { t(it) })
        val ids = SmartOfflinePlan.rank(signals, emptySet(), now).map { it.track.id }
        assertEquals(listOf("hit", "fresh", "twice"), ids)
        assertEquals(Reason.Favorite, SmartOfflinePlan.rank(signals, emptySet(), now).first { it.track.id == "fresh" }.reason)
    }

    @Test fun aFavouritePlayedOftenOutranksAFavouriteAlone() {
        val signals = Signals(favorites = listOf(t("a") to now - day, t("b") to now - day),
            recentPlays = mapOf("b" to 3), known = mapOf("b" to t("b")))
        assertEquals(listOf("b", "a"), SmartOfflinePlan.rank(signals, emptySet(), now).map { it.track.id })
    }

    @Test fun deletedByHandLocalFilesAndManualDownloadsAreNeverProposed() {
        val signals = Signals(favorites = listOf(t("gone") to now, t("local:1") to now, t("ok") to now, t("mine") to now))
        val ids = SmartOfflinePlan.rank(signals, excluded = setOf("gone", "mine"), now = now).map { it.track.id }
        assertEquals(listOf("ok"), ids)
    }

    @Test fun theMostPlayedPlaylistsGiveTheirTitles() {
        val heard = listOf(t("p1"), t("p2"))
        val silent = listOf(t("s1"))
        val signals = Signals(playlists = listOf(silent, heard), recentPlays = mapOf("p1" to 1))
        val ranked = SmartOfflinePlan.rank(signals, emptySet(), now)
        assertEquals(listOf("p1", "p2"), ranked.map { it.track.id })
        assertTrue(ranked.all { it.reason == Reason.Playlist })
        assertEquals(1, SmartOfflinePlan.topPlaylists(listOf(silent, heard), mapOf("p1" to 1)).size)
    }


    // ------------------------------------------------------------------ the plan

    @Test fun downloadsInRankOrderUntilTheLimit() {
        val plan = SmartOfflinePlan.plan(ranked("a", "b", "c", "d"), emptyList(), 35 * mb, emptySet(), tenMb)
        assertEquals(listOf("a", "b", "c"), plan.download.map { it.track.id })
        assertTrue(plan.evict.isEmpty())
        assertTrue(plan.autoBytesAfter <= 35 * mb)
    }

    @Test fun aSmallerTitleStillFitsAfterOneThatDoesNot() {
        val sizes = mapOf("big" to 30 * mb, "small" to 5 * mb)
        val plan = SmartOfflinePlan.plan(ranked("a", "big", "small"), emptyList(), 20 * mb, emptySet(), { sizes[it.id] ?: 10 * mb })
        assertEquals(listOf("a", "small"), plan.download.map { it.track.id })
    }

    @Test fun roomIsMadeByRemovingTheLeastPlayedAutomaticDownloadsFirst() {
        val owned = listOf(
            Owned("loved", 10 * mb, auto = true, plays = 30, downloadedAt = 1),
            Owned("meh", 10 * mb, auto = true, plays = 1, downloadedAt = 5),
            Owned("never", 10 * mb, auto = true, plays = 0, downloadedAt = 9))
        // Two new titles rank above every existing one: 20 MB must be freed under a 30 MB limit.
        val plan = SmartOfflinePlan.plan(ranked("n1", "n2"), owned, 30 * mb, emptySet(), tenMb)
        assertEquals(listOf("n1", "n2"), plan.download.map { it.track.id })
        assertEquals(listOf("never", "meh"), plan.evict)
        assertEquals(30 * mb, plan.autoBytesAfter)
    }

    @Test fun nothingIsRemovedWhileThereIsRoom() {
        val owned = listOf(Owned("old", 10 * mb, auto = true, plays = 0, downloadedAt = 1))
        val plan = SmartOfflinePlan.plan(ranked("n1"), owned, 100 * mb, emptySet(), tenMb)
        assertTrue(plan.evict.isEmpty())
        assertEquals(listOf("n1"), plan.download.map { it.track.id })
    }

    @Test fun aLowerLimitRemovesAutomaticDownloadsUntilItFits() {
        val owned = (1..5).map { Owned("a$it", 10 * mb, auto = true, plays = it, downloadedAt = it.toLong()) }
        val plan = SmartOfflinePlan.plan(emptyList(), owned, 25 * mb, emptySet(), tenMb)
        assertEquals(listOf("a1", "a2", "a3"), plan.evict)
        assertTrue(plan.download.isEmpty())
    }

    @Test fun aWantedAutomaticDownloadIsKeptEvenIfLittlePlayed() {
        val owned = listOf(Owned("fav", 10 * mb, auto = true, plays = 0, downloadedAt = 1),
            Owned("other", 10 * mb, auto = true, plays = 5, downloadedAt = 1))
        val plan = SmartOfflinePlan.plan(ranked("fav", "new"), owned, 20 * mb, emptySet(), tenMb)
        assertEquals(listOf("other"), plan.evict)
        assertEquals(listOf("new"), plan.download.map { it.track.id })
    }

    @Test fun manualDownloadsAreNeverRemovedNorCountedNorFetchedAgain() {
        val owned = listOf(Owned("mine", 500 * mb, auto = false, plays = 0, downloadedAt = 0),
            Owned("auto", 10 * mb, auto = true, plays = 0, downloadedAt = 0))
        val plan = SmartOfflinePlan.plan(ranked("mine", "x", "y"), owned, 20 * mb, emptySet(), tenMb)
        assertFalse("mine" in plan.evict)
        assertFalse(plan.download.any { it.track.id == "mine" })
        assertEquals(listOf("auto"), plan.evict)
        assertEquals(listOf("x", "y"), plan.download.map { it.track.id })
    }

    @Test fun aProtectedTitleIsNeverRemovedAndNewOnesWaitInstead() {
        val owned = listOf(Owned("playing", 20 * mb, auto = true, plays = 0, downloadedAt = 0))
        val plan = SmartOfflinePlan.plan(ranked("n1", "n2"), owned, 20 * mb, setOf("playing"), tenMb)
        assertTrue(plan.evict.isEmpty())
        assertTrue(plan.download.isEmpty())
        assertTrue(plan.autoBytesAfter <= 20 * mb)
    }

    @Test fun aPassDownloadsAtMostItsShare() {
        val many = ranked(*(1..40).map { "t$it" }.toTypedArray())
        assertEquals(SmartOfflinePlan.MAX_NEW_PER_RUN, SmartOfflinePlan.plan(many, emptyList(), Long.MAX_VALUE / 4, emptySet(), tenMb).download.size)
    }

    // ------------------------------------------------------------------ limits, sizes and plays

    @Test fun limitsInGigabytesOrAShareOfTheFreeStorageAlwaysLeaveOneGigabyte() {
        val gb = 1_000_000_000L
        assertEquals(2 * gb, SmartOfflinePlan.limitBytes(2, 25, freeBytes = 50 * gb, autoBytes = 0))
        assertEquals(10 * gb, SmartOfflinePlan.limitBytes(0, 25, freeBytes = 36 * gb, autoBytes = 4 * gb))
        assertEquals(2 * gb, SmartOfflinePlan.limitBytes(5, 25, freeBytes = 3 * gb, autoBytes = 0))
        assertEquals(0L, SmartOfflinePlan.limitBytes(1, 25, freeBytes = gb / 2, autoBytes = 0))
        assertEquals(SmartOfflinePlan.DEFAULT_GB, SmartOfflinePlan.clampGb(3))
        assertEquals(0, SmartOfflinePlan.clampGb(0))
        assertEquals(SmartOfflinePlan.DEFAULT_PERCENT, SmartOfflinePlan.clampPercent(33))
    }

    @Test fun theEstimateFollowsTheDownloadQuality() {
        val song = t("s", minutes = 4)
        val flac = SmartOfflinePlan.estimate(song, 0)
        val high = SmartOfflinePlan.estimate(song, 1)
        val quick = SmartOfflinePlan.estimate(song, 2)
        assertTrue(flac > high && high > quick)
        assertEquals(240 * 125_000L + 200_000L, flac)
    }

    @Test fun recentPlaysCountThisMonthAndTheEndOfTheLastOne() {
        val months = mapOf(
            "2026-11" to MonthStat(plays = mapOf("a" to 3)),
            "2026-10" to MonthStat(plays = mapOf("a" to 31, "b" to 62)),
            "2026-09" to MonthStat(plays = mapOf("c" to 100)))
        val plays = SmartOfflinePlan.recentPlays(months, LocalDate.of(2026, 11, 18))
        // 12 of October's 31 days are inside the 30-day window.
        assertEquals(3 + 12, plays["a"])
        assertEquals(24, plays["b"])
        assertFalse("c" in plays)
        assertEquals(mapOf("a" to 3), SmartOfflinePlan.recentPlays(months, LocalDate.of(2026, 11, 30)))
    }
}
