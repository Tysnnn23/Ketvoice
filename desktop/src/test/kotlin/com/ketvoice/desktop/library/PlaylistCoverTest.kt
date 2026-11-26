package com.ketvoice.desktop.library

import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.ui.LibraryBridge
import com.ketvoice.desktop.ui.PlaylistMosaic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 9.2: a phone playlist shows on Windows with its name (an emoji alone included) and its cover, as on the phone. */
class PlaylistCoverTest {
    private fun song(id: String, cover: String?) = Track(id, "Titre $id", "Artiste", "Album", coverUrl = cover)

    @Test fun aPlaylistKeepsItsOwnCoverAndItsEmojiName() {
        val playlist = LocalPlaylist("pl-1", "🔥", entries = listOf(PlaylistEntry("e1", "Titre", "Artiste", track = song("a", null))),
            coverUrl = "https://example.com/own.jpg")
        val ui = LibraryBridge.toUi(playlist)
        assertEquals("🔥", ui.name)
        assertEquals("https://example.com/own.jpg", ui.cover)
        // Only an address another device can load becomes the playlist's own artwork.
        assertNull(LibraryBridge.toUi(playlist.copy(coverUrl = "file:///C:/cover.jpg")).cover)
    }

    @Test fun theMosaicShowsTheFirstFourTitlesAndAnEmptyPlaylistNone() {
        val tracks = listOf(song("a", "https://example.com/a.jpg"), song("b", null), song("c", "https://example.com/c.jpg"),
            song("d", "https://example.com/a.jpg"), song("e", "https://example.com/e.jpg"))
        assertEquals(listOf("a", "b", "c", "d"), PlaylistMosaic.tiles(tracks).map { it?.id })
        assertEquals(emptyList(), PlaylistMosaic.tiles(emptyList()))
    }
}
