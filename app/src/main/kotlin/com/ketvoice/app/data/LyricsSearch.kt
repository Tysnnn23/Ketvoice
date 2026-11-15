package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Finding a title by a few of its words. On the device first: the lyrics already loaded for the
 * titles played or downloaded here are kept in a small index (nothing leaves the phone). For a
 * sentence of three words or more, Genius is asked too: it knows the lyrics of titles never played.
 */
object LyricsSearch {
    data class Indexed(val track: Track, val text: String)
    /** A title found online by its lyrics: what to search the catalogue for. */
    data class Found(val title: String, val artist: String, val cover: String?) { val query: String get() = "$title $artist" }

    private const val LIMIT = 1_500
    private const val MAX_TEXT = 6_000
    private var file: File? = null
    private val entries = LinkedHashMap<String, Indexed>()

    @Synchronized fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "lyrics-index.json")
        runCatching {
            val array = JSONArray(file!!.takeIf { it.isFile }?.readText() ?: return)
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val track = Track(o.getString("id"), o.getString("title"), o.getString("artist"), o.optString("album"),
                    durationMs = o.optLong("ms"), coverUrl = o.optString("cover").takeIf { it.isNotBlank() })
                entries[track.id] = Indexed(track, o.getString("text"))
            }
        }
    }

    /** Words compared without accents, case or punctuation. */
    internal fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Lyrics loaded for [track]: kept in the index, newest first. */
    fun remember(track: Track, lines: List<MusicApi.LyricLine>) {
        if (track.id.isBlank() || lines.isEmpty()) return
        val text = normalize(lines.joinToString(" ") { it.text }).take(MAX_TEXT)
        if (text.length < 20) return
        synchronized(this) {
            entries.remove(track.id)
            entries[track.id] = Indexed(track, text)
            while (entries.size > LIMIT) entries.remove(entries.keys.first())
        }
        save()
    }

    private fun save() {
        val (target, json) = synchronized(this) {
            (file ?: return) to JSONArray().apply {
                entries.values.forEach { e ->
                    put(JSONObject().put("id", e.track.id).put("title", e.track.title).put("artist", e.track.artist)
                        .put("album", e.track.album).put("ms", e.track.durationMs).put("cover", e.track.coverUrl ?: "").put("text", e.text))
                }
            }.toString()
        }
        runCatching { File(target.path + ".part").apply { writeText(json); renameTo(target) } }
    }

    /** Titles on the device whose lyrics hold [query]: the exact words in a row first, then all of its words. */
    fun local(query: String, limit: Int = 8): List<Track> {
        val q = normalize(query)
        if (q.length < 4) return emptyList()
        val words = q.split(' ').filter { it.length >= 2 }
        val all = synchronized(this) { entries.values.toList() }.asReversed()
        return rank(all, q, words).take(limit).map { it.track }
    }

    internal fun rank(all: List<Indexed>, q: String, words: List<String>): List<Indexed> {
        val phrase = all.filter { " ${it.text} ".contains(" $q ") || it.text.contains(q) }
        val loose = if (words.size < 2) emptyList() else all.filter { e -> e !in phrase && words.all { w -> e.text.contains(w) } }
        return phrase + loose
    }

    /** Worth asking online: a sentence of three words or more. */
    fun looksLikeLyrics(query: String): Boolean = normalize(query).split(' ').count { it.length >= 2 } >= 3

    /** Titles Genius finds by their lyrics (the words of [query] are sent to Genius). */
    suspend fun online(query: String, limit: Int = 5): List<Found> = withContext(Dispatchers.IO) {
        val url = URL("https://genius.com/api/search/lyric?per_page=$limit&q=" + URLEncoder.encode(query.trim().take(200), "UTF-8"))
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 7_000; setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Ketvoice")
        }
        try {
            if (c.responseCode !in 200..299) return@withContext emptyList()
            parse(c.inputStream.bufferedReader().use { it.readText() }).take(limit)
        } catch (_: Exception) { emptyList() } finally { c.disconnect() }
    }

    internal fun parse(json: String): List<Found> = runCatching {
        val sections = JSONObject(json).getJSONObject("response").getJSONArray("sections")
        val out = ArrayList<Found>()
        for (s in 0 until sections.length()) {
            val hits = sections.getJSONObject(s).optJSONArray("hits") ?: continue
            for (h in 0 until hits.length()) {
                val r = hits.getJSONObject(h).optJSONObject("result") ?: continue
                val title = r.optString("title").trim(); val artist = r.optJSONObject("primary_artist")?.optString("name")?.trim().orEmpty()
                if (title.isNotEmpty() && artist.isNotEmpty() && out.none { it.title.equals(title, true) && it.artist.equals(artist, true) })
                    out += Found(title, artist, r.optString("song_art_image_thumbnail_url").takeIf { it.startsWith("https://") })
            }
        }
        out
    }.getOrDefault(emptyList())
}
