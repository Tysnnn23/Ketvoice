package com.ketvoice.app.player

import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.SearchRank
import com.ketvoice.app.data.Track

/** An album of the listener's library as the car shows it: the titles the listener has, in their order. */
internal data class AutoAlbum(val key: String, val title: String, val artist: String, val tracks: List<Track>) {
    val cover: String? get() = tracks.firstNotNullOfOrNull { it.coverUrl?.takeIf(String::isNotBlank) }
}

/** An artist of the listener's library. */
internal data class AutoArtist(val key: String, val name: String, val tracks: List<Track>) {
    val cover: String? get() = tracks.firstNotNullOfOrNull { it.coverUrl?.takeIf(String::isNotBlank) }
}

/** An artist a radio can be built around: the listener's most played, with the catalogue's id when known. */
internal data class AutoArtistSeed(val name: String, val catalogId: String?, val tracks: List<Track>)

/** Seeds of the Radio tab. */
internal data class AutoStations(val artists: List<AutoArtistSeed> = emptyList(), val titles: List<Track> = emptyList())

/**
 * Albums and artists of the library (8.4), grouped from the titles the listener keeps: favourites, playlists,
 * downloads and local files. Nothing here asks the network or changes a title.
 */
internal object AutoCollections {
    private fun artistName(track: Track) = PersonalMixes.primaryArtist(track).ifBlank { track.artist.trim() }

    fun artistKey(name: String): String = SearchRank.fold(name)

    fun albumKey(title: String, artist: String): String = SearchRank.fold(title) + "|" + artistKey(artist)

    /** Albums by title, A to Z; titles without an album are left out. */
    fun albums(tracks: List<Track>): List<AutoAlbum> =
        tracks.filter { it.album.isNotBlank() && artistKey(artistName(it)).isNotEmpty() }
            .groupBy { albumKey(it.album, artistName(it)) }
            .map { (key, list) -> AutoAlbum(key, list.first().album.trim(), artistName(list.first()), list.distinctBy { it.id }) }
            .sortedWith(compareBy<AutoAlbum> { SearchRank.fold(it.title) }.thenBy { it.key })

    /** Artists by name, A to Z. */
    fun artists(tracks: List<Track>): List<AutoArtist> =
        tracks.filter { artistKey(artistName(it)).isNotEmpty() }
            .groupBy { artistKey(artistName(it)) }
            .map { (key, list) -> AutoArtist(key, artistName(list.first()), list.distinctBy { it.id }) }
            .sortedWith(compareBy<AutoArtist> { it.key })

    /** Titles A to Z, as a library's song list. */
    fun songs(tracks: List<Track>): List<Track> = tracks.sortedWith(compareBy<Track> { SearchRank.fold(it.title) }.thenBy { it.id })

    /** Every word of [query] in the album's title or artist. */
    fun matches(album: AutoAlbum, query: String): Boolean = words(query).let { words ->
        val own = SearchRank.fold(album.title + " " + album.artist)
        words.isNotEmpty() && words.all { own.split(' ').contains(it) }
    }

    /** The artist's name is the query, or the query names the artist (« hello adele »). */
    fun matches(artist: AutoArtist, query: String): Boolean {
        val q = SearchRank.fold(query)
        if (q.isEmpty() || artist.key.length < 2) return false
        return artist.key == q || artist.key.startsWith("$q ") || (artist.key.length >= 3 && " $q ".contains(" ${artist.key} "))
    }

    private fun words(query: String) = SearchRank.fold(query).split(' ').filter { it.isNotEmpty() }
}
