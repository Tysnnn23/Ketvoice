package com.ketvoice.desktop.audio

import java.io.File
import java.security.MessageDigest
import java.util.BitSet

/**
 * The smart cache of streamed audio (Windows 2.0): [HttpRangeSource]'s `.bin`/`.idx` pairs under `Data/cache/audio`,
 * named by title and quality tier when the player knows the title (so a fresh, differently signed address still finds
 * what is on disk), least recently *played* first out once the folder passes [limitBytes] (« Taille du cache »).
 * Each `.idx` holds the byte length, the pieces on disk and the address last used (for an instant start, see
 * [ready]). A title whose bytes are all here plays without the network; one warmed ahead ([CacheWarmer]) starts from
 * its first seconds on disk while a fresh address is asked for behind it.
 */
object StreamCache {
    const val DEFAULT_GB = 2
    val SIZES_GB = listOf(1, 2, 5, 10, 20, 50)

    @Volatile var dir: File? = null
    /** Bytes the folder may hold; the title being played is never evicted. */
    @Volatile var limitBytes: Long = DEFAULT_GB.toLong() shl 30

    fun folder(): File = (dir ?: HttpRangeSource.defaultCacheDir()).apply { mkdirs() }

    /** The cache key of [trackId] played at quality [tier] (the same title in another tier is another file). */
    fun key(trackId: String, tier: String): String = "t:$trackId|$tier"

    fun name(key: String): String = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    /** What one `.idx` says. */
    data class Index(val total: Long, val have: BitSet, val url: String?) {
        val complete: Boolean get() = total >= 0 && have.nextClearBit(0).toLong() * HttpRangeSource.CHUNK >= total
        val bytesOnDisk: Long get() = if (total < 0) 0 else minOf(total, have.cardinality().toLong() * HttpRangeSource.CHUNK)
        /** Bytes from the start that are all here. */
        val head: Long get() = if (total < 0) 0 else minOf(total, have.nextClearBit(0).toLong() * HttpRangeSource.CHUNK)
    }

    fun readIndex(idx: File): Index? = runCatching {
        val lines = idx.readLines()
        val total = lines[0].toLong()
        Index(total, BitSet.valueOf(java.util.Base64.getDecoder().decode(lines[1])), lines.getOrNull(2)?.takeIf { it.startsWith("http") })
    }.getOrNull()

    fun writeIndex(idx: File, index: Index) {
        val url = index.url?.let { "\n$it" }.orEmpty()
        idx.writeText("${index.total}\n" + java.util.Base64.getEncoder().encodeToString(index.have.toByteArray()) + url + "\n")
    }

    /** The cached state of [key], or null when nothing of it is on disk. */
    fun lookup(key: String): Pair<File, Index>? {
        val base = name(key)
        val bin = File(folder(), "$base.bin")
        val index = readIndex(File(folder(), "$base.idx")) ?: return null
        return if (bin.isFile) bin to index else null
    }

    /** Marks [bin] as just played (eviction goes by this time). */
    fun touch(bin: File) { runCatching { bin.setLastModified(System.currentTimeMillis()) } }

    /**
     * Least recently played out first until the folder holds at most [limitBytes]; [keep] (base names) stays. Each file
     * counts by the bytes really on disk (a sparse `.bin` of a partly played title is small).
     */
    fun trim(folder: File = folder(), keep: Set<String> = emptySet(), limit: Long = limitBytes): Long {
        val bins = folder.listFiles { f -> f.name.endsWith(".bin") } ?: return 0
        val sizes = bins.associateWith { onDisk(it) }
        var used = sizes.values.sum()
        var freed = 0L
        for (f in bins.sortedBy { it.lastModified() }) {
            if (used <= limit) break
            if (f.name.removeSuffix(".bin") in keep) continue
            val size = sizes.getValue(f)
            if (f.delete()) { used -= size; freed += size; File(folder, f.name.removeSuffix(".bin") + ".idx").delete() }
        }
        return freed
    }

    private fun onDisk(bin: File): Long =
        readIndex(File(bin.parentFile, bin.name.removeSuffix(".bin") + ".idx"))?.bytesOnDisk?.takeIf { it > 0 } ?: bin.length()

    /** « Cache » in Settings: what is on disk now. */
    data class Stats(val bytes: Long, val titles: Int, val complete: Int, val limit: Long)

    fun stats(folder: File = folder()): Stats {
        val bins = folder.listFiles { f -> f.name.endsWith(".bin") }.orEmpty()
        var complete = 0
        var bytes = 0L
        for (bin in bins) {
            val index = readIndex(File(folder, bin.name.removeSuffix(".bin") + ".idx"))
            if (index?.complete == true) complete++
            bytes += index?.bytesOnDisk?.takeIf { it > 0 } ?: bin.length()
        }
        return Stats(bytes, bins.size, complete, limitBytes)
    }

    /** Empties the folder (files open for playback stay until closed). */
    fun clear(folder: File = folder()) {
        folder.listFiles { f -> f.name.endsWith(".bin") || f.name.endsWith(".idx") }?.forEach { it.delete() }
    }

    /**
     * What [trackId] at [tier] can start from without asking its sources: the whole file (played as a local file), or
     * the start of it and the address last used (refreshed through [refresh] when that address no longer answers).
     * Null when less than [minHead] bytes from its start are here.
     */
    fun ready(trackId: String, tier: String, headers: Map<String, String>, refresh: (() -> String?)?, minHead: Long = MIN_HEAD): AudioSource? {
        val key = key(trackId, tier)
        val (bin, index) = lookup(key) ?: return null
        if (index.complete && bin.length() >= index.total) { touch(bin); return AudioSource.LocalFile(bin.path) }
        val url = index.url ?: return null
        if (index.head < minHead) return null
        return AudioSource.Url(url, headers, cacheKey = key, refresh = refresh)
    }

    /** About 10 s of FLAC or 50 s of MP3: what a title warmed ahead keeps of its start. */
    const val MIN_HEAD = 2L shl 20
}
