package com.ketvoice.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * « Musique locale pro » (7.0): lyrics for the listener's own files. A `.lrc` beside the file (same name, in the
 * chosen folder) comes first; otherwise the app's lyrics providers ([MusicApi.bestLyrics], with the corrected
 * title and artist), fetched ahead on request and kept in the lyrics cache so they also play offline.
 */
internal object LocalLyrics {
    private val sidecars = ConcurrentHashMap<String, Uri>()
    private var app: Context? = null
    data class Progress(val done: Int, val total: Int, val found: Int)
    private val _fetching = MutableStateFlow<Progress?>(null)
    val fetching: StateFlow<Progress?> = _fetching
    private val _synced = MutableStateFlow<Set<String>>(emptySet())
    /** Local ids with synced lyrics ready on the device. */
    val synced: StateFlow<Set<String>> = _synced

    fun init(context: Context) { app = context.applicationContext; LyricsDiskCache.init(context.applicationContext) }

    fun noteSidecar(trackId: String, uri: Uri) { sidecars[trackId] = uri }
    fun clearSidecars() = sidecars.clear()

    private fun key(id: String) = "local-lyrics:$id"

    /** What LyricsRepo shows for a local title before asking the providers: its `.lrc`, or what was fetched ahead. */
    fun lines(trackId: String): Pair<String, List<MusicApi.LyricLine>>? {
        if (!trackId.startsWith("local:")) return null
        sidecar(trackId)?.let { return it }
        return LyricsDiskCache.read(key(trackId))?.takeIf { it.second.isNotEmpty() }
    }

    /** Lyrics found for a local title some other way (Windows 2.0: written in the file's tags), kept as if fetched ahead. */
    fun keep(trackId: String, result: Pair<String, List<MusicApi.LyricLine>>) {
        if (trackId.startsWith("local:") && result.second.isNotEmpty()) LyricsDiskCache.write(key(trackId), result)
    }

    private fun sidecar(trackId: String): Pair<String, List<MusicApi.LyricLine>>? {
        val uri = sidecars[trackId] ?: return null
        val context = app ?: return null
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        if (bytes.size > 512 * 1024) return null
        val parsed = runCatching { CustomLyrics.parse(CustomLyrics.decode(bytes)) }.getOrNull() ?: return null
        return ("Fichier .lrc" to parsed.second).takeIf { parsed.second.isNotEmpty() }
    }

    /** Fetches lyrics for local titles that have none yet; [fetch] is the app's providers (injectable for tests). */
    suspend fun fetchAll(tracks: List<Track>, fetch: suspend (Track) -> Pair<String, List<MusicApi.LyricLine>>? = ::providers,
                         pauseMs: Long = 300L): Int {
        if (_fetching.value != null) return 0
        val todo = tracks.filter { it.id.startsWith("local:") && lines(it.id) == null }.take(200)
        var found = 0
        _fetching.value = Progress(0, todo.size, 0)
        try {
            todo.forEachIndexed { i, track ->
                val result = runCatching { fetch(track) }.getOrNull()
                if (result != null && result.second.isNotEmpty()) {
                    LyricsDiskCache.write(key(track.id), result)
                    if (result.second.any { it.timeMs >= 0 }) { found++; _synced.value = _synced.value + track.id }
                }
                _fetching.value = Progress(i + 1, todo.size, found)
                if (pauseMs > 0) delay(pauseMs)
            }
        } finally { _fetching.value = null }
        return found
    }

    /** Synced lyrics already on the device for these titles (after a restart). */
    fun refreshSynced(tracks: List<Track>) {
        _synced.value = tracks.filter { t -> lines(t.id)?.second?.any { it.timeMs >= 0 } == true }.mapTo(HashSet()) { it.id }
    }

    private suspend fun providers(track: Track): Pair<String, List<MusicApi.LyricLine>>? =
        MusicApi.bestLyrics(track)?.let { it.first to it.second }
}
