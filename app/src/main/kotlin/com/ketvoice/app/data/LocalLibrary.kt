package com.ketvoice.app.data

/**
 * « Musique locale pro » (7.0): the listener's files as proper albums, artists and folders, from their cleaned-up
 * tags ([LocalTags]). Pure, host-tested.
 *
 * - An album is its title and its album artist: two « Greatest Hits » of different artists stay apart, the titles
 *   of one album tagged « A » and « A feat. B » stay together.
 * - Without an album artist, the titles of one album in one folder with several artists are a compilation; a
 *   compilation flag or « Various Artists » says so too.
 * - Titles are in disc, then track order; a missing number goes last, by title.
 */
internal data class LocalDisc(val number: Int, val tracks: List<Track>)

internal data class LocalAlbumGroup(
    val key: String,
    val title: String,
    /** The album artist; null for a compilation. */
    val artist: String?,
    val compilation: Boolean,
    val year: Int?,
    val discs: List<LocalDisc>,
    val folder: String,
) {
    val tracks: List<Track> get() = discs.flatMap { it.tracks }
    val durationMs: Long get() = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
}

internal data class LocalArtistGroup(val key: String, val name: String, val albums: List<LocalAlbumGroup>, val tracks: List<Track>)

internal data class LocalFolder(val path: String, val name: String, val parent: String, val tracks: List<Track>)

internal object LocalLibrary {
    private fun tagsOf(track: Track, tags: Map<String, LocalTags>): LocalTags =
        tags[track.id] ?: LocalTags(track.title, track.artist.takeIf { LocalTagNormalizer.clean(it) != null },
            LocalTagNormalizer.clean(track.album), null, durationMs = track.durationMs)

    private fun artistKey(name: String?): String = LocalTagNormalizer.key(name?.let(LocalTagNormalizer::primaryArtist))

    private class Bucket(val albumKey: String, val owner: String, val entries: MutableList<Pair<Track, LocalTags>> = mutableListOf())

    fun albums(tracks: List<Track>, tags: Map<String, LocalTags>): List<LocalAlbumGroup> {
        val buckets = LinkedHashMap<String, Bucket>()
        fun put(albumKey: String, owner: String, entry: Pair<Track, LocalTags>) =
            buckets.getOrPut("$albumKey\u0000$owner") { Bucket(albumKey, owner) }.entries.add(entry)
        val loose = LinkedHashMap<String, MutableList<Pair<Track, LocalTags>>>()
        for (track in tracks.distinctBy { it.id }) {
            val t = tagsOf(track, tags)
            val album = t.album ?: continue
            val albumKey = LocalTagNormalizer.key(album).ifEmpty { album.lowercase() }
            when {
                t.compilation -> put(albumKey, VARIOUS, track to t)
                t.albumArtist != null -> put(albumKey, artistKey(t.albumArtist), track to t)
                else -> loose.getOrPut("$albumKey\u0000${t.folder}") { mutableListOf() } += track to t
            }
        }
        for ((key, entries) in loose) settle(key.substringBefore('\u0000'), entries, ::put)
        return buckets.values.map(::group).sortedWith(compareBy({ LocalTagNormalizer.key(it.title) }, { it.artist.orEmpty().lowercase() }))
    }

    /** Titles of one album and folder without an album artist: one artist's album, or a compilation. */
    private fun settle(albumKey: String, entries: List<Pair<Track, LocalTags>>, put: (String, String, Pair<Track, LocalTags>) -> Unit) {
        val artists = entries.map { artistKey(it.second.artist) }.distinct()
        val folder = entries.first().second.folder
        when {
            artists.size == 1 -> entries.forEach { put(albumKey, artists.single(), it) }
            folder.isNotEmpty() -> entries.forEach { put(albumKey, VARIOUS, it) }
            // No folder to tell an album from a namesake: each artist keeps their own.
            else -> entries.forEach { put(albumKey, artistKey(it.second.artist), it) }
        }
    }

    private fun group(bucket: Bucket): LocalAlbumGroup {
        val entries = bucket.entries
        val compilation = bucket.owner == VARIOUS
        val artist = if (compilation) null else entries.firstNotNullOfOrNull { it.second.albumArtist }
            ?: entries.firstNotNullOfOrNull { it.second.artist?.let(LocalTagNormalizer::primaryArtist) }
        val discs = entries.groupBy { it.second.discNo ?: 1 }.toSortedMap().map { (n, list) ->
            LocalDisc(n, list.sortedWith(compareBy<Pair<Track, LocalTags>>({ it.second.trackNo ?: Int.MAX_VALUE },
                { LocalTagNormalizer.key(it.second.title) })).map { it.first })
        }
        val year = entries.mapNotNull { it.second.year }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val title = entries.groupingBy { it.second.album!! }.eachCount().maxBy { it.value }.key
        val folders = entries.map { it.second.folder }.distinct()
        return LocalAlbumGroup("${bucket.albumKey}\u0000${bucket.owner}", title, artist, compilation, year, discs,
            folders.singleOrNull().orEmpty())
    }

    /** Artists by their albums (album artist) and their own titles, the largest collection first. */
    fun artists(tracks: List<Track>, tags: Map<String, LocalTags>, albums: List<LocalAlbumGroup> = albums(tracks, tags)): List<LocalArtistGroup> {
        val names = LinkedHashMap<String, String>()
        val owned = LinkedHashMap<String, MutableList<Track>>()
        for (track in tracks.distinctBy { it.id }) {
            val t = tagsOf(track, tags)
            val name = t.filedArtist ?: continue
            val key = artistKey(name).ifEmpty { continue }
            names.putIfAbsent(key, name)
            owned.getOrPut(key) { mutableListOf() } += track
        }
        return owned.map { (key, list) ->
            val theirs = albums.filter { !it.compilation && artistKey(it.artist) == key }
            val order = theirs.flatMap { it.tracks }.withIndex().associate { it.value.id to it.index }
            LocalArtistGroup(key, names.getValue(key), theirs,
                list.sortedWith(compareBy({ order[it.id] ?: Int.MAX_VALUE }, { LocalTagNormalizer.key(it.title) })))
        }.sortedWith(compareByDescending<LocalArtistGroup> { it.tracks.size }.thenBy { it.key })
    }

    /** The folders that hold titles, in path order; each keeps its titles in disc and track order. */
    fun folders(tracks: List<Track>, tags: Map<String, LocalTags>): List<LocalFolder> = tracks.distinctBy { it.id }
        .groupBy { tagsOf(it, tags).folder }
        .filterKeys { it.isNotEmpty() }
        .map { (path, list) ->
            val sorted = list.sortedWith(compareBy({ tagsOf(it, tags).discNo ?: 1 }, { tagsOf(it, tags).trackNo ?: Int.MAX_VALUE },
                { LocalTagNormalizer.key(it.title) }))
            LocalFolder(path, path.substringAfterLast('/'), path.substringBeforeLast('/', ""), sorted)
        }
        .sortedBy { it.path.lowercase() }

    private const val VARIOUS = "\u0001various"
}
