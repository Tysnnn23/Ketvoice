package com.ketvoice.app

/**
 * The three Ketvoice APKs published side by side. Each one updates itself to its own asset of a release
 * (`Ketvoice-<version>.apk`, `-Quest.apk`, `-Lite.apk`), never to another edition's.
 */
enum class Edition(val assetSuffix: String) {
    Mobile(""), Quest("-Quest"), Lite("-Lite");

    /** The asset of [version] this edition installs, e.g. `Ketvoice-6.1.0-Lite.apk`. */
    fun assetName(version: String): String = "Ketvoice-$version$assetSuffix.apk"

    /** Shown after the version in Settings and in a shared report: nothing for mobile, " · Quest", " · Lite". */
    val label: String get() = if (this == Mobile) "" else " · $name"

    companion object {
        val current: Edition = of(BuildConfig.IS_QUEST, BuildConfig.IS_LITE)

        fun of(quest: Boolean, lite: Boolean): Edition = when {
            quest -> Quest
            lite -> Lite
            else -> Mobile
        }
    }
}

/**
 * Ketvoice Lite (6.1; owner, 2026-11-17: « App plus rapide et légère sera un autre apk que tu va créer et qui
 * aura les meme effet mais sera adapté »): the phone app with the same features and the same effects, packaged
 * and tuned for modest phones. Everything here is a pure function of the edition (and of the phone's memory),
 * so the choices are host-tested; the mobile and Quest editions keep exactly the values they had.
 *
 * What stays: glass blur, Canvas, the lyrics animations, Ketmix, the voice level. What is lighter by default is
 * what the performance mode (5.9.2) already defines as barely visible, plus memory and work budgets.
 */
object LiteTuning {
    val lite: Boolean get() = BuildConfig.IS_LITE

    /**
     * A phone with little memory: Android's own low-RAM flag, or about 4 GB or less. Set once at start by
     * [KetvoiceApp]; the budgets below read it.
     */
    @Volatile var modest: Boolean = false
        internal set

    const val MODEST_TOTAL_BYTES = 4_400_000_000L

    fun modest(lowRamDevice: Boolean, totalMemBytes: Long): Boolean =
        lowRamDevice || (totalMemBytes in 1 until MODEST_TOTAL_BYTES)

    // ------------------------------------------------------------------ settings defaults (fresh installs)
    // A listener's saved choices always win: switching edition keeps the settings (same application id).

    /** The performance mode: on by default in Lite (glass keeps its blur, Canvas keeps playing). */
    fun defaultPerformanceMode(lite: Boolean = this.lite): Boolean = lite

    /** Separation model `ketmixStemsQuality`: 0 automatic in mobile, 2 always the quick one in Lite. */
    fun defaultStemsQuality(lite: Boolean = this.lite): Int = if (lite) 2 else 0

    // ------------------------------------------------------------------ images (Coil)

    /** Share of the app's memory the decoded covers may take. */
    fun imageMemoryPercent(lite: Boolean = this.lite, modest: Boolean = this.modest): Double = when {
        !lite -> 0.20
        modest -> 0.10
        else -> 0.14
    }

    /** Covers kept on the disk (re-fetchable; saved covers live apart in `artwork-permanent`). */
    fun imageDiskBytes(lite: Boolean = this.lite): Long = if (lite) 512L * 1024L * 1024L else 2L * 1024L * 1024L * 1024L

    /** Covers decoded at the same time: fewer large bitmaps in memory at once on a modest phone. Null keeps Coil's default. */
    fun imageDecoders(lite: Boolean = this.lite): Int? = if (lite) 2 else null

    // ------------------------------------------------------------------ Ketmix

    /** Titles Ketmix prepares ahead (their intro and their ending), before the metered-network rule (1). */
    fun ketmixLookahead(lite: Boolean = this.lite, modest: Boolean = this.modest, full: Int = 3): Int = when {
        !lite -> full
        modest -> 1
        else -> 2
    }

    // ------------------------------------------------------------------ Canvas

    /**
     * Buffer of a Canvas (a short silent loop, often a cached file): Media3's default keeps up to 50 s of
     * video in memory; Lite keeps a few seconds, enough for a loop. Null keeps the default.
     */
    data class CanvasBuffer(val minMs: Int, val maxMs: Int, val playMs: Int, val rebufferMs: Int, val bytes: Int)

    fun canvasBuffer(lite: Boolean = this.lite): CanvasBuffer? =
        if (lite) CanvasBuffer(minMs = 4_000, maxMs = 10_000, playMs = 1_000, rebufferMs = 1_500, bytes = 6 * 1024 * 1024) else null

    // ------------------------------------------------------------------ packaged images

    /**
     * The release banner of the update dialog, packaged as an asset: a 1.8 MB PNG in mobile and Quest
     * (src/standard/assets), a high-quality WebP of it in Lite (src/lite/assets, tools/lite/make_lite_resources.py).
     */
    fun bannerAsset(lite: Boolean = this.lite): String = if (lite) "release_banner.webp" else "release_banner.png"

    // ------------------------------------------------------------------ start

    /**
     * Work that need not run before the first screen: in Lite it starts a few seconds later, once the
     * first frame is drawn (a placed widget is brought up to date then; weekly discoveries are scheduled then).
     */
    enum class StartWork { StemLibrary, DiscoverySchedule, NowPlayingWidget, Widgets }

    const val DEFERRED_START_MS = 4_000L

    /** Mobile and Quest start the same work too, sooner (6.2.2): the first frame comes first everywhere. */
    const val QUICK_DEFERRED_START_MS = 1_500L

    fun deferred(lite: Boolean = this.lite): Set<StartWork> = StartWork.entries.toSet()

    fun deferredStartMs(lite: Boolean = this.lite): Long = if (lite) DEFERRED_START_MS else QUICK_DEFERRED_START_MS
}
