package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The extended manifest's fields (docs/manifest.md, `manifestVersion` 2): optional `lyrics`, `canvas` and `cover`
 * endpoints, read only from the `endpoints` object, from `lyricsUrl`/`canvasUrl`/`coverUrl`, or as default paths when
 * `resources` names them. A top-level `cover` or `canvas` string is never taken as an endpoint (older manifests may use
 * it for the source's own picture), so a manifest of version 1 reads exactly as before.
 */
internal object ManifestExtras {
    data class Fields(val lyrics: String?, val canvas: String?, val cover: String?, val resources: Set<String>, val version: Int)

    private val KNOWN = setOf("search", "stream", "download", "lyrics", "canvas", "cover")

    fun read(root: JSONObject, endpoints: JSONObject?): Fields {
        fun pick(nested: String, vararg flat: String): String? =
            (link(endpoints?.opt(nested)) ?: flat.firstNotNullOfOrNull { link(root.opt(it)) })
                ?.takeIf { it.startsWith("https://") || it.startsWith("/") }
        val resources = root.optJSONArray("resources")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                when (val r = array.opt(i)) {
                    is String -> r
                    is JSONObject -> r.optString("name").ifBlank { r.optString("type") }
                    else -> null
                }?.trim()?.lowercase()?.takeIf { it in KNOWN }
            }.toSet()
        }.orEmpty()
        val lyrics = pick("lyrics", "lyricsUrl", "lyrics_url")
        val canvas = pick("canvas", "canvasUrl", "canvas_url")
        val cover = pick("cover", "coverUrl", "cover_url")
        val declared = root.optInt("manifestVersion", 0)
        val extended = lyrics != null || canvas != null || cover != null || resources.any { it in setOf("lyrics", "canvas", "cover") }
        return Fields(lyrics, canvas, cover, resources, if (declared > 0) declared else if (extended) 2 else 1)
    }

    private fun link(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotBlank() && it != "null" }
        is JSONObject -> listOf("url", "path", "endpoint").firstNotNullOfOrNull { value.optString(it).trim().takeIf(String::isNotBlank) }
        else -> null
    }
}

/**
 * What enabled custom sources serve beyond audio, when their manifest declares it: lyrics (an extra provider of the
 * lyrics engine, [MusicApi.SRC_SOURCES], asked after the built-in ones), Canvas and covers (used only when the usual
 * lookups found nothing). Sources are asked in the listener's order; the first answer wins.
 */
object SourceExtras {
    const val LYRICS_PATH = "/lyrics?title={title}&artist={artist}&album={album}&duration={duration}&isrc={isrc}"
    const val CANVAS_PATH = "/canvas?title={title}&artist={artist}&album={album}&isrc={isrc}"
    const val COVER_PATH = "/cover?title={title}&artist={artist}&album={album}&isrc={isrc}"

    /** A request's answer: its body (at most 2 MB of text) and its content type. Tests replace it. */
    internal var fetch: suspend (String) -> Pair<String, String>? = ::httpGet

    private fun manifests(): List<Pair<CustomSource, SourceManifest>> = CustomSources.enabledSources()
        .mapNotNull { s -> parseManifest(s.manifestUrl, s.manifestJson)?.let { s to it } }

    /** The lyrics engine's extra provider when an enabled source declares lyrics, else none. */
    fun lyricsSources(): List<String> =
        if (manifests().any { it.second.resolvedLyrics != null }) listOf(MusicApi.SRC_SOURCES) else emptyList()

    suspend fun lyrics(track: Track, plain: Boolean): Pair<String, List<MusicApi.LyricLine>> = withContext(Dispatchers.IO) {
        for ((source, manifest) in manifests()) {
            val template = manifest.resolvedLyrics ?: continue
            val raw = fetch(url(template, track)) ?: continue
            val lines = parseLyrics(raw.first)
            if (lines.isNotEmpty()) return@withContext source.name to if (plain) lines.map { MusicApi.LyricLine(-1L, it.text) } else lines
        }
        MusicApi.sourceName(MusicApi.SRC_SOURCES) to emptyList()
    }

    /** A Canvas video address from the first enabled source that has one for [track]. */
    suspend fun canvas(track: Track): String? = firstLink(track, { it.resolvedCanvas }, CANVAS_KEYS, image = false)

    /** A cover address from the first enabled source that has one for [track]. */
    suspend fun cover(track: Track): String? = firstLink(track, { it.resolvedCover }, COVER_KEYS, image = true)

