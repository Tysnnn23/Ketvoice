package com.ketvoice.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ketvoice.app.ui.sub.ArtistDiscography
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * New releases of the artists the listener follows (6.3): a notification when one of them puts out an album, a single
 * or an EP. The catalogue gives no release date, only a year, so a release is « new » when its id was not in the last
 * discography seen for that artist; the first time an artist is seen nothing is announced, it only becomes the
 * reference. At most [MAX_ARTISTS] artists are checked a day, a few seconds apart, and at most [MAX_ALERTS]
 * notifications are posted per run. Switch: Settings → Notifications, key `releaseAlerts`.
 */
object ReleaseWatch {
    const val MAX_ARTISTS = 25
    const val MAX_ALERTS = 3
    const val EXTRA_ARTIST_ID = "ketvoice.release.artist.id"
    const val EXTRA_ARTIST_NAME = "ketvoice.release.artist.name"
    private const val PREFS = "release-watch"
    private const val CHANNEL = "new-releases"
    private const val NAME = "release-watch"
    private const val KEEP_IDS = 120

    /** A release found for a followed artist. */
    data class Found(val artist: String, val artistId: String, val album: Album)

    /**
     * The releases of [albums] that were not in [known] and are recent: from last year on when their year is known.
     * With no [known] list (the artist is new to the watch) nothing is announced.
     */
    internal fun fresh(known: Set<String>?, albums: List<Album>, thisYear: Int): List<Album> {
        if (known == null) return emptyList()
        return albums.filter { it.id.isNotBlank() && it.id !in known && (ArtistDiscography.year(it)?.let { y -> y >= thisYear - 1 } ?: true) }
    }

    /** What to remember for an artist after a check: every id seen, newest first, bounded. */
    internal fun remembered(known: Set<String>?, albums: List<Album>): List<String> =
        (albums.map { it.id } + known.orEmpty()).filter { it.isNotBlank() }.distinct().take(KEEP_IDS)

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        val request = PeriodicWorkRequestBuilder<ReleaseWatchWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One pass over the followed artists. Returns what was announced. */
    internal suspend fun check(context: Context, artists: List<ArtistPrefs.Entry>, fetch: suspend (String) -> ArtistPage?,
                               thisYear: Int, pause: Long = 1_500L): List<Found> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val found = mutableListOf<Found>()
        for (entry in artists.filter { it.id.isNotBlank() }.take(MAX_ARTISTS)) {
            val page = runCatching { fetch(entry.id) }.getOrNull() ?: continue
            if (page.albums.isEmpty()) continue
            val known = prefs.getString(entry.id, null)?.let { decode(it) }
            if (found.size < MAX_ALERTS) fresh(known, page.albums, thisYear).take(MAX_ALERTS - found.size)
                .forEach { found += Found(entry.name, entry.id, it) }
            prefs.edit().putString(entry.id, JSONArray(remembered(known, page.albums)).toString()).apply()
            delay(pause)
        }
        return found
    }

    private fun decode(text: String): Set<String> = runCatching {
        JSONArray(text).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    }.getOrDefault(emptySet())

    fun announce(context: Context, found: Found) {
        val app = context.applicationContext
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, AppText.t("Nouvelles sorties"), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, found.album.id.hashCode(),
                Intent(app, com.ketvoice.app.MainActivity::class.java).setAction("ketvoice.release.open")
                    .putExtra(EXTRA_ARTIST_ID, found.artistId).putExtra(EXTRA_ARTIST_NAME, found.artist)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(com.ketvoice.app.R.drawable.ic_widget_play)
                .setContentTitle(found.artist)
                .setContentText(AppText.t("Nouvelle sortie : %s").replace("%s", found.album.title))
                .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION).build()
            manager.notify(1000 + (found.album.id.hashCode() and 0xFFFF), notification)
        } catch (_: RuntimeException) {
            // Notifications refused at that moment: the next pass has nothing new to say, the artist page shows the release.
        }
    }
}

class ReleaseWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return runCatching {
            SettingsStore.load(app)
            if (!AppSettings.releaseAlerts.value) return@runCatching Result.success()
            ArtistPrefs.init(app)
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            ReleaseWatch.check(app, ArtistPrefs.favourites, { YouTubeCatalog.artist(it) }, year).forEach { ReleaseWatch.announce(app, it) }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
