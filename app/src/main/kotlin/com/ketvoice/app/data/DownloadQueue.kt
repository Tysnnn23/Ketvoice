package com.ketvoice.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where a queued download stands between two app launches.
 *
 * [Pending] restarts by itself, [Paused] waits for the user, [Failed] keeps the
 * row visible so it can be retried instead of vanishing silently.
 */
enum class QueueState {
    Pending,
    Paused,
    Failed;

    companion object {
        fun parse(raw: String?): QueueState =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Pending
    }
}

/** A user download that survives process death. */
data class QueuedDownload(
    val track: Track,
    val state: QueueState = QueueState.Pending,
    val addedAt: Long = 0L
)

/**
 * Pure (de)serialisation of the download queue.
 *
 * The queue used to live only in memory (`ConcurrentHashMap.newKeySet`), so a
 * process kill silently dropped every pending download. This object owns the
 * on-disk shape; [Downloader] owns the file and the coroutines, which keeps
 * every rule below testable on the host JVM.
 */
object DownloadQueue {
    private const val VERSION = 1

    fun encode(entries: List<QueuedDownload>): String =
        JSONObject()
            .put("version", VERSION)
            .put("entries", JSONArray().apply { entries.forEach { put(row(it)) } })
            .toString()

    /**
     * Never throws: a corrupt or foreign file yields what can still be read.
     * Rows without an id or a title, and duplicated ids, are dropped.
     */
    fun decode(raw: String?): List<QueuedDownload> {
        if (raw.isNullOrBlank()) return emptyList()
        val rows = runCatching { JSONObject(raw).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        val seen = LinkedHashMap<String, QueuedDownload>()
        for (index in 0 until rows.length()) {
            val entry = runCatching { parseRow(rows.optJSONObject(index)) }.getOrNull() ?: continue
            val previous = seen[entry.track.id]
            if (previous == null || entry.addedAt >= previous.addedAt) seen[entry.track.id] = entry
        }
        return seen.values.toList()
    }

    /** Adds a download, or refreshes the state of the one already queued. */
    fun withEntry(
        entries: List<QueuedDownload>,
        track: Track,
        state: QueueState = QueueState.Pending,
        now: Long = System.currentTimeMillis()
    ): List<QueuedDownload> {
        val existing = entries.firstOrNull { it.track.id == track.id }
        val entry = QueuedDownload(track, state, existing?.addedAt ?: now)
        return entries.filterNot { it.track.id == track.id } + entry
    }

    fun withState(entries: List<QueuedDownload>, id: String, state: QueueState): List<QueuedDownload> =
        entries.map { if (it.track.id == id) it.copy(state = state) else it }

    fun remove(entries: List<QueuedDownload>, id: String): List<QueuedDownload> =
        entries.filterNot { it.track.id == id }

    /** Drops entries that are already on disk, so a restore never re-downloads. */
    fun pending(entries: List<QueuedDownload>, downloadedIds: Set<String>): List<QueuedDownload> =
        entries.filterNot { it.track.id in downloadedIds }

    fun find(entries: List<QueuedDownload>, id: String): QueuedDownload? =
        entries.firstOrNull { it.track.id == id }

    /** The phase the UI shows for a restored entry. */
    fun phaseFor(state: QueueState): DownloadPhase = when (state) {
        QueueState.Pending -> DownloadPhase.Resolving
        QueueState.Paused -> DownloadPhase.Paused
        QueueState.Failed -> DownloadPhase.Failed
    }

    /** Only pending entries restart by themselves; the rest wait for the user. */
    fun startsImmediately(state: QueueState): Boolean = state == QueueState.Pending

    /** Paused downloads still own a slot in the busy set so the controls show. */
    fun countsAsBusy(state: QueueState): Boolean = state != QueueState.Failed

    internal fun trackToJson(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("title", track.title)
        put("artist", track.artist)
        put("album", track.album)
        put("durationMs", track.durationMs)
        put("lyricsProvider", track.lyricsProvider)
        put("explicit", track.explicit)
        put("youtubeCatalogVerified", track.youtubeCatalogVerified)
        track.streamUrl?.let { put("streamUrl", it) }
        track.downloadUrl?.let { put("downloadUrl", it) }
        track.canvasUrl?.let { put("canvasUrl", it) }
        track.coverUrl?.let { put("coverUrl", it) }
        track.addon?.let { put("addon", it) }
        track.addonId?.let { put("addonId", it) }
        track.videoId?.let { put("videoId", it) }
        track.spotifyId?.let { put("spotifyId", it) }
        track.isrc?.let { put("isrc", it) }
        track.albumId?.let { put("albumId", it) }
        track.artistId?.let { put("artistId", it) }
        track.sourceOverride?.let { put("sourceOverride", it) }
    }

    internal fun trackFromJson(raw: JSONObject?): Track? {
        if (raw == null) return null
        val id = raw.optString("id").trim()
        val title = raw.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        fun text(key: String): String? = raw.optString(key).trim().takeIf { it.isNotEmpty() }
        return Track(
            id = id,
            title = title,
            artist = raw.optString("artist"),
            album = raw.optString("album"),
            durationMs = raw.optLong("durationMs"),
            streamUrl = text("streamUrl"),
            downloadUrl = text("downloadUrl"),
            canvasUrl = text("canvasUrl"),
            lyricsProvider = raw.optString("lyricsProvider").ifBlank { "LRCLIB" },
            explicit = raw.optBoolean("explicit"),
            coverUrl = text("coverUrl"),
            addon = text("addon"),
            addonId = text("addonId"),
            videoId = text("videoId"),
            spotifyId = text("spotifyId"),
            isrc = text("isrc"),
            youtubeCatalogVerified = raw.optBoolean("youtubeCatalogVerified"),
            albumId = text("albumId"),
            artistId = text("artistId"),
            sourceOverride = text("sourceOverride")
        )
    }

    private fun row(entry: QueuedDownload): JSONObject = JSONObject().apply {
        put("track", trackToJson(entry.track))
        put("state", entry.state.name)
        put("addedAt", entry.addedAt)
    }

    private fun parseRow(raw: JSONObject?): QueuedDownload? {
        val row = raw ?: return null
        val track = trackFromJson(row.optJSONObject("track")) ?: return null
        return QueuedDownload(track, QueueState.parse(row.optString("state")), row.optLong("addedAt"))
    }
}

/**
 * Durable home of the download queue.
 *
 * Owns the in-memory list and its file, so `Downloader` only decides *what*
 * changes instead of repeating the read/prune/write dance for every transition.
 * The physical write is injected: the app passes `AtomicFile` (fsync + rename),
 * host tests pass a counter or a throwing writer.
 */
class DownloadQueueStore(
    private val file: File,
    private val writer: (File, ByteArray) -> Unit = ::replaceWithRename
) {

    @Volatile
    var entries: List<QueuedDownload> = emptyList()
        private set

    /**
     * Reads the queue and drops entries whose track is already on disk
     * (finished while the app was not running). The file is rewritten when the
     * prune changed it, so the stale rows cannot come back on the next launch.
     *
     * A missing, unreadable or corrupt file yields an empty queue instead of
     * failing the launch.
     */
    fun load(downloadedIds: Set<String> = emptySet()): List<QueuedDownload> {
        val raw = runCatching { file.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()
        val restored = DownloadQueue.decode(raw)
        val surviving = DownloadQueue.pending(restored, downloadedIds)
        synchronized(this) {
            entries = surviving
            if (surviving != restored) persist(surviving)
        }
        return surviving
    }

    /** Applies a transition and persists it; a no-op change writes nothing. */
    fun mutate(change: (List<QueuedDownload>) -> List<QueuedDownload>): List<QueuedDownload> =
        synchronized(this) {
            val next = change(entries)
            if (next == entries) entries else {
                entries = next
                persist(next)
                next
            }
        }

    private fun persist(list: List<QueuedDownload>) {
        // A failed write must never propagate: the queue is a convenience, the
        // running download is not.
        runCatching { writer(file, DownloadQueue.encode(list).toByteArray(Charsets.UTF_8)) }
    }

    private companion object {
        /** Plain-JVM fallback used by host tests: temp file + rename. */
        fun replaceWithRename(file: File, bytes: ByteArray) {
            val temp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            temp.writeBytes(bytes)
            if (file.exists() && !file.delete()) throw java.io.IOException("cannot replace $file")
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }
}
