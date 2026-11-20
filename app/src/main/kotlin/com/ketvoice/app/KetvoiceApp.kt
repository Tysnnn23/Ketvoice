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
        // Every process keeps its crash reports from the very start (6.1.1).
        runCatching { com.ketvoice.app.util.Crash.install(this) }
        // Ketvoice Lite: budgets follow the phone's memory (LiteTuning). Since 6.6 the mobile edition finds a modest
        // phone too, for the rules that change nothing one sees; Quest keeps its values.
        if (!BuildConfig.IS_QUEST) runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val gl = runCatching { am.deviceConfigurationInfo.reqGlEsVersion }.getOrDefault(0)
            LiteTuning.device = LiteTuning.device(am.isLowRamDevice, info.totalMem, gl)
            LiteTuning.modest = LiteTuning.device.modest
        }
        // The work process (6.1, separation and Ketmix's preparation): only what its work reads, nothing that plays,
        // shows or writes the app's settings and journal.
        if (WorkProcess.isWork(getProcessName(), packageName)) {
            runCatching { com.ketvoice.app.stems.StemModel.init(this) }
            return
        }
        com.ketvoice.app.data.AppText.init(this)
        // The listener's language is read beside the main thread, before the first screen asks for a text (7.6).
        if (android.os.Build.FINGERPRINT != "robolectric") com.ketvoice.app.data.AppText.warm(this)
        // The settings and the lists the first screen reads are loaded beside the main thread, which would wait for them.
        com.ketvoice.app.util.PrefsWarmup.start(this)
        // What the first screen does not need starts after it: a few seconds later in Lite, 1.5 s later elsewhere (6.2.2).
        val later = LiteTuning.deferred()
        val delayMs = LiteTuning.deferredStartMs()
        fun start(work: LiteTuning.StartWork, block: () -> Unit) {
            // Host tests (Robolectric) start it at once: a task posted for later would land in the middle of a test.
            if (work !in later || android.os.Build.FINGERPRINT == "robolectric") runCatching(block)
            else android.os.Handler(mainLooper).postDelayed({ runCatching(block) }, delayMs)
        }
        // The voice separation model (optional download) and the separations already made: file checks only.
        runCatching { com.ketvoice.app.stems.StemModel.init(this) }
        // Downloaded titles are prepared for Ketmix while the phone charges (waits 20 s, then works on its own thread).
        start(LiteTuning.StartWork.StemLibrary) { com.ketvoice.app.stems.StemLibrary.init(this) }
        runCatching { com.ketvoice.app.stems.VoiceSeparation.init(this) }
        runCatching { com.ketvoice.app.data.PlaybackJournal.init(this) }
        // Weekly discoveries were removed in 7.4: their background pass and their saved week go with them.
        if (android.os.Build.FINGERPRINT != "robolectric")
            start(LiteTuning.StartWork.DiscoverySchedule) {
                runCatching { androidx.work.WorkManager.getInstance(this).cancelUniqueWork("weekly-discoveries") }
                runCatching { deleteSharedPreferences("weekly-discoveries") }
            }
        // New releases of the followed artists (6.3): checked twice a day in the background.
        if (android.os.Build.FINGERPRINT != "robolectric")
            start(LiteTuning.StartWork.DiscoverySchedule) { com.ketvoice.app.data.ReleaseWatch.schedule(this) }
        // « Hors ligne intelligent » (7.0): its passes on an unmetered network, scheduled while it is on, cancelled otherwise.
        if (android.os.Build.FINGERPRINT != "robolectric")
            start(LiteTuning.StartWork.DiscoverySchedule) { com.ketvoice.app.data.SmartOffline.schedule(this) }
        // The Canvas of favourites and playlists are fetched ahead on Wi-Fi (6.2.3): its own coroutine, first run 45 s after the start.
        if (android.os.Build.FINGERPRINT != "robolectric") runCatching { com.ketvoice.app.data.CanvasPreload.start(this) }
        // Keeps the placed home-screen widgets in step with playback (7.4: no drawing while none is placed).
        if (!BuildConfig.IS_QUEST) start(LiteTuning.StartWork.Widgets) { com.ketvoice.app.widget.NowPlayingWidgets.start(this) }
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
        // 6.1: the covers held in memory (up to a fifth of the app's memory) go once no screen of the app is shown:
        // the playing process stays small behind other apps, and they come back from the disk cache when it returns.
        // Ketvoice Lite on a modest device lets them go at the first warning (LiteTuning.releaseImagesAt).
        val images = LiteTuning.releaseImagesAt(level)
        if (images && !WorkProcess.isWork(getProcessName(), packageName))
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
    }

    override fun newImageLoader(): ImageLoader {
        val dir = File(filesDir, "images").apply { if (!exists()) mkdirs() }
        return ImageLoader.Builder(this)
            .components {
                add(com.ketvoice.app.data.PermanentImageInterceptor(this@KetvoiceApp))
                // No picture is decoded larger than the screen (LiteTuning.decodeBoundPx; every edition since 6.6).
                val metrics = resources.displayMetrics
                LiteTuning.decodeBoundPx(maxOf(metrics.widthPixels, metrics.heightPixels))?.let { add(DecodeBound(it)) }
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(LiteTuning.imageMemoryPercent())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(dir)
                    .maxSizeBytes(LiteTuning.imageDiskBytes())
                    .build()
            }
            .apply { LiteTuning.imageDecoders()?.let { bitmapFactoryMaxParallelism(it) } }
            // Lite on a modest device: covers without transparency in RGB_565, half the memory.
            .allowRgb565(LiteTuning.imageRgb565())
            // In the performance mode images appear at once instead of fading in: one animated layer less per image.
            .crossfade(!runCatching {
                getSharedPreferences("ketvoice_settings", MODE_PRIVATE)
                    .getBoolean("highPerformanceMode", LiteTuning.defaultPerformanceMode())
            }.getOrDefault(LiteTuning.defaultPerformanceMode()))
            .build()
    }
}

/** Bounds a decode to [bound] pixels on its longer side, a request without a size included. */
internal class DecodeBound(private val bound: Int) : coil.intercept.Interceptor {
    override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
        val w = (chain.size.width as? coil.size.Dimension.Pixels)?.px ?: -1
        val h = (chain.size.height as? coil.size.Dimension.Pixels)?.px ?: -1
        val (bw, bh) = LiteTuning.boundedDecode(w, h, bound)
        if (bw == w && bh == h) return chain.proceed(chain.request)
        return chain.withSize(coil.size.Size(bw, bh)).proceed(chain.request)
    }
}

internal object WorkProcess {
    /** The name of the work process, as declared for StemWorkService in the manifest. */
    const val SUFFIX = ":stems"
    fun isWork(processName: String?, packageName: String): Boolean = processName == packageName + SUFFIX
}

internal object MemoryRelief {
    @Suppress("DEPRECATION")
    fun releaseModels(level: Int, charging: Boolean): Boolean =
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !charging ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE

    /** The images kept in memory go when the app's screens are hidden, and whenever memory runs low. */
    @Suppress("DEPRECATION")
    fun releaseImages(level: Int): Boolean = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW // low, critical, UI hidden and above

    @Suppress("DEPRECATION")
    fun note(level: Int): String? = when (level) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "memory: the phone is running low"
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "memory: the phone is critically low"
        android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "memory: Ketvoice is next in line to be stopped"
        else -> null
    }
}
