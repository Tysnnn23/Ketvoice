package com.ketvoice.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class KetvoiceApp : Application(), ImageLoaderFactory {

    companion object {
        @JvmStatic
        lateinit var instance: KetvoiceApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.ketvoice.app.data.AppText.init(this)
        // The voice separation model (optional download) and the separations already made: file checks only.
        runCatching { com.ketvoice.app.stems.StemModel.init(this) }
        // Downloaded titles are prepared for Ketmix while the phone charges (waits 20 s, then works on its own thread).
        runCatching { com.ketvoice.app.stems.StemLibrary.init(this) }
        runCatching { com.ketvoice.app.stems.VoiceSeparation.init(this) }
        runCatching { com.ketvoice.app.data.PlaybackJournal.init(this) }
        // The week's discoveries (6.1): read at once, renewed in the background on an unmetered network.
        runCatching { com.ketvoice.app.data.WeeklyDiscoveries.init(this) }
        if (android.os.Build.FINGERPRINT != "robolectric") runCatching { com.ketvoice.app.data.DiscoveryWork.schedule(this) }
        // Keeps a placed home-screen widget in step with playback (no work when none is placed).
        if (!BuildConfig.IS_QUEST) runCatching { com.ketvoice.app.widget.NowPlayingWidgetUpdater.start(this) }
        if (!BuildConfig.IS_QUEST) runCatching { com.ketvoice.app.widget.KetvoiceWidgetsUpdater.start(this) }
        // Bound the re-fetchable caches once per process, off the main thread and after startup.
        Thread({
            runCatching { Thread.sleep(20_000); com.ketvoice.app.data.DiskBudget.trimAll(this) }
        }, "ketvoice-disk-budget").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Away from the screen on battery, the separation models (up to a few hundred MB once used) are let go:
     * battery managers such as MagicOS stop the biggest processes first, music or not (6.0.0 Beta 5).
     * They load again the next time a separation runs. Memory warnings go to the playback journal.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryRelief.note(level)?.let { com.ketvoice.app.data.PlaybackJournal.add(it) }
        if (MemoryRelief.releaseModels(level, com.ketvoice.app.stems.StemModel.charging()))
            Thread({ runCatching { com.ketvoice.app.stems.StemEngine.close() } }, "ketvoice-release-models")
                .apply { isDaemon = true }.start()
    }

    override fun newImageLoader(): ImageLoader {
        val dir = File(filesDir, "images").apply { if (!exists()) mkdirs() }
        return ImageLoader.Builder(this)
            .components { add(com.ketvoice.app.data.PermanentImageInterceptor(this@KetvoiceApp)) }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(dir)
                    .maxSizeBytes(2L * 1024L * 1024L * 1024L)
                    .build()
            }
            // In the performance mode images appear at once instead of fading in: one animated layer less per image.
            .crossfade(!runCatching { getSharedPreferences("ketvoice_settings", MODE_PRIVATE).getBoolean("highPerformanceMode", false) }.getOrDefault(false))
            .build()
    }
}

internal object MemoryRelief {
    @Suppress("DEPRECATION")
    fun releaseModels(level: Int, charging: Boolean): Boolean =
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !charging ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE

    @Suppress("DEPRECATION")
    fun note(level: Int): String? = when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "memory: the phone is running low"
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "memory: the phone is critically low"
        android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "memory: Ketvoice is next in line to be stopped"
        else -> null
    }
}
