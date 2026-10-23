package com.ketvoice.app.ui.library

import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AlbumOrderTest {
    private val tracks = listOf(
        Track("1", "one", "Zed", "Échos", albumId = "MPRE1"),
        Track("2", "two", "Alice", "Zèbre", albumId = "MPRE2"),
        Track("3", "three", "Alice", "Alpha", albumId = "MPRE3"))
    @Test fun recentOrderIsNotMutated() {
        assertSame(tracks, AlbumOrder.sort(tracks, 0))
        assertEquals(listOf("1", "2", "3"), tracks.map { it.id })
    }
    @Test fun titlesUseLocaleCollationAndKeepIdentity() {
        assertEquals(listOf("3", "1", "2"), AlbumOrder.sort(tracks, 1, Locale.FRENCH).map { it.id })
    }
    @Test fun artistsUseAlbumAsTieBreakAndKeepIdentity() {
        assertEquals(listOf("3", "2", "1"), AlbumOrder.sort(tracks, 2, Locale.FRENCH).map { it.id })
    }
    @Test fun unknownSortAndEmptyCollectionsAreSafe() {
        assertSame(tracks, AlbumOrder.sort(tracks, -1))
        assertTrue(AlbumOrder.sort(emptyList(), 1).isEmpty())
    }
}
