package com.ketvoice.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.ketvoice.app.data.DownloadPhase
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.desktop.audio.AudioEvent
import com.ketvoice.desktop.library.DesktopDownloader
import com.ketvoice.desktop.library.DesktopLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.util.UUID

/** Hi-Res, as the app grades it: lossless above 48 kHz or 16 bits. */
val AudioEvent.Format.hiRes: Boolean get() = lossless && (sampleRate > 48_000 || bitDepth > 16)

/** The player as the desktop UI drives it: `Player`'s contract names only (see win-common, « Contract »). */
interface PlayerControls {
    val state: StateFlow<PlayerUiState>
    val volume: StateFlow<Float>
    val loading: StateFlow<Boolean>
    /** The engine's real format of the title heard (the Lossless badge); null until it reports one. */
    val format: StateFlow<AudioEvent.Format?>
    fun playQueue(tracks: List<Track>, index: Int)
    fun toggle()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun setVolume(v: Float)
    fun enqueue(track: Track)
    fun playNext(track: Track)
    fun removeAt(index: Int)
    fun moveQueue(from: Int, to: Int)
    fun setShuffle(on: Boolean)
    fun setRepeat(one: Boolean, all: Boolean)
    /** Playback problems for the listener (« Flux indisponible · réessayez »), in French: the toast translates them. */
    val errors: kotlinx.coroutines.flow.SharedFlow<String> get() = NoErrors
}

private val NoErrors = kotlinx.coroutines.flow.MutableSharedFlow<String>()

data class UserPlaylist(val id: String, val name: String, val tracks: List<Track> = emptyList())

/** A download and its progress (0..1; 1 when the file is complete). */
data class DownloadItem(val track: Track, val progress: Float) { val done: Boolean get() = progress >= 1f }

/** The listener's library as the UI reads it (DesktopLibrary's StateFlows). */
interface LibraryStore {
    val favourites: StateFlow<List<Track>>
    val playlists: StateFlow<List<UserPlaylist>>
    val downloads: StateFlow<List<DownloadItem>>
    val history: StateFlow<List<Track>>
    /** Ids of the library's titles, newest addition first. */
    val added: StateFlow<List<String>>
    fun toggleFavourite(track: Track)
    /** Creates a playlist holding [tracks]; [onCreated] gets its id on the UI thread. */
    fun createPlaylist(name: String, tracks: List<Track> = emptyList(), onCreated: (String) -> Unit = {})
    fun renamePlaylist(playlistId: String, name: String)
    fun deletePlaylist(playlistId: String)
    /** Titles in the music folders of this computer (Settings › Musique locale). */
    val localTracks: StateFlow<List<Track>> get() = NoLocalTracks
    fun addToPlaylist(playlistId: String, track: Track)
    /** Adds [tracks] in their order (an album, a whole selection). */
    fun addAllToPlaylist(playlistId: String, tracks: List<Track>) = tracks.forEach { addToPlaylist(playlistId, it) }
    /** A playlist imported from a link: its titles found in Ketvoice, the others kept as named entries. */
    fun importPlaylist(name: String, entries: List<com.ketvoice.app.data.PlaylistEntry>, cover: String?, onCreated: (String) -> Unit = {}) =
        createPlaylist(name, entries.mapNotNull { it.track }, onCreated)
    fun removeFromPlaylist(playlistId: String, index: Int)
    fun movePlaylistEntry(playlistId: String, from: Int, to: Int)
    fun download(track: Track)
}

private val NoLocalTracks: StateFlow<List<Track>> = MutableStateFlow(emptyList())

val LocalPlayer = staticCompositionLocalOf<PlayerControls> { PlayerBridge }
val LocalLibrary = staticCompositionLocalOf<LibraryStore> { LibraryBridge }

