package com.ketvoice.desktop.player

import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.HttpRangeSource
import com.ketvoice.desktop.audio.PlaybackPrefs
import com.ketvoice.desktop.audio.StreamCache
import com.ketvoice.desktop.library.DesktopDownloader
import com.ketvoice.desktop.library.DesktopLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * « Préparer les favoris » (Windows 2.0, smart cache): the start of every favourite and playlist title (newest first)
 * is fetched ahead into [StreamCache], so it starts at once, even before its sources answer. Runs in the background a
 * minute after start and then every few hours, one title at a time, never while the player is loading, never past a
 * quarter of the cache's size, and skips what is downloaded or already warm.
 */
object CacheWarmer {
    data class Status(val running: Boolean = false, val warmed: Int = 0, val candidates: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private var job: Job? = null
    /** Titles to warm in one round. */
    const val MAX_TITLES = 150
    private const val FIRST_DELAY_MS = 60_000L
    private const val EVERY_MS = 6L * 3600 * 1000

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            delay(FIRST_DELAY_MS)
            while (isActive) {
                if (PlaybackPrefs.warm.value) runCatching { round() }
                delay(EVERY_MS)
            }
        }
    }

    /** Now (Settings' « Préparer maintenant »). */
    fun runNow(scope: CoroutineScope) { scope.launch(Dispatchers.IO) { runCatching { round() } } }

    /** Favourites first, then playlists, newest first; each title once; downloaded ones left out. */
    internal fun candidates(favorites: List<Track>, playlists: List<List<Track>>, downloaded: (String) -> Boolean): List<Track> =
        (favorites + playlists.flatten()).filter { it.id.isNotBlank() && !it.id.startsWith("local:") && !downloaded(it.id) }
            .distinctBy { it.id }.take(MAX_TITLES)

    /** How many bytes warming may take, out of the cache's [limit]. */
    internal fun budget(limit: Long): Long = limit / 4

    private suspend fun round() = withContext(Dispatchers.IO) {
        if (_status.value.running) return@withContext
        val list = candidates(DesktopLibrary.favorites.value, DesktopLibrary.playlists.value.map { p -> p.entries.mapNotNull { it.track } },
            downloaded = { DesktopDownloader.fileOf(it)?.isFile == true })
        _status.value = Status(true, 0, list.size)
        var spent = 0L
        var warmed = 0
        try {
            for (track in list) {
                if (!isActive || !PlaybackPrefs.warm.value || spent >= budget(StreamCache.limitBytes)) break
                while (Player.loading.value) delay(2_000)
                val tier = DesktopStreams.tier()
                if (StreamCache.lookup(StreamCache.key(track.id, tier))?.second?.let { it.head >= StreamCache.MIN_HEAD || it.complete } == true) {
                    warmed++; continue
                }
                val source = runCatching { DesktopStreams.source(track) }.getOrNull() as? AudioSource.Url ?: continue
                spent += warm(source)
                warmed++
                _status.value = Status(true, warmed, list.size)
                delay(1_500)
            }
        } finally {
            _status.value = Status(false, warmed, list.size)
        }
    }

    /** Reads the first [StreamCache.MIN_HEAD] bytes of [source] onto disk; the bytes fetched. */
    private fun warm(source: AudioSource.Url): Long {
        val http = HttpRangeSource(source.url, source.headers, key = source.cacheKey, refresh = source.refresh)
        return try {
            val buf = ByteArray(HttpRangeSource.CHUNK)
            var at = 0L
            val until = if (http.size > 0) minOf(http.size, StreamCache.MIN_HEAD) else StreamCache.MIN_HEAD
            while (at < until) {
                val n = http.readAt(at, buf, 0, buf.size)
                if (n <= 0) break
                at += n
            }
            at
        } catch (_: Exception) { 0L } finally { http.close() }
    }
}
