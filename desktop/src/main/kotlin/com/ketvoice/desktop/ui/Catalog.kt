package com.ketvoice.desktop.ui

import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.SearchGroups
import com.ketvoice.app.data.SearchRank
import kotlinx.coroutines.async

import androidx.compose.runtime.staticCompositionLocalOf
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.HomeFeed
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Recommender
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.YouTubeCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** An album as pages link to it. [id] is the catalogue's id when there is one. */
data class AlbumRef(val id: String?, val title: String, val artist: String, val cover: String?, val year: String? = null, val type: String = "Album")
data class ArtistRef(val id: String?, val name: String, val picture: String? = null)
data class Shelf(val title: String, val subtitle: String = "", val tracks: List<Track> = emptyList(), val albums: List<AlbumRef> = emptyList())
data class HomeData(val forYou: List<Track>, val recent: List<Track>, val shelves: List<Shelf>)
data class SearchResults(val tracks: List<Track>, val albums: List<AlbumRef>, val artists: List<ArtistRef>)
data class AlbumData(val ref: AlbumRef, val tracks: List<Track>, val recommended: List<Track>)
data class ArtistData(val ref: ArtistRef, val top: List<Track>, val albums: List<AlbumRef>, val singles: List<AlbumRef>,
                      val about: String = "", val listeners: String = "", val related: List<ArtistRef> = emptyList())
data class Category(val id: String, val title: String, val cover: String?)

/** Where the pages read the catalogue from: the app's shared catalogue code, or fixtures in tests. */
interface CatalogSource {
    suspend fun home(): HomeData
    suspend fun search(query: String): SearchResults
    suspend fun album(ref: AlbumRef): AlbumData?
    suspend fun artist(ref: ArtistRef): ArtistData?
    suspend fun categories(): List<Category>
    suspend fun category(category: Category): List<Track>
    /** False when the catalogue cannot be reached at all: the pages then show the offline state. */
    suspend fun online(): Boolean = true
}

val LocalCatalog = staticCompositionLocalOf<CatalogSource> { LiveCatalog }

private fun Track.albumRef() = AlbumRef(albumId, album, artist.substringBefore(',').trim(), coverUrl)

