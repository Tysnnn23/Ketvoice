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
        // Keeps a placed home-screen widget in step with playback (no work when none is placed).
        if (!BuildConfig.IS_QUEST) runCatching { com.ketvoice.app.widget.NowPlayingWidgetUpdater.start(this) }
        if (!BuildConfig.IS_QUEST) runCatching { com.ketvoice.app.widget.KetvoiceWidgetsUpdater.start(this) }
        // Bound the re-fetchable caches once per process, off the main thread and after startup.
        Thread({
            runCatching { Thread.sleep(20_000); com.ketvoice.app.data.DiskBudget.trimAll(this) }
        }, "ketvoice-disk-budget").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
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
