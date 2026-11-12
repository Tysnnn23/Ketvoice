package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class PersonalBackupTest {
    @Test fun exportAndImportPreserveMetadataButNeverIncludeCredentialsOrSignedUrls() {
        val track = Track("tidal:42", "Song", "Artist", "Album", streamUrl = "https://audio.example/?token=SECRET",
            downloadUrl = "https://audio.example/?signature=SECRET", coverUrl = "https://image.example/?token=SECRET",
            canvasUrl = "https://canvas.example/?token=SECRET", addon = "cs:source", addonId = "tidal:42")
        val playlist = LocalPlaylist(id = "playlist-id", name = "My list", pinned = true,
            spotifyUrl = "https://open.spotify.com/playlist/abcdefghijklmnopqrstuv?token=SECRET",
            entries = listOf(PlaylistEntry(id = "entry-id", title = track.title, artist = track.artist, track = track)))
        val data = PersonalBackupData(listOf(playlist), setOf(track.id), mapOf("themeMode" to 1, "betterLyricsKey" to "SECRET"),
            listOf(track), listOf(track), mapOf(track.id to 4), 42_000, mapOf(LyricsOffsets.key(track.id) to 300))
        val encoded = PersonalBackup.encode(data)
        assertFalse(String(encoded).contains("SECRET"))
        val restored = PersonalBackup.decode(encoded)
        assertEquals("My list", restored.playlists.single().name)
        assertTrue(restored.playlists.single().pinned)
        assertEquals("https://open.spotify.com/playlist/abcdefghijklmnopqrstuv", restored.playlists.single().spotifyUrl)
        assertEquals("tidal:42", restored.playlists.single().entries.single().track?.id)
        assertNull(restored.playlists.single().entries.single().track?.streamUrl)
        assertEquals(setOf("tidal:42"), restored.favorites)
        assertEquals(1, restored.settings["themeMode"])
        assertFalse(restored.settings.containsKey("betterLyricsKey"))
        assertEquals(42_000L, restored.listenedMs)
        assertEquals(300, restored.lyricOffsets[LyricsOffsets.key(track.id)])
    }

    @Test fun unknownVersionsAndOversizedFilesAreRejectedBeforeImport() {
        assertThrows(Exception::class.java) {
            PersonalBackup.decode("""{"kind":"ketvoice-personal-backup","version":2}""".toByteArray())
        }
        assertThrows(Exception::class.java) {
            PersonalBackup.readLimited(ByteArrayInputStream(ByteArray(PersonalBackup.MAX_BYTES + 1)))
        }
    }

    @Test fun localContentUrisSurviveWithoutExportingFileOrNetworkPaths() {
        val local = Track("local:42", "Local", "Artist", "Album", streamUrl = "content://media/external/audio/42")
        val data = PersonalBackupData(emptyList(), emptySet(), emptyMap(), listOf(local), listOf(local), emptyMap(), 0)
        assertEquals(local.streamUrl, PersonalBackup.decode(PersonalBackup.encode(data)).recent.single().streamUrl)
        val unsafe = local.copy(streamUrl = "content://media/external/audio/42?token=SECRET")
        assertNull(PersonalBackup.decode(PersonalBackup.encode(data.copy(recent = listOf(unsafe)))).recent.single().streamUrl)
    }
}
