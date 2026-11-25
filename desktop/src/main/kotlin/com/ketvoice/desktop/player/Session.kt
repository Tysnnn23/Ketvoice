package com.ketvoice.desktop.player

import android.content.Context
import android.util.AtomicFile
import com.ketvoice.app.data.DownloadQueue
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Near-instant start (Windows 2.0): the window opens on what the listener left — the page, the queue and the title
 * where it stood (paused), its cover read from disk — before the catalogue, the account and Ketmix have started.
 * Kept in `files/session-v1.json` (the queue: at most [MAX_QUEUE] titles around the current one, never a stream
 * address) and `cache/session-cover` (the current title's cover as it was shown).
 */
object Session {
    const val MAX_QUEUE = 500
    private const val FILE = "session-v1.json"

    data class Saved(val tracks: List<Track>, val index: Int, val positionMs: Long, val page: String?)

    @Volatile var page: String? = null
    private var file: AtomicFile? = null
    private var coverFile: File? = null
    @Volatile var restored: Saved? = null; private set

    private fun files(context: Context) {
        if (file == null) file = AtomicFile(File(context.filesDir, FILE))
        if (coverFile == null) coverFile = File(context.cacheDir, "session-cover")
    }

    /** What was saved last time (fast: one small file), or null. */
    fun load(context: Context = Context.app): Saved? {
        files(context)
        val saved = runCatching { decode(String(file!!.readFully(), Charsets.UTF_8)) }.getOrNull()
        restored = saved
        page = saved?.page
        return saved
    }

    /** The saved cover's bytes, for the window's first frame. */
    fun coverBytes(): ByteArray? = coverFile?.takeIf { it.isFile && it.length() in 1..(8L shl 20) }?.readBytes()

    /** Puts the saved queue back in the player, paused where it stood. */
    fun restore(saved: Saved) {
        if (saved.tracks.isEmpty()) return
        Player.resumeFrom(saved.tracks, saved.index, saved.positionMs, play = false)
    }

    /** Saves the player's queue and position (2 s after they settle) and the page, from now on. */
    @OptIn(FlowPreview::class)
    fun follow(scope: CoroutineScope, coverOf: suspend (Track) -> ByteArray?) {
        scope.launch(Dispatchers.IO) {
            Player.state.map { Triple(it.queueEntryIds, it.index, it.positionMs / 15_000) }.distinctUntilChanged().debounce(2_000)
                .collect { runCatching { save() } }
        }
        scope.launch(Dispatchers.IO) {
            Player.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { track ->
                val bytes = track?.let { runCatching { coverOf(it) }.getOrNull() } ?: return@collect
                runCatching { coverFile?.writeBytes(bytes) }
            }
        }
    }

    /** Now (at quit, and from [follow]). */
    @Synchronized fun save() {
        val f = file ?: return
        val s = Player.state.value
        val text = encode(Saved(s.queue, s.index, s.positionMs, page))
        runCatching {
            val out = f.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
        }
    }

    /** The saved form: titles without stream addresses or downloaded files, at most [MAX_QUEUE] around the current one. */
    fun encode(saved: Saved): String {
        val from = (saved.index - MAX_QUEUE / 2).coerceIn(0, maxOf(0, saved.tracks.size - MAX_QUEUE))
        val kept = saved.tracks.drop(from).take(MAX_QUEUE).map { clean(it) }
        return JSONObject().put("version", 1)
            .put("index", if (saved.index < 0) -1 else saved.index - from)
            .put("positionMs", saved.positionMs.coerceAtLeast(0))
            .put("page", saved.page ?: JSONObject.NULL)
            .put("queue", JSONArray(kept.map(DownloadQueue::trackToJson))).toString()
    }

    fun decode(text: String): Saved? = runCatching {
        val json = JSONObject(text)
        require(json.optInt("version") == 1)
        val rows = json.getJSONArray("queue")
        val tracks = (0 until minOf(rows.length(), MAX_QUEUE)).mapNotNull { rows.optJSONObject(it)?.let(DownloadQueue::trackFromJson) }
        val index = json.optInt("index", -1).let { if (tracks.isEmpty()) -1 else it.coerceIn(0, tracks.lastIndex) }
        Saved(tracks, index, json.optLong("positionMs").coerceAtLeast(0), json.optString("page").takeIf { !json.isNull("page") && it.isNotBlank() })
    }.getOrNull()

    private fun clean(t: Track): Track = t.copy(streamUrl = t.streamUrl?.takeIf { it.startsWith("file:") }, downloadUrl = null)
}