    private suspend fun firstLink(track: Track, endpoint: (SourceManifest) -> String?, keys: List<String>, image: Boolean): String? =
        withContext(Dispatchers.IO) {
            for ((_, manifest) in manifests()) {
                val target = url(endpoint(manifest) ?: continue, track)
                val (body, type) = fetch(target) ?: continue
                if (type.startsWith(if (image) "image/" else "video/")) return@withContext target
                parseLink(body, keys)?.let { return@withContext it }
            }
            null
        }

    private fun url(template: String, track: Track): String = CustomSources.fill(template, query = "", id = track.addonId.orEmpty(),
        quality = "", title = track.title, artist = track.artist.substringBefore(','), album = track.album,
        durationSec = track.durationMs / 1000, isrc = track.isrc.orEmpty())

    internal val CANVAS_KEYS = listOf("canvas", "canvasUrl", "video", "videoUrl", "url", "mp4")
    internal val COVER_KEYS = listOf("cover", "coverUrl", "artwork", "artworkUrl", "image", "url", "picture")

    /** An https address from a plain answer or from the first of [keys] in a JSON one (also under `data`). */
    internal fun parseLink(raw: String, keys: List<String>): String? {
        val text = raw.trim()
        if (text.startsWith("https://") && text.none { it.isWhitespace() }) return text
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return listOfNotNull(json, json.optJSONObject("data")).firstNotNullOfOrNull { o ->
            keys.firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.startsWith("https://") && it.length <= 2000 } }
        }
    }

    /**
     * Lyrics from a source's answer: TTML (word timing), LRC (`syncedLyrics`, `lrc`, `synced`) or plain text
     * (`plainLyrics`, `plain`, `lyrics`, `text`), in a JSON object (also under `data`) or as the raw body.
     */
    internal fun parseLyrics(raw: String): List<MusicApi.LyricLine> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json == null) return fromText(text)
        for (o in listOfNotNull(json, json.optJSONObject("data"))) {
            o.optString("ttml").takeIf { it.isNotBlank() }?.let { ttml -> LyricsFormats.ttml(ttml).takeIf { it.isNotEmpty() }?.let { return it } }
            listOf("syncedLyrics", "lrc", "synced").firstNotNullOfOrNull { o.optString(it).takeIf(String::isNotBlank) }
                ?.let { lrc -> MusicApi.parseLrc(lrc).takeIf { it.isNotEmpty() }?.let { return it } }
            (o.opt("lines") as? JSONArray)?.let { lines -> fromLines(lines).takeIf { it.isNotEmpty() }?.let { return it } }
            listOf("plainLyrics", "plain", "lyrics", "text").firstNotNullOfOrNull { o.optString(it).takeIf(String::isNotBlank) }
                ?.let { return plain(it) }
        }
        return emptyList()
    }

    private fun fromText(text: String): List<MusicApi.LyricLine> = when {
        text.startsWith("<") -> runCatching { LyricsFormats.ttml(text) }.getOrDefault(emptyList())
        text.startsWith("[") -> MusicApi.parseLrc(text).ifEmpty { plain(text) }
        else -> plain(text)
    }

    /** `lines: [{"time": ms, "text": …}]` (`timeMs`, `startTimeMs` also read). */
    private fun fromLines(array: JSONArray): List<MusicApi.LyricLine> = (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val words = o.optString("text").ifBlank { o.optString("words") }
        val at = listOf("time", "timeMs", "startTimeMs").firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.toDoubleOrNull() }
        MusicApi.LyricLine(at?.toLong() ?: -1L, words)
    }.filter { it.text.isNotBlank() || it.timeMs >= 0 }

    private fun plain(text: String) = text.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        .take(400).map { MusicApi.LyricLine(-1L, it) }

    private const val MAX_BODY = 2_000_000

    /** At most [limit] bytes; InputStream.readNBytes needs API 33 and the phone app starts at 31. */
    internal fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private suspend fun httpGet(address: String): Pair<String, String>? {
        if (!address.startsWith("https://")) return null
        return try {
            CancellableConnection.use({ URL(address).openConnection() as HttpURLConnection }) { c ->
                c.connectTimeout = 8_000; c.readTimeout = 12_000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Ketvoice/2.0")
                c.setRequestProperty("Accept", "application/json, text/plain, */*")
                if (c.responseCode !in 200..299) return@use null
                val type = c.contentType.orEmpty().lowercase()
                if (type.startsWith("image/") || type.startsWith("video/")) return@use "" to type
                val bytes = c.inputStream.use { input -> readAtMost(input, MAX_BODY + 1) }
                if (bytes.size > MAX_BODY) null else String(bytes, Charsets.UTF_8) to type
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
}