/** The real facade. Every call goes to `Player` by its contract name. */
object PlayerBridge : PlayerControls {
    override val state: StateFlow<PlayerUiState> get() = Player.state
    override val volume: StateFlow<Float> get() = Player.volume
    override val loading: StateFlow<Boolean> get() = Player.loading
    override val format: StateFlow<AudioEvent.Format?> get() = Player.format
    override fun playQueue(tracks: List<Track>, index: Int) = Player.playQueue(tracks, index)
    override fun toggle() = Player.toggle()
    override fun next() = Player.next()
    override fun previous() = Player.previous()
    override fun seekTo(ms: Long) = Player.seekTo(ms)
    override fun setVolume(v: Float) = Player.setVolume(v)
    override fun enqueue(track: Track) = Player.enqueue(track)
    override fun playNext(track: Track) = Player.playNext(track)
    override fun removeAt(index: Int) = Player.removeAt(index)
    override fun moveQueue(from: Int, to: Int) = Player.moveQueue(from, to)
    override fun setShuffle(on: Boolean) = Player.setShuffle(on)
    override fun setRepeat(one: Boolean, all: Boolean) = Player.setRepeat(one, all)
    override val errors: kotlinx.coroutines.flow.SharedFlow<String> get() = Player.error
}

/**
 * The library on disk: [DesktopLibrary] (favourites, the shared playlist store, history) and
 * [DesktopDownloader] (saved files plus the ones in progress). Writes run off the UI thread.
 */
object LibraryBridge : LibraryStore {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val favourites: StateFlow<List<Track>> get() = DesktopLibrary.favorites
    override val playlists: StateFlow<List<UserPlaylist>> by lazy {
        DesktopLibrary.playlists.map { it.map(::toUi) }.stateIn(io, SharingStarted.Eagerly, DesktopLibrary.playlists.value.map(::toUi))
    }
    override val downloads: StateFlow<List<DownloadItem>> by lazy {
        combine(DesktopDownloader.items, DesktopDownloader.tasks, ::downloadsOf)
            .stateIn(io, SharingStarted.Eagerly, downloadsOf(DesktopDownloader.items.value, DesktopDownloader.tasks.value))
    }
    override val history: StateFlow<List<Track>> get() = DesktopLibrary.history
    override val added: StateFlow<List<String>> by lazy {
        combine(DesktopLibrary.favorites, DesktopLibrary.playlists, DesktopDownloader.items, ::addedOf)
            .stateIn(io, SharingStarted.Eagerly, addedOf(DesktopLibrary.favorites.value, DesktopLibrary.playlists.value, DesktopDownloader.items.value))
    }

    override fun toggleFavourite(track: Track) { DesktopLibrary.toggleFavorite(track) }
    override fun createPlaylist(name: String, tracks: List<Track>, onCreated: (String) -> Unit) {
        io.launch {
            val id = DesktopLibrary.createPlaylist(name, tracks)
            launch(Dispatchers.Swing) { onCreated(id) }
        }
    }
    override fun renamePlaylist(playlistId: String, name: String) { io.launch { runCatching { DesktopLibrary.renamePlaylist(playlistId, name) } } }
    override fun deletePlaylist(playlistId: String) { io.launch { runCatching { DesktopLibrary.deletePlaylist(playlistId) } } }
    override val localTracks: StateFlow<List<Track>> get() = com.ketvoice.desktop.library.LocalFolders.tracks
    // Each title once in a playlist, as the shared store's addAll keeps it.
    override fun addToPlaylist(playlistId: String, track: Track) { io.launch { DesktopLibrary.addAllToPlaylist(playlistId, listOf(track)) } }
    override fun addAllToPlaylist(playlistId: String, tracks: List<Track>) { io.launch { DesktopLibrary.addAllToPlaylist(playlistId, tracks) } }
    override fun importPlaylist(name: String, entries: List<com.ketvoice.app.data.PlaylistEntry>, cover: String?, onCreated: (String) -> Unit) {
        io.launch {
            val id = runCatching { com.ketvoice.app.data.PlaylistStore.create(name, entries, null, cover) }.getOrNull() ?: return@launch
            launch(Dispatchers.Swing) { onCreated(id) }
        }
    }
    override fun removeFromPlaylist(playlistId: String, index: Int) {
        val entry = DesktopLibrary.playlists.value.firstOrNull { it.id == playlistId }?.entries?.getOrNull(index) ?: return
        io.launch { DesktopLibrary.removeFromPlaylist(playlistId, entry.id) }
    }
    override fun movePlaylistEntry(playlistId: String, from: Int, to: Int) { io.launch { DesktopLibrary.movePlaylistEntry(playlistId, from, to) } }
    override fun download(track: Track) = DesktopDownloader.download(track)

