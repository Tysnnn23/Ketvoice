package com.ketvoice.app.sync

import android.content.Context
import android.util.AtomicFile
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/**
 * Titles made favourites on another device: this one may never have played them, so what shows
 * them (title, artist, cover) is kept here for the Favourites page. Only favourites live here, and
 * the file goes with the account when the listener signs out.
 */
object SyncedTracks {
    private const val LIMIT = 5000
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks
    private var file: AtomicFile? = null

    private fun storage(context: Context): AtomicFile =
        file ?: AtomicFile(File(File(context.filesDir, "account").apply { mkdirs() }, "favorite-tracks.json")).also { file = it }

    fun init(context: Context) {
        val stored = storage(context)
        if (!stored.baseFile.exists()) return
        _tracks.value = runCatching {
            val array = JSONArray(String(stored.readFully(), Charsets.UTF_8))
            (0 until minOf(array.length(), LIMIT)).mapNotNull { SyncTracks.parse(array.getJSONObject(it).toString()) }
        }.getOrDefault(emptyList())
    }

    /** Every title this device knows, by id: history, downloads, the queue, then what the account brought. */
    internal fun lookup(): Map<String, Track> {
        val out = LinkedHashMap<String, Track>()
        fun add(tracks: List<Track>) = tracks.forEach { out.putIfAbsent(it.id, it) }
        add(com.ketvoice.app.data.ListeningHistory.knownTracks.value)
        add(com.ketvoice.app.data.ListeningHistory.tracks.value)
        add(com.ketvoice.app.data.Downloader.items.value.map { it.track })
        add(com.ketvoice.app.player.Player.state.value.queue)
        add(_tracks.value)
        return out
    }

    internal fun remember(context: Context, tracks: List<Track>) {
        val next = tracks.distinctBy { it.id }.take(LIMIT)
        if (next == _tracks.value) return
        val stored = storage(context)
        val bytes = JSONArray(next.map(SyncTracks::json)).toString().toByteArray(Charsets.UTF_8)
        val out = stored.startWrite()
        try { out.write(bytes); stored.finishWrite(out) } catch (error: Exception) { stored.failWrite(out); throw error }
        _tracks.value = next
    }

    internal fun forget(context: Context) {
        storage(context).delete()
        _tracks.value = emptyList()
    }
}
