package com.ketvoice.app.data

/** Search inside what the listener already has: nothing here goes to the network. */
internal object LibrarySearch {
    private fun matches(track: Track, needle: String) = track.title.lowercase().contains(needle) ||
        track.artist.lowercase().contains(needle) || track.album.lowercase().contains(needle)

    /**
     * Titles of the whole library matching [query]: downloads first (they play offline),
     * then playlist titles, files of the device and titles already listened to.
     */
    fun tracks(query: String, downloads: List<Track>, playlists: List<LocalPlaylist>, local: List<Track>,
               known: List<Track>, limit: Int = 200): List<Track> {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return emptyList()
        val fromPlaylists = playlists.asSequence().flatMap { it.entries.asSequence() }.mapNotNull { it.track }
        return (downloads.asSequence() + fromPlaylists + local.asSequence() + known.asSequence())
            .filter { matches(it, needle) }
            .distinctBy { it.id }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    /** Playlists whose name matches [query]. */
    fun playlists(query: String, playlists: List<LocalPlaylist>): List<LocalPlaylist> {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return emptyList()
        return playlists.filter { it.name.lowercase().contains(needle) }
    }
}
