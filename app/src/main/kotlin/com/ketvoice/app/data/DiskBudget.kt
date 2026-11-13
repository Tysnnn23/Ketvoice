package com.ketvoice.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Upper bounds for the re-fetchable caches that used to grow forever
 * (`lyrics-v2`, `artwork-permanent`, `canvas`). Least recently used files go first;
 * anything a download still references is never deleted, so offline tracks keep
 * their cover and Canvas.
 */
object DiskBudget {
    const val LYRICS_BYTES = 64L * 1024 * 1024
    const val ARTWORK_BYTES = 768L * 1024 * 1024
    const val CANVAS_BYTES = 512L * 1024 * 1024
    private const val TOUCH_AFTER_MS = 24L * 60 * 60 * 1000

    /** Deletes the oldest unprotected files until [dir] fits in 90 % of [maxBytes]. Returns bytes freed. */
    fun trim(dir: File, maxBytes: Long, now: Long = System.currentTimeMillis(), protected: (File) -> Boolean = { false }): Long {
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0L
        var freed = 0L
        // Abandoned writes (older than an hour) are never valid entries; fresh ones may be in flight.
        for (part in files.filter { it.name.endsWith(".part") && now - it.lastModified() > 60L * 60 * 1000 }) {
            val size = part.length()
            if (part.delete()) freed += size
        }
        val live = files.filterNot { it.name.endsWith(".part") }
        var total = live.sumOf { it.length() }
        if (total <= maxBytes) return freed
        val target = maxBytes * 9 / 10
        for (file in live.sortedBy { it.lastModified() }) {
            if (total <= target) break
            if (protected(file)) continue
            val size = file.length()
            if (file.delete()) { total -= size; freed += size }
        }
        return freed
    }

    /** Marks a cache hit, at most once a day so reads do not turn into constant writes. */
    fun touch(file: File, now: Long = System.currentTimeMillis()) {
        if (now - file.lastModified() > TOUCH_AFTER_MS) file.setLastModified(now)
    }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Cover cache keys and Canvas paths that downloaded tracks depend on. */
    internal data class DownloadRefs(val artworkKeys: Set<String>, val canvasPaths: Set<String>)

    internal fun downloadRefs(downloads: File): DownloadRefs {
        val artwork = mutableSetOf<String>()
        val canvas = mutableSetOf<String>()
        downloads.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(".lyrics.json") }?.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                o.optString("coverUrl").takeIf { it.startsWith("https://") }?.let { artwork += sha256(it) }
                o.optString("canvasFile").takeIf { it.isNotBlank() }?.let { canvas += File(it).absolutePath }
                o.optString("canvasSpotifyId").takeIf { it.isNotBlank() }?.let { canvas += "spotify:$it" }
            }
        }
        return DownloadRefs(artwork, canvas)
    }

    /** One pass over all bounded caches; safe to call from any background thread. */
    fun trimAll(context: Context) {
        val files = context.filesDir
        val refs = downloadRefs(File(files, "downloads"))
        trim(File(files, "lyrics-v2"), LYRICS_BYTES)
        trim(File(files, "artwork-permanent"), ARTWORK_BYTES) { it.name in refs.artworkKeys }
        val canvasFreed = trim(File(files, "canvas"), CANVAS_BYTES) {
            it.absolutePath in refs.canvasPaths || "spotify:${it.nameWithoutExtension}" in refs.canvasPaths
        }
        if (canvasFreed > 0) CanvasRepo.dropMissing()
    }
}
