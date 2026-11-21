package com.ketvoice.desktop.library

import android.content.Context
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.DownloadQueue
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopLibraryTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val a = Track("um:1", "Un", "Artiste A", "Album A", durationMs = 1_000, coverUrl = "https://c.test/1.jpg", videoId = "v1",
        streamUrl = "https://stream.test/expiring")
    private val b = Track("yt:2", "Deux", "Artiste B", "Album B", isrc = "FR0000000002")
    private val c = Track("um:3", "Trois", "Artiste A", "Album A")

    @Before fun setUp() {
        root = Files.createTempDirectory("kv-library").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        DesktopLibrary.resetForTest(); DesktopDownloader.resetForTest()
        DesktopLibrary.init(Context(root), scope)
    }

    @After fun tearDown() {
        DesktopLibrary.resetForTest(); DesktopDownloader.resetForTest(); scope.cancel(); root.deleteRecursively()
    }

    private fun reopen() {
        DesktopLibrary.resetForTest(); DesktopDownloader.resetForTest()
        DesktopLibrary.init(Context(root), scope)
    }

    @Test fun favouritesAndPlaylistsSurviveARestart() = runBlocking {
        DesktopLibrary.setFavorite(a, true)
        DesktopLibrary.toggleFavorite(b)
        DesktopLibrary.toggleFavorite(c)
        DesktopLibrary.toggleFavorite(c)
        assertEquals(listOf("yt:2", "um:1"), DesktopLibrary.favorites.value.map { it.id })
        assertEquals(setOf("yt:2", "um:1"), AppSettings.favoriteIds.value)

        val id = DesktopLibrary.createPlaylist("Soirée", listOf(a, b, a))
        DesktopLibrary.addToPlaylist(id, c)
        DesktopLibrary.renamePlaylist(id, "Soirée d'été")
        DesktopLibrary.movePlaylistEntry(id, 2, 0)
        val dropped = PlaylistStore.lists.value.single().entries.first { it.track?.id == "yt:2" }.id
        DesktopLibrary.removeFromPlaylist(id, dropped)
        val other = DesktopLibrary.createPlaylist("Vide")
        DesktopLibrary.deletePlaylist(other)

        reopen()
        assertEquals(listOf("yt:2", "um:1"), DesktopLibrary.favorites.value.map { it.id })
        assertEquals(setOf("yt:2", "um:1"), AppSettings.favoriteIds.value)
        assertTrue(DesktopLibrary.isFavorite("um:1"))
        assertFalse(DesktopLibrary.isFavorite("um:3"))
        // Saved as the account keeps a title: no stream address, the ids that find it again.
        val saved = DesktopLibrary.favorites.value.first { it.id == "um:1" }
        assertNull(saved.streamUrl)
        assertEquals("v1", saved.videoId)
        assertEquals("FR0000000002", DesktopLibrary.favorites.value.first().isrc)
        val list = DesktopLibrary.playlists.value.single()
        assertEquals("Soirée d'été", list.name)
        assertEquals(listOf("um:3", "um:1"), list.entries.map { it.track?.id })
        assertNull(list.entries.first { it.track?.id == "um:1" }.track?.streamUrl)
        // The Android playlist file and the sync's track shape.
        assertTrue(File(root, "files/playlists-v1.json").isFile)
        val row = JSONObject(File(root, "files/favorites-v1.json").readText()).getJSONArray("entries").getJSONObject(0)
        assertEquals(b, DownloadQueue.trackFromJson(row.getJSONObject("track")))
    }

    @Test fun recentlyPlayedKeepsOneTitlePerAlbum() {
        val heard = listOf(c, b, a, Track("um:4", "Quatre", "Artiste C", ""), Track("um:5", "Cinq", "Artiste C", ""))
        assertEquals(listOf("um:3", "yt:2", "um:4", "um:5"), DesktopLibrary.recentOf(heard).map { it.id })
    }
}
