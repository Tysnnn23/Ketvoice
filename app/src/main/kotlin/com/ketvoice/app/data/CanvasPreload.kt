package com.ketvoice.app.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Canvas fetched ahead on Wi-Fi (6.2.3, setting `canvasPreloadWifi`): the favourites, then the playlists' titles, so that
 * their Canvas is already on the device when they play. Only on an unmetered network (whatever « Canvas en données
 * mobiles » says), only while the app is in front or the device charges; what to fetch and when to stop is decided by
 * [CanvasPreloadPlan]. [CanvasRepo.resolve] keeps the owner's « Canvas off » and the per-title exclusion.
 */
object CanvasPreload {
    /** The first run waits, so that nothing here competes with the start of the app. */
    internal const val START_DELAY_MS = 45_000L
    private const val NEXT_RUN_MS = 30 * 60_000L
    private const val RECHECK_MS = 3 * 60_000L
    /** A title looked up in the last hours is not looked up again by a later run (a title with no Canvas would eat every run). */
    private const val RETRY_AFTER_MS = 6 * 60 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private val tried = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            delay(START_DELAY_MS)
            while (true) {
                val ran = try { eligible(app) && run(app) > 0 } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                delay(if (ran) NEXT_RUN_MS else RECHECK_MS)
            }
        }
    }

    private fun eligible(app: Context): Boolean = conditions(app).let { it.enabled && it.canvasOn && it.unmetered && it.inFrontOrCharging }

    internal fun conditions(app: Context): CanvasPreloadPlan.Conditions {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return CanvasPreloadPlan.Conditions(
            enabled = AppSettings.canvasPreloadWifi.value,
            canvasOn = AppSettings.animatedArtwork.value || AppSettings.albumHeroCanvas.value,
            unmetered = CanvasRepo.unmetered(app),
            inFrontOrCharging = com.ketvoice.app.automix.Automix.appInFront || charging(app),
            network = runCatching { cm?.activeNetwork?.toString() }.getOrNull())
    }

    private fun charging(app: Context): Boolean =
        runCatching { app.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true }.getOrDefault(false)

    /** One run: walks the plan until it stops. Returns how many titles were looked up. */
    private suspend fun run(app: Context): Int {
        CanvasRepo.init(app)
        val plan = CanvasPreloadPlan(titles())
        while (true) {
            when (val step = plan.next(System.currentTimeMillis(), conditions(app), ::needed)) {
                is CanvasPreloadPlan.Step.Fetch -> {
                    tried[step.track.id] = System.currentTimeMillis()
                    try { CanvasRepo.resolve(step.track, ahead = true) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                is CanvasPreloadPlan.Step.Wait -> delay(step.ms)
                is CanvasPreloadPlan.Step.Stop -> return plan.fetched
            }
        }
    }

    private fun needed(track: Track): Boolean {
        if (CanvasRepo.localPath(track.id) != null || CanvasRepo.isExcluded(track.id) || MediaOverrides.canvasUnused(track)) return false
        return System.currentTimeMillis() - (tried[track.id] ?: 0L) > RETRY_AFTER_MS
    }

    private fun titles(): List<Track> {
        val ids = AppSettings.favoriteIds.value
        val known = ListeningHistory.knownTracks.value + ListeningHistory.tracks.value +
            Downloader.items.value.map { it.track } + com.ketvoice.app.sync.SyncedTracks.tracks.value
        val favorites = known.filter { it.id in ids }
        val lists = PlaylistStore.lists.value.map { list -> list.entries.mapNotNull { it.track } }
        return CanvasPreloadPlan.order(favorites, lists)
    }
}
