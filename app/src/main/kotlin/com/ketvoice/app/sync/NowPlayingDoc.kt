package com.ketvoice.app.sync

import com.ketvoice.app.data.Track
import com.ketvoice.app.player.PlayerUiState
import org.json.JSONArray
import org.json.JSONObject

/*
 * The account's « now playing » document (`ketvoice_library`, kind `now_playing`), in its own file so the phone and
 * Ketvoice for Windows share it (moved out of NowPlayingSync.kt, unchanged).
 */

/** What another of the listener's devices plays (or played last), offered here: « Reprendre ici ». */
data class RemoteSession(val deviceId: String, val deviceName: String, val queue: List<Track>, val index: Int,
                         val positionMs: Long, val playing: Boolean, val at: Long) {
    val track: Track get() = queue[index]

    /** Where the title is now: a title still playing there has moved on since it was written. */
    fun positionAt(now: Long): Long {
        val moved = if (playing) (now - at).coerceIn(0, 6 * 3600_000L) else 0L
        val end = track.durationMs.takeIf { it > 0 }?.minus(1_000) ?: Long.MAX_VALUE
        return (positionMs + moved).coerceIn(0, end.coerceAtLeast(0))
    }

    /** Still playing there: written by a device that was playing, less than a minute ago. */
    fun live(now: Long): Boolean = playing && now - at < 60_000L
}

internal object NowPlayingDoc {
    const val QUEUE_LIMIT = 50

    fun encode(state: PlayerUiState, deviceId: String, deviceName: String, at: Long): JSONObject? {
        val current = state.current ?: return null
        if (current.id.startsWith("local:")) return null
        val (queue, index) = window(state.queue, state.index)
        return JSONObject().put("device_id", deviceId).put("device_name", deviceName).put("playing", state.isPlaying)
            .put("position_ms", state.positionMs.coerceAtLeast(0)).put("at", at).put("index", index)
            .put("queue", JSONArray(queue.map(SyncTracks::json)))
    }

    /** The titles around the one playing (this device's own files left out), and where it now stands among them. */
    fun window(queue: List<Track>, index: Int): Pair<List<Track>, Int> {
        val playable = queue.withIndex().filter { !it.value.id.startsWith("local:") }
        val at = playable.indexOfFirst { it.index == index }.coerceAtLeast(0)
        val from = minOf(at - 5, playable.size - QUEUE_LIMIT).coerceAtLeast(0)
        val slice = playable.subList(from, minOf(playable.size, from + QUEUE_LIMIT)).map { it.value }
        return slice to (at - from)
    }

    fun decode(doc: JSONObject?): RemoteSession? = runCatching {
        doc ?: return null
        val rows = doc.getJSONArray("queue")
        val queue = (0 until minOf(rows.length(), QUEUE_LIMIT)).mapNotNull { SyncTracks.parse(rows.getJSONObject(it).toString()) }
        val index = doc.getInt("index")
        if (index !in queue.indices) return null
        RemoteSession(doc.getString("device_id"), doc.optString("device_name").take(40).ifBlank { "Appareil" }, queue, index,
            doc.optLong("position_ms").coerceAtLeast(0), doc.optBoolean("playing"), doc.optLong("at"))
    }.getOrNull()
}
