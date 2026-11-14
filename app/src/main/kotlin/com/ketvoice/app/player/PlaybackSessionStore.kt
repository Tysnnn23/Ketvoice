package com.ketvoice.app.player

import android.content.Context
import android.util.AtomicFile
import com.ketvoice.app.data.DownloadQueue
import com.ketvoice.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class PlaybackSnapshot(
    val queue: List<Track>, val entryIds: List<String>, val index: Int, val positionMs: Long,
    val shuffle: Boolean, val repeatOne: Boolean, val autoplay: Boolean,
    val repeatAll: Boolean = false, val linearOrder: List<String> = emptyList()
) {
    fun toPlayerState(): PlayerUiState = PlayerUiState(queue = queue, queueEntryIds = entryIds, index = index,
        positionMs = positionMs, durationMs = queue[index].durationMs, shuffle = shuffle,
        repeatOne = repeatOne, autoplay = autoplay, repeatAll = repeatAll, linearOrder = linearOrder)
}

internal object PlaybackSessionCodec {
    private const val MAX_QUEUE = 5000
    const val MAX_BYTES = 16 * 1024 * 1024

    private fun safeIdentity(value: String?): String? = value?.takeIf {
        it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) && "://" !in it && '?' !in it && '#' !in it
    }

    private fun safeTrack(track: Track): Track {
        require(safeIdentity(track.id) != null && track.title.isNotBlank() && track.title.length <= 1000 &&
            track.artist.length <= 1000 && track.album.length <= 1000)
        val localUri = track.streamUrl?.takeIf { track.id.startsWith("local:") &&
            (it.startsWith("content://") || it.startsWith("file://")) && '?' !in it && '#' !in it }
        return track.copy(streamUrl = localUri, downloadUrl = null, canvasUrl = null,
            coverUrl = track.coverUrl?.takeUnless { '?' in it || '#' in it },
            addon = safeIdentity(track.addon), addonId = safeIdentity(track.addonId),
            videoId = safeIdentity(track.videoId), spotifyId = safeIdentity(track.spotifyId),
            isrc = safeIdentity(track.isrc), albumId = safeIdentity(track.albumId),
            artistId = safeIdentity(track.artistId), sourceOverride = safeIdentity(track.sourceOverride),
            lyricsProvider = safeIdentity(track.lyricsProvider) ?: "LRCLIB")
    }

    fun encode(state: PlayerUiState, positionMs: Long): ByteArray {
        require(state.queue.size <= MAX_QUEUE)
        val rows = JSONArray()
        state.queue.forEachIndexed { index, track ->
            // Local content URIs are needed for offline playback; remote stream URLs expire.
            rows.put(JSONObject().put("track", DownloadQueue.trackToJson(safeTrack(track)))
                .put("entryId", state.queueEntryIds.getOrNull(index).orEmpty()))
        }
        val bytes = JSONObject().put("version", 1).put("queue", rows).put("index", state.index)
            .put("positionMs", positionMs.coerceAtLeast(0)).put("shuffle", state.shuffle)
            .put("repeatOne", state.repeatOne).put("autoplay", state.autoplay)
            .put("repeatAll", state.repeatAll && !state.repeatOne)
            .apply { if (state.shuffle && state.linearOrder.isNotEmpty()) put("linearOrder", JSONArray(state.linearOrder)) }
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        return bytes
    }

    fun decode(bytes: ByteArray): PlaybackSnapshot? {
        require(bytes.size <= MAX_BYTES)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optInt("version", -1) == 1)
        val rows = root.getJSONArray("queue")
        require(rows.length() <= MAX_QUEUE)
        if (rows.length() == 0) return null
        val tracks = ArrayList<Track>(rows.length())
        val ids = ArrayList<String>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val track = requireNotNull(DownloadQueue.trackFromJson(row.optJSONObject("track")))
            tracks += safeTrack(track)
            ids += row.optString("entryId")
        }
        val index = root.getInt("index")
        require(index in tracks.indices)
        val entryIds = if (ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) ids
            else QueueEntryIds.reconcile(emptyList(), emptyList(), tracks.map { it.id })
        val shuffle = root.optBoolean("shuffle")
        val repeatOne = root.optBoolean("repeatOne")
        // The remembered order is only trusted when it names exactly the saved entries.
        val linear = root.optJSONArray("linearOrder")?.let { rows -> List(rows.length()) { rows.optString(it) } }
            ?.takeIf { shuffle && entryIds === ids && it.size == ids.size && it.toSet() == ids.toSet() }.orEmpty()
        return PlaybackSnapshot(tracks, entryIds, index, root.optLong("positionMs").coerceAtLeast(0),
            shuffle, repeatOne, root.optBoolean("autoplay", true),
            repeatAll = root.optBoolean("repeatAll") && !repeatOne, linearOrder = linear)
    }
}

/** A damaged snapshot is left untouched rather than replaced by an empty session. */
internal class PlaybackSessionStore(context: Context,
                                    private val file: AtomicFile = AtomicFile(File(context.filesDir, "playback-session-v1.json"))) {
    private val mutex = Mutex()
    private var writable = true

    suspend fun load(): PlaybackSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
            try {
                require(file.baseFile.length() <= PlaybackSessionCodec.MAX_BYTES &&
                    File(file.baseFile.path + ".bak").length() <= PlaybackSessionCodec.MAX_BYTES)
                PlaybackSessionCodec.decode(file.readFully())
            }
            catch (_: Exception) { writable = false; null }
        }
    }

    suspend fun save(state: PlayerUiState, positionMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!writable) return@withLock
            val bytes = runCatching { PlaybackSessionCodec.encode(state, positionMs) }.getOrNull() ?: return@withLock
            var output: java.io.FileOutputStream? = null
            try {
                output = file.startWrite()
                output.write(bytes)
                file.finishWrite(output)
                output = null
                check(file.readFully().contentEquals(bytes))
            } catch (_: Exception) {
                if (output != null) file.failWrite(output)
            }
        }
    }
}
