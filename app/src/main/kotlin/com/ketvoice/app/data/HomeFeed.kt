package com.ketvoice.app.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

data class HomeShelf(val title: String, val subtitle: String, val tracks: List<Track>)

object HomeFeed {
    private val _shelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val shelves: StateFlow<List<HomeShelf>> = _shelves
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums
    private var started = false
    private var personal = false
    private var localShelves: List<HomeShelf> = emptyList()
    private val _origin = MutableStateFlow("Sélections locales")
    val origin: StateFlow<String> = _origin
    fun refreshYouTube() {
        scope.launch {
            if (!AppSettings.personalizedYouTubeHome.value) return@launch
            val data = YouTubeCatalog.personalHome()
            if (data.isNotEmpty() && YouTubeMusicSession.profile.value.connected) {
                _shelves.value = data
                personal = true
                _origin.value = "Accueil YouTube Music personnel"
                ArtworkRepo.enrich(data.flatMap { it.tracks })
            }
        }
    }
    fun clearPersonalized() { personal = false; _shelves.value = localShelves; _origin.value = "Sélections locales" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = Random(System.nanoTime())

    /** Avoid repeating last launch's leading cards when there are alternatives. */
    fun pick(pool: List<Track>, previous: Set<String>, limit: Int, random: Random): List<Track> {
        val unique = pool.distinctBy { it.id }
        return (unique.filterNot { it.id in previous }.shuffled(random) +
            unique.filter { it.id in previous }.shuffled(random)).take(limit)
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val prefs = context.getSharedPreferences("home-feed-v1", Context.MODE_PRIVATE)
        val gson = Gson()
        val previous = prefs.getStringSet("previous", emptySet()).orEmpty().toSet()
        val recent = ListeningHistory.tracks.value
        fun shelves(pool: List<Track>, discoveries: List<Track>): List<HomeShelf> {
            val forYou = pick(pool, previous, 12, random)
            return listOf(
                HomeShelf("Fait pour vous", if (recent.isEmpty()) "Une sélection pour commencer" else "Inspiré de vos dernières écoutes", forYou),
                HomeShelf("À découvrir", "Changez d'horizon", pick(discoveries, previous + forYou.map { it.id }, 12, random)),
                HomeShelf("À écouter aujourd'hui", "Votre sélection du jour", pick(pool + discoveries, previous + forYou.map { it.id }, 12, random))
            )
        }
        val cached = runCatching { gson.fromJson(prefs.getString("pool", "[]"), Array<Track>::class.java).toList() }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            localShelves = shelves(cached, cached)
            if (!personal) _shelves.value = localShelves
            _albums.value = cached.filter { !it.albumId.isNullOrBlank() && it.albumId!!.all(Char::isDigit) }
                .distinctBy { it.albumId }.shuffled(random).take(12).map {
                    Album(it.albumId!!, it.album, it.artist, "Sortie du catalogue", it.coverUrl)
                }
        }
        _loading.value = true
        scope.launch {
            try {
                val defaults = listOf("Werenoi", "Juice WRLD", "PNL", "Damso", "Gazo", "Tiakola", "Orelsan",
                    "The Weeknd", "Aya Nakamura", "SZA", "Billie Eilish", "Travis Scott", "Ninho", "SCH", "Dua Lipa", "Kendrick Lamar")
                val artists = recent.map { it.artist.substringBefore(',').trim() }.distinct().take(3)
                    .ifEmpty { defaults.shuffled(random).take(3) }
                val fetched = coroutineScope {
                    val personalized = async { coroutineScope {
                        artists.map { artist -> async { search("artist:\"$artist\"") } }.flatMap { it.await() }
                    } }
                    val fresh = async { search(defaults.filterNot { it in artists }.random(random)) }
                    personalized.await() to fresh.await()
                }
                val pool = fetched.first.ifEmpty { cached }
                val discover = fetched.second.ifEmpty { pool }
                if (pool.isNotEmpty()) {
                    _albums.value = (pool + discover).filter { !it.albumId.isNullOrBlank() && it.albumId!!.all(Char::isDigit) }
                        .distinctBy { it.albumId }.shuffled(random).take(12).map {
                            Album(it.albumId!!, it.album, it.artist, "Sortie du catalogue", it.coverUrl)
                        }
                    val result = shelves(pool, discover)
                    localShelves = result
                    if (!personal) _shelves.value = result
                    prefs.edit().putString("pool", gson.toJson((pool + discover).distinctBy { it.id }))
                        .putStringSet("previous", result.flatMap { it.tracks.take(4) }.map { it.id }.toSet()).apply()
                    ArtworkRepo.enrich(result.flatMap { it.tracks })
                }
            } catch (_: Exception) {
                // Keep the already-published offline shelves if an endpoint fails.
            } finally { _loading.value = false; refreshYouTube() }
        }
    }

    private fun search(query: String): List<Track> {
        var c: HttpURLConnection? = null
        return try {
            c = URL("https://api.deezer.com/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=35")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 9000
            if (c.responseCode !in 200..299) return emptyList()
            val data = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data") ?: return emptyList()
            CatalogRepo.parseTracks(data)
        } catch (_: Exception) { emptyList() } finally { c?.disconnect() }
    }
}