/** The app's Home, search and catalogue pages through the shared code (HomeFeed, Recommender, CatalogRepo, YouTubeCatalog). */
object LiveCatalog : CatalogSource {
    @Volatile private var started = false
    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            val context = android.content.Context.app
            runCatching { MusicApi.init(context) }
            runCatching { ListeningHistory.init(context) }
            runCatching { com.ketvoice.app.data.TasteSignals.init(context) }
            runCatching { HomeFeed.start(context) }
            runCatching { com.ketvoice.app.data.SearchHistory.init(context) }
            started = true
        }
    }

    override suspend fun home(): HomeData {
        start()
        // The feed fills in the background; wait a moment for its first shelves.
        val shelves = withTimeoutOrNull(8_000) { HomeFeed.shelves.first { it.isNotEmpty() } } ?: HomeFeed.shelves.value
        val pool = shelves.flatMap { it.tracks }
        // One title per album heard lately (DesktopLibrary), as Apple Music's « Récemment écouté ».
        val recent = com.ketvoice.desktop.library.DesktopLibrary.recentlyPlayed.value.ifEmpty { ListeningHistory.tracks.value }.take(24)
        return HomeData(
            forYou = runCatching { Recommender.forYou(pool, 12) }.getOrDefault(emptyList()).ifEmpty { pool.take(12) },
            recent = recent,
            shelves = shelves.map { Shelf(it.title, it.subtitle, it.tracks) },
        )
    }

    /**
     * The phone's « Tout » search: the catalogue, the published titles and the add-ons together, in one order
     * (SearchRank: text match first, then what the listener plays and likes), versions of one recording grouped
     * (SearchGroups), and artists whose name is the query first.
     */
    override suspend fun search(query: String): SearchResults = kotlinx.coroutines.coroutineScope {
        start()
        val yt = async { safe { MusicApi.searchYtMusic(query) } }
        val published = async { safe { com.ketvoice.app.data.PublicationApi.search(query) } }
        val addons = async { safe { MusicApi.searchAddons(query) } }
        val albumsAsync = async { safe { CatalogRepo.searchAlbums(query) } }
        val artistsAsync = async { safe { YouTubeCatalog.searchArtists(query) } }
        val found = yt.await() + published.await() + addons.await()
        val taste = runCatching { SearchRank.liveTaste() }.getOrDefault(SearchRank.Taste())
        val tracks = SearchGroups.group(SearchRank.rank(query, found, taste).filter(ArtistPrefs::allows)).map { it.primary }
        val albums = albumsAsync.await().map { AlbumRef(it.id, it.title, it.artist, it.cover, it.releaseDate?.take(4)) }
        val artists = SearchRank.artistsFirst(query, artistsAsync.await()) { it.name }.map { ArtistRef(it.id, it.name, it.photo) }
        SearchResults(tracks, albums.ifEmpty { tracks.map { it.albumRef() }.distinctBy { it.title + it.artist }.take(8) }, artists)
    }

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
        try { block() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { emptyList() }

    /** One short request to the catalogue's host: does the network reach it at all? */
    override suspend fun online(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = java.net.URL("https://music.youtube.com/").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000; c.instanceFollowRedirects = false
            try { c.responseCode > 0 } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    override suspend fun album(ref: AlbumRef): AlbumData? {
        start()
        val page = ref.id?.let { runCatching { YouTubeCatalog.album(it) }.getOrNull() }
        val tracks = page?.tracks ?: runCatching { MusicApi.searchAll("${ref.artist} ${ref.title}") }.getOrDefault(emptyList())
            .filter { it.album.equals(ref.title, ignoreCase = true) }
        val recommended = runCatching { CatalogRepo.artistRadio(ref.artist) }.getOrDefault(emptyList())
            .filterNot { r -> tracks.any { it.id == r.id } }.take(12)
        return AlbumData(ref.copy(cover = page?.cover ?: ref.cover, year = page?.releaseDate?.take(4) ?: ref.year), tracks, recommended)
    }

    override suspend fun artist(ref: ArtistRef): ArtistData? {
        start()
        val id = ref.id ?: runCatching { YouTubeCatalog.searchArtists(ref.name) }.getOrNull()?.firstOrNull()?.id
        val page = id?.let { runCatching { YouTubeCatalog.artist(it) }.getOrNull() }
        val releases = page?.albums.orEmpty().map { AlbumRef(it.id, it.title, it.artist, it.coverUrl, type = it.type) }
        val top = page?.tracks ?: runCatching { CatalogRepo.artistTop(ref.name) }.getOrDefault(emptyList())
        return ArtistData(ref.copy(id = id, picture = page?.artist?.photo ?: ref.picture), top,
            releases.filterNot { it.type.isSingle() }, releases.filter { it.type.isSingle() },
            about = page?.description.orEmpty(), listeners = page?.listeners.orEmpty(),
            related = page?.related.orEmpty().map { ArtistRef(it.id, it.name, it.photo) })
    }

    override suspend fun categories(): List<Category> =
        runCatching { CatalogRepo.genres() }.getOrDefault(emptyList()).map { Category(it.id, it.title, it.cover) }

    override suspend fun category(category: Category): List<Track> =
        runCatching { CatalogRepo.page(com.ketvoice.app.data.CatalogRequest("genre", category.id, category.title))?.tracks }.getOrNull().orEmpty()
}

internal fun String.isSingle() = contains("single", ignoreCase = true) || contains("EP", ignoreCase = false)

/** The library's views (Apple Music's « Titres », « Albums », « Artistes ») over the listener's titles. */
object LibraryViews {
    fun songs(lib: LibraryStore): List<Track> =
        (lib.favourites.value + lib.playlists.value.flatMap { it.tracks } + lib.downloads.value.map { it.track } + lib.localTracks.value).distinctBy { it.id }

    fun albums(songs: List<Track>): List<AlbumRef> = songs.filter { it.album.isNotBlank() }
        .groupBy { it.album.lowercase() to it.artist.substringBefore(',').trim().lowercase() }
        .values.map { it.first().albumRef() }.sortedBy { it.title.lowercase() }

    fun artists(songs: List<Track>): List<ArtistRef> = songs.groupBy { it.artist.substringBefore(',').trim() }
        .map { (name, t) -> ArtistRef(t.first().artistId, name, null) }.sortedBy { it.name.lowercase() }

    /** Albums by the order their first title was added, newest first. */
    fun recentlyAdded(songs: List<Track>, added: List<String>): List<AlbumRef> {
        val order = added.withIndex().associate { it.value to it.index }
        return songs.sortedBy { order[it.id] ?: Int.MAX_VALUE }.map { it.albumRef() }.distinctBy { it.title.lowercase() + "|" + it.artist.lowercase() }
    }

    /** The phone's « Bibliothèque » search: the listener's titles and the ones heard, matching every word, in SearchRank's order. */
    fun search(lib: LibraryStore, query: String): List<Track> {
        val words = SearchRank.fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val pool = (songs(lib) + lib.history.value).distinctBy { it.id }.filter { t ->
            val text = SearchRank.fold("${t.title} ${t.artist} ${t.album}")
            words.all { text.contains(it) }
        }
        return SearchRank.rank(query, pool)
    }

    /** Where each title stands in [added] (newest first); a title never added goes last. */
    private fun addedRank(added: List<String>): Map<String, Int> = added.withIndex().associate { it.value to it.index }

    private fun <K> firstAdded(songs: List<Track>, added: List<String>, key: (Track) -> K): Map<K, Int> {
        val rank = addedRank(added)
        return songs.groupBy(key).mapValues { (_, t) -> t.minOf { rank[it.id] ?: Int.MAX_VALUE } }
    }

    /** Albums in the order Settings › Disposition chose: "0" newest added first, "1" title A–Z (as always), "2" artist A–Z. */
    fun albums(songs: List<Track>, sort: String, added: List<String>): List<AlbumRef> {
        val all = albums(songs)
        return when (sort) {
            "0" -> firstAdded(songs, added) { it.album.lowercase() + "|" + it.artist.substringBefore(',').trim().lowercase() }
                .let { rank -> all.sortedBy { rank[it.title.lowercase() + "|" + it.artist.lowercase()] ?: Int.MAX_VALUE } }
            "2" -> all.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            else -> all
        }
    }

    /** Artists in the order Settings › Disposition chose: "0" newest added first, "1" name A–Z (as always). */
    fun artists(songs: List<Track>, sort: String, added: List<String>): List<ArtistRef> {
        val all = artists(songs)
        if (sort != "0") return all
        val rank = firstAdded(songs, added) { it.artist.substringBefore(',').trim() }
        return all.sortedBy { rank[it.name] ?: Int.MAX_VALUE }
    }

    /** Titles newest added first (« Récents »). */
    fun byAdded(songs: List<Track>, added: List<String>): List<Track> = addedRank(added).let { rank -> songs.sortedBy { rank[it.id] ?: Int.MAX_VALUE } }

    enum class SortKey { TITLE, ARTIST, ALBUM, DURATION }

    /** The column « Titres » opens sorted by: null for « Récents » (newest added first). */
    fun initialKey(sort: String): SortKey? = when (sort) { "0" -> null; "2" -> SortKey.ARTIST; else -> SortKey.TITLE }
    fun sort(songs: List<Track>, key: SortKey, ascending: Boolean): List<Track> {
        val sorted = when (key) {
            SortKey.TITLE -> songs.sortedBy { it.title.lowercase() }
            SortKey.ARTIST -> songs.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }))
            SortKey.ALBUM -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
            SortKey.DURATION -> songs.sortedBy { it.durationMs }
        }
        return if (ascending) sorted else sorted.reversed()
    }
}

internal fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
