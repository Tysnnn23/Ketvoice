@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.ketvoice.app.data.HomeShelf
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.SearchGroups
import com.ketvoice.app.data.SearchRank
import com.ketvoice.app.data.Track
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/*
 * Android Auto (and any other media browser: Android Automotive, Wear, Bluetooth head units).
 *
 * The browse tree is built from the listener's own stores, the way the phone shows them (8.4):
 *
 *   root
 *   ├── Accueil          resume, your mixes, featured shelves (grid), then the recently played titles
 *   ├── Bibliothèque     Playlists · Albums · Artistes · Titres · Favoris · Téléchargements (category list)
 *   ├── Radio            radio of the title playing, of your artists and of your most played titles
 *   └── Téléchargements  the downloaded titles (they play offline)
 *
 * Collections start with « Tout lire » and « Lecture aléatoire ». A title carries its list in its media id,
 * so playing it from the car queues the rest of the list. Nothing is resolved while browsing: a car only
 * sends media ids, and the title goes to [Player] — the phone's queue — which resolves it exactly as a tap
 * on the phone does (downloaded file first, then the cache, then the stream sources). The session never puts
 * anything on the engine itself for these requests.
 */

/** Media ids of the browse tree. Parts are URL-encoded: track and playlist ids may hold any character. */
internal object AutoIds {
    const val ROOT = "ketvoice.auto"
    const val RECENT = "ketvoice.auto.recent"
    const val HOME = "ketvoice.auto.home"
    const val LIBRARY = "ketvoice.auto.library"
    const val RADIO = "ketvoice.auto.radio"
    const val DOWNLOADS = "ketvoice.auto.downloads"
    const val FAVORITES = "ketvoice.auto.favorites"
    const val PLAYLISTS = "ketvoice.auto.playlists"
    const val ALBUMS = "ketvoice.auto.albums"
    const val ARTISTS = "ketvoice.auto.artists"
    const val SONGS = "ketvoice.auto.songs"
    /** The title playing or played last, from the car's « recent » root and the Home tab. */
    const val RESUME = "ketvoice.auto.resume"
    private const val PLAYLIST = "ketvoice.auto.playlist/"
    private const val MIX = "ketvoice.auto.mix/"
    private const val SEARCH = "ketvoice.auto.search/"
    private const val TRACK = "ketvoice.auto.track/"
    private const val ALBUM = "ketvoice.auto.album/"
    private const val ARTIST = "ketvoice.auto.artist/"
    private const val SHELF = "ketvoice.auto.shelf/"
    private const val ALL = "ketvoice.auto.all/"
    private const val SHUFFLE = "ketvoice.auto.shuffle/"
    private const val STATION = "ketvoice.auto.station/"

    val TABS = listOf(HOME, LIBRARY, RADIO, DOWNLOADS)
    private val FIXED = setOf(ROOT, RECENT, HOME, LIBRARY, RADIO, DOWNLOADS, FAVORITES, PLAYLISTS, ALBUMS, ARTISTS, SONGS)

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun dec(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    private fun part(mediaId: String, prefix: String) = mediaId.takeIf { it.startsWith(prefix) }?.let { dec(it.removePrefix(prefix)) }

    fun playlist(id: String) = PLAYLIST + enc(id)
    fun mix(id: String) = MIX + enc(id)
    fun search(query: String) = SEARCH + enc(query.trim())
    fun track(parent: String, trackId: String) = TRACK + enc(parent) + "/" + enc(trackId)
    fun album(key: String) = ALBUM + enc(key)
    fun artist(key: String) = ARTIST + enc(key)
    fun shelf(title: String) = SHELF + enc(title)
    /** « Tout lire » / « Lecture aléatoire » at the top of the list [parent]. */
    fun all(parent: String) = ALL + enc(parent)
    fun shuffle(parent: String) = SHUFFLE + enc(parent)
    /** A radio station: [kind] is « track » (a title id) or « artist » (an artist's name). */
    fun station(kind: String, value: String) = STATION + enc(kind) + "/" + enc(value)

    fun playlistId(mediaId: String): String? = part(mediaId, PLAYLIST)
    fun mixId(mediaId: String): String? = part(mediaId, MIX)
    fun searchQuery(mediaId: String): String? = part(mediaId, SEARCH)
    fun albumKey(mediaId: String): String? = part(mediaId, ALBUM)
    fun artistKey(mediaId: String): String? = part(mediaId, ARTIST)
    fun shelfTitle(mediaId: String): String? = part(mediaId, SHELF)
    fun allOf(mediaId: String): String? = part(mediaId, ALL)
    fun shuffleOf(mediaId: String): String? = part(mediaId, SHUFFLE)

    fun stationOf(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(STATION)) return null
        val parts = mediaId.removePrefix(STATION).split('/')
        if (parts.size != 2) return null
        return (dec(parts[0]) ?: return null) to (dec(parts[1])?.takeIf { it.isNotBlank() } ?: return null)
    }