    /** An imported entry the catalogue has not matched yet still shows by its title and artist. */
    internal fun toUi(p: LocalPlaylist) = UserPlaylist(p.id, p.name, p.entries.map { e ->
        e.track ?: Track("entry:${e.id}", e.title, e.artist, "")
    })

    internal fun downloadsOf(items: List<DesktopDownloader.Item>, tasks: Map<String, com.ketvoice.app.data.DownloadTask>): List<DownloadItem> {
        val saved = items.map { DownloadItem(it.track, 1f) }
        val going = tasks.filterKeys { id -> items.none { it.track.id == id } }
            .filterValues { it.phase != DownloadPhase.Cancelled && it.phase != DownloadPhase.Complete }
            .mapNotNull { (id, task) ->
                val track = DesktopDownloader.trackOf(id) ?: return@mapNotNull null
                val total = task.total?.takeIf { it > 0 }
                DownloadItem(track, if (total == null) 0f else (task.bytes.toFloat() / total).coerceIn(0f, .99f))
            }
        return going + saved
    }

    private fun addedOf(favourites: List<Track>, playlists: List<LocalPlaylist>, items: List<DesktopDownloader.Item>): List<String> =
        (items.sortedByDescending { it.downloadedAt }.map { it.track.id } + favourites.map { it.id } +
            playlists.flatMap { p -> p.entries.asReversed().mapNotNull { it.track?.id } }).distinct()
}

/** The library in memory, for tests and previews. */
open class MemoryLibrary : LibraryStore {
    private val _favourites = MutableStateFlow<List<Track>>(emptyList())
    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val _history = MutableStateFlow<List<Track>>(emptyList())
    private val _added = MutableStateFlow<List<String>>(emptyList())
    override val favourites = _favourites.asStateFlow()
    override val playlists = _playlists.asStateFlow()
    override val downloads = _downloads.asStateFlow()
    override val history = _history.asStateFlow()
    override val added = _added.asStateFlow()

    private fun noteAdded(track: Track) = _added.update { listOf(track.id) + it.filterNot { id -> id == track.id } }
    private fun edit(id: String, block: (UserPlaylist) -> UserPlaylist) = _playlists.update { all -> all.map { if (it.id == id) block(it) else it } }

    override fun toggleFavourite(track: Track) {
        _favourites.update { list -> if (list.any { it.id == track.id }) list.filterNot { it.id == track.id } else listOf(track) + list }
        if (_favourites.value.any { it.id == track.id }) noteAdded(track)
    }
    override fun createPlaylist(name: String, tracks: List<Track>, onCreated: (String) -> Unit) {
        val p = UserPlaylist(UUID.randomUUID().toString(), name, tracks.distinctBy { it.id })
        _playlists.update { it + p }
        tracks.forEach(::noteAdded)
        onCreated(p.id)
    }
    override fun renamePlaylist(playlistId: String, name: String) { if (name.isNotBlank()) edit(playlistId) { it.copy(name = name.trim()) } }
    override fun deletePlaylist(playlistId: String) = _playlists.update { all -> all.filterNot { it.id == playlistId } }
    override fun addToPlaylist(playlistId: String, track: Track) {
        edit(playlistId) { if (it.tracks.none { t -> t.id == track.id }) it.copy(tracks = it.tracks + track) else it }
        noteAdded(track)
    }
    override fun removeFromPlaylist(playlistId: String, index: Int) =
        edit(playlistId) { p -> p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index }) }
    override fun movePlaylistEntry(playlistId: String, from: Int, to: Int) = edit(playlistId) { p ->
        if (from !in p.tracks.indices || to !in p.tracks.indices) p
        else p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) })
    }
    override fun download(track: Track) {
        if (_downloads.value.none { it.track.id == track.id }) _downloads.update { it + DownloadItem(track, 0f) }
        noteAdded(track)
    }
    fun seed(favourites: List<Track> = emptyList(), playlists: List<UserPlaylist> = emptyList(),
             downloads: List<DownloadItem> = emptyList(), history: List<Track> = emptyList()) {
        _favourites.value = favourites; _playlists.value = playlists; _downloads.value = downloads; _history.value = history
        _added.value = (favourites + playlists.flatMap { it.tracks } + downloads.map { it.track }).map { it.id }.distinct()
    }
}
