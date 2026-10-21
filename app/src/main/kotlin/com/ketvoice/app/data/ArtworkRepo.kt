package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pochettes réelles pour les contenus éditoriaux (Accueil) :
 * recherche Deezer publique, matching titre + artiste, résultats mis en cache
 * dans les préférences (une seule requête par piste/album, pour toujours).
 * Aucune clé requise ; les images restent servies par le CDN Deezer.
 */
object ArtworkRepo {

    private const val PREFS = "ketvoice_art"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val _covers = MutableStateFlow<Map<String, String>>(emptyMap())
    val covers: StateFlow<Map<String, String>> = _covers

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var context: Context? = null
    private var loaded = false
    private var prefetched = false
    private val inFlight = mutableSetOf<String>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        context = ctx.applicationContext
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = mutableMapOf<String, String>()
        p.all.forEach { (k, v) ->
            if (v is String && v.startsWith("https://")) map[k] = v
            else if (v is String && v.isBlank()) p.edit().remove(k).apply()
        }
        _covers.value = map
    }

    fun coverForTrack(track: Track): String? = _covers.value["t:${track.id}"] ?: track.coverUrl
    fun coverForAlbum(album: Album): String? = _covers.value["a:${album.id}"] ?: album.coverUrl

    /**
     * Remplace les vignettes YouTube par de vraies pochettes d'album
     * (Deezer). Appelé dès qu'une recherche affiche des résultats.
     */
    fun enrich(tracks: List<Track>) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val todo = tracks.filter { track ->
            (track.coverUrl.isNullOrBlank() || !track.coverUrl.startsWith("https://") || track.addon == null) &&
                (failedAt[track.id]?.let { System.currentTimeMillis() - it > 120_000 } != false) &&
                prefs.getString("t:${track.id}", null) == null && synchronized(inFlight) {
                if (inFlight.contains(track.id)) false else {
                    inFlight += track.id
                    true
                }
            }
        }
        if (todo.isEmpty()) return
        scope.launch {
            try {
                todo.chunked(6).forEach { chunk ->
                    coroutineScope {
                        chunk.map { track ->
                            async(Dispatchers.IO) {
                                val url = runCatching {
                                    directCover(track) ?: searchTrackCover(track.title, track.artist)
                                }.getOrNull() ?: ""
                                if (url.isNotEmpty()) {
                                    prefs.edit().putString("t:${track.id}", url).apply()
                                    _covers.update { it + ("t:${track.id}" to url) }
                                    failedAt.remove(track.id)
                                } else {
                                    failedAt[track.id] = System.currentTimeMillis()
                                }
                                synchronized(inFlight) { inFlight.remove(track.id) }
                            }
                        }.forEach { it.await() }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Récupère les pochettes manquantes en tâche de fond (une fois par session). */
    fun prefetch(tracks: List<Track>, albums: List<Album>) {
        val ctx = context ?: return
        enrich(tracks)
        if (prefetched) return
        prefetched = true
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val todoAlbums = albums.filter { it.coverUrl.isNullOrBlank() && !prefs.contains("a:${it.id}") }
        if (todoAlbums.isEmpty()) return
        scope.launch {
            for (a in todoAlbums) {
                val url = searchTrackCover(a.title, a.artist) ?: ""
                prefs.edit().putString("a:${a.id}", url).apply()
                if (url.isNotEmpty()) _covers.update { it + ("a:${a.id}" to url) }
                delay(80)
            }
        }
    }

    private fun searchTrackCover(title: String, artist: String): String? {
        val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val raw = fetch("https://api.deezer.com/search?q=$q&limit=10") ?: return searchItunesCover(title, artist)
        val data = runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull() ?: return searchItunesCover(title, artist)
        val wantTitle = norm(title)
        val wantArtist = norm(artist.substringBefore(','))
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val t = norm(o.optString("title"))
            val ar = norm(o.optJSONObject("artist")?.optString("name").orEmpty())
            var score = 0
            if (t == wantTitle) score += 4
            else if (t.contains(wantTitle) || wantTitle.contains(t)) score += 2
            if (wantArtist.isNotEmpty() && (ar.contains(wantArtist) || wantArtist.contains(ar))) score += 2
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        if (bestScore < 4) return searchItunesCover(title, artist)
        val album = best?.optJSONObject("album") ?: return searchItunesCover(title, artist)
        val xl = album.optString("cover_xl")
        if (xl.isNotBlank()) return xl
        return album.optString("cover_big").takeIf { it.isNotBlank() }
            ?: searchItunesCover(title, artist)
    }

    private fun directCover(track: Track): String? {
        val endpoint = when {
            track.addon == "um" && track.addonId?.all(Char::isDigit) == true -> "track/${track.addonId}"
            !track.albumId.isNullOrBlank() && track.albumId.all(Char::isDigit) -> "album/${track.albumId}"
            else -> return null
        }
        val obj = fetch("https://api.deezer.com/$endpoint")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val album = obj.optJSONObject("album") ?: obj
        return album.optString("cover_xl", album.optString("cover_big")).takeIf { it.startsWith("https://") }
    }

    /** Secours iTunes : couverture d'album officielle, sans clé. */
    private fun searchItunesCover(title: String, artist: String): String? {
        return try {
            val term = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val raw = fetch("https://itunes.apple.com/search?term=$term&entity=song&limit=8&country=FR")
                ?: return null
            val arr = JSONObject(raw).optJSONArray("results") ?: return null
            val wantTitle = norm(title)
            val wantArtist = norm(artist.substringBefore(','))
            var best: JSONObject? = null
            var bestScore = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = norm(o.optString("trackName"))
                val ar = norm(o.optString("artistName"))
                var score = 0
                if (t == wantTitle) score += 4
                else if (t.contains(wantTitle) || wantTitle.contains(t)) score += 2
                if (wantArtist.isNotEmpty() && (ar.contains(wantArtist) || wantArtist.contains(ar))) score += 2
                if (score > bestScore) {
                    bestScore = score
                    best = o
                }
            }
            if (bestScore < 4) return null
            best?.optString("artworkUrl100")
                ?.replace("100x100bb", "600x600bb")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetch(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 14_000
            setRequestProperty("User-Agent", UA)
        }
        if (c.responseCode !in 200..299) {
            c.disconnect()
            null
        } else {
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            text
        }
    } catch (_: Exception) {
        null
    }

    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9à-ÿ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
