package com.ketvoice.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Lyrics the listener brings from a file (5.9.1): chosen once from the lyrics sources of the player,
 * kept inside the app and used for that title from then on, before any online source. The format
 * is told by the content, not the name: LRC (with several times per line, enhanced word tags and
 * A2), TTML, SRT, WebVTT, ASS/SSA (with karaoke \k timing), YRC, JSON (the shapes of the common
 * lyrics services) and plain text.
 */
object CustomLyrics {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val PREFS = "custom-lyrics"
    private var prefs: android.content.SharedPreferences? = null
    private var folder: File? = null

    data class Entry(val file: String, val name: String, val format: String, val active: Boolean)

    @Synchronized fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        folder = File(context.filesDir, "custom-lyrics").apply { mkdirs() }
    }

    @Synchronized fun entry(trackId: String): Entry? = runCatching {
        val o = JSONObject(prefs?.getString(trackId, null) ?: return null)
        Entry(o.getString("file"), o.optString("name"), o.optString("format"), o.optBoolean("active", true))
    }.getOrNull()

    /** The lines of the title's own file, when it has one and it is the one in use. */
    fun lines(trackId: String): Pair<Entry, List<MusicApi.LyricLine>>? {
        val entry = entry(trackId)?.takeIf { it.active } ?: return null
        val file = File(folder ?: return null, entry.file).takeIf { it.isFile } ?: return null
        val parsed = runCatching { parse(decode(file.readBytes())) }.getOrNull() ?: return null
        return entry to parsed.second
    }

    /** Reads [uri], parses it and keeps it for [trackId]. Throws with a message for the listener when the file is unusable. */
    fun choose(context: Context, trackId: String, uri: Uri): Entry {
        init(context)
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "paroles"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            var total = 0
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                total += n; require(total <= MAX_BYTES) { "Fichier trop volumineux (2 Mo maximum)" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("Fichier illisible")
        val (format, lines) = parse(decode(bytes))
        require(lines.isNotEmpty()) { "Aucune parole trouvée dans ce fichier" }
        val stored = "${trackId.hashCode().toUInt().toString(16)}-${System.currentTimeMillis()}.txt"
        synchronized(this) {
            entry(trackId)?.let { File(folder, it.file).delete() }
            File(folder, stored).writeBytes(bytes)
            val entry = Entry(stored, name, format, true)
            prefs?.edit { putString(trackId, JSONObject().put("file", stored).put("name", name).put("format", format).put("active", true).toString()) }
            return entry
        }
    }

    @Synchronized fun setActive(trackId: String, active: Boolean) {
        val e = entry(trackId) ?: return
        prefs?.edit { putString(trackId, JSONObject().put("file", e.file).put("name", e.name).put("format", e.format).put("active", active).toString()) }
    }

    @Synchronized fun forget(trackId: String) {
        entry(trackId)?.let { File(folder ?: return@let, it.file).delete() }
        prefs?.edit { remove(trackId) }
    }

    /** Text from the file's bytes: UTF-16 by its mark, UTF-8 when valid, else Latin-1 (older Windows files). */
    fun decode(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> null
        }
        if (charset != null) return String(bytes, charset).removePrefix("\uFEFF")
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, Charsets.ISO_8859_1) }.removePrefix("\uFEFF")
    }

    /** The format found and the lines read from [text]. */
    fun parse(text: String): Pair<String, List<MusicApi.LyricLine>> {
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        val head = t.trimStart().take(400)
        return when {
            head.startsWith("{") || head.startsWith("[{") || head.startsWith("[\n{") || head.startsWith("[ {") -> "json" to json(t)
            head.contains("<tt") || head.startsWith("<?xml") -> "ttml" to LyricsFormats.ttml(t)
            head.startsWith("WEBVTT") -> "vtt" to cues(t, vtt = true)
            t.contains("[Script Info]") || t.contains("\nDialogue:") || t.startsWith("Dialogue:") -> "ass" to ass(t)
            Regex("^\\[\\d+,\\d+]", RegexOption.MULTILINE).containsMatchIn(t) -> "yrc" to MusicApi.parseYrc(t)
            Regex("^\\[\\d{1,3}:\\d{2}([.:]\\d{1,3})?]", RegexOption.MULTILINE).containsMatchIn(t) -> "lrc" to lrc(t)
            Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->").containsMatchIn(t) -> "srt" to cues(t, vtt = false)
            else -> "txt" to plain(t)
        }.let { (format, lines) -> format to lines.filter { it.text.isNotBlank() } }
    }

    /** LRC, with lines that carry several times ("[00:12.00][01:30.00]chorus") written once per time. */
    internal fun lrc(text: String): List<MusicApi.LyricLine> {
        val stamp = Regex("^((?:\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?])+)(.*)$")
        val expanded = text.lines().flatMap { raw ->
            val m = stamp.find(raw.trim()) ?: return@flatMap listOf(raw)
            Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]").findAll(m.groupValues[1]).map { it.value + m.groupValues[2] }.toList()
        }.joinToString("\n")
        return MusicApi.parseLrc(expanded)
    }

    private fun clock(value: String): Long? {
        val m = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})").find(value.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val ms = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return h * 3_600_000 + m.groupValues[2].toLong() * 60_000 + m.groupValues[3].toLong() * 1_000 + ms
    }

    /** SRT and WebVTT: a time line "start --> end", then the text of the cue. */
    internal fun cues(text: String, vtt: Boolean): List<MusicApi.LyricLine> {
        val out = mutableListOf<MusicApi.LyricLine>()
        val blocks = text.split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val rows = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val timeRow = rows.indexOfFirst { "-->" in it }
            if (timeRow < 0) continue
            val start = clock(rows[timeRow].substringBefore("-->")) ?: continue
            val words = rows.drop(timeRow + 1).joinToString(" ")
                .replace(Regex("<[^>]+>"), "").replace(Regex("\\{[^}]*}"), "").trim()
            if (words.isNotEmpty()) out += MusicApi.LyricLine(start, words)
        }
        return out.sortedBy { it.timeMs }
    }

    /** ASS/SSA "Dialogue:" lines; {\k} karaoke tags give each syllable its time. */
    internal fun ass(text: String): List<MusicApi.LyricLine> {
        val out = mutableListOf<MusicApi.LyricLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (!line.startsWith("Dialogue:")) continue
            val fields = line.removePrefix("Dialogue:").split(',', limit = 10)
            if (fields.size < 10) continue
            val start = clock(fields[1]) ?: continue
            val body = fields[9]
            val karaoke = Regex("\\{\\\\[kK][fo]?(\\d+)}([^{]*)").findAll(body).toList()
            if (karaoke.isNotEmpty()) {
                var at = start
                val words = karaoke.mapNotNull { k ->
                    val length = k.groupValues[1].toLong() * 10
                    val word = k.groupValues[2]
                    val w = if (word.isEmpty()) null else MusicApi.LyricWord(at, word, at + length)
                    at += length; w
                }
                val joined = words.joinToString("") { it.text }.trim()
                if (joined.isNotEmpty()) out += MusicApi.LyricLine(start, joined, words, syllableTimed = true)
            } else {
                val plain = body.replace(Regex("\\{[^}]*}"), "").replace("\\N", " ").replace("\\n", " ").trim()
                if (plain.isNotEmpty()) out += MusicApi.LyricLine(start, plain)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** JSON of the usual shapes: a list of lines with a start and words, Musixmatch richsync, or any nesting of them. */
    internal fun json(text: String): List<MusicApi.LyricLine> {
        val root: Any = runCatching { JSONObject(text) }.getOrNull() ?: runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val array = findLines(root) ?: return emptyList()
        val out = mutableListOf<MusicApi.LyricLine>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val start = timeOf(o, "startTimeMs", "startMs", "start", "time", "ts", "timeMs", "begin") ?: -1L
            val parts = o.optJSONArray("l") ?: o.optJSONArray("syllables")
            if (parts != null && start >= 0) {
                // Musixmatch richsync: characters with offsets in seconds from the line's start.
                val words = (0 until parts.length()).mapNotNull { j ->
                    val p = parts.optJSONObject(j) ?: return@mapNotNull null
                    val c = p.optString("c", p.optString("text")); if (c.isEmpty()) return@mapNotNull null
                    MusicApi.LyricWord(start + (p.optDouble("o", 0.0) * 1000).toLong(), c)
                }
                val joined = words.joinToString("") { it.text }.trim()
                if (joined.isNotEmpty()) { out += MusicApi.LyricLine(start, joined, words); continue }
            }
            val words = listOf("words", "text", "line", "lyrics", "x", "content").firstNotNullOfOrNull { k ->
                o.optString(k).takeIf { it.isNotBlank() && o.opt(k) is String }
            } ?: continue
            out += MusicApi.LyricLine(start, words.trim())
        }
        return if (out.any { it.timeMs >= 0 }) out.sortedBy { it.timeMs } else out
    }

    private fun timeOf(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.opt(k)
            if (v is String && ':' in v) clock(v)?.let { return it }
            val n = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null } ?: continue
            val seconds = k == "ts" || (k in setOf("start", "time", "begin") && n % 1.0 != 0.0 && n < 36_000)
            return if (seconds) (n * 1000).toLong() else n.toLong()
        }
        return null
    }

    private fun findLines(node: Any?): JSONArray? {
        when (node) {
            is JSONArray -> {
                val first = node.optJSONObject(0)
                if (first != null && listOf("words", "text", "line", "x", "lyrics", "l").any { first.has(it) }) return node
                for (i in 0 until node.length()) findLines(node.opt(i))?.let { return it }
            }
            is JSONObject -> {
                for (key in listOf("lines", "lyrics", "richsync", "syncedLyrics", "data")) {
                    val v = node.opt(key)
                    val parsed = if (v is String && v.trimStart().startsWith("[")) runCatching { JSONArray(v) }.getOrNull() else v
                    findLines(parsed)?.let { return it }
                }
                val keys = node.keys()
                while (keys.hasNext()) findLines(node.opt(keys.next()))?.let { return it }
            }
        }
        return null
    }

    /** Plain text: one line per line, without times (shown as the provider's unsynchronised lyrics are). */
    internal fun plain(text: String): List<MusicApi.LyricLine> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { MusicApi.LyricLine(-1L, it) }
}
