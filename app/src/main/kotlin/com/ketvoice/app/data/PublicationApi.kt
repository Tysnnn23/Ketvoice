package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

object PublicationApi {
    private const val ROOT = "https://xodklrunqlzyeiqtovgx.supabase.co"
    private const val BASE = "$ROOT/functions/v1/ketvoice-content"
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun connection(url: String, method: String, admin: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 12_000; readTimeout = 90_000
            instanceFollowRedirects = false
            setRequestProperty("apikey", KetvoiceApi.ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${KetvoiceApi.ANON_KEY}")
            setRequestProperty("Accept", "application/json")
            admin?.let { setRequestProperty("x-ketvoice-admin", it) }
        }

    private fun response(c: HttpURLConnection): JSONObject {
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (status !in 200..299) error(json.optString("error_description").ifBlank {
            json.optString("msg").ifBlank { json.optString("error").ifBlank { "Service de publication indisponible" } }
        }.take(250))
        return json
    }

    internal fun auth(path: String, body: JSONObject, token: String? = null): JSONObject {
        val c = connection("$ROOT/auth/v1$path", "POST")
        return try {
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            response(c)
        } finally { c.disconnect() }
    }

    internal fun adminSession(token: String): Boolean {
        val c = connection("$BASE/admin/session", "GET", token)
        return try { response(c).optBoolean("allowed") } finally { c.disconnect() }
    }

    private fun get(path: String): JSONObject? {
        val c = connection(BASE + path, "GET")
        c.readTimeout = 12_000
        return try { response(c) } catch (_: Exception) { null } finally { c.disconnect() }
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val rows = get("/search?q=${enc(query)}")?.optJSONArray("tracks") ?: JSONArray()
        (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let(::track) }
    }

    private fun track(item: JSONObject): Track? {
        val id = item.optString("id")
        val stream = item.optString("streamURL")
        if (!id.startsWith("kv:upload:") || !stream.startsWith("$ROOT/storage/v1/object/public/ketvoice-public-media/")) return null
        return Track(id, item.optString("title"), item.optString("artist"), item.optString("album"),
            durationMs = item.optLong("durationMs"), streamUrl = stream, downloadUrl = stream,
            coverUrl = item.optString("artworkURL").takeIf { it.startsWith("https://") },
            addon = "upload", addonId = item.optString("addonId"), lyricsProvider = "Ketvoice API")
    }

    suspend fun stream(wanted: Track): MusicApi.StreamInfo? = withContext(Dispatchers.IO) {
        val song = wanted.takeIf { it.streamUrl?.startsWith("$ROOT/storage/v1/object/public/ketvoice-public-media/") == true }
            ?: get("/track?id=${enc(wanted.addonId ?: wanted.id.removePrefix("kv:upload:"))}")?.optJSONObject("track")?.let(::track)
        val url = song?.streamUrl ?: return@withContext null
        MusicApi.StreamInfo(url, url.substringBefore('?').substringAfterLast('.'), "Ketvoice API", wanted.id, url)
    }

    suspend fun lyrics(track: Track): List<MusicApi.LyricLine> = withContext(Dispatchers.IO) {
        val query = "/lyrics?trackId=${enc(track.id)}&title=${enc(track.title)}&artist=${enc(track.artist)}" +
            "&durationMs=${track.durationMs}" + (track.isrc?.let { "&isrc=${enc(it)}" } ?: "")
        val versions = get(query)?.optJSONArray("versions") ?: return@withContext emptyList()
        (0 until versions.length()).mapNotNull { index ->
            val version = versions.optJSONObject(index) ?: return@mapNotNull null
            val content = version.optString("content")
            if (content.length > 1_048_576) return@mapNotNull null
            when (version.optString("format")) {
                "ttml" -> LyricsFormats.ttml(content)
                "lrc" -> MusicApi.parseLrc(content)
                else -> emptyList()
            }.takeIf { it.isNotEmpty() }
        }.maxByOrNull { ExtraLyricsProviders.rank(it) * 10000 + it.size.coerceAtMost(9999) }.orEmpty()
    }

    suspend fun publishLyrics(track: Track, content: ImportedLyrics) = withContext(Dispatchers.IO) {
        val token = PublisherSession.token()
        val body = metadata(track).put("content", content.content).put("format", content.format).put("rightsConfirmed", true)
        val c = connection("$BASE/admin/lyrics", "POST", token)
        try {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            require(response(c).optBoolean("published")) { "Publication impossible" }
        } finally { c.disconnect() }
        if (com.ketvoice.app.player.Player.state.value.current?.id == track.id) LyricsRepo.load(track, force = true, refresh = true)
    }

    private fun metadata(track: Track) = JSONObject().put("trackId", track.id).put("title", track.title)
        .put("artist", track.artist).put("album", track.album).put("durationMs", track.durationMs).put("isrc", track.isrc ?: "")

    suspend fun publishSong(track: Track, audio: File, cover: File?, lyrics: File?, onProgress: (Int) -> Unit): Track = withContext(Dispatchers.IO) {
        val token = PublisherSession.token()
        val boundary = "Ketvoice${UUID.randomUUID()}"
        val c = connection("$BASE/admin/song", "POST", token)
        val files = listOfNotNull("audio" to audio, cover?.let { "cover" to it }, lyrics?.let { "lyrics" to it })
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
        var sent = 0L
        try {
            c.doOutput = true; c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                fun write(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
                write("--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n${metadata(track).put("rightsConfirmed", true)}\r\n")
                files.forEach { (name, file) ->
                    val filename = "$name.${file.extension.lowercase()}"
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer); if (read < 0) break
                            out.write(buffer, 0, read); sent += read
                            onProgress((sent * 98 / total).toInt().coerceIn(0, 98))
                        }
                    }
                    write("\r\n")
                }
                write("--$boundary--\r\n")
            }
            val result = response(c)
            require(result.optBoolean("published")) { "Publication impossible" }
            onProgress(100)
            track(result.getJSONObject("track")) ?: error("Réponse de publication invalide")
        } finally { c.disconnect() }
    }
}