    /** The list a title was chosen in and its own id, or null when [mediaId] is not a title of the tree. */
    fun trackOf(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(TRACK)) return null
        val parts = mediaId.removePrefix(TRACK).split('/')
        if (parts.size != 2) return null
        val parent = dec(parts[0]) ?: return null
        val track = dec(parts[1])?.takeIf { it.isNotBlank() } ?: return null
        return parent to track
    }

    fun isTabOrRoot(mediaId: String) = mediaId in FIXED

    /** Any id of this tree (a car's request with such an id is never a voice search). */
    fun isOwn(mediaId: String) = mediaId == ROOT || mediaId.startsWith("$ROOT.")
}

/** What the car can show: the listener's real stores. Tests give their own. */
internal interface AutoSource {
    /** Loads the stores once (on the main thread, as the phone does when it opens). */
    fun prepare() {}
    fun recent(): List<Track>
    fun favorites(): List<Track>
    fun playlists(): List<LocalPlaylist>
    fun downloads(): List<Track>
    fun isDownloaded(trackId: String): Boolean
    fun mixes(): List<MixCard>
    fun mixTitle(card: MixCard): String
    fun mixSubtitle(card: MixCard): String
    /** Tracks of a mix, or null when it needs the catalogue and the catalogue did not answer. */
    suspend fun mixTracks(id: String): List<Track>?
    fun searchLibrary(query: String): List<Track>
    fun searchPlaylists(query: String): List<LocalPlaylist>
    /** The catalogue search of the phone's « Tout » segment, blocked artists left out, one entry per recording. */
    suspend fun searchCatalogue(query: String): List<Track>
    /** A title the listener has somewhere (queue, history, downloads, playlists), by id. */
    fun lookup(trackId: String): Track?
    /** Cover of a title as the app shows it (enriched cover, then the owner's overrides). */
    fun cover(track: Track): String?
    fun text(french: String): String

    /** The titles the listener keeps: favourites, downloads, playlists (and, on the phone, local files). */
    fun library(): List<Track> = (favorites() + downloads() + playlists().flatMap { list -> list.entries.mapNotNull { it.track } })
        .distinctBy { it.id }
    /** Home's featured shelves, as the phone last built them (never fetched for the car). */
    fun shelves(): List<HomeShelf> = emptyList()
    fun stations(): AutoStations = AutoStations()
    /** The title of the phone's queue (playing, paused or restored), or null. */
    fun current(): Track? = null
    fun playing(): Boolean = false
    fun online(): Boolean = true
    fun taste(): SearchRank.Taste = SearchRank.Taste()
}

/** Where a car's request ends: the phone's queue. */
internal interface AutoPlayback {
    fun play(tracks: List<Track>, index: Int)
    fun shuffle(tracks: List<Track>)
    fun enqueue(tracks: List<Track>)
    /** Plays what was playing (or the restored session); false when there is nothing to resume. */
    suspend fun resume(): Boolean
    fun radio(seed: Track)
    fun artistRadio(name: String, catalogId: String?, tracks: List<Track>)
}

/** What a request from the car asks for, once its media ids are known. */
internal sealed interface AutoRequest {
    data class Play(val tracks: List<Track>, val index: Int) : AutoRequest
    data class Shuffle(val tracks: List<Track>) : AutoRequest
    data class Enqueue(val tracks: List<Track>) : AutoRequest
    data class Radio(val seed: Track) : AutoRequest
    data class ArtistRadio(val name: String, val catalogId: String?, val tracks: List<Track>) : AutoRequest
    data object Resume : AutoRequest
    data class Missing(val message: String) : AutoRequest
}

/** Thrown when a browse list needs the network and it did not answer in time. */
internal class AutoUnavailable : Exception()

