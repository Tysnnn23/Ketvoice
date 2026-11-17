package com.ketvoice.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Makes the week's discoveries in the background, so they are ready on Monday before Home is
 * opened. It runs on an unmetered network only, with enough battery; on a metered network the
 * selection is made when Home or the car asks for it. Nothing is downloaded but the catalogue's
 * small answers.
 */
class WeeklyDiscoveriesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return runCatching {
            // Only the stores the selection reads: nothing that starts playback or downloads.
            ListeningHistory.init(app)
            ArtistPrefs.init(app)
            PlaylistStore.init(app)
            MusicApi.init(app)
            WeeklyDiscoveries.init(app)
            if (WeeklyDiscoveries.current() != null) return@runCatching Result.success()
            val base = WeeklyDiscoveries.appTaste()
            val favorites = base.favorites.ifEmpty {
                app.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).getStringSet("favoriteIds", emptySet())?.toSet().orEmpty()
            }
            val taste = base.copy(favorites = favorites, library = base.library + DiscoveryWork.downloadedTitles(app))
            if (taste.totalPlays < WeeklyDiscoveries.MIN_PLAYS) return@runCatching Result.success()
            if (WeeklyDiscoveries.ensure(taste, WeeklyDiscoveries.AppSources) != null) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
    }
}

internal object DiscoveryWork {
    private const val NAME = "weekly-discoveries"

    /** Every twelve hours: the first run after Monday's start makes the week's selection, the others find it made. */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<WeeklyDiscoveriesWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Titles kept offline, read from their small descriptions without starting the downloader. */
    fun downloadedTitles(context: Context): List<Track> =
        File(context.filesDir, "downloads").listFiles { f -> f.extension == "json" && !f.name.endsWith(".lyrics.json") }
            ?.mapNotNull { file ->
                runCatching {
                    val o = JSONObject(file.readText(Charsets.UTF_8))
                    Track(o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"))
                }.getOrNull()
            }.orEmpty()
}
