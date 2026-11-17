@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.LibrarySearch
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.SearchGroups
import com.ketvoice.app.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

/*
 * Android Auto (and any other media browser: Android Automotive, Wear, Bluetooth head units).
 *
 * The browse tree is built from the listener's own stores, the way the phone shows them:
 *
 *   root
 *   ├── Accueil          your mixes (grid) then the recently played titles
 *   ├── Favoris          the favourite titles
 *   ├── Playlists        the listener's playlists (grid) → their titles
 *   └── Téléchargements  the downloaded titles (they play offline)
 *
 * A title carries its list in its media id, so playing it from the car queues the rest of the
 * list. Nothing is resolved while browsing: a car only sends media ids, and the title goes to
 * [Player] — the phone's queue — which resolves it exactly as a tap on the phone does
 * (downloaded file first, then the cache, then the stream sources). The session never puts
 * anything on the engine itself for these requests.
 */

/** Media ids of the browse tree. Parts are URL-encoded: track and playlist ids may hold any character. */
internal object AutoIds {
    const val ROOT = "ketvoice.auto"
    const val HOME = "ketvoice.auto.home"
    const val FAVORITES = "ketvoice.auto.favorites"
    const val PLAYLISTS = "ketvoice.auto.playlists"
    const val DOWNLOADS = "ketvoice.auto.downloads"
    private const val PLAYLIST = "ketvoice.auto.playlist/"
    private const val MIX = "ketvoice.auto.mix/"
    private const val SEARCH = "ketvoice.auto.search/"
    private const val TRACK = "ketvoice.auto.track/"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun dec(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

    fun playlist(id: String) = PLAYLIST + enc(id)
    fun mix(id: String) = MIX + enc(id)
    fun search(query: String) = SEARCH + enc(query.trim())
    fun track(parent: String, trackId: String) = TRACK + enc(parent) + "/" + enc(trackId)

    fun playlistId(mediaId: String): String? = mediaId.takeIf { it.startsWith(PLAYLIST) }?.let { dec(it.removePrefix(PLAYLIST)) }
    fun mixId(mediaId: String): String? = mediaId.takeIf { it.startsWith(MIX) }?.let { dec(it.removePrefix(MIX)) }
    fun searchQuery(mediaId: String): String? = mediaId.takeIf { it.startsWith(SEARCH) }?.let { dec(it.removePrefix(SEARCH)) }

    /** The list a title was chosen in and its own id, or null when [mediaId] is not a title of the tree. */
    fun trackOf(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(TRACK)) return null
        val parts = mediaId.removePrefix(TRACK).split('/')
        if (parts.size != 2) return null
        val parent = dec(parts[0]) ?: return null
        val track = dec(parts[1])?.takeIf { it.isNotBlank() } ?: return null
        return parent to track
    }

    fun isTabOrRoot(mediaId: String) = mediaId in setOf(ROOT, HOME, FAVORITES, PLAYLISTS, DOWNLOADS)
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
}

/** Where a car's request ends: the phone's queue. */
internal interface AutoPlayback {
    fun play(tracks: List<Track>, index: Int)
    fun shuffle(tracks: List<Track>)
    fun enqueue(tracks: List<Track>)
    /** Plays what was playing (or the restored session); false when there is nothing to resume. */
    suspend fun resume(): Boolean
}

