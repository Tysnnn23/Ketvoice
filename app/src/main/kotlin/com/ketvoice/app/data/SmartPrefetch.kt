package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 8.2 « Préchargement intelligent » (owner): on Wi-Fi (unmetered) only, what is likely to be opened next is warmed —
 * the album and artist pages of the playing title, the covers of the next titles of the queue and of the first Home
 * rows. Bounded ([NEXT_TITLES], [HOME_COVERS]), one pass at a time per kind, cancelled when the title or Home changes
 * (collectLatest) and between items when the phone leaves Wi-Fi or the automatic economy holds. Pages go through
 * RecentAnswers (CatalogRepo, ArtistPages → ArtistCache), covers through Coil's disk cache; nothing is shown.
 */
object SmartPrefetch {
    internal const val NEXT_TITLES = 3
    internal const val HOME_COVERS = 18
    /** Lets the first sound and the first Home frame go first. */
    private const val SETTLE_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch { aroundPlayback(app) }
        scope.launch { aroundHome(app) }
    }

    private suspend fun aroundPlayback(app: Context) {
        com.ketvoice.app.player.Player.state
            .map { s -> s.current to s.queue.drop(s.index + 1).take(NEXT_TITLES) }
            .distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second.map { it.id } == b.second.map { it.id } }
            .collectLatest { (current, next) ->
                if (current == null) return@collectLatest
                delay(SETTLE_MS)
                if (!allowed(app)) return@collectLatest
                pages(app, current)
                covers(app, nextCovers(next))
            }
    }

    private suspend fun aroundHome(app: Context) {
        combine(HomeFeed.shelves, HomeFeed.albums) { shelves, albums -> homeCovers(shelves, albums) }
            .distinctUntilChanged()
            .collectLatest { urls ->
                delay(SETTLE_MS)
                covers(app, urls)
            }
    }

    /** The playing title's album page and artist page, as the player opens them. */
    private suspend fun pages(app: Context, track: Track) {
        album(track)?.let { request ->
            runCatching { CatalogRepo.page(request) }.getOrNull()?.let { CatalogPageCache.put(request, it) }
        }
        if (!allowed(app) || track.artist.isBlank()) return
        runCatching { ArtistPages.cachedOrFetch(track.artistId.orEmpty(), track.artist) }
    }

    internal fun album(track: Track): CatalogRequest? {
        val id = track.albumId?.takeIf { it.isNotBlank() } ?: return null
        if (!id.startsWith("MPRE") && !id.all { it.isDigit() }) return null
        return CatalogRequest("album", id, track.album)
    }

    internal fun nextCovers(next: List<Track>): List<String> =
        next.take(NEXT_TITLES).mapNotNull { coverOf(it) }.distinct()

    internal fun homeCovers(shelves: List<HomeShelf>, albums: List<Album>): List<String> {
        val fromShelves = shelves.take(3).flatMap { it.tracks.take(6) }.mapNotNull { coverOf(it) }
        val fromAlbums = albums.take(6).mapNotNull { ArtworkRepo.coverForAlbum(it)?.takeIf(::isWeb) }
        return (fromShelves + fromAlbums).distinct().take(HOME_COVERS)
    }

    private fun coverOf(track: Track): String? = (ArtworkRepo.coverForTrack(track) ?: track.coverUrl)?.takeIf(::isWeb)

    private fun isWeb(url: String) = url.startsWith("https://") || url.startsWith("http://")

    /** One cover after the other into Coil's disk cache, at a small decode size, never into the memory cache. */
    private suspend fun covers(app: Context, urls: List<String>) {
        val loader = coil.Coil.imageLoader(app)
        for (url in urls) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!allowed(app)) return
            val request = coil.request.ImageRequest.Builder(app).data(url).size(256)
                .memoryCachePolicy(coil.request.CachePolicy.READ_ONLY).build()
            runCatching { loader.execute(request) }
        }
    }

    private fun allowed(app: Context): Boolean =
        com.ketvoice.app.util.EconomyMode.holding.value == null && CanvasRepo.unmetered(app)
}
