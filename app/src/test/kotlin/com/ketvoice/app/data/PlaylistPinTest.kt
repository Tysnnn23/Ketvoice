package com.ketvoice.app.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PlaylistPinTest {
    @Test fun existingPlaylistJsonWithoutPinRemainsReadable() {
        val row = Gson().fromJson("""{"id":"old","name":"Existing","entries":[],"createdAt":42}""", LocalPlaylist::class.java)
        assertEquals("old", row.id)
        assertFalse(row.pinned)
        assertEquals(42, row.createdAt)
    }

    @Test fun pinRoundTripDoesNotChangeThePlaylistIdentityOrEntries() {
        val row = LocalPlaylist(id = "stable", name = "Pinned", entries = listOf(PlaylistEntry(id = "entry", title = "Title", artist = "Artist")), createdAt = 42)
        val pinned = row.copy(pinned = true)
        val decoded = Gson().fromJson(Gson().toJson(pinned), LocalPlaylist::class.java)
        assertTrue(decoded.pinned)
        assertEquals(row, decoded.copy(pinned = false))
    }
}
