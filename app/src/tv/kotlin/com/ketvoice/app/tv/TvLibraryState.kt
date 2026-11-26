package com.ketvoice.app.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track

/**
 * What the listener owns, as the phone's pages read it (and as the account syncs it): listening history, favourites
 * (those made on other devices included), downloads and playlists. One snapshot per change of any of them.
 */
class TvLibrary(
    val recent: List<Track>,
    val known: List<Track>,
    val counts: Map<String, Int>,
    val favorites: List<Track>,
    val favoriteIds: Set<String>,
    val downloads: List<Track>,
    val playlists: List<LocalPlaylist>,
    private val covers: Map<String, String>,
) {
    /** The cover of [track] as the phone shows it (a found album cover, a listener's override, else its own). */
    fun cover(track: Track): String? = MediaOverrides.cover(track, covers["t:${track.id}"] ?: track.coverUrl)

    /** The titles of a collection page that lives on the device. */
    fun collection(kind: String, id: String): List<Track> = when (kind) {
        "favorites" -> favorites
        "downloads" -> downloads
        "history" -> recent
        // As on the phone, an entry not matched to a title yet (an import) is not playable here.
        "playlist" -> playlists.firstOrNull { it.id == id }?.entries?.mapNotNull { it.track }.orEmpty()
        "localAlbum" -> (favorites + downloads + known).distinctBy { it.id }.filter { TvRows.albumKey(it) == id }
        else -> emptyList()
    }

    fun isLocal(kind: String) = kind in LOCAL_KINDS

    companion object {
        val LOCAL_KINDS = setOf("favorites", "downloads", "history", "playlist", "localAlbum")
    }
}

@Composable
fun rememberTvLibrary(): TvLibrary {
    val recent by ListeningHistory.tracks.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val downloads by Downloader.items.collectAsState()
    val playlists by PlaylistStore.lists.collectAsState()
    val synced by com.ketvoice.app.sync.SyncedTracks.tracks.collectAsState()
    val covers by ArtworkRepo.covers.collectAsState()
    val favoriteIds = AppSettings.favoriteIds.value
    return remember(recent, known, counts, downloads, playlists, synced, covers, favoriteIds) {
        val downloaded = downloads.map { it.track }
        // Favourites made on the account's other devices are shown too (their titles came with the sync).
        val favorites = (known + recent + downloaded + synced).distinctBy { it.id }.filter { it.id in favoriteIds }
        TvLibrary(recent, known, counts, favorites, favoriteIds, downloaded, playlists, covers)
    }
}
