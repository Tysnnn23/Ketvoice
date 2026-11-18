package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ListeningAndHomeTest {
    private fun song(id: Int) = Track("$id", "Titre $id", "Artiste", "Album")

    @Test fun listenedTimeAccumulatesOnlyRealPlayedDeltas() {
        ListeningHistory.resetListenedMs()
        // Deux tics normaux de lecture (≈200 ms chacun) : comptés.
        ListeningHistory.addListenedMs(200)
        ListeningHistory.addListenedMs(200)
        // Seek ou saut de piste : delta nul ou négatif, jamais compté.
        ListeningHistory.addListenedMs(0)
        ListeningHistory.addListenedMs(-4_000)
        // Shown every PUBLISH_MS of listening (6.6), or at once by a flush.
        assertEquals(0L, ListeningHistory.listenedMs.value)
        ListeningHistory.flushListenedMs()
        assertEquals(400L, ListeningHistory.listenedMs.value)
        ListeningHistory.resetListenedMs()
        assertEquals(0L, ListeningHistory.listenedMs.value)
    }

    /** 6.6: the listening figures every page reads move every few seconds, not at each 200 ms tick of the player. */
    @Test fun listeningFiguresMoveEveryFewSecondsNotAtEachTick() {
        ListeningHistory.resetListenedMs()
        val months0 = ListeningHistory.months.value
        val days0 = ListeningHistory.days.value
        val years0 = ListeningHistory.years.value
        // 4.8 s of ticks: nothing published yet, so no page recomputes its week, month or year.
        repeat(24) { ListeningHistory.addListenedMs(200) }
        assertSame(months0, ListeningHistory.months.value)
        assertSame(days0, ListeningHistory.days.value)
        assertSame(years0, ListeningHistory.years.value)
        assertEquals(0L, ListeningHistory.listenedMs.value)
        // The 25th tick reaches PUBLISH_MS: everything moves at once, nothing lost.
        ListeningHistory.addListenedMs(200)
        assertEquals(ListeningHistory.PUBLISH_MS, ListeningHistory.listenedMs.value)
        assertNotSame(months0, ListeningHistory.months.value)
        ListeningHistory.resetListenedMs()
    }

    @Test fun actualRecentMovesToFrontWithoutDuplicates() {
        val history = listOf(song(1), song(2), song(3))
        val updated = ListeningHistory.recentWith(history, song(2))
        assertEquals(listOf("2", "1", "3"), updated.map { it.id })
    }

    @Test fun sameRecordingFromAnotherProviderIsNotADuplicateRecent() {
        val old = song(1)
        val alternate = old.copy(id = "yt:alternate")
        assertEquals(listOf(alternate), ListeningHistory.recentWith(listOf(old), alternate))
    }

    @Test fun recentHistoryIsBounded() {
        assertEquals(80, ListeningHistory.recentWith((1..100).map(::song), song(101)).size)
    }

    @Test fun freshLaunchPrefersUnseenCardsAndKeepsUniqueTracks() {
        val pool = (1..25).map(::song)
        val selected = HomeFeed.pick(pool + pool, setOf("1", "2", "3", "4"), 12, Random(5))
        assertEquals(12, selected.size)
        assertEquals(12, selected.map { it.id }.toSet().size)
        assertTrue(selected.none { it.id in setOf("1", "2", "3", "4") })
    }

    @Test fun offlinePoolCanStillFillAShelfWithoutInventingTracks() {
        val pool = (1..4).map(::song)
        val selected = HomeFeed.pick(pool, pool.map { it.id }.toSet(), 12, Random(5))
        assertEquals(pool.toSet(), selected.toSet())
    }

    @Test fun personalizationPrefersFreshTracksFromFamiliarArtists() {
        val recent = Track("old", "Old song", "Fav artist", "Album")
        val familiar = Track("new", "New song", "Fav artist", "Album")
        val other = Track("other", "Other song", "Other artist", "Album")
        val alternate = recent.copy(id = "another-provider")
        val ranked = HomeFeed.personalizedPick(listOf(alternate, other, familiar), emptySet(), listOf(recent),
            emptyList(), 3, Random(5))
        assertEquals("new", ranked.first().id)
        assertEquals("another-provider", ranked.last().id)
    }

    @Test fun personalizationStillFillsASmallOfflinePool() {
        val track = song(1)
        assertEquals(listOf(track), HomeFeed.personalizedPick(listOf(track), setOf(track.id), listOf(track),
            emptyList(), 12, Random(1)))
    }

    @Test fun lyricTimelineMovesForwardAndBackwardOnSeek() {
        val state = LyricsRepo.State(lines = listOf(MusicApi.LyricLine(1000, "A"), MusicApi.LyricLine(2000, "B"), MusicApi.LyricLine(3000, "C")))
        assertEquals(-1, state.indexAt(0))
        assertEquals(0, state.indexAt(1000))
        assertEquals(2, state.indexAt(4000))
        assertEquals(0, state.indexAt(1500))
        assertEquals(-1, state.copy(isTimed = false).indexAt(4000))
    }
}
