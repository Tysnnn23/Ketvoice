package com.ketvoice.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object ExtraLyricsProviders {
    val status = ConcurrentHashMap<String, String>()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(source: String, url: String): JSONObject? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 25000
                setRequestProperty("User-Agent", "Ketvoice/2.0")
                if (source == MusicApi.SRC_BETTER && AppSettings.betterLyricsKey.value.isNotBlank()) {
                    setRequestProperty("X-API-Key", AppSettings.betterLyricsKey.value.trim())
                }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                status[source] = when (code) {
                    401, 403 -> "Clé ou accès requis"
                    404 -> "Aucune parole trouvée"
                    429 -> "Quota temporairement atteint"
                    else -> "Service indisponible ($code)"
                }
                null
            } else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            status[source] = "Service injoignable"
            null
        } finally { c?.disconnect() }
    }

    fun fetch(source: String, track: Track, plain: Boolean): Pair<String, List<MusicApi.LyricLine>> {
        status.remove(source)
        var label = MusicApi.sourceName(source)
        val cleanTitle = track.title.replace(Regex("(?i)\\s*[\\[(](official.*|audio.*|video.*|lyrics?.*)[\\])]"), "").trim()
        val title = enc(cleanTitle)
        val artist = enc(track.artist.substringBefore(','))
        val lines = when (source) {
            MusicApi.SRC_LRCRED -> {
                val isrc = track.isrc ?: resolveIsrc(track)
                if (isrc == null) {
                    status[source] = "Enregistrement ISRC non trouvé"
                    emptyList()
                } else {
                    var c: HttpURLConnection? = null
                    try {
                        c = URL("https://lrc.red/s/$isrc.ttml").openConnection() as HttpURLConnection
                        c.connectTimeout = 6000; c.readTimeout = 15000
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                        if (c.responseCode in 200..299) LyricsFormats.ttml(c.inputStream.bufferedReader().use { it.readText() })
                        else { status[source] = "Aucune parole accessible pour cet ISRC"; emptyList() }
                    } catch (_: Exception) { status[source] = "Service temporairement injoignable"; emptyList() }
                    finally { c?.disconnect() }
                }
            }
            MusicApi.SRC_BETTER -> {
                val query = "s=$title&a=$artist" +
                    if (track.durationMs > 0) "&d=${track.durationMs / 1000}" else ""
                val json = get(source, "https://api.betterlyrics.org/getLyrics?$query")
                LyricsFormats.ttml(json?.optString("ttml").orEmpty())
            }
            MusicApi.SRC_LIRIQO -> {
                val duration = if (track.durationMs > 0) "&duration=${track.durationMs / 1000}" else ""
                val json = get(source, "https://api.liriqo-alfarrizi.my.id/v1/lyrics?title=$title&artist=$artist$duration")
                val root = json?.optJSONObject("data") ?: json
                val tracks = root?.optJSONArray("tracks") ?: JSONArray()
                val candidates = (0 until tracks.length()).mapNotNull { i ->
                    val t = tracks.optJSONObject(i) ?: return@mapNotNull null
                    val provider = t.optString("provider")

                    if (provider.contains("wordsync", true) || t.optBoolean("estimated", false)) return@mapNotNull null
                    val native = provider in setOf("golyrics_apple_music", "amlyrics_apple_music",
                        "binimum_apple_music", "spicylyrics", "spicylyrics_apple_music", "qq_portato", "kugou_legato")
                    val xml = t.optString("ttml").takeIf { it.startsWith("<") }
                    val parsed = xml?.let { LyricsFormats.ttml(it) }?.takeIf { it.isNotEmpty() }
                        ?: timed(t.optJSONArray("timed"), native, t.optString("syncLevel") == "syllable")
                            .ifEmpty { plain(t.optString("plain")) }
                    if (parsed.isEmpty()) null else Triple(provider, parsed, rank(parsed))
                }
                val best = candidates.maxByOrNull { it.third }
                if (best != null) label = "LiriQo · ${best.first.replace('_', ' ')}"
                best?.second ?: emptyList()
            }
            MusicApi.SRC_LYRICA -> {
                val base = AppSettings.lyricaUrl.value.trim().trimEnd('/')
                if (!base.startsWith("https://")) {
                    status[source] = "URL HTTPS d'une instance Lyrica requise"
                    emptyList()
                } else {
                    val json = get(source, "$base/lyrics/?artist=$artist&song=$title&timestamps=true&word=true&syllabus=true")
                    val data = json?.optJSONObject("data") ?: json
                    val provider = data?.optString("source").orEmpty()

                    timed(data?.optJSONArray("timed_lyrics"), provider in setOf("apple_music", "applemusic", "lrcmux"),
                        data?.optString("sync_level") == "syllable").ifEmpty { plain(data?.optString("lyrics").orEmpty()) }
                }
            }
            else -> emptyList()
        }
        if (lines.isEmpty()) status.putIfAbsent(source, "Aucune parole trouvée")
        else status.remove(source)
        return label to if (plain) lines.map { MusicApi.LyricLine(-1L, it.text) } else lines
    }

    fun rank(lines: List<MusicApi.LyricLine>): Int = when {
        lines.any { it.syllableTimed && it.words.isNotEmpty() } -> 3
        lines.any { it.words.size >= 2 } -> 2
        lines.any { it.timeMs >= 0 } -> 1
        else -> 0
    }

    private fun resolveIsrc(track: Track): String? {
        if (track.addon == "um" && track.addonId?.all { it.isDigit() } == true) {
            get(MusicApi.SRC_LRCRED, "https://api.deezer.com/track/${track.addonId}")
                ?.optString("isrc")?.takeIf { it.matches(Regex("[A-Za-z]{2}[A-Za-z0-9]{3}[0-9]{7}")) }?.let { return it }
        }
        val q = enc("${track.title} ${track.artist.substringBefore(',')}")
        val items = get(MusicApi.SRC_LRCRED, "https://api.deezer.com/search?q=$q&limit=8")?.optJSONArray("data") ?: return null
        fun normalize(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
        val title = normalize(track.title)
        val artist = normalize(track.artist.substringBefore(','))
        val best = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.filter {
            val name = normalize(it.optString("title_short", it.optString("title")))
            val singer = normalize(it.optJSONObject("artist")?.optString("name").orEmpty())
            (name == title || name.contains(title) || title.contains(name)) && artist.isNotBlank() &&
                (singer.contains(artist) || artist.contains(singer)) &&
                (track.durationMs <= 0 || kotlin.math.abs(it.optLong("duration") * 1000 - track.durationMs) < 15_000)
        }.minByOrNull { kotlin.math.abs(it.optLong("duration") * 1000 - track.durationMs) } ?: return null
        return get(MusicApi.SRC_LRCRED, "https://api.deezer.com/track/${best.optString("id")}")
            ?.optString("isrc")?.takeIf { it.matches(Regex("[A-Za-z]{2}[A-Za-z0-9]{3}[0-9]{7}")) }
    }

    private fun plain(text: String) = text.takeUnless { it == "null" }.orEmpty().lines()
        .filter { it.isNotBlank() }.map { MusicApi.LyricLine(-1, it) }

    private fun timed(array: JSONArray?, native: Boolean, syllable: Boolean): List<MusicApi.LyricLine> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val line = array.optJSONObject(i) ?: return@mapNotNull null
            val text = line.optString("text")
            if (text.isBlank() || line.optBoolean("isInstrumental")) return@mapNotNull null
            val start = line.optLong("start", line.optLong("start_time", -1))
            val words = mutableListOf<MusicApi.LyricWord>()
            if (native) {
                val raw = line.optJSONArray("words") ?: JSONArray()
                for (j in 0 until raw.length()) {
                    val w = raw.optJSONObject(j) ?: continue
                    val syllables = w.optJSONArray("syllables")
                    val parts = syllables?.takeIf { it.length() > 0 } ?: JSONArray().put(w)
                    for (k in 0 until parts.length()) {
                        val part = parts.optJSONObject(k) ?: continue
                        val at = part.optLong("start", -1)
                        if (at >= 0) words += MusicApi.LyricWord(at, part.optString("text"), part.optLong("end", -1))
                    }
                }
            }
            val aligned = LyricsFormats.align(text, words)
            MusicApi.LyricLine(start, text, aligned, syllable && aligned.isNotEmpty())
        }.sortedBy { it.timeMs }
    }
}
