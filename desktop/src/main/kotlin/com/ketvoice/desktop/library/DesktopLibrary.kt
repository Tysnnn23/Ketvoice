package com.ketvoice.desktop.library

import android.content.Context
import android.util.AtomicFile
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.DownloadQueue
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The desktop library: favourites (the heart), playlists, downloads, history and recently played, as
 * StateFlows, kept in `%LOCALAPPDATA%\Ketvoice\Data`. Playlists are the shared [PlaylistStore]
 * (`files/playlists-v1.json`, the Android file); history is the shared [ListeningHistory]; favourites
 * are ids in [AppSettings.favoriteIds] as on Android, with each title kept in the account sync's
 * track shape ([DownloadQueue.trackToJson]). The account sync (shared `FavoritesKind`) replaces
 * [AppSettings.favoriteIds]; the list follows it ([adoptIds]), and ids whose title this computer does
 * not know yet stay kept (`hidden`) so a sync never drops them.
 */
object DesktopLibrary {
    private const val FAVORITES_FILE = "favorites-v1.json"
    private const val RECENT_MAX = 30

    private val _favorites = MutableStateFlow<List<Track>>(emptyList())
    /** Favourite titles, the latest hearted first. */
    val favorites: StateFlow<List<Track>> = _favorites
    val playlists: StateFlow<List<LocalPlaylist>> get() = PlaylistStore.lists
    val downloads: StateFlow<List<DesktopDownloader.Item>> get() = DesktopDownloader.items
    /** Titles heard, the latest first (the shared history also keeps plays, minutes and days). */
    val history: StateFlow<List<Track>> get() = ListeningHistory.tracks
    private var _recent: StateFlow<List<Track>> = MutableStateFlow(emptyList())
    /** One title per album (or single) heard lately, the latest first. */
    val recentlyPlayed: StateFlow<List<Track>> get() = _recent

    private var favoritesFile: AtomicFile? = null
    private val favoritesAddedAt = HashMap<String, Long>()
    /** Favourites another device sent without a title this computer knows: kept, not shown. */
    private val hidden = LinkedHashSet<String>()

    @Synchronized
    fun init(context: Context = Context.app, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
             downloads: File = File(context.dataDir, "downloads")) {
        ListeningHistory.init(context)
        PlaylistStore.init(context)
        val file = AtomicFile(File(context.filesDir, FAVORITES_FILE))
        favoritesFile = file
        loadFavorites(file)
        _recent = ListeningHistory.tracks.map(::recentOf).stateIn(scope, SharingStarted.Eagerly, recentOf(ListeningHistory.tracks.value))
        DesktopDownloader.init(downloads, scope)
        scope.launch { snapshotFlow { AppSettings.favoriteIds.value }.collect(::adoptIds) }
    }

    /** The account sync set [ids]: new ones (known through the shared title lookup) come first, removed ones go. */
    @Synchronized
    internal fun adoptIds(@Suppress("UNUSED_PARAMETER") seen: Set<String>) {
        // The flow can hand over a value older than one written since: always adopt what is there now.
        val ids = AppSettings.favoriteIds.value
        val now = _favorites.value
        if (now.mapTo(LinkedHashSet()) { it.id } + hidden == ids) return
        val byId = now.associateBy { it.id }
        val known by lazy { com.ketvoice.app.sync.SyncedTracks.lookup() }
        val added = ids.filter { it !in byId }.mapNotNull { id -> known[id]?.takeIf { it.title.isNotBlank() }?.let(::clean) }
        val next = added + now.filter { it.id in ids }
        hidden.clear(); hidden += ids - next.mapTo(HashSet()) { it.id }
        _favorites.value = next
        saveFavorites(next)
    }

    fun isFavorite(id: String): Boolean = id in AppSettings.favoriteIds.value

    @Synchronized
    fun setFavorite(track: Track, on: Boolean) {
        val now = _favorites.value
        val next = if (on) listOf(clean(track)) + now.filterNot { it.id == track.id } else now.filterNot { it.id == track.id }
        if (on) favoritesAddedAt[track.id] = System.currentTimeMillis() else favoritesAddedAt.remove(track.id)
        // The account sync sends a favourite with its title, looked up among the titles this computer knows.
        if (on) runCatching { ListeningHistory.rememberTrack(clean(track)) }
        hidden -= track.id
        _favorites.value = next
        AppSettings.favoriteIds.value = next.mapTo(LinkedHashSet()) { it.id } + hidden
        saveFavorites(next)
    }

    fun toggleFavorite(track: Track) = setFavorite(track, !isFavorite(track.id))

    // Playlists: the shared store's rules (names, limits, each title once on addAll, safe tracks).
    suspend fun createPlaylist(name: String, tracks: List<Track> = emptyList()): String =
        PlaylistStore.create(name).also { id -> if (tracks.isNotEmpty()) PlaylistStore.addAll(id, tracks) }
    suspend fun renamePlaylist(id: String, name: String) = PlaylistStore.rename(id, name)
    suspend fun deletePlaylist(id: String) = PlaylistStore.delete(id)
    suspend fun addToPlaylist(id: String, track: Track) = PlaylistStore.add(id, track)
    suspend fun addAllToPlaylist(id: String, tracks: List<Track>) = PlaylistStore.addAll(id, tracks)
    suspend fun removeFromPlaylist(id: String, entryId: String) = PlaylistStore.remove(id, entryId)
    suspend fun movePlaylistEntry(id: String, from: Int, to: Int) = PlaylistStore.move(id, from, to)

    internal fun recentOf(tracks: List<Track>): List<Track> = tracks
        .distinctBy { t -> t.album.takeIf { it.isNotBlank() }?.let { "a:" + MediaOverrides.normalize(it) + "|" + MediaOverrides.normalize(MediaOverrides.mainArtist(t.artist)) } ?: "t:" + t.id }
        .take(RECENT_MAX)

    /** As the account keeps a title: never a stream address or a downloaded file. */
    private fun clean(track: Track): Track = track.copy(streamUrl = null, downloadUrl = null, canvasUrl = null,
        coverUrl = track.coverUrl?.takeIf { it.startsWith("https://") })

    private fun loadFavorites(file: AtomicFile) {
        val rows = runCatching { JSONObject(String(file.readFully(), Charsets.UTF_8)).optJSONArray("entries") }.getOrNull() ?: JSONArray()
        favoritesAddedAt.clear()
        val tracks = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { row ->
            DownloadQueue.trackFromJson(row.optJSONObject("track"))?.also { favoritesAddedAt[it.id] = row.optLong("addedAt") }
        }.distinctBy { it.id }
        hidden.clear()
        runCatching { JSONObject(String(file.readFully(), Charsets.UTF_8)).optJSONArray("hidden") }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() && tracks.none { t -> t.id == it } }?.let(hidden::add)
        }
        _favorites.value = tracks
        AppSettings.favoriteIds.value = tracks.mapTo(LinkedHashSet()) { it.id } + hidden
    }

    private fun saveFavorites(tracks: List<Track>) {
        val file = favoritesFile ?: return
        val json = JSONObject().put("version", 1).put("entries", JSONArray(tracks.map {
            JSONObject().put("track", DownloadQueue.trackToJson(it)).put("addedAt", favoritesAddedAt[it.id] ?: 0L)
        })).put("hidden", JSONArray(hidden.toList()))
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }

    /** Tests: forget what is in memory (files stay). */
    internal fun resetForTest() {
        favoritesFile = null; favoritesAddedAt.clear(); hidden.clear(); _favorites.value = emptyList()
        AppSettings.favoriteIds.value = emptySet()
        PlaylistStore.resetForTest()
    }
}
