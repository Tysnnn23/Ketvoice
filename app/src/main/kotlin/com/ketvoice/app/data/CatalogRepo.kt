package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.HttpURLConnection

data class CatalogRequest(val kind: String, val id: String, val title: String)
data class CatalogGenre(val id: String, val title: String, val cover: String?)
data class CatalogPage(val title: String, val subtitle: String, val cover: String?, val tracks: List<Track>)

object CatalogRepo {
    private var genreCache: List<CatalogGenre> = emptyList()
    private fun get(path: String): JSONObject? {
        var c: HttpURLConnection? = null
        return try {
            c = URL("https://api.deezer.com/$path").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 10000
            if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }
    suspend fun genres(): List<CatalogGenre> = withContext(Dispatchers.IO) {
        if (genreCache.isNotEmpty()) return@withContext genreCache
        val data = get("genre")?.optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            if (o.optInt("id") == 0) return@mapNotNull null
            CatalogGenre(o.optString("id"), o.optString("name"), o.optString("picture_xl").takeIf { it.startsWith("https://") })
        }.also { genreCache = it }
    }
    suspend fun page(request: CatalogRequest): CatalogPage? = withContext(Dispatchers.IO) {
        if (request.kind == "album" && request.id.startsWith("MPRE")) return@withContext YouTubeCatalog.album(request.id)
        if (!request.id.all { it.isDigit() } || request.id.isBlank()) return@withContext null
        when (request.kind) {
            "album" -> {
                val o = get("album/${request.id}") ?: return@withContext null
                if (o.has("error")) return@withContext null
                val cover = o.optString("cover_xl").takeIf { it.isNotBlank() }
                val artist = o.optJSONObject("artist")
                val response = o.optJSONObject("tracks")
                val all = JSONArray()
                var current = response
                var pages = 0
                while (current != null && pages++ < 10) {
                    val data = current.optJSONArray("data") ?: break
                    for (index in 0 until data.length()) all.put(data.get(index))
                    val next = current.optString("next")
                    current = if (next.startsWith("https://api.deezer.com/")) get(next.removePrefix("https://api.deezer.com/")) else null
                }
                val tracks = parseTracks(all, o, artist)
                CatalogPage(o.optString("title"), artist?.optString("name").orEmpty(), cover, tracks)
            }
            "genre" -> {
                val o = get("chart/${request.id}/tracks?limit=60") ?: return@withContext null
                CatalogPage(request.title, "Titres du catalogue · Deezer", null, parseTracks(o.optJSONArray("data")))
            }
            else -> null
        }
    }
    internal fun parseTracks(data: JSONArray?, release: JSONObject? = null, artist: JSONObject? = null): List<Track> {
        if (data == null) return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val t = data.optJSONObject(i) ?: return@mapNotNull null
            if (t.has("readable") && !t.optBoolean("readable")) return@mapNotNull null
            val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val album = release ?: t.optJSONObject("album")
            val singer = t.optJSONObject("artist") ?: artist
            Track("um:$id", t.optString("title"), singer?.optString("name").orEmpty(), album?.optString("title").orEmpty(),
                durationMs = t.optLong("duration") * 1000, explicit = t.optBoolean("explicit_lyrics"),
                coverUrl = album?.optString("cover_xl")?.takeIf { it.startsWith("https://") },
                addon = "um", addonId = id, isrc = t.optString("isrc").takeIf { it.isNotBlank() && it != "null" },
                albumId = album?.optString("id"), artistId = singer?.optString("id"))
        }.distinctBy { it.id }
    }
}
