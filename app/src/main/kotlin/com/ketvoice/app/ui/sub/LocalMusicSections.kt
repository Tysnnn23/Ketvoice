package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.Track

/** How the device's titles are ordered. */
internal enum class LocalSort(val label: String) { Title("Titre"), Artist("Artiste"), Album("Album"), Duration("Durée") }

/** Placeholders written for untagged files: they are not a real artist or album to group by. */
private val untagged = setOf("", "artiste inconnu", "unknown artist", "<unknown>", "unknown", "album inconnu", "unknown album")

internal fun filterLocal(tracks: List<Track>, query: String): List<Track> {
    val term = query.trim()
    if (term.isEmpty()) return tracks
    return tracks.filter { it.title.contains(term, true) || it.artist.contains(term, true) || it.album.contains(term, true) }
}

internal fun sortLocal(tracks: List<Track>, sort: LocalSort): List<Track> = when (sort) {
    LocalSort.Title -> tracks.sortedBy { it.title.lowercase() }
    LocalSort.Artist -> tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
    LocalSort.Album -> tracks.sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
    LocalSort.Duration -> tracks.sortedByDescending { it.durationMs }
}

internal data class LocalGroup(val key: String, val title: String, val subtitle: String?, val tracks: List<Track>) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
}

private fun firstArtist(track: Track) = track.artist.substringBefore(',').trim()

/** Titles grouped by album, in alphabetical order; untagged files are left out. */
internal fun localAlbums(tracks: List<Track>): List<LocalGroup> = tracks
    .filter { it.album.trim().lowercase() !in untagged }
    .groupBy { it.album.trim().lowercase() + "\u0000" + firstArtist(it).lowercase() }
    .map { (key, group) ->
        LocalGroup(key, group.first().album.trim(), firstArtist(group.first()).takeIf { it.lowercase() !in untagged }, group)
    }
    .sortedWith(compareBy({ it.title.lowercase() }, { it.key }))

/** Titles grouped by their first credited artist, largest collection first. */
internal fun localArtists(tracks: List<Track>): List<LocalGroup> = tracks
    .filter { firstArtist(it).lowercase() !in untagged }
    .groupBy { firstArtist(it).lowercase() }
    .map { (key, group) -> LocalGroup(key, firstArtist(group.first()), null, group) }
    .sortedWith(compareByDescending<LocalGroup> { it.tracks.size }.thenBy { it.key })
