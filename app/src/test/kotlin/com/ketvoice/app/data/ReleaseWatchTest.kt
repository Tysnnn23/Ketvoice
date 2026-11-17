package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class ReleaseWatchTest {
    private fun album(id: String, year: Int? = 2026) = Album(id, "Titre $id", "Artiste", if (year != null) "Album · $year" else "Album")

    @Test fun anArtistNewToTheWatchAnnouncesNothing() {
        assertTrue(ReleaseWatch.fresh(null, listOf(album("a"), album("b")), 2026).isEmpty())
    }

    @Test fun onlyReleasesNotSeenBeforeAreNew() {
        val found = ReleaseWatch.fresh(setOf("a"), listOf(album("c"), album("b"), album("a")), 2026)
        assertEquals(listOf("c", "b"), found.map { it.id })
    }

    @Test fun anOldReleaseThatJustAppearedInTheListIsNotNews() {
        val found = ReleaseWatch.fresh(setOf("a"), listOf(album("old", 2009), album("last-year", 2025), album("unknown", null)), 2026)
        assertEquals(listOf("last-year", "unknown"), found.map { it.id })
    }

    @Test fun whatIsRememberedKeepsTheNewestFirstAndIsBounded() {
        val seen = (1..200).map { "old$it" }.toSet()
        val kept = ReleaseWatch.remembered(seen, listOf(album("new1"), album("new2")))
        assertEquals(listOf("new1", "new2"), kept.take(2))
        assertEquals(120, kept.size)
        assertEquals(kept.size, kept.toSet().size)
    }
}