internal class AutoLibrary(
    private val source: AutoSource,
    private val playback: AutoPlayback,
    private val artwork: (String?) -> Uri? = { url -> url?.takeIf { it.isNotBlank() }?.let(Uri::parse) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val searchTimeoutMs: Long = SEARCH_TIMEOUT_MS,
    private val mixTimeoutMs: Long = MIX_TIMEOUT_MS,
    /** The app's package, for the icons of tabs and categories; none in the host tests. */
    private val iconPackage: String? = null
) {
    companion object {
        /** Longest list a car is given: driving screens are short, and Auto cuts long lists anyway. */
        const val MAX_ITEMS = 100
        /** A queue started from the car keeps at most this many titles (a playlist holds up to 500). */
        const val MAX_QUEUE = 500
        const val LOCAL_TTL_MS = 20_000L
        const val REMOTE_TTL_MS = 30 * 60_000L
        const val MIX_TIMEOUT_MS = 10_000L
        const val SEARCH_TIMEOUT_MS = 9_000L
        /** Stations of each kind on the Radio tab. */
        const val STATIONS = 8
        private const val SEARCH_CACHE = 8
        private const val LIBRARY_KEY = "ketvoice.auto.library-titles"
    }

    private class Entry<T>(val at: Long, val value: T)
    /** Lists already given to the car: its pages, and the queue of a title chosen in it, stay the same. */
    private val lists = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Entry<List<Track>>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<List<Track>>>?) = size > 32
    })
    private val searches = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Entry<SearchResults>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<SearchResults>>?) = size > SEARCH_CACHE
    })
    @Volatile private var grouped: Triple<List<Track>, List<AutoAlbum>, List<AutoArtist>>? = null

    internal data class SearchResults(val playlists: List<LocalPlaylist>, val library: List<Track>, val catalogue: List<Track>,
                                      val artists: List<AutoArtist> = emptyList(), val albums: List<AutoAlbum> = emptyList()) {
        val tracks: List<Track> get() = library + catalogue
        val size: Int get() = (artists.size + albums.size + playlists.size + library.size + catalogue.size).coerceAtMost(MAX_ITEMS)
    }

    fun prepare() = source.prepare()
    private fun text(french: String) = source.text(french)

    // ------------------------------------------------------------------ Browse

    fun root(): MediaItem = folder(AutoIds.ROOT, "Ketvoice", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** The root of the system's « recent » request (playback resumption): the title to pick up again. */
    fun recentRoot(): MediaItem = folder(AutoIds.RECENT, "Ketvoice", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** Default looks of the tree: folders (playlists, mixes, albums) as a grid, titles as a list. */
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    /** Four tabs (Android Auto's limit), always present: an empty one says so on the car's own screen. */
    fun tabs(): List<MediaItem> = listOf(
        folder(AutoIds.HOME, text("Accueil"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            browsable = GRID, playable = LIST, icon = "ic_car_home"),
        folder(AutoIds.LIBRARY, text("Bibliothèque"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            browsable = CATEGORY, icon = "ic_car_library"),
        folder(AutoIds.RADIO, text("Radio"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS,
            playable = GRID, icon = "media3_icon_radio"),
        folder(AutoIds.DOWNLOADS, text("Téléchargements"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            playable = LIST, icon = "ic_car_download"))

    /** The library's categories: icons in a list, each a list of its own. */
    fun categories(): List<MediaItem> = listOf(
        folder(AutoIds.PLAYLISTS, text("Playlists"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            browsable = GRID, icon = "ic_car_playlist"),
        folder(AutoIds.ALBUMS, text("Albums"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            browsable = GRID, icon = "media3_icon_album"),
        folder(AutoIds.ARTISTS, text("Artistes"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            browsable = LIST, icon = "media3_icon_artist"),
        folder(AutoIds.SONGS, text("Titres"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            playable = LIST, icon = "ic_car_song"),
        folder(AutoIds.FAVORITES, text("Favoris"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            playable = LIST, icon = "media3_icon_heart_filled"),
        folder(AutoIds.DOWNLOADS, text("Téléchargements"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            playable = LIST, icon = "ic_car_download"))

    /** Children of [parentId], one page; null for an id the tree does not have. [tabLimit]: the car's own tab limit. */
    suspend fun children(parentId: String, page: Int, pageSize: Int, tabLimit: Int = AutoIds.TABS.size): List<MediaItem>? {
        val all = if (parentId == AutoIds.ROOT) tabs().take(tabLimit.coerceIn(1, AutoIds.TABS.size)) else list(parentId) ?: return null
        return paged(all.take(MAX_ITEMS), page, pageSize)
    }

    private suspend fun list(parentId: String): List<MediaItem>? = when (parentId) {
        AutoIds.RECENT -> listOfNotNull(resumeItem(source.current() ?: source.recent().firstOrNull()))
        AutoIds.HOME -> home()
        AutoIds.LIBRARY -> categories()
        AutoIds.RADIO -> radio()
        AutoIds.PLAYLISTS -> source.playlists().sortedByDescending { it.pinned }.map(::playlistItem)
        AutoIds.ALBUMS -> groups().second.map(::albumItem)
        AutoIds.ARTISTS -> groups().third.map(::artistItem)
        AutoIds.SONGS -> withActions(parentId, tracksOf(parentId).orEmpty(), playAll = false)
        AutoIds.FAVORITES, AutoIds.DOWNLOADS -> withActions(parentId, tracksOf(parentId).orEmpty())
        else -> collection(parentId)
    }

    private suspend fun collection(parentId: String): List<MediaItem>? {
        AutoIds.artistKey(parentId)?.let { return artistPage(it) }
        AutoIds.searchQuery(parentId)?.let { return searchItems(it) }
        // Offline, a mix that is not ready yet would only spin: the car is offered the downloads instead.
        if (AutoIds.mixId(parentId) != null && lists[parentId] == null && !source.online()) return offline()
        val tracks = tracksOf(parentId) ?: return null
        return withActions(parentId, tracks)
    }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == AutoIds.ROOT) return root()
        (tabs() + categories()).firstOrNull { it.mediaId == mediaId }?.let { return it }
        AutoIds.playlistId(mediaId)?.let { id -> return source.playlists().firstOrNull { it.id == id }?.let(::playlistItem) }
        AutoIds.mixId(mediaId)?.let { id -> return source.mixes().firstOrNull { it.id == id }?.let(::mixItem) }
        AutoIds.albumKey(mediaId)?.let { key -> return groups().second.firstOrNull { it.key == key }?.let(::albumItem) }
        AutoIds.artistKey(mediaId)?.let { key -> return groups().third.firstOrNull { it.key == key }?.let(::artistItem) }
        if (mediaId == AutoIds.RESUME) return resumeItem(source.current() ?: source.recent().firstOrNull())
        val (parent, trackId) = AutoIds.trackOf(mediaId) ?: return null
        val track = tracksOf(parent)?.firstOrNull { it.id == trackId } ?: findTrack(trackId) ?: return null
        return trackItem(parent, track, null)
    }

    private fun home(): List<MediaItem> {
        val offline = if (source.online()) emptyList() else offline()
        val resume = if (source.playing()) null else resumeItem(source.current())?.withGroup(text("Reprendre"))
        val mixes = source.mixes().map { mixItem(it).withGroup(text("Vos mix")) }
        val shelves = source.shelves().filter { it.tracks.isNotEmpty() }.map { shelfItem(it).withGroup(text("À la une")) }
        val recent = trackItems(AutoIds.HOME, cached(AutoIds.HOME, LOCAL_TTL_MS) { source.recent() }, text("Récemment écoutés"))
        return offline + listOfNotNull(resume) + mixes + shelves + recent
    }

    /** Offline: one clear choice, the downloads, which play without a network. */
    private fun offline(): List<MediaItem> =
        if (source.downloads().isEmpty()) emptyList()
        else listOf(actionItem(AutoIds.shuffle(AutoIds.DOWNLOADS), text("Lire vos téléchargements"), text("Hors ligne"),
            "media3_icon_shuffle_on").withGroup(text("Hors ligne")))

    private fun radio(): List<MediaItem> {
        val current = source.current()
        val now = listOfNotNull(current?.let {
            stationItem(AutoIds.station("track", it.id), it.title, text("Lancer la radio"), source.cover(it)).withGroup(text("Lecture en cours"))
        })
        val seeds = source.stations()
        val artists = seeds.artists.take(STATIONS).map { seed ->
            stationItem(AutoIds.station("artist", seed.name), seed.name, text("Radio de l'artiste"),
                seed.tracks.firstOrNull()?.let(source::cover)).withGroup(text("Vos artistes"))
        }
        val titles = seeds.titles.filter { it.id != current?.id }.take(STATIONS).map { track ->
            stationItem(AutoIds.station("track", track.id), track.title, track.artist, source.cover(track)).withGroup(text("D'après vos écoutes"))
        }
        return now + artists + titles
    }

    private fun artistPage(key: String): List<MediaItem>? {
        val artist = groups().third.firstOrNull { it.key == key } ?: return null
        val parent = AutoIds.artist(key)
        val actions = listOf(actionItem(AutoIds.shuffle(parent), text("Lecture aléatoire"), artist.name, "media3_icon_shuffle_on"),
            stationItem(AutoIds.station("artist", artist.name), text("Radio de l'artiste"), artist.name, null, icon = "media3_icon_radio"))
        val albums = groups().second.filter { AutoCollections.artistKey(it.artist) == key }.map { albumItem(it).withGroup(text("Albums")) }
        return actions + albums + trackItems(parent, artist.tracks, text("Titres"))
    }

    /** All the titles of a list, in its order (the queue of a title chosen in it), or null for an unknown list. */
    suspend fun tracksOf(parentId: String): List<Track>? = when (parentId) {
        AutoIds.HOME -> cached(parentId, LOCAL_TTL_MS) { source.recent() }
        AutoIds.FAVORITES -> cached(parentId, LOCAL_TTL_MS) { source.favorites() }
        AutoIds.DOWNLOADS -> cached(parentId, LOCAL_TTL_MS) { source.downloads() }
        AutoIds.SONGS -> cached(parentId, LOCAL_TTL_MS) { AutoCollections.songs(library()) }
        else -> collectionTracks(parentId)
    }?.take(MAX_QUEUE)

    private suspend fun collectionTracks(parentId: String): List<Track>? {
        AutoIds.playlistId(parentId)?.let { id ->
            val list = source.playlists().firstOrNull { it.id == id } ?: return null
            return cached(parentId, LOCAL_TTL_MS) { list.entries.mapNotNull { it.track } }
        }
        AutoIds.mixId(parentId)?.let { id -> return mixTracks(parentId, id) }
        AutoIds.searchQuery(parentId)?.let { return search(it).tracks }
        AutoIds.albumKey(parentId)?.let { key -> return groups().second.firstOrNull { it.key == key }?.tracks }
        AutoIds.artistKey(parentId)?.let { key -> return groups().third.firstOrNull { it.key == key }?.tracks }
        AutoIds.shelfTitle(parentId)?.let { title ->
            return cached(parentId, LOCAL_TTL_MS) { source.shelves().firstOrNull { it.title == title }?.tracks.orEmpty() }.ifEmpty { null }
        }
        return null
    }

    private suspend fun mixTracks(parentId: String, id: String): List<Track> =
        lists[parentId]?.takeIf { clock() - it.at < REMOTE_TTL_MS }?.value
            ?: (withTimeoutOrNull(mixTimeoutMs) { source.mixTracks(id) } ?: throw AutoUnavailable())
                .also { lists[parentId] = Entry(clock(), it) }

    private inline fun cached(key: String, ttl: Long, load: () -> List<Track>): List<Track> {
        lists[key]?.takeIf { clock() - it.at < ttl }?.let { return it.value }
        return load().distinctBy { it.id }.take(MAX_QUEUE).also { lists[key] = Entry(clock(), it) }
    }

    private fun library(): List<Track> = cached(LIBRARY_KEY, LOCAL_TTL_MS) { source.library() }

    /** The library, its albums and its artists, grouped once per version of the library. */
    private fun groups(): Triple<List<Track>, List<AutoAlbum>, List<AutoArtist>> {
        val titles = library()
        grouped?.takeIf { it.first === titles }?.let { return it }
        return Triple(titles, AutoCollections.albums(titles), AutoCollections.artists(titles)).also { grouped = it }
    }

    // ------------------------------------------------------------------ Search

    /** Runs (or reuses) the search for [query]; returns how many items it has for the car. */
    suspend fun searchCount(query: String): Int = search(query).size

    suspend fun searchResult(query: String, page: Int, pageSize: Int): List<MediaItem> =
        paged(searchItems(query), page, pageSize)

    private suspend fun searchItems(query: String): List<MediaItem> {
        val results = search(query)
        val parent = AutoIds.search(query)
        return (results.artists.map { artistItem(it).withGroup(text("Artistes")) } +
            results.albums.map { albumItem(it).withGroup(text("Albums")) } +
            results.playlists.map { playlistItem(it).withGroup(text("Playlists")) } +
            trackItems(parent, results.library, text("Bibliothèque")) +
            trackItems(parent, results.catalogue, text("Titres"))).take(MAX_ITEMS)
    }

    /**
     * What the listener has first (downloads play offline), then the catalogue as the phone's search
     * ranks it (exact title, then the artist named, then every word, what the listener plays a little higher),
     * without a second copy of a recording already found in the library.
     */
    internal suspend fun search(query: String): SearchResults {
        val key = MediaOverrides.normalize(query)
        if (key.isBlank()) return SearchResults(emptyList(), emptyList(), emptyList())
        searches[key]?.takeIf { clock() - it.at < REMOTE_TTL_MS }?.let { return it.value }
        val taste = runCatching { source.taste() }.getOrDefault(SearchRank.Taste())
        val library = SearchRank.rank(query, source.searchLibrary(query).distinctBy { it.id }, taste).take(MAX_ITEMS)
        val found = withTimeoutOrNull(searchTimeoutMs) { source.searchCatalogue(query) } ?: emptyList()
        val catalogue = SearchRank.rank(query, found.filter { item -> library.none { it.id == item.id || SearchGroups.sameRecording(it, item) } }
            .distinctBy { it.id }, taste).take(MAX_ITEMS)
        val (_, albums, artists) = groups()
        val results = SearchResults(source.searchPlaylists(query).take(10), library, catalogue,
            artists.filter { AutoCollections.matches(it, query) }.take(3), albums.filter { AutoCollections.matches(it, query) }.take(6))
        // An empty answer (no network) is not kept: the next try asks again.
        if (results.size > 0) searches[key] = Entry(clock(), results)
        return results
    }

    // ------------------------------------------------------------------ Play requests

    /** Turns what a controller sent (media ids, or a voice query) into a request for the phone's queue. */
    suspend fun request(items: List<MediaItem>, startIndex: Int): AutoRequest {
        if (items.isEmpty()) return AutoRequest.Missing(text("Titre introuvable"))
        if (items.size == 1) {
            val item = items[0]
            val id = item.mediaId
            val query = item.requestMetadata.searchQuery
            if (id.isBlank() || (query != null && !AutoIds.isOwn(id) && findTrack(id) == null))
                return voice(query.orEmpty(), item.requestMetadata.extras)
            return single(id)
        }
        val resolved = items.mapIndexedNotNull { index, item -> trackFor(item.mediaId)?.let { index to it } }
        if (resolved.isEmpty()) return AutoRequest.Missing(text("Titre introuvable"))
        val start = resolved.indexOfFirst { it.first >= startIndex.coerceAtLeast(0) }.coerceAtLeast(0)
        return AutoRequest.Play(resolved.map { it.second }, start)
    }

    /** Titles to add after the queue (« add to queue » of a controller). */
    suspend fun enqueueRequest(items: List<MediaItem>): AutoRequest {
        val tracks = items.flatMap { item ->
            AutoIds.trackOf(item.mediaId)?.let { listOfNotNull(trackFor(item.mediaId)) }
                ?: runCatching { tracksOf(item.mediaId) }.getOrNull()
                ?: listOfNotNull(findTrack(item.mediaId))
        }
        return if (tracks.isEmpty()) AutoRequest.Missing(text("Titre introuvable")) else AutoRequest.Enqueue(tracks)
    }

    /** Carries [request] out; returns a message for the car when nothing could be played. */
    suspend fun perform(request: AutoRequest): String? {
        when (request) {
            is AutoRequest.Play -> playback.play(request.tracks, request.index.coerceIn(request.tracks.indices))
            is AutoRequest.Shuffle -> playback.shuffle(request.tracks)
            is AutoRequest.Enqueue -> playback.enqueue(request.tracks)
            is AutoRequest.Radio -> if (source.online()) playback.radio(request.seed) else return radioOffline()
            is AutoRequest.ArtistRadio -> if (source.online()) playback.artistRadio(request.name, request.catalogId, request.tracks)
                else return radioOffline()
            AutoRequest.Resume -> return resume()
            is AutoRequest.Missing -> return request.message
        }
        return null
    }

    private fun radioOffline() = text("Hors ligne : la radio a besoin d'une connexion")

    /** What was playing; with nothing to pick up again, the favourites, a mix, what was played last, the downloads. */
    private suspend fun resume(): String? {
        if (playback.resume()) return null
        val fallback = fallback() ?: return text("Aucun résultat")
        playback.shuffle(fallback.take(MAX_QUEUE))
        return null
    }

    private suspend fun fallback(): List<Track>? {
        if (!source.online()) return listOf(source.downloads(), source.favorites()).firstOrNull { it.isNotEmpty() }
        source.favorites().takeIf { it.isNotEmpty() }?.let { return it }
        source.mixes().firstOrNull()?.let { card ->
            withTimeoutOrNull(mixTimeoutMs) { source.mixTracks(card.id) }?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return listOf(source.recent(), source.downloads()).firstOrNull { it.isNotEmpty() }
    }

    private suspend fun single(mediaId: String): AutoRequest {
        if (mediaId == AutoIds.RESUME) return AutoRequest.Resume
        AutoIds.trackOf(mediaId)?.let { (parent, trackId) -> return chosen(parent, trackId) }
        AutoIds.allOf(mediaId)?.let { return whole(it, shuffled = false) }
        AutoIds.shuffleOf(mediaId)?.let { return whole(it, shuffled = true) }
        AutoIds.stationOf(mediaId)?.let { (kind, value) -> return station(kind, value) }
        // A list itself (a playlist, a mix, a tab): played from its first title.
        if (mediaId != AutoIds.ROOT && mediaId != AutoIds.PLAYLISTS) {
            val list = runCatching { tracksOf(mediaId) }.getOrNull()
            if (list != null) return if (list.isEmpty()) AutoRequest.Missing(text("Aucun résultat")) else AutoRequest.Play(list, 0)
        }
        // A bare track id, as other controllers may send.
        return findTrack(mediaId)?.let { AutoRequest.Play(listOf(it), 0) } ?: AutoRequest.Missing(text("Titre introuvable"))
    }

    private suspend fun chosen(parent: String, trackId: String): AutoRequest {
        val list = runCatching { tracksOf(parent) }.getOrNull().orEmpty()
        val index = list.indexOfFirst { it.id == trackId }
        if (index >= 0) return AutoRequest.Play(list, index)
        val track = findTrack(trackId) ?: return AutoRequest.Missing(text("Titre introuvable"))
        return AutoRequest.Play(listOf(track), 0)
    }

    private suspend fun whole(parent: String, shuffled: Boolean): AutoRequest {
        val list = runCatching { tracksOf(parent) }.getOrNull()
        if (list.isNullOrEmpty()) return AutoRequest.Missing(text(if (list == null) "Titre introuvable" else "Aucun résultat"))
        return if (shuffled) AutoRequest.Shuffle(list) else AutoRequest.Play(list, 0)
    }

    private fun station(kind: String, value: String): AutoRequest {
        if (kind == "artist") {
            source.stations().artists.firstOrNull { it.name == value }?.let { return AutoRequest.ArtistRadio(it.name, it.catalogId, it.tracks) }
            val tracks = groups().third.firstOrNull { it.key == AutoCollections.artistKey(value) }?.tracks
            return if (tracks.isNullOrEmpty()) AutoRequest.Missing(text("Titre introuvable")) else AutoRequest.ArtistRadio(value, null, tracks)
        }
        val seed = source.current()?.takeIf { it.id == value } ?: source.stations().titles.firstOrNull { it.id == value } ?: findTrack(value)
        return seed?.let { AutoRequest.Radio(it) } ?: AutoRequest.Missing(text("Titre introuvable"))
    }

    private suspend fun trackFor(mediaId: String): Track? {
        val (parent, trackId) = AutoIds.trackOf(mediaId) ?: return findTrack(mediaId)
        return runCatching { tracksOf(parent) }.getOrNull()?.firstOrNull { it.id == trackId } ?: findTrack(trackId)
    }

    private fun findTrack(trackId: String): Track? {
        synchronized(searches) { searches.values.toList() }.forEach { entry -> entry.value.tracks.firstOrNull { it.id == trackId }?.let { return it } }
        synchronized(lists) { lists.values.toList() }.forEach { entry -> entry.value.firstOrNull { it.id == trackId }?.let { return it } }
        return source.lookup(trackId)
    }

    // ------------------------------------------------------------------ Voice

    /**
     * « Hey Google, joue X sur Ketvoice ». An empty query (or « de la musique ») picks up what was playing.
     * « Mes favoris », « mes téléchargements », « mon mix » and a playlist's name play that collection;
     * « radio X » starts a radio; « X en aléatoire » shuffles. The assistant's extras (artist, album, title,
     * genre, playlist and the focus) narrow the search; otherwise the search runs and its best match plays first.
     */
    internal suspend fun voice(query: String, extras: Bundle?): AutoRequest {
        val heard = VoiceAsk.of(query, extras)
        if (heard.isEmpty) return AutoRequest.Resume
        // « Radio Ga Ga » is a title: the words as said name one, so no radio is meant.
        val ask = if (heard.radio && VoiceMatch.namesTitle(heard.spoken, search(heard.spoken).tracks))
            heard.copy(text = heard.spoken, radio = false, shuffle = false) else heard
        val request = if (ask.radio) voiceRadio(ask) else voiceTarget(ask)
        return if (ask.shuffle && request is AutoRequest.Play) AutoRequest.Shuffle(request.tracks) else request
    }

    private suspend fun voiceTarget(ask: VoiceAsk): AutoRequest {
        named(ask)?.let { return it }
        if (ask.focus == VoiceAsk.GENRE || (ask.genre != null && ask.title == null && ask.artist == null)) {
            val genre = ask.genre ?: ask.text
            return search(genre).tracks.takeIf { it.isNotEmpty() }?.let { AutoRequest.Shuffle(it.take(MAX_QUEUE)) } ?: nothingFor(genre)
        }
        if (ask.focus == VoiceAsk.ALBUM) libraryAlbum(ask)?.let { return AutoRequest.Play(it.tracks, 0) }
        return searched(ask)
    }

    private suspend fun searched(ask: VoiceAsk): AutoRequest {
        val text = ask.text
        val found = search(text).tracks
        if (found.isEmpty()) return nothingFor(text)
        val ranked = VoiceMatch.rank(found, text, ask.title, ask.artist)
        val queue = when {
            ask.focus == VoiceAsk.ARTIST && ask.artist != null -> ranked.filter { VoiceMatch.sameArtist(it, ask.artist) }.ifEmpty { ranked }
            ask.focus == VoiceAsk.ALBUM && ask.album != null ->
                ranked.filter { MediaOverrides.normalize(it.album).contains(MediaOverrides.normalize(ask.album)) }.ifEmpty { ranked }
            // One title asked for: it plays, and « Lecture automatique » carries on with titles like it.
            ask.focus == VoiceAsk.TITLE -> ranked.take(1)
            else -> ranked
        }
        return AutoRequest.Play(queue.take(MAX_QUEUE), 0)
    }

    private fun nothingFor(text: String) = AutoRequest.Missing(text("Aucun résultat pour « $text »"))

    /** A collection named aloud: a playlist, the favourites, the downloads, a personal mix. */
    private suspend fun named(ask: VoiceAsk): AutoRequest? {
        val wanted = MediaOverrides.normalize(ask.playlist ?: ask.text)
        if (wanted.isEmpty()) return null
        val playlists = source.playlists()
        val list = playlists.firstOrNull { MediaOverrides.normalize(it.name) == wanted }
            ?: playlists.takeIf { ask.focus == VoiceAsk.PLAYLIST }?.firstOrNull { MediaOverrides.normalize(it.name).contains(wanted) }
        list?.entries?.mapNotNull { it.track }?.takeIf { it.isNotEmpty() }?.let { return AutoRequest.Play(it.take(MAX_QUEUE), 0) }
        if (wanted in VoiceMatch.favoriteWords) source.favorites().takeIf { it.isNotEmpty() }?.let { return AutoRequest.Shuffle(it.take(MAX_QUEUE)) }
        if (wanted in VoiceMatch.downloadWords) source.downloads().takeIf { it.isNotEmpty() }?.let { return AutoRequest.Shuffle(it.take(MAX_QUEUE)) }
        if (wanted in VoiceMatch.mixWords) source.mixes().firstOrNull()?.let { card ->
            withTimeoutOrNull(mixTimeoutMs) { source.mixTracks(card.id) }?.takeIf { it.isNotEmpty() }?.let { return AutoRequest.Play(it.take(MAX_QUEUE), 0) }
        }
        return null
    }

    private fun libraryAlbum(ask: VoiceAsk): AutoAlbum? {
        val name = SearchRank.fold(ask.album ?: return null)
        val albums = groups().second.filter { SearchRank.fold(it.title) == name }
        return albums.firstOrNull { ask.artist != null && VoiceMatch.sameArtist(it.tracks.first(), ask.artist) } ?: albums.firstOrNull()
    }

    /** « Radio Adele », « la radio de Hello »: the artist's radio when the words name an artist, else a title's. */
    private suspend fun voiceRadio(ask: VoiceAsk): AutoRequest {
        if (ask.text.isEmpty() && ask.artist == null) {
            val seed = source.current() ?: source.stations().titles.firstOrNull() ?: source.recent().firstOrNull()
            return seed?.let { AutoRequest.Radio(it) } ?: AutoRequest.Missing(text("Aucun résultat"))
        }
        val name = ask.artist ?: ask.text
        source.stations().artists.firstOrNull { SearchRank.fold(it.name) == SearchRank.fold(name) }
            ?.let { return AutoRequest.ArtistRadio(it.name, it.catalogId, it.tracks) }
        val found = search(name).tracks
        if (found.isEmpty()) return nothingFor(name)
        val ranked = VoiceMatch.rank(found, name, ask.title, ask.artist)
        val byArtist = ranked.filter { VoiceMatch.sameArtist(it, name) }
        if (byArtist.isNotEmpty() && (ask.focus == VoiceAsk.ARTIST || SearchRank.namesArtist(name, ranked)))
            return AutoRequest.ArtistRadio(PersonalMixes.primaryArtist(byArtist.first()).ifBlank { name }, byArtist.first().artistId, byArtist)
        return AutoRequest.Radio(ranked.first())
    }

    // ------------------------------------------------------------------ Items

    private fun trackItems(parent: String, tracks: List<Track>, group: String? = null): List<MediaItem> =
        tracks.take(MAX_ITEMS).map { trackItem(parent, it, group) }

    /** « Tout lire » and « Lecture aléatoire » above a list's titles (« Lecture aléatoire » only, for [playAll] false). */
    private fun withActions(parent: String, tracks: List<Track>, playAll: Boolean = true): List<MediaItem> {
        if (tracks.isEmpty()) return emptyList()
        val count = countText(tracks.size)
        val actions = listOfNotNull(
            if (playAll) actionItem(AutoIds.all(parent), text("Tout lire"), count, "media3_icon_play") else null,
            actionItem(AutoIds.shuffle(parent), text("Lecture aléatoire"), count, "media3_icon_shuffle_on"))
        return actions + trackItems(parent, tracks)
    }

    private fun countText(count: Int) = text(if (count == 1) "1 titre" else "$count titres")

    private fun trackItem(parent: String, track: Track, group: String?): MediaItem {
        val extras = Bundle()
        if (track.explicit) extras.putLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT, MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT)
        if (source.isDownloaded(track.id))
            extras.putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
        if (group != null) extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        return MediaItem.Builder()
            .setMediaId(AutoIds.track(parent, track.id))
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album.ifBlank { null })
                .setDisplayTitle(track.title)
                .setSubtitle(track.artist)
                .setArtworkUri(artwork(source.cover(track)))
                .setDurationMs(track.durationMs.takeIf { it > 0 })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build())
            .build()
    }

    /** A playable line that is not a title: « Tout lire », « Lecture aléatoire », « Lire vos téléchargements ». */
    private fun actionItem(id: String, title: String, subtitle: String?, icon: String): MediaItem =
        playable(id, title, subtitle, iconUri(icon), MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private fun stationItem(id: String, title: String, subtitle: String?, cover: String?, icon: String? = null): MediaItem =
        playable(id, title, subtitle, artwork(cover) ?: iconUri(icon ?: "media3_icon_radio"), MediaMetadata.MEDIA_TYPE_RADIO_STATION)

    private fun playable(id: String, title: String, subtitle: String?, art: Uri?, type: Int): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(art)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(type)
            .build())
        .build()

    private fun resumeItem(track: Track?): MediaItem? = track?.let {
        playable(AutoIds.RESUME, it.title, it.artist, artwork(source.cover(it)), MediaMetadata.MEDIA_TYPE_MUSIC)
    }

    private fun playlistItem(list: LocalPlaylist): MediaItem {
        val tracks = list.entries.mapNotNull { it.track }
        val cover = list.coverUrl ?: tracks.firstNotNullOfOrNull(source::cover)
        return folder(AutoIds.playlist(list.id), list.name, countText(tracks.size), cover, MediaMetadata.MEDIA_TYPE_PLAYLIST,
            playable = LIST, badges = badges(tracks))
    }

    private fun mixItem(card: MixCard): MediaItem =
        folder(AutoIds.mix(card.id), text(source.mixTitle(card)), text(source.mixSubtitle(card)),
            card.covers.firstOrNull { !it.isNullOrBlank() }, MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = LIST)

    private fun shelfItem(shelf: HomeShelf): MediaItem =
        folder(AutoIds.shelf(shelf.title), text(shelf.title), text(shelf.subtitle), shelf.tracks.firstNotNullOfOrNull(source::cover),
            MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = LIST)

    private fun albumItem(album: AutoAlbum): MediaItem =
        folder(AutoIds.album(album.key), album.title, album.artist, album.tracks.firstNotNullOfOrNull(source::cover) ?: album.cover,
            MediaMetadata.MEDIA_TYPE_ALBUM, playable = LIST, badges = badges(album.tracks))

    private fun artistItem(artist: AutoArtist): MediaItem =
        folder(AutoIds.artist(artist.key), artist.name, countText(artist.tracks.size),
            artist.tracks.firstNotNullOfOrNull(source::cover) ?: artist.cover, MediaMetadata.MEDIA_TYPE_ARTIST,
            browsable = GRID, playable = LIST)

    /** A collection is explicit when one of its titles is, downloaded when all of them are. */
    private fun badges(tracks: List<Track>): Bundle = Bundle().apply {
        if (tracks.any { it.explicit }) putLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT, MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT)
        if (tracks.isNotEmpty() && tracks.all { source.isDownloaded(it.id) })
            putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
    }

    private fun folder(id: String, title: String, subtitle: String?, cover: String?, type: Int, browsable: Int? = null,
                       playable: Int? = null, icon: String? = null, badges: Bundle? = null): MediaItem {
        val extras = badges ?: Bundle()
        if (browsable != null) extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
        if (playable != null) extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artwork(cover) ?: icon?.let(::iconUri))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setExtras(extras)
                .build())
            .build()
    }

    /** A drawable of the app as a car reads it (tinted by the car: category and tab icons are plain vectors). */
    private fun iconUri(name: String): Uri? = iconPackage?.let { Uri.parse("android.resource://$it/drawable/$name") }

    private fun MediaItem.withGroup(group: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY)
        extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }
}

private const val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
private const val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
private const val CATEGORY = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM

/** One page of [items]; an out-of-range page is empty (no overflow on Media3's « everything » page size). */
internal fun <T> paged(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val from = page.toLong() * pageSize
    if (from >= items.size) return emptyList()
    val to = minOf(items.size.toLong(), from + pageSize).toInt()
    return items.subList(from.toInt(), to)
}
