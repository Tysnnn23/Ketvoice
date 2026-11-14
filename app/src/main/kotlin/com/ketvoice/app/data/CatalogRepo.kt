package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.HttpURLConnection

data class CatalogRequest(val kind: String, val id: String, val title: String)
data class CatalogGenre(val id: String, val title: String, val cover: String?)
data class CatalogPage(val title: String, val subtitle: String, val cover: String?, val tracks: List<Track>,
    val releaseDate: String? = null)

object CatalogRepo {
    private var genreCache: List<CatalogGenre> = emptyList()
    private fun get(path: String): JSONObject? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(ProviderConfig.publicUrl("https://api.deezer.com/$path") ?: return null).openConnection() as HttpURLConnection
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
                CatalogPage(o.optString("title"), artist?.optString("name").orEmpty(), cover, tracks,
                    releaseDate = o.optString("release_date").takeIf { it.isNotBlank() && it != "null" })
            }
            "genre" -> {
                val o = get("chart/${request.id}/tracks?limit=60") ?: return@withContext null
                CatalogPage(request.title, "Titres du catalogue · Deezer", null, parseTracks(o.optJSONArray("data")))
            }
            else -> null
        }
    }
    internal fun sameArtistName(a: String, b: String): Boolean {
        fun plain(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().filter(Char::isLetterOrDigit)
        return plain(a).isNotEmpty() && plain(a) == plain(b)
    }

    /**
     * The catalogue id of an artist. A search hit is accepted only when its name is the
     * requested name: the first hit of a homonym or of a loosely related artist would
     * fill a mix with someone else's music.
     */
    private fun artistId(name: String, knownId: String?): String? {
        knownId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { return it }
        if (name.isBlank()) return null
        val hits = get("search/artist?q=${java.net.URLEncoder.encode(name.take(80), "UTF-8")}&limit=5")
            ?.optJSONArray("data") ?: return null
        for (index in 0 until hits.length()) {
            val hit = hits.optJSONObject(index) ?: continue
            val id = hit.optString("id")
            if (id.isNotBlank() && id.all(Char::isDigit) && sameArtistName(hit.optString("name"), name)) return id
        }
        return null
    }

    /** The artist's most listened tracks in the public catalogue; empty when unavailable or unknown there. */
    suspend fun artistTop(name: String, knownId: String? = null): List<Track> = withContext(Dispatchers.IO) {
        val id = artistId(name, knownId) ?: return@withContext emptyList()
        parseTracks(get("artist/$id/top?limit=30")?.optJSONArray("data"))
    }

    /** Tracks by artists in the same style, from the catalogue's artist radio; empty when unavailable. */
    suspend fun artistRadio(name: String, knownId: String? = null): List<Track> = withContext(Dispatchers.IO) {
        val id = artistId(name, knownId) ?: return@withContext emptyList()
        parseTracks(get("artist/$id/radio")?.optJSONArray("data"))
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
