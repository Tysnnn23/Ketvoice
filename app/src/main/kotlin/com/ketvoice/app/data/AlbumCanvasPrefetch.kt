package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 7.5 motion75: the Canvas of an album whose tile is on screen (Home, Library) is fetched ahead on an unmetered
 * network, with « Précharger en Wi-Fi » on and the animated Canvas chosen for album pages, so that opening the album
 * shows its motion artwork (and its still) at once. One album at a time, a few per session.
 */
object AlbumCanvasPrefetch {
    internal const val PER_SESSION = 12
    private val tried = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The network check, replaced by the host tests. */
    internal var unmetered: (Context) -> Boolean = { CanvasRepo.unmetered(it) }

    /** Whether album Canvas are fetched ahead with these settings. */
    internal fun wanted(preload: Boolean, pagesCanvas: Boolean, choice: Int, reduced: Boolean): Boolean =
        preload && com.ketvoice.app.ui.sub.AlbumHeader.wantsCanvas(pagesCanvas, choice, reduced)

    private fun allowed(context: Context, key: String): Boolean =
        wanted(AppSettings.canvasPreloadWifi.value, AppSettings.albumHeroCanvas.value, AppSettings.albumHeader.value,
            com.ketvoice.app.ui.motion.Motion.reduced) &&
            tried.size < PER_SESSION && key !in tried && unmetered(context)

    /** A catalogue album's tile is on screen: its page (kept for its opening) gives the first title, whose Canvas is kept. */
    fun album(context: Context, request: CatalogRequest) {
        if (request.kind != "album" || request.id.isBlank() || CanvasRepo.pageFile(request.id) != null) return
        if (!allowed(context, request.id)) return
        tried += request.id
        scope.launch {
            mutex.withLock {
                try {
                    CanvasRepo.init(context)
                    val page = CatalogPageCache.get(request) ?: (if (request.id.startsWith("MPRE")) YouTubeCatalog.album(request.id)
                        else CatalogRepo.page(request))?.also { CatalogPageCache.put(request, it) }
                    val first = page?.tracks?.firstOrNull() ?: return@withLock
                    CanvasRepo.resolve(first, ahead = true)
                    CanvasRepo.localPath(first.id)?.let { CanvasRepo.rememberPage(request.id, it.absolutePath) }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    /** An album of the library (its titles known) is on screen: its first title's Canvas is kept. */
    fun album(context: Context, first: Track) {
        if (CanvasRepo.localPath(first.id) != null || !allowed(context, "t:" + first.id)) return
        tried += "t:" + first.id
        scope.launch {
            mutex.withLock {
                try { CanvasRepo.init(context); CanvasRepo.resolve(first, ahead = true) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }
}

/**
 * Catalogue album pages fetched lately, kept in memory (7.5 motion75): an album prefetched from its tile, or opened a
 * moment ago, opens with its titles at once instead of waiting for the catalogue again.
 */
object CatalogPageCache {
    private const val KEEP_MS = 30 * 60_000L
    private val pages = object : LinkedHashMap<String, Pair<Long, CatalogPage>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, CatalogPage>>?) = size > 16
    }

    private fun key(request: CatalogRequest) = request.kind + ":" + request.id

    fun get(request: CatalogRequest, now: Long = System.currentTimeMillis()): CatalogPage? = synchronized(pages) {
        pages[key(request)]?.takeIf { now - it.first < KEEP_MS }?.second
    }

    fun put(request: CatalogRequest, page: CatalogPage, now: Long = System.currentTimeMillis()) {
        if (page.tracks.isEmpty()) return
        synchronized(pages) { pages[key(request)] = now to page }
    }
}
