package com.ketvoice.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * « Playlists téléchargées à jour » (8.4): a playlist the listener downloaded that gains titles (from another of
 * their devices through the account's sync, or a shared playlist someone else edits) is spotted in the background,
 * and a notification « Nouveaux sons dans X » downloads only the new titles when touched.
 *
 * A playlist is followed once the listener downloaded it from its menu, or once every one of its titles is on the
 * device; it is no longer followed when none of its titles is on the device or in the download queue. What a check knows of a playlist is
 * the titles it held at the last check, so each new title is announced once; titles the listener adds themselves are
 * never announced. Nothing is ever deleted here: a title removed from the playlist keeps its download (the rules of
 * « Hors ligne intelligent » are untouched, and the new titles are downloaded as the listener's own).
 * Switch: Settings → Downloads, key `playlistUpdates` (on).
 */
object PlaylistUpdates {
    const val ACTION = "ketvoice.playlist.update"
    const val EXTRA_PLAYLIST = "ketvoice.playlist.id"
    const val EXTRA_TRACKS = "ketvoice.playlist.tracks"
    const val MAX_ALERTS = 3
    private const val PREFS = "playlist-updates"
    private const val KNOWN = "known"
    private const val CHANNEL = "playlist-updates"
    private const val WORK = "playlist-updates"
    private const val WORK_SOON = "playlist-updates-soon"

    /** New titles of one downloaded playlist: what a notification carries. */
    data class Update(val playlistId: String, val name: String, val trackIds: List<String>)

    /** What a check finds, and what it remembers afterwards (playlist id → its titles at this check). */
    data class Check(val updates: List<Update>, val known: Map<String, Set<String>>)

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var app: Context? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (app == null) app = context.applicationContext
        PlaylistStore.ownAdditionHook = ::noteAdded
        PlaylistStore.elsewhereHook = ::changedElsewhere
    }

    /** The playlists changed from elsewhere (the account's sync, a shared playlist): a check soon, if the app set one up. */
    fun changedElsewhere() { val context = app ?: return; runCatching { checkSoon(context) } }

    private fun ids(playlist: LocalPlaylist): List<String> =
        playlist.entries.mapNotNull { it.track?.id }.filter { it.isNotBlank() && !it.startsWith("local:") }.distinct()

    /**
     * The check itself, without Android: for every followed playlist, the titles not known at the last check and not
     * on the device or on their way ([downloaded]). A playlist not followed yet starts to be when it is wholly
     * downloaded (nothing announced then).
     */
    fun diff(playlists: List<LocalPlaylist>, downloaded: Set<String>, known: Map<String, Set<String>>): Check {
        val next = HashMap<String, Set<String>>()
        val updates = mutableListOf<Update>()
        for (playlist in playlists) {
            val titles = ids(playlist)
            val before = known[playlist.id]
            if (before == null) {
                if (titles.isNotEmpty() && titles.all { it in downloaded }) next[playlist.id] = titles.toSet()
                continue
            }
            // Every download of it removed: the listener let it go.
            if (titles.isNotEmpty() && titles.none { it in downloaded }) continue
            val fresh = titles.filter { it !in before && it !in downloaded }
            if (fresh.isNotEmpty()) updates += Update(playlist.id, playlist.name, fresh)
            next[playlist.id] = titles.toSet()
        }
        return Check(updates, next)
    }

    // ------------------------------------------------------------------ what is remembered

    internal fun decode(text: String?): Map<String, Set<String>> = runCatching {
        val root = JSONObject(text ?: return emptyMap())
        root.keys().asSequence().associateWith { key ->
            root.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        }
    }.getOrDefault(emptyMap())

    internal fun encode(known: Map<String, Set<String>>): String =
        JSONObject().apply { known.forEach { (id, titles) -> put(id, JSONArray(titles.sorted())) } }.toString()

    private fun known(): Map<String, Set<String>> = decode(prefs?.getString(KNOWN, null))

    @Synchronized private fun remember(change: (Map<String, Set<String>>) -> Map<String, Set<String>>) {
        val p = prefs ?: return
        p.edit().putString(KNOWN, encode(change(known()))).apply()
    }

    /** « Télécharger » from a playlist's menu: the playlist is followed from now on, with the titles it holds. */
    fun follow(playlist: LocalPlaylist) = remember { it + (playlist.id to ids(playlist).toSet()) }

    /** Titles the listener added to a playlist themselves: never announced as new. */
    fun noteAdded(playlistId: String, trackIds: Collection<String>) = remember { known ->
        val before = known[playlistId] ?: return@remember known
        known + (playlistId to before + trackIds)
    }

    /** The titles on this device, and those waiting in the download queue: they are on their way. */
    private fun downloadedHere(): Set<String> = Downloader.items.value.mapTo(HashSet()) { it.track.id } + Downloader.busy.value

    /** One pass: what is new in the followed playlists. Reads the playlists and the downloads of this device. */
    @Synchronized fun check(context: Context, here: Set<String> = downloadedHere()): List<Update> {
        init(context)
        val result = diff(PlaylistStore.lists.value, here, known())
        prefs?.edit()?.putString(KNOWN, encode(result.known))?.apply()
        return result.updates
    }

    // ------------------------------------------------------------------ notification

    /** What touching the notification of [update] asks the app: download these titles of this playlist. */
    fun intent(context: Context, update: Update): Intent =
        Intent(context, com.ketvoice.app.MainActivity::class.java).setAction(ACTION)
            .putExtra(EXTRA_PLAYLIST, update.playlistId)
            .putExtra(EXTRA_TRACKS, update.trackIds.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun title(update: Update): String = AppText.t("Nouveaux sons dans {0}").replace("{0}", update.name)

    fun text(update: Update): String = if (update.trackIds.size == 1) AppText.t("1 nouveau titre : touchez pour le télécharger")
        else AppText.t("{0} nouveaux titres : touchez pour les télécharger").replace("{0}", update.trackIds.size.toString())

    fun notificationId(update: Update): Int = 3000 + (update.playlistId.hashCode() and 0xFFFF)

    fun announce(context: Context, update: Update) {
        val app = context.applicationContext
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, AppText.t("Playlists téléchargées"), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, notificationId(update), intent(app, update),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(com.ketvoice.app.R.drawable.ic_widget_play)
                .setContentTitle(title(update)).setContentText(text(update))
                .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION).build()
            manager.notify(notificationId(update), notification)
        } catch (_: RuntimeException) {
            // Refused at that moment: the playlist page still shows the titles to download.
        }
    }

    /**
     * The notification was touched: the new titles still in the playlist and not on the device are downloaded, as the
     * listener's own downloads. Returns how many were started.
     */
    fun downloadNew(playlistId: String, trackIds: Collection<String>): Int {
        val wanted = trackIds.toSet()
        val playlist = PlaylistStore.lists.value.firstOrNull { it.id == playlistId } ?: return 0
        val tracks = playlist.entries.mapNotNull { it.track }.distinctBy { it.id }
            .filter { it.id in wanted && Downloader.itemFor(it.id) == null }
        tracks.forEach { Downloader.download(it) }
        return tracks.size
    }

    // ------------------------------------------------------------------ scheduling

    private fun network(): NetworkType = if (AppSettings.downloadsWifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(network()).setRequiresBatteryNotLow(true).build()

    /**
     * Every six hours while it is on, on the network the downloads may use; cancelled when it is off. [replace]: a
     * setting changed (the switch, « Wi-Fi uniquement »); at the start the running schedule is kept.
     */
    fun schedule(context: Context, replace: Boolean = false) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (!AppSettings.playlistUpdates.value) { work.cancelUniqueWork(WORK); work.cancelUniqueWork(WORK_SOON); return }
        val request = PeriodicWorkRequestBuilder<PlaylistUpdatesWorker>(6, TimeUnit.HOURS).setConstraints(constraints()).build()
        work.enqueueUniquePeriodicWork(WORK, if (replace) ExistingPeriodicWorkPolicy.REPLACE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Playlists changed from elsewhere (the sync, a shared playlist): a check a few minutes later. */
    fun checkSoon(context: Context) {
        if (!AppSettings.playlistUpdates.value) return
        val request = OneTimeWorkRequestBuilder<PlaylistUpdatesWorker>().setConstraints(constraints())
            .setInitialDelay(3, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_SOON, ExistingWorkPolicy.REPLACE, request)
    }
}

/** The background check of [PlaylistUpdates]: at most [PlaylistUpdates.MAX_ALERTS] notifications a pass. */
class PlaylistUpdatesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return runCatching {
            if (!SettingsStore.loaded) SettingsStore.load(app)
            if (!AppSettings.playlistUpdates.value) return@runCatching Result.success()
            PlaylistStore.init(app)
            Downloader.init(app)
            PlaylistUpdates.check(app).take(PlaylistUpdates.MAX_ALERTS).forEach { PlaylistUpdates.announce(app, it) }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
