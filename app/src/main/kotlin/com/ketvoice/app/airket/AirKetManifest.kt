package com.ketvoice.app.airket

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** One file of a transfer. The id is stable between sessions, which is what makes a transfer resumable. */
internal data class AirKetEntry(val kind: Kind, val name: String, val size: Long) {
    enum class Kind(val code: String, val optional: Boolean) {
        Audio("audio", true), Meta("meta", true), Lyrics("lyrics", true), Lrc("lrc", true), Cover("cover", true),
        Canvas("canvas", true), Stem("stem", true), CustomLyrics("custom-lyrics", false);
        companion object { fun of(code: String) = entries.firstOrNull { it.code == code } }
    }

    val id: String get() = "${kind.code}/$name"

    companion object {
        /** File names are plain: the receiver builds its own paths from them, so nothing else is accepted. */
        private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        fun validName(name: String) = safeName.matches(name) && ".." !in name
        const val MAX_FILE = 8L * 1024 * 1024 * 1024
        const val MAX_ENTRIES = 60_000
    }
}

/** What the sender can give: the list of files, the custom lyrics index and what it had to leave out. */
internal data class AirKetManifest(val entries: List<AirKetEntry>, val customLyrics: Map<String, String> = emptyMap(),
                                   val leftOutDash: Int = 0) {
    val downloadBytes: Long get() = entries.filter { it.kind.optional }.sumOf { it.size }
    val downloadFiles: Int get() = entries.count { it.kind.optional }
    val titles: Int get() = entries.count { it.kind == AirKetEntry.Kind.Audio }

    fun encode(): ByteArray {
        val rows = JSONArray().apply { entries.forEach { put(JSONObject().put("k", it.kind.code).put("n", it.name).put("s", it.size)) } }
        return JSONObject().put("version", 1).put("files", rows).put("customLyrics", JSONObject(customLyrics))
            .put("leftOutDash", leftOutDash).toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun decode(bytes: ByteArray): AirKetManifest {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            require(root.getInt("version") == 1)
            val rows = root.getJSONArray("files")
            require(rows.length() <= AirKetEntry.MAX_ENTRIES)
            val entries = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                val kind = requireNotNull(AirKetEntry.Kind.of(row.getString("k")))
                val name = row.getString("n").also { require(AirKetEntry.validName(it)) }
                AirKetEntry(kind, name, row.getLong("s").also { require(it in 0..AirKetEntry.MAX_FILE) })
            }
            require(entries.map { it.id }.distinct().size == entries.size)
            val custom = root.optJSONObject("customLyrics") ?: JSONObject()
            require(custom.length() <= 5000)
            val index = custom.keys().asSequence().associateWith { key -> custom.getString(key).also { require(it.length <= 2000) } }
            return AirKetManifest(entries, index, root.optInt("leftOutDash").coerceAtLeast(0))
        }
    }
}

/**
 * The receiver's staging folder: a file being received is `<digest of its id>.part`, a verified one `.ok`.
 * Both stay when a transfer stops, so the next one goes on where it was; they are installed together at the end.
 */
internal class AirKetStaging(val dir: File) {
    init { dir.mkdirs() }

    private fun stem(id: String) = Hkdf.hex(Hkdf.sha256(id.toByteArray(Charsets.UTF_8))).take(32)
    fun part(entry: AirKetEntry) = File(dir, stem(entry.id) + ".part")
    fun verified(entry: AirKetEntry) = File(dir, stem(entry.id) + ".ok")

    /** What the next request for this file asks for: from the end of the part, or the whole size to re-check a verified file. */
    fun resumeFrom(entry: AirKetEntry): Long {
        val ok = verified(entry)
        if (ok.isFile && ok.length() == entry.size) return entry.size
        val part = part(entry)
        return if (part.isFile && part.length() in 0 until entry.size) part.length() else 0L
    }

    fun markVerified(entry: AirKetEntry) {
        val part = part(entry)
        val ok = verified(entry)
        ok.delete()
        check(part.renameTo(ok) || ok.isFile)
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    /** Bytes already held for [entries], for the progress bar of a resumed transfer. */
    fun held(entries: List<AirKetEntry>): Long = entries.sumOf { e ->
        val ok = verified(e)
        if (ok.isFile && ok.length() == e.size) e.size else part(e).takeIf { it.isFile }?.length()?.coerceAtMost(e.size) ?: 0L
    }

    companion object {
        /** Staging left by a transfer nobody resumed is dropped after a week. */
        const val KEEP_MS = 7L * 24 * 3600 * 1000
    }
}

internal object AirKetDigest {
    fun of(input: InputStream, limit: Long = Long.MAX_VALUE): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var left = limit
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            digest.update(buffer, 0, n); left -= n
        }
        return Hkdf.hex(digest.digest())
    }

    fun of(file: File): String = file.inputStream().buffered().use { of(it) }
}
