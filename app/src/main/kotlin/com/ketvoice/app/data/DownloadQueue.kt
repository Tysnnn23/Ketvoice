package com.ketvoice.app.data

import org.json.JSONArray
import org.json.JSONObject

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
            artistId = text("artistId")
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
