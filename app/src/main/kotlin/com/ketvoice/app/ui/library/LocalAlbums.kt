package com.ketvoice.app.ui.library

import com.ketvoice.app.data.Track
import java.util.Locale

internal data class LocalAlbum(val key: String, val title: String, val artist: String, val tracks: List<Track>)

/** Local grouping only: missing catalogue IDs must not trigger a remote album lookup. */
internal fun localAlbums(tracks: List<Track>): List<LocalAlbum> = tracks.distinctBy { it.id }
    .filter { it.album.isNotBlank() }
    .groupBy { track ->
        track.albumId?.takeIf { it.isNotBlank() }?.let { "id:$it" }
            ?: "${track.artist.trim().lowercase(Locale.ROOT)}\u0000${track.album.trim().lowercase(Locale.ROOT)}"
    }.map { (key, songs) -> LocalAlbum(key, songs.first().album, songs.first().artist, songs) }
