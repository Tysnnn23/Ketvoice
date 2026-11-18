package com.ketvoice.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * « Hors ligne intelligent » (7.0): on an unmetered network, and while the phone charges unless the listener says
 * otherwise, Ketvoice downloads by itself what the listener plays most and their new favourites, within the room
 * they chose ([SmartOfflinePlan] decides; this object remembers and acts).
 *
 * It remembers, in the `smart-offline` preferences: which downloads it made itself (`auto`, the only ones it may
 * ever remove), which titles the listener deleted by hand (`deleted`, never downloaded again by it) and when each
 * favourite was first seen (`favSeen`, what makes a favourite « new »). The favourites of 5.8.0
 * ([FavoritesOffline], `autoOfflineFavorites`) are automatic downloads too and go through here.
 */
object SmartOffline {
    private const val PREFS = "smart-offline"
    private const val MAX_DELETED = 3000
    private var prefs: SharedPreferences? = null
    private val _auto = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of the titles Ketvoice downloaded on its own: they carry the « Auto » badge in Downloads. */
    val autoIds: StateFlow<Set<String>> = _auto
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    /** Downloads shows only the automatic downloads (from « Voir ce qui a été téléchargé automatiquement »). */
    val showAutoOnly = mutableStateOf(false)

    @Synchronized fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _auto.value = p.getStringSet("auto", emptySet())?.toSet().orEmpty()
    }

    /** For host tests: forget everything this object holds in memory. */
    @Synchronized internal fun resetForTest() { prefs = null; _auto.value = emptySet(); showAutoOnly.value = false }

    private fun deleted(): Set<String> = prefs?.getStringSet("deleted", emptySet())?.toSet().orEmpty()

    @Synchronized internal fun markAuto(id: String) {
        val now = _auto.value + id
        _auto.value = now
        prefs?.edit()?.putStringSet("auto", now)?.apply()
    }

    @Synchronized private fun unmarkAuto(ids: Collection<String>) {
        if (ids.none { it in _auto.value }) return
        val now = _auto.value - ids.toSet()
        _auto.value = now
        prefs?.edit()?.putStringSet("auto", now)?.apply()
    }

    /** The listener asked for this download: it becomes theirs, and may be offered again later. */
    @Synchronized fun noteManualDownload(id: String) {
        unmarkAuto(listOf(id))
        val gone = deleted()
        if (id in gone) prefs?.edit()?.putStringSet("deleted", gone - id)?.apply()
    }

    /** The listener deleted these downloads by hand: Ketvoice never downloads them again on its own. */
    @Synchronized fun noteDeletedByHand(ids: Collection<String>) {
        if (ids.isEmpty()) return
        unmarkAuto(ids)
        val now = (deleted() + ids).toList().takeLast(MAX_DELETED).toSet()
        prefs?.edit()?.putStringSet("deleted", now)?.apply()
    }

    internal fun deletedByHand(id: String): Boolean = id in deleted()

    // ------------------------------------------------------------------ favourites

    /** Favourites seen for the first time now are new; those there before Ketvoice watched them are not. */
    @Synchronized fun noteFavorites(now: Set<String>, at: Long = System.currentTimeMillis()) {
        val p = prefs ?: return
        val first = !p.contains("favSeen")
        val seen = favoritesSeen().toMutableMap()
        var changed = first
        for (id in now) if (id !in seen) { seen[id] = if (first) 0L else at; changed = true }
        // Unfavourited titles leave the record (a favourite again later is new again).
        if (seen.keys.retainAll(now)) changed = true
        if (changed) p.edit().putString("favSeen", JSONObject(seen as Map<*, *>).toString()).apply()
    }

    internal fun favoritesSeen(): Map<String, Long> = runCatching {
        val o = JSONObject(prefs?.getString("favSeen", null) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.optLong(it) }
    }.getOrDefault(emptyMap())

    /**
     * A title just made a favourite (from [FavoritesOffline]). With « Hors ligne intelligent » on, it waits for the
     * next pass on an unmetered network (asked now); otherwise the 5.8.0 behaviour: downloaded at once, as an
     * automatic download. Never for a title the listener deleted by hand.
     */
    fun favoriteAdded(context: Context?, track: Track) {
        if (deletedByHand(track.id) || Downloader.itemFor(track.id) != null || track.id in Downloader.busy.value) return
        if (AppSettings.smartOffline.value) { context?.let { runCatching { scheduleSoon(it) } }; return }
        markAuto(track.id)
        if (Downloader.download(track, auto = true) == null && Downloader.itemFor(track.id) == null) unmarkAuto(listOf(track.id))
    }

    // ------------------------------------------------------------------ one pass

    data class Outcome(val downloaded: Int, val removed: Int, val skipped: String? = null)

    /** One pass: plan, make room, then download one title at a time while the conditions hold. */
    suspend fun run(context: Context, conditions: () -> Boolean = { true }): Outcome {
        val app = context.applicationContext
        init(app); Downloader.init(app)
        if (!AppSettings.smartOffline.value) return Outcome(0, 0, "off")
        if (!_running.compareAndSet(false, true)) return Outcome(0, 0, "busy")
        try {
            noteFavorites(AppSettings.favoriteIds.value)
            val plan = currentPlan(app)
            val removed = if (transferring()) 0 else evict(plan.evict)
            var downloaded = 0
            for (candidate in plan.download) {
                if (!conditions() || !AppSettings.smartOffline.value) break
                if (fetch(candidate.track)) downloaded++
            }
            prefs?.edit()?.putLong("lastRun", System.currentTimeMillis())?.apply()
            return Outcome(downloaded, removed)
        } finally { _running.value = false }
    }

    /** The plan as things stand now (also what Settings can show). */
    internal fun currentPlan(context: Context): SmartOfflinePlan.Plan {
        val items = Downloader.items.value
        val auto = _auto.value
        val counts = ListeningHistory.playCounts.value
        val owned = items.map { SmartOfflinePlan.Owned(it.track.id, it.bytes, it.track.id in auto, counts[it.track.id] ?: 0, it.file.lastModified()) }
        val autoBytes = owned.filter { it.auto }.sumOf { it.bytes }
        val free = runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)
        val limit = SmartOfflinePlan.limitBytes(AppSettings.smartOfflineGb.value, AppSettings.smartOfflinePercent.value, free, autoBytes)
        val excluded = deleted() + items.filter { it.track.id !in auto }.map { it.track.id }
        val ranked = SmartOfflinePlan.rank(signals(), excluded, System.currentTimeMillis())
        val quality = AppSettings.downloadQualityMode.value
        return SmartOfflinePlan.plan(ranked, owned, limit, protectedIds(), { SmartOfflinePlan.estimate(it, quality) })
    }

    private fun signals(): SmartOfflinePlan.Signals {
        val known = knownTracks()
        val seen = favoritesSeen()
        val favorites = AppSettings.favoriteIds.value.mapNotNull { id -> known[id]?.let { it to (seen[id] ?: 0L) } }
        val recent = SmartOfflinePlan.recentPlays(ListeningHistory.months.value, LocalDate.now())
        val playlists = PlaylistStore.lists.value.map { list -> list.entries.mapNotNull { it.track } }
        val week = runCatching { WeeklyDiscoveries.current()?.tracks }.getOrNull().orEmpty()
        val played = ListeningHistory.tracks.value.mapTo(HashSet()) { it.id }
        return SmartOfflinePlan.Signals(favorites, recent, playlists, week, played, known)
    }

    /** Every title object Ketvoice knows without asking the network, by id. */
    private fun knownTracks(): Map<String, Track> {
        val out = HashMap<String, Track>()
        ListeningHistory.months.value.values.forEach { out.putAll(it.tracks) }
        PlaylistStore.lists.value.forEach { list -> list.entries.forEach { e -> e.track?.let { out.putIfAbsent(it.id, it) } } }
        ListeningHistory.tracks.value.forEach { out[it.id] = it }
        ListeningHistory.knownTracks.value.forEach { out.putIfAbsent(it.id, it) }
        return out
    }

    /** What is playing or queued next, and what Air Ket is sending: never removed. */
    private fun protectedIds(): Set<String> {
        val state = com.ketvoice.app.player.Player.state.value
        return (listOfNotNull(state.current?.id) + state.queue.take(5).map { it.id }).toSet()
    }

    /** An Air Ket transfer in either direction: the downloads folder is being read or filled, nothing is removed. */
    internal fun transferring(): Boolean = runCatching {
        val airket = com.ketvoice.app.airket.AirKet
        val send = airket.send.value.phase
        val receive = airket.receive.value.phase
        airket.ongoing.value != null ||
            send in setOf(com.ketvoice.app.airket.AirKetSendUi.Phase.Preparing, com.ketvoice.app.airket.AirKetSendUi.Phase.Waiting,
                com.ketvoice.app.airket.AirKetSendUi.Phase.Paired, com.ketvoice.app.airket.AirKetSendUi.Phase.Sending) ||
            receive in setOf(com.ketvoice.app.airket.AirKetReceiveUi.Phase.Receiving, com.ketvoice.app.airket.AirKetReceiveUi.Phase.Installing)
    }.getOrDefault(true)

    /** Removes automatic downloads only, re-checked one by one at the moment of removal. */
    private fun evict(ids: List<String>): Int {
        var removed = 0
        for (id in ids) {
            if (id !in _auto.value) continue
            val item = Downloader.itemFor(id) ?: continue
            // A removal that left nothing of the audio behind counts too (a cover it could not clear is no reason to keep the mark).
            if (Downloader.delete(item) || (item.dashManifestUrl == null && !item.file.exists())) { unmarkAuto(listOf(id)); removed++ }
        }
        return removed
    }

    /** One automatic download, quietly; a stop of the pass cancels it and leaves nothing half done. */
    private suspend fun fetch(track: Track): Boolean {
        if (Downloader.itemFor(track.id) != null || deletedByHand(track.id)) return false
        markAuto(track.id)
        val job = Downloader.download(track, auto = true)
        try { job?.join() } catch (cancelled: CancellationException) {
            Downloader.cancel(track.id); unmarkAuto(listOf(track.id)); throw cancelled
        }
        val ok = Downloader.itemFor(track.id) != null
        if (!ok) unmarkAuto(listOf(track.id))
        return ok
    }

    // ------------------------------------------------------------------ what Settings shows and does

    data class Usage(val count: Int, val bytes: Long)

    fun usage(): Usage {
        val auto = _auto.value
        val items = Downloader.items.value.filter { it.track.id in auto }
        return Usage(items.size, items.sumOf { it.bytes })
    }

    /** « Supprimer les téléchargements automatiques »: every automatic download goes; those made by hand stay. */
    fun clearAuto(): Int = evict(_auto.value.toList()).also {
        // Ids whose files were already gone leave the record too.
        unmarkAuto(_auto.value.filter { Downloader.itemFor(it) == null && it !in Downloader.busy.value })
    }

    // ------------------------------------------------------------------ scheduling

    private const val WORK = "smart-offline"
    private const val WORK_SOON = "smart-offline-soon"

    private fun constraints(charging: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .setRequiresCharging(charging)
        .build()

    /**
     * Every six hours while it is on, under its conditions; cancelled when it is off. Reads the saved settings itself.
     * [replace]: the conditions changed (from Settings); at the app's start the running schedule is kept.
     */
    fun schedule(context: Context, replace: Boolean = false) {
        val p = context.applicationContext.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        val on = if (SettingsStore.loaded) AppSettings.smartOffline.value else p.getBoolean("smartOffline", false)
        val charging = if (SettingsStore.loaded) AppSettings.smartOfflineCharging.value else p.getBoolean("smartOfflineCharging", true)
        val work = WorkManager.getInstance(context.applicationContext)
        if (!on) { work.cancelUniqueWork(WORK); work.cancelUniqueWork(WORK_SOON); return }
        val request = PeriodicWorkRequestBuilder<SmartOfflineWorker>(6, TimeUnit.HOURS).setConstraints(constraints(charging)).build()
        work.enqueueUniquePeriodicWork(WORK, if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A pass as soon as the conditions allow (a new favourite, the feature just turned on). */
    fun scheduleSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmartOfflineWorker>()
            .setConstraints(constraints(AppSettings.smartOfflineCharging.value)).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_SOON, ExistingWorkPolicy.REPLACE, request)
    }

    /** The settings changed: the schedule follows. */
    fun settingsChanged(context: Context) {
        runCatching { schedule(context, replace = true) }
        if (AppSettings.smartOffline.value) runCatching { scheduleSoon(context) }
    }

    /** The automatic downloads, newest first, for the list in Downloads. */
    fun autoItems(): List<Downloader.Item> = Downloader.items.value.filter { it.track.id in _auto.value }
}

/** The pass of « Hors ligne intelligent », under the constraints [SmartOffline.schedule] sets. */
class SmartOfflineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            if (!SettingsStore.loaded) SettingsStore.load(app)
            if (!AppSettings.smartOffline.value) return Result.success()
            ListeningHistory.init(app)
            PlaylistStore.init(app)
            WeeklyDiscoveries.init(app)
            SmartOffline.run(app) { !isStopped }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
