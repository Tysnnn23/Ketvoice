package com.ketvoice.app.data

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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

    fun pick(pool: List<Track>, previous: Set<String>, limit: Int, random: Random): List<Track> {
        val unique = pool.distinctBy { it.id }
        return (unique.filterNot { it.id in previous }.shuffled(random) +
            unique.filter { it.id in previous }.shuffled(random)).take(limit)
    }

    internal fun personalizedPick(pool: List<Track>, previous: Set<String>, recent: List<Track>,
                                  favorites: List<Track>, limit: Int, random: Random): List<Track> {
        fun recording(track: Track) = "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"
        val recentRecordings = recent.map(::recording).toSet()
        val artists = (favorites + recent).map { it.artist.substringBefore(',').trim().lowercase() }
            .filter { it.isNotBlank() }.toSet()
        return pool.distinctBy { it.id }.shuffled(random)
            .sortedWith(compareBy<Track> { if (it.id in previous || recording(it) in recentRecordings) 1 else 0 }
                .thenBy { if (it.artist.substringBefore(',').trim().lowercase() in artists) 0 else 1 })
            .take(limit)
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val prefs = context.getSharedPreferences("home-feed-v1", Context.MODE_PRIVATE)
        val gson = Gson()
        val previous = prefs.getStringSet("previous", emptySet()).orEmpty().toSet()
        val poolLock = Any()
        var latestPool = emptyList<Track>()
        var latestDiscoveries = emptyList<Track>()
        fun shelves(pool: List<Track>, discoveries: List<Track>, recent: List<Track>): List<HomeShelf> {
            val favorites = ListeningHistory.knownTracks.value.filter { it.id in AppSettings.favoriteIds.value }
            val forYou = personalizedPick(pool, previous, recent, favorites, 12, random)
            val discovered = personalizedPick(discoveries, previous + forYou.map { it.id }, recent, emptyList(), 12, random)
            return listOf(
                HomeShelf("Fait pour vous", if (recent.isEmpty() && favorites.isEmpty()) "Une sélection pour commencer"
                    else "Basé sur vos favoris et écoutes", forYou),
                HomeShelf("À découvrir", "Changez d'horizon", discovered),
                HomeShelf("À écouter aujourd'hui", "Votre sélection du jour", personalizedPick(pool + discoveries,
                    previous + forYou.map { it.id } + discovered.map { it.id }, recent, favorites, 12, random))
            )
        }
        fun updateLocal(pool: List<Track>, discoveries: List<Track>, recent: List<Track>) = synchronized(poolLock) {
            latestPool = pool
            latestDiscoveries = discoveries
            localShelves = shelves(pool, discoveries, recent)
            if (!personal) _shelves.value = localShelves
            localShelves
        }
        val cached = runCatching { gson.fromJson(prefs.getString("pool", "[]"), Array<Track>::class.java).toList() }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            updateLocal(cached, cached, ListeningHistory.tracks.value)
            _albums.value = cached.filter { !it.albumId.isNullOrBlank() && it.albumId!!.all(Char::isDigit) }
                .distinctBy { it.albumId }.shuffled(random).take(12).map {
                    Album(it.albumId!!, it.album, it.artist, "Sortie du catalogue", it.coverUrl)
                }
        }
        scope.launch {
            combine(ListeningHistory.tracks, snapshotFlow { AppSettings.favoriteIds.value }) { history, _ -> history }
                .drop(1).collect { history ->
                synchronized(poolLock) {
                    if (latestPool.isNotEmpty()) updateLocal(latestPool, latestDiscoveries, history)
                }
            }
        }
        _loading.value = true
        scope.launch {
            try {
                val defaults = listOf("Werenoi", "Juice WRLD", "PNL", "Damso", "Gazo", "Tiakola", "Orelsan",
                    "The Weeknd", "Aya Nakamura", "SZA", "Billie Eilish", "Travis Scott", "Ninho", "SCH", "Dua Lipa", "Kendrick Lamar")
                val recent = ListeningHistory.tracks.value
                val favorites = ListeningHistory.knownTracks.value.filter { it.id in AppSettings.favoriteIds.value }
                val artists = (favorites + recent).map { it.artist.substringBefore(',').trim() }.distinct().take(3)
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
                    val result = updateLocal(pool, discover, ListeningHistory.tracks.value)
                    prefs.edit().putString("pool", gson.toJson((pool + discover).distinctBy { it.id }))
                        .putStringSet("previous", result.flatMap { it.tracks.take(4) }.map { it.id }.toSet()).apply()
                    ArtworkRepo.enrich(result.flatMap { it.tracks })
                }
            } catch (_: Exception) {

            } finally { _loading.value = false; refreshYouTube() }
        }
    }

    private fun search(query: String): List<Track> {
        var c: HttpURLConnection? = null
        return try {
            c = URL(ProviderConfig.publicUrl("https://api.deezer.com/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=35") ?: return emptyList())
                .openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 9000
            if (c.responseCode !in 200..299) return emptyList()
            val data = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data") ?: return emptyList()
            CatalogRepo.parseTracks(data)
        } catch (_: Exception) { emptyList() } finally { c?.disconnect() }
    }
}