/** What a request from the car asks for, once its media ids are known. */
internal sealed interface AutoRequest {
    data class Play(val tracks: List<Track>, val index: Int) : AutoRequest
    data class Shuffle(val tracks: List<Track>) : AutoRequest
    data class Enqueue(val tracks: List<Track>) : AutoRequest
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
    private val mixTimeoutMs: Long = MIX_TIMEOUT_MS
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
        private const val SEARCH_CACHE = 8
        private const val PLAYLIST_FOCUS = "vnd.android.cursor.item/playlist"
        private const val ARTIST_FOCUS = "vnd.android.cursor.item/artist"
        private const val ALBUM_FOCUS = "vnd.android.cursor.item/album"
    }

    private class Entry<T>(val at: Long, val value: T)
    /** Lists already given to the car: its pages, and the queue of a title chosen in it, stay the same. */
    private val lists = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Entry<List<Track>>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<List<Track>>>?) = size > 24
    })
    private val searches = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Entry<SearchResults>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<SearchResults>>?) = size > SEARCH_CACHE
    })

    internal data class SearchResults(val playlists: List<LocalPlaylist>, val library: List<Track>, val catalogue: List<Track>) {
        val tracks: List<Track> get() = library + catalogue
        val size: Int get() = (playlists.size + library.size + catalogue.size).coerceAtMost(MAX_ITEMS)
    }

    fun prepare() = source.prepare()

    // ------------------------------------------------------------------ Browse

    fun root(): MediaItem = folder(AutoIds.ROOT, "Ketvoice", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    /** Default looks of the tree: folders (playlists, mixes) as a grid, titles as a list. */
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    /** At most four tabs (Android Auto's limit), always present: an empty one says so on the car's own screen. */
    fun tabs(): List<MediaItem> = listOf(
        folder(AutoIds.HOME, source.text("Accueil"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        folder(AutoIds.FAVORITES, source.text("Favoris"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        folder(AutoIds.PLAYLISTS, source.text("Playlists"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        folder(AutoIds.DOWNLOADS, source.text("Téléchargements"), null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))

    /** Children of [parentId], one page; null for an id the tree does not have. */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>? {
        val all: List<MediaItem> = when (parentId) {
            AutoIds.ROOT -> tabs()
            AutoIds.HOME -> home()
            AutoIds.PLAYLISTS -> source.playlists().sortedByDescending { it.pinned }.map(::playlistItem)
            AutoIds.FAVORITES, AutoIds.DOWNLOADS -> trackItems(parentId, tracksOf(parentId) ?: emptyList())
            else -> when {
                AutoIds.playlistId(parentId) != null || AutoIds.mixId(parentId) != null ->
                    trackItems(parentId, tracksOf(parentId) ?: return null)
                AutoIds.searchQuery(parentId) != null -> searchItems(AutoIds.searchQuery(parentId)!!)
                else -> return null
            }
        }
        return paged(all.take(MAX_ITEMS), page, pageSize)
    }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == AutoIds.ROOT) return root()
        tabs().firstOrNull { it.mediaId == mediaId }?.let { return it }
        AutoIds.playlistId(mediaId)?.let { id -> return source.playlists().firstOrNull { it.id == id }?.let(::playlistItem) }
        AutoIds.mixId(mediaId)?.let { id -> return source.mixes().firstOrNull { it.id == id }?.let(::mixItem) }
        val (parent, trackId) = AutoIds.trackOf(mediaId) ?: return null
        val track = tracksOf(parent)?.firstOrNull { it.id == trackId } ?: findTrack(trackId) ?: return null
        return trackItem(parent, track, null)
    }

    private fun home(): List<MediaItem> {
        val mixes = source.mixes().map { mixItem(it).withGroup(source.text("Vos mix")) }
        val recent = trackItems(AutoIds.HOME, cached(AutoIds.HOME, LOCAL_TTL_MS) { source.recent() }, source.text("Récemment écoutés"))
        return mixes + recent
    }

    /** All the titles of a list, in its order (the queue of a title chosen in it), or null for an unknown list. */
    suspend fun tracksOf(parentId: String): List<Track>? = when (parentId) {
        AutoIds.HOME -> cached(parentId, LOCAL_TTL_MS) { source.recent() }
        AutoIds.FAVORITES -> cached(parentId, LOCAL_TTL_MS) { source.favorites() }
        AutoIds.DOWNLOADS -> cached(parentId, LOCAL_TTL_MS) { source.downloads() }
        else -> {
            val playlist = AutoIds.playlistId(parentId)
            val mix = AutoIds.mixId(parentId)
            val query = AutoIds.searchQuery(parentId)
            when {
                playlist != null -> source.playlists().firstOrNull { it.id == playlist }?.let { list ->
                    cached(parentId, LOCAL_TTL_MS) { list.entries.mapNotNull { it.track } }
                }
                mix != null -> lists[parentId]?.takeIf { clock() - it.at < REMOTE_TTL_MS }?.value
                    ?: (withTimeoutOrNull(mixTimeoutMs) { source.mixTracks(mix) } ?: throw AutoUnavailable())
                        .also { lists[parentId] = Entry(clock(), it) }
                query != null -> search(query).tracks
                else -> null
            }
        }
    }?.take(MAX_QUEUE)

    private inline fun cached(key: String, ttl: Long, load: () -> List<Track>): List<Track> {
        lists[key]?.takeIf { clock() - it.at < ttl }?.let { return it.value }
        return load().distinctBy { it.id }.take(MAX_QUEUE).also { lists[key] = Entry(clock(), it) }
    }

    // ------------------------------------------------------------------ Search

    /** Runs (or reuses) the search for [query]; returns how many items it has for the car. */
    suspend fun searchCount(query: String): Int = search(query).size

    suspend fun searchResult(query: String, page: Int, pageSize: Int): List<MediaItem> =
        paged(searchItems(query), page, pageSize)

    private suspend fun searchItems(query: String): List<MediaItem> {
        val results = search(query)
        val parent = AutoIds.search(query)
        return (results.playlists.map { playlistItem(it).withGroup(source.text("Playlists")) } +
            trackItems(parent, results.library, source.text("Bibliothèque")) +
            trackItems(parent, results.catalogue, source.text("Titres"))).take(MAX_ITEMS)
    }

    /**
     * What the listener has first (downloads play offline), then the catalogue as the phone's search
     * shows it, without a second copy of a recording already found in the library.
     */
    internal suspend fun search(query: String): SearchResults {
        val key = MediaOverrides.normalize(query)
        if (key.isBlank()) return SearchResults(emptyList(), emptyList(), emptyList())
        searches[key]?.takeIf { clock() - it.at < REMOTE_TTL_MS }?.let { return it.value }
        val playlists = source.searchPlaylists(query).take(10)
        val library = source.searchLibrary(query).distinctBy { it.id }.take(MAX_ITEMS)
        val catalogue = (withTimeoutOrNull(searchTimeoutMs) { source.searchCatalogue(query) } ?: emptyList())
            .filter { found -> library.none { it.id == found.id || SearchGroups.sameRecording(it, found) } }
            .distinctBy { it.id }
            .take(MAX_ITEMS)
        val results = SearchResults(playlists, library, catalogue)
        // An empty answer (no network) is not kept: the next try asks again.
        if (results.size > 0) searches[key] = Entry(clock(), results)
        return results
    }

    // ------------------------------------------------------------------ Play requests

    /** Turns what a controller sent (media ids, or a voice query) into a request for the phone's queue. */
    suspend fun request(items: List<MediaItem>, startIndex: Int): AutoRequest {
        if (items.isEmpty()) return AutoRequest.Missing(source.text("Titre introuvable"))
        if (items.size == 1) {
            val item = items[0]
            val id = item.mediaId
            val query = item.requestMetadata.searchQuery
            if (id.isBlank() || (query != null && AutoIds.trackOf(id) == null && !AutoIds.isTabOrRoot(id) &&
                    AutoIds.playlistId(id) == null && AutoIds.mixId(id) == null && findTrack(id) == null))
                return voice(query.orEmpty(), item.requestMetadata.extras)
            return single(id)
        }
        val resolved = items.mapIndexedNotNull { index, item -> trackFor(item.mediaId)?.let { index to it } }
        if (resolved.isEmpty()) return AutoRequest.Missing(source.text("Titre introuvable"))
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
        return if (tracks.isEmpty()) AutoRequest.Missing(source.text("Titre introuvable")) else AutoRequest.Enqueue(tracks)
    }

    /** Carries [request] out; returns a message for the car when nothing could be played. */
    suspend fun perform(request: AutoRequest): String? {
        when (request) {
            is AutoRequest.Play -> playback.play(request.tracks, request.index.coerceIn(request.tracks.indices))
            is AutoRequest.Shuffle -> playback.shuffle(request.tracks)
            is AutoRequest.Enqueue -> playback.enqueue(request.tracks)
            AutoRequest.Resume -> if (!playback.resume()) {
                // Nothing to pick up again: the favourites, else what was played last, else the downloads.
                val fallback = listOf(source.favorites(), source.recent(), source.downloads()).firstOrNull { it.isNotEmpty() }
                    ?: return source.text("Aucun résultat")
                playback.shuffle(fallback.take(MAX_QUEUE))
            }
            is AutoRequest.Missing -> return request.message
        }
        return null
    }

    private suspend fun single(mediaId: String): AutoRequest {
        AutoIds.trackOf(mediaId)?.let { (parent, trackId) ->
            val list = runCatching { tracksOf(parent) }.getOrNull().orEmpty()
            val index = list.indexOfFirst { it.id == trackId }
            if (index >= 0) return AutoRequest.Play(list, index)
            val track = findTrack(trackId) ?: return AutoRequest.Missing(source.text("Titre introuvable"))
            return AutoRequest.Play(listOf(track), 0)
        }
        // A list itself (a playlist, a mix, a tab): played from its first title.
        if (mediaId != AutoIds.ROOT && mediaId != AutoIds.PLAYLISTS) {
            val list = runCatching { tracksOf(mediaId) }.getOrNull()
            if (list != null) return if (list.isEmpty()) AutoRequest.Missing(source.text("Aucun résultat")) else AutoRequest.Play(list, 0)
        }
        // A bare track id, as other controllers may send.
        return findTrack(mediaId)?.let { AutoRequest.Play(listOf(it), 0) } ?: AutoRequest.Missing(source.text("Titre introuvable"))
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

    /**
     * « Hey Google, joue X sur Ketvoice ». An empty query picks up what was playing. A playlist named
     * like the query plays that playlist; otherwise the search runs and its best match plays first,
     * the other results after it.
     */
    internal suspend fun voice(query: String, extras: Bundle?): AutoRequest {
        val q = query.trim()
        val focus = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS)
        val artist = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
        val title = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
        val album = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }
        val playlistName = extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_PLAYLIST)?.trim()?.takeIf { it.isNotEmpty() }
        if (q.isEmpty() && artist == null && title == null && playlistName == null && album == null) return AutoRequest.Resume

        val wanted = MediaOverrides.normalize(playlistName ?: q)
        if (wanted.isNotEmpty()) {
            val playlists = source.playlists()
            val named = playlists.firstOrNull { MediaOverrides.normalize(it.name) == wanted }
                ?: playlists.takeIf { focus == PLAYLIST_FOCUS }?.firstOrNull { MediaOverrides.normalize(it.name).contains(wanted) }
            named?.entries?.mapNotNull { it.track }?.takeIf { it.isNotEmpty() }?.let { return AutoRequest.Play(it.take(MAX_QUEUE), 0) }
            if (wanted in VoiceMatch.favoriteWords) source.favorites().takeIf { it.isNotEmpty() }?.let { return AutoRequest.Shuffle(it) }
        }

        val text = q.ifEmpty { listOfNotNull(title, artist ?: album).joinToString(" ") }
        val found = search(text).tracks
        if (found.isEmpty()) return AutoRequest.Missing(source.text("Aucun résultat pour « $text »"))
        val ranked = VoiceMatch.rank(found, text, title, artist)
        val queue = if (focus == ARTIST_FOCUS && artist != null)
            ranked.filter { VoiceMatch.sameArtist(it, artist) }.ifEmpty { ranked } else ranked
        return AutoRequest.Play(queue.take(MAX_QUEUE), 0)
    }

    // ------------------------------------------------------------------ Items

    private fun trackItems(parent: String, tracks: List<Track>, group: String? = null): List<MediaItem> =
        tracks.take(MAX_ITEMS).map { trackItem(parent, it, group) }

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

    private fun playlistItem(list: LocalPlaylist): MediaItem {
        val count = list.entries.count { it.track != null }
        val cover = list.coverUrl ?: list.entries.firstNotNullOfOrNull { entry -> entry.track?.let(source::cover) }
        return folder(AutoIds.playlist(list.id), list.name, source.text(if (count == 1) "1 titre" else "$count titres"), cover,
            MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    private fun mixItem(card: MixCard): MediaItem =
        folder(AutoIds.mix(card.id), source.text(source.mixTitle(card)), source.text(source.mixSubtitle(card)),
            card.covers.firstOrNull { !it.isNullOrBlank() }, MediaMetadata.MEDIA_TYPE_PLAYLIST,
            playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)

    private fun folder(id: String, title: String, subtitle: String?, cover: String?, type: Int, playable: Int? = null): MediaItem {
        val extras = Bundle()
        if (playable != null) extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
        // The playlists tab holds folders: a grid of covers. The other tabs keep titles in a list.
        if (id == AutoIds.PLAYLISTS || id == AutoIds.HOME)
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        if (AutoIds.isTabOrRoot(id)) extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artwork(cover))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setExtras(extras)
                .build())
            .build()
    }

    private fun MediaItem.withGroup(group: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY)
        extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }
}

/** One page of [items]; an out-of-range page is empty (no overflow on Media3's « everything » page size). */
internal fun <T> paged(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val from = page.toLong() * pageSize
    if (from >= items.size) return emptyList()
    val to = minOf(items.size.toLong(), from + pageSize).toInt()
    return items.subList(from.toInt(), to)
}

/** Which result a spoken request means. */
internal object VoiceMatch {
    /** Spoken names of the favourites (French first, then the other interface languages' most common words). */
    val favoriteWords = setOf("favoris", "mes favoris", "mes titres favoris", "titres favoris", "favorites", "my favorites",
        "favourites", "my favourites", "liked songs", "favoritos", "mis favoritos", "preferiti", "i miei preferiti", "favoriten")

    private fun tokens(value: String) = MediaOverrides.normalize(value).split(' ').filter { it.isNotEmpty() }

    fun sameArtist(track: Track, artist: String): Boolean {
        val wanted = MediaOverrides.normalize(artist)
        return wanted.isNotEmpty() && MediaOverrides.normalize(track.artist).contains(wanted)
    }

    /** How well [track] answers the query: words found in its title and artist, plus an exact title or the artist named. */
    fun score(track: Track, query: String, title: String?, artist: String?): Double {
        val words = tokens(query)
        val own = (tokens(track.title) + tokens(track.artist)).toSet()
        var score = if (words.isEmpty()) 0.0 else words.count { it in own }.toDouble() / words.size
        val name = MediaOverrides.normalize(track.title)
        val spoken = MediaOverrides.normalize(query)
        if (name.isNotEmpty() && (spoken == name || spoken.startsWith("$name "))) score += .5
        if (title != null && MediaOverrides.normalize(title) == name) score += .5
        if (artist != null && sameArtist(track, artist)) score += .5
        return score
    }

    /** Best match first; equal scores keep the search's order (the library before the catalogue). */
    fun rank(tracks: List<Track>, query: String, title: String?, artist: String?): List<Track> =
        tracks.withIndex().sortedWith(compareByDescending<IndexedValue<Track>> { score(it.value, query, title, artist) }.thenBy { it.index })
            .map { it.value }
}

/** The listener's stores, read the way the phone's screens read them. */
internal class AppAutoSource(private val context: Context) : AutoSource {
    override fun prepare() {
        // Opening the app does the same: the stores, artwork, overrides and the restored session.
        Player.connect(context)
        runCatching { ArtistPrefs.init(context) }
    }

    private fun live(tracks: List<Track>) = PersonalMixes.withLocalFiles(tracks, LocalMusic.tracks.value)

    override fun recent(): List<Track> = live(ListeningHistory.tracks.value)

    override fun favorites(): List<Track> {
        val ids = AppSettings.favoriteIds.value
        return live((ListeningHistory.knownTracks.value + ListeningHistory.tracks.value + Downloader.items.value.map { it.track })
            .distinctBy { it.id }.filter { it.id in ids })
    }

    override fun playlists(): List<LocalPlaylist> = PlaylistStore.lists.value
    override fun downloads(): List<Track> = Downloader.items.value.map { it.track }
    override fun isDownloaded(trackId: String): Boolean = Downloader.itemFor(trackId) != null

    override fun mixes(): List<MixCard> = runCatching {
        PersonalMixes.propose(ListeningHistory.knownTracks.value.filter(ArtistPrefs::allows),
            ListeningHistory.tracks.value.filter(ArtistPrefs::allows), ListeningHistory.playCounts.value, AppSettings.favoriteIds.value)
    }.getOrDefault(emptyList())

    override fun mixTitle(card: MixCard): String = com.ketvoice.app.ui.home.mixTitle(card)
    override fun mixSubtitle(card: MixCard): String = com.ketvoice.app.ui.home.mixSubtitle(card)
    override suspend fun mixTracks(id: String): List<Track>? = withContext(Dispatchers.IO) { runCatching { PersonalMixes.tracks(id) }.getOrNull() }

    override fun searchLibrary(query: String): List<Track> = live(LibrarySearch.tracks(query, downloads(), PlaylistStore.lists.value,
        LocalMusic.tracks.value, ListeningHistory.knownTracks.value, limit = AutoLibrary.MAX_ITEMS))

    override fun searchPlaylists(query: String): List<LocalPlaylist> = LibrarySearch.playlists(query, PlaylistStore.lists.value)

    override suspend fun searchCatalogue(query: String): List<Track> {
        val latest = AtomicReference<List<Track>>(emptyList())
        val searches = mutableListOf<suspend () -> List<Track>>(
            { MusicApi.searchYtMusic(query) }, { PublicationApi.search(query) }, { MusicApi.searchAddons(query) })
        CustomSources.sources.value.filter { it.enabled }.forEach { source -> searches += { CustomSources.search(source, query) } }
        // What arrived in time is kept: a slow source never holds the car's screen.
        withTimeoutOrNull(AutoLibrary.SEARCH_TIMEOUT_MS - 500) {
            withContext(Dispatchers.IO) { SearchGroups.collectProgressively(searches) { latest.set(it) } }
        }
        val found = SearchGroups.group(latest.get().filter(ArtistPrefs::allows)).map { it.primary }
        ArtworkRepo.enrich(found)
        return found
    }

    override fun lookup(trackId: String): Track? {
        val state = Player.state.value
        return live(listOfNotNull(
            state.queue.firstOrNull { it.id == trackId },
            Downloader.itemFor(trackId)?.track,
            ListeningHistory.tracks.value.firstOrNull { it.id == trackId },
            ListeningHistory.knownTracks.value.firstOrNull { it.id == trackId },
            PlaylistStore.lists.value.firstNotNullOfOrNull { list -> list.entries.firstOrNull { it.track?.id == trackId }?.track },
            LocalMusic.tracks.value.firstOrNull { it.id == trackId })).firstOrNull()
    }

    override fun cover(track: Track): String? = ArtworkRepo.coverForTrack(track) ?: track.coverUrl
    override fun text(french: String): String = AppText.t(french)
}

/** Sends the car's requests to the phone's queue, on the main thread where [Player] lives. */
internal class PlayerAutoPlayback(private val context: Context) : AutoPlayback {
    override fun play(tracks: List<Track>, index: Int) {
        Player.connect(context)
        Player.playTrack(tracks[index], tracks, index)
    }

    override fun shuffle(tracks: List<Track>) {
        Player.connect(context)
        Player.playShuffled(tracks)
    }

    override fun enqueue(tracks: List<Track>) {
        Player.connect(context)
        Player.enqueue(tracks)
    }

    override suspend fun resume(): Boolean {
        Player.connect(context)
        // The saved session is read from the disk when the queue first connects.
        withTimeoutOrNull(2_500) { Player.state.first { it.current != null } } ?: return false
        if (!Player.state.value.playWhenReady) Player.toggle()
        return true
    }
}

/** Raised so the session leaves its player alone: the phone's queue took the request. */
private class HandledByQueue : CancellationException("Handled by the Ketvoice queue")

/**
 * The library half of the playback service's session callback. Requests carrying a stream
 * (the app's own controller) go to the player as before; requests carrying only media ids or a
 * voice query are resolved here and handed to the phone's queue.
 */
@UnstableApi
internal open class AutoLibraryCallback(private val context: Context) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal val library: AutoLibrary by lazy {
        val app = context.applicationContext
        AutoLibrary(testSource ?: AppAutoSource(app), testPlayback ?: PlayerAutoPlayback(app), { AutoArtwork.uri(app, it) })
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try { result.set(block()) }
            catch (cancelled: CancellationException) { result.cancel(false) }
            catch (error: Throwable) { result.setException(error) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }

    private fun listResult(params: LibraryParams?, block: suspend () -> List<MediaItem>?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        future {
            try {
                block()?.let { LibraryResult.ofItemList(it, params) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } catch (_: AutoUnavailable) {
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                                  params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        // Playback resumption from the system's recent list is not offered (no media button receiver).
        if (params?.isRecent == true) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
        runCatching { library.prepare() }
        val rootParams = LibraryParams.Builder().setOffline(params?.isOffline == true).setExtras(library.rootExtras()).build()
        return Futures.immediateFuture(LibraryResult.ofItem(library.root(), rootParams))
    }

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                               page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        listResult(params) { library.children(parentId, page, pageSize) }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                           mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
        try {
            library.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } catch (_: AutoUnavailable) { LibraryResult.ofError(SessionError.ERROR_IO) }
    }

    override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                             params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(LibraryResult.ofVoid())

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                          params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            // Always answered, even with nothing: a car waits for this count before it shows the results.
            val count = runCatching { library.searchCount(query) }.getOrDefault(0)
            runCatching { session.notifySearchResultChanged(browser, query, count, params) }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                   page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        listResult(params) { library.searchResult(query, page, pageSize) }

    /**
     * Items without a stream (a car's media ids, a voice query) are resolved by the phone's queue. The app's own
     * items pass as before, and so does a bare address (« play from URI »), which stays unsupported as it was.
     */
    private fun needsQueue(items: List<MediaItem>) = items.isNotEmpty() && items.any { it.localConfiguration == null } &&
        items.none { it.mediaId.isBlank() && it.requestMetadata.searchQuery == null && it.requestMetadata.mediaUri != null }

    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                 mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
        if (!needsQueue(mediaItems)) return super.onAddMediaItems(mediaSession, controller, mediaItems)
        return future {
            report(mediaSession, library.perform(library.enqueueRequest(mediaItems)))
            throw HandledByQueue()
        }
    }

    override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                 mediaItems: MutableList<MediaItem>, startIndex: Int,
                                 startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (!needsQueue(mediaItems)) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        return future {
            report(mediaSession, library.perform(library.request(mediaItems, startIndex)))
            throw HandledByQueue()
        }
    }

    /** « Play » from a car with nothing loaded picks up the phone's last session (the app itself keeps its own behaviour). */
    override fun onPlaybackResumption(mediaSession: MediaSession,
                                      controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (controller.packageName == context.packageName) return super.onPlaybackResumption(mediaSession, controller)
        return future {
            report(mediaSession, library.perform(AutoRequest.Resume))
            throw HandledByQueue()
        }
    }

    private fun report(session: MediaSession, message: String?) {
        if (message != null) runCatching { session.sendError(SessionError(SessionError.ERROR_BAD_VALUE, message)) }
    }

    companion object {
        /** Stores and queue of the Robolectric tests; never set by the app. */
        @androidx.annotation.VisibleForTesting internal var testSource: AutoSource? = null
        @androidx.annotation.VisibleForTesting internal var testPlayback: AutoPlayback? = null
    }
}

/**
 * Builds the playback service's session as a library session: same player, same activity, same
 * callback (which keeps the service's custom commands), plus the browse tree above.
 */
@UnstableApi
internal class AutoLibrarySessionBuilder(private val service: MediaLibraryService, private val player: MediaPlayer) {
    private var activity: PendingIntent? = null
    private var callback: AutoLibraryCallback? = null

    fun setSessionActivity(intent: PendingIntent) = apply { activity = intent }
    fun setCallback(value: AutoLibraryCallback) = apply { callback = value }

    fun build(): MediaLibrarySession {
        val builder = MediaLibrarySession.Builder(service, player, checkNotNull(callback))
        activity?.let(builder::setSessionActivity)
        return builder.build()
    }
}
