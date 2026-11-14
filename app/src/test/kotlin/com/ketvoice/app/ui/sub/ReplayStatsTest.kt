package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class ReplayStatsTest {
    private fun track(id: String, artist: String) = Track(id, "Title $id", artist, "Album")

    @Test fun ranksTitlesAndArtistsByCountedPlays() {
        val known = listOf(track("a", "Nova"), track("b", "Nova, Guest"), track("c", "Orion"))
        val recent = listOf(track("d", "Artiste inconnu"))
        val stats = replayStats(known, recent, mapOf("a" to 4, "b" to 1, "c" to 3, "d" to 9, "gone" to 2))
        assertEquals(19, stats.totalPlays)
        assertEquals(5, stats.distinctTracks)
        // A play of a title the app no longer knows is counted but cannot be listed.
        assertEquals(listOf("d", "a", "c", "b"), stats.topTracks.map { it.first.id })
        assertEquals(listOf(9, 4, 3, 1), stats.topTracks.map { it.second })
        // "Artiste inconnu" is a placeholder, never an artist of the listener.
        assertEquals(listOf("Nova", "Orion"), stats.topArtists.map { it.name })
        assertEquals(5, stats.topArtists.first().plays)
        assertEquals(2, stats.artistCount)
    }

    @Test fun nothingListenedGivesAnEmptyReplay() {
        val stats = replayStats(listOf(track("a", "Nova")), emptyList(), emptyMap())
        assertEquals(0, stats.totalPlays)
        assertTrue(stats.topTracks.isEmpty())
        assertTrue(stats.topArtists.isEmpty())
    }

    @Test fun topListsAreBoundedAndStable() {
        val known = (1..40).map { track("t$it", "Artist $it") }
        val counts = known.associate { it.id to 2 }
        val stats = replayStats(known, emptyList(), counts)
        assertEquals(20, stats.topTracks.size)
        assertEquals(10, stats.topArtists.size)
        assertEquals(40, stats.artistCount)
        assertEquals(stats.topTracks.map { it.first.id }, replayStats(known.reversed(), emptyList(), counts).topTracks.map { it.first.id })
    }
}
