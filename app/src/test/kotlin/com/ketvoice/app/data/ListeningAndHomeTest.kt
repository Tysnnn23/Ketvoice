package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ListeningAndHomeTest {
    private fun song(id: Int) = Track("$id", "Titre $id", "Artiste", "Album")

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

    @Test fun lyricTimelineMovesForwardAndBackwardOnSeek() {
        val state = LyricsRepo.State(lines = listOf(MusicApi.LyricLine(1000, "A"), MusicApi.LyricLine(2000, "B"), MusicApi.LyricLine(3000, "C")))
        assertEquals(-1, state.indexAt(0))
        assertEquals(0, state.indexAt(1000))
        assertEquals(2, state.indexAt(4000))
        assertEquals(0, state.indexAt(1500))
        assertEquals(-1, state.copy(isTimed = false).indexAt(4000))
    }
}
