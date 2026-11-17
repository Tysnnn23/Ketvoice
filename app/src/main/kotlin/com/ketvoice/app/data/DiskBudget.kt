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
        for (file in leastRecentlyUsed(live, maxBytes, { it.length() }, { it.lastModified() }, protected)) {
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    /**
     * The items to delete so that the total fits in 90 % of [maxBytes]: the least recently used first, never a
     * protected one. Nothing while the total is within [maxBytes].
     */
    fun <T> leastRecentlyUsed(items: List<T>, maxBytes: Long, size: (T) -> Long, modified: (T) -> Long,
                              protected: (T) -> Boolean): List<T> {
        var total = items.sumOf(size)
        if (total <= maxBytes) return emptyList()
        val target = maxBytes * 9 / 10
        val out = ArrayList<T>()
        for (item in items.sortedBy(modified)) {
            if (total <= target) break
            if (protected(item)) continue
            total -= size(item); out += item
        }
        return out
    }

    /** Marks a cache hit, at most once a day so reads do not turn into constant writes. */
    fun touch(file: File, now: Long = System.currentTimeMillis()) {
        if (now - file.lastModified() > TOUCH_AFTER_MS) file.setLastModified(now)
    }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Cover cache keys and Canvas paths that downloaded tracks depend on. */
    internal data class DownloadRefs(val artworkKeys: Set<String>, val canvasPaths: Set<String>,
                                     /** (album, artist) of downloaded titles that took their album's Canvas (no file of their own). */
                                     val albums: Set<Pair<String, String>> = emptySet())

    internal fun downloadRefs(downloads: File): DownloadRefs {
        val artwork = mutableSetOf<String>()
        val canvas = mutableSetOf<String>()
        val albums = mutableSetOf<Pair<String, String>>()
        downloads.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(".lyrics.json") }?.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                o.optString("coverUrl").takeIf { it.startsWith("https://") }?.let { artwork += sha256(it) }
                o.optString("canvasFile").takeIf { it.isNotBlank() }?.let { canvas += File(it).absolutePath }
                o.optString("canvasSpotifyId").takeIf { it.isNotBlank() }?.let { canvas += "spotify:$it" }
                // A title downloaded without a Canvas of its own shows its album's (6.2.2): that file is needed too.
                if (o.optString("canvasFile").isBlank() && o.optString("canvasSpotifyId").isBlank() && o.optString("album").isNotBlank())
                    albums += o.optString("album") to o.optString("artist")
            }
        }
        return DownloadRefs(artwork, canvas, albums)
    }

    /** One pass over all bounded caches; safe to call from any background thread. */
    fun trimAll(context: Context) {
        val files = context.filesDir
        val refs = downloadRefs(File(files, "downloads"))
        trim(File(files, "lyrics-v2"), LYRICS_BYTES)
        trim(File(files, "artwork-permanent"), ARTWORK_BYTES) { it.name in refs.artworkKeys }
        val canvasKept = canvasKept(refs) { album, artist -> CanvasRepo.albumFile(album, artist) }
        val canvasFreed = trim(File(files, "canvas"), CanvasCachePlan.limitBytes(AppSettings.canvasCacheMb.value), protected = canvasKept)
        if (canvasFreed > 0) CanvasRepo.dropMissing()
    }

    /** The Canvas files downloaded titles depend on: their own, and their album's. */
    internal fun canvasKept(refs: DownloadRefs, albumFile: (String, String) -> File?): (File) -> Boolean {
        val albumPaths = refs.albums.mapNotNull { (album, artist) -> albumFile(album, artist)?.absolutePath }.toSet()
        return { it.absolutePath in refs.canvasPaths || "spotify:${it.nameWithoutExtension}" in refs.canvasPaths ||
            it.absolutePath in albumPaths }
    }
}
