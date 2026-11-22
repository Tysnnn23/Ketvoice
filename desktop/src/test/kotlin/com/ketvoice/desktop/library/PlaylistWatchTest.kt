package com.ketvoice.desktop.library

import android.content.Context
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistFollow
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** « Playlists téléchargées à jour » on Windows: the phone's rules ([PlaylistFollow]) over this computer's downloads. */
class PlaylistWatchTest {
    private lateinit var root: File
    private fun track(n: Int) = Track("yt:track000000$n", "Title $n", "Artist", "Album", 200_000)
    private fun playlist(id: String, vararg tracks: Track) = LocalPlaylist(id, "Road trip", tracks.map { PlaylistRules.entry(it) })

    @Before fun setUp() {
        root = Files.createTempDirectory("kv-watch").toFile()
        AppSettings.playlistUpdates.value = true
        PlaylistWatch.resetForTest(Context(root).getSharedPreferences("playlist-updates", Context.MODE_PRIVATE))
    }

    @After fun tearDown() { PlaylistWatch.resetForTest(); AppSettings.playlistUpdates.value = true; root.deleteRecursively() }

    @Test fun aWhollyDownloadedPlaylistIsFollowedAndItsNewTitlesOffered() {
        val first = playlist("p1", track(1), track(2))
        assertTrue(PlaylistWatch.check(listOf(first), setOf(track(1).id, track(2).id)).isEmpty())
        val grown = playlist("p1", track(1), track(2), track(3), track(4))
        val updates = PlaylistWatch.check(listOf(grown), setOf(track(1).id, track(2).id, track(4).id))
        assertEquals(listOf(PlaylistFollow.Update("p1", "Road trip", listOf(track(3).id))), updates)
        assertEquals(updates, PlaylistWatch.pending.value)
        // Each new title is offered once.
        assertTrue(PlaylistWatch.check(listOf(grown), setOf(track(1).id, track(2).id, track(4).id)).isEmpty())
    }

    @Test fun titlesTheListenerAddsAreNeverOffered() {
        PlaylistWatch.follow(playlist("p1", track(1)))
        PlaylistWatch.noteAdded("p1", listOf(track(2).id))
        assertTrue(PlaylistWatch.check(listOf(playlist("p1", track(1), track(2))), setOf(track(1).id)).isEmpty())
    }

    @Test fun downloadStartsOnlyTheNewTitlesAndLaterDismisses() {
        val grown = playlist("p1", track(1), track(2), track(3))
        PlaylistWatch.offer(listOf(PlaylistFollow.Update("p1", "Road trip", listOf(track(2).id))))
        PlaylistWatch.offer(listOf(PlaylistFollow.Update("p1", "Road trip", listOf(track(3).id))))
        val update = PlaylistWatch.pending.value.single()
        assertEquals(listOf(track(2).id, track(3).id), update.trackIds)
        val started = mutableListOf<String>()
        assertEquals(2, PlaylistWatch.download(update, listOf(grown)) { started += it.id })
        assertEquals(listOf(track(2).id, track(3).id), started)
        assertTrue(PlaylistWatch.pending.value.isEmpty())
        PlaylistWatch.offer(listOf(update))
        PlaylistWatch.dismiss(update)
        assertTrue(PlaylistWatch.pending.value.isEmpty())
    }

    @Test fun offTheSwitchNothingIsChecked() {
        AppSettings.playlistUpdates.value = false
        PlaylistWatch.follow(playlist("p1", track(1)))
        assertTrue(PlaylistWatch.check(listOf(playlist("p1", track(1), track(2))), setOf(track(1).id)).isEmpty())
        assertTrue(PlaylistWatch.pending.value.isEmpty())
    }
}
