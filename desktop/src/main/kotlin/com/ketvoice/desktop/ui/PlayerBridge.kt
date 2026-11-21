package com.ketvoice.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** What the decoder reports about the current title (AudioEngine's `AudioEvent.Format`). */
data class AudioFormatInfo(val codec: String, val sampleRate: Int, val bitDepth: Int, val channels: Int, val bitrate: Int) {
    /** A lossless codec, as the app's Lossless badge grades it; Hi-Res above 48 kHz. */
    val lossless: Boolean get() = codec.lowercase().removePrefix("audio/").let { c -> c in LOSSLESS || c.startsWith("pcm") }
    val hiRes: Boolean get() = lossless && (sampleRate > 48_000 || bitDepth > 16)
    private companion object { val LOSSLESS = setOf("flac", "x-flac", "alac", "wav", "x-wav", "raw", "aiff") }
}

/** The player as the desktop UI drives it: `Player`'s contract names only (see win-common, « Contract »). */
interface PlayerControls {
    val state: StateFlow<PlayerUiState>
    val volume: StateFlow<Float>
    val loading: StateFlow<Boolean>
    val format: StateFlow<AudioFormatInfo?>
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
}

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
    fun createPlaylist(name: String): UserPlaylist
    fun addToPlaylist(playlistId: String, track: Track)
    fun download(track: Track)
}

val LocalPlayer = staticCompositionLocalOf<PlayerControls> { PlayerBridge }
val LocalLibrary = staticCompositionLocalOf<LibraryStore> { LibraryBridge }

/** The real facade. Every call goes to `Player` by its contract name. */
object PlayerBridge : PlayerControls {
    override val state: StateFlow<PlayerUiState> get() = Player.state
    override val volume: StateFlow<Float> get() = Player.volume
    override val loading: StateFlow<Boolean> get() = Player.loading
    // Merge: fed from AudioEngine's AudioEvent.Format once `winaudio` and `winplayer` land.
    override val format: StateFlow<AudioFormatInfo?> = MutableStateFlow(null)
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
}

/*
 * Stubs of the contract members `winplayer` adds to `Player` (playQueue, toggle, next, previous,
 * seekTo, setVolume, volume, enqueue, removeAt, loading). Once its `Player` is merged its members
 * shadow these extensions: delete this block, nothing else changes.
 */
private val stubVolume = MutableStateFlow(1f)
private val stubLoading = MutableStateFlow(false)
val Player.volume: StateFlow<Float> get() = stubVolume
val Player.loading: StateFlow<Boolean> get() = stubLoading
fun Player.playQueue(tracks: List<Track>, index: Int) = setQueue(tracks, index)
fun Player.toggle() {}
fun Player.next() {}
fun Player.previous() {}
fun Player.seekTo(ms: Long) {}
fun Player.setVolume(v: Float) { stubVolume.value = v.coerceIn(0f, 1f) }
fun Player.enqueue(track: Track) { if (state.value.queue.isEmpty()) setQueue(listOf(track)) }
fun Player.removeAt(index: Int) {}

/**
 * The library until `winplayer`'s DesktopLibrary is merged: kept in memory. At the merge each
 * member forwards to DesktopLibrary's flow or function of the same meaning.
 */
object LibraryBridge : MemoryLibrary()

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

    override fun toggleFavourite(track: Track) {
        _favourites.update { list -> if (list.any { it.id == track.id }) list.filterNot { it.id == track.id } else listOf(track) + list }
        if (_favourites.value.any { it.id == track.id }) noteAdded(track)
    }
    override fun createPlaylist(name: String): UserPlaylist =
        UserPlaylist(UUID.randomUUID().toString(), name).also { p -> _playlists.update { it + p } }
    override fun addToPlaylist(playlistId: String, track: Track) {
        _playlists.update { all -> all.map { if (it.id == playlistId && it.tracks.none { t -> t.id == track.id }) it.copy(tracks = it.tracks + track) else it } }
        noteAdded(track)
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
