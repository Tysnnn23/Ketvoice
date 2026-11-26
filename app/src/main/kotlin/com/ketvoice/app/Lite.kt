package com.ketvoice.app

/**
 * The Ketvoice APKs. Each one updates itself to its own asset, never to another edition's: mobile, Quest and Lite
 * share the `v<version>` releases (`Ketvoice-<version>.apk`, `-Quest.apk`, `-Lite.apk`); Ketvoice TV has its own
 * package (`com.ketvoice.tv`), version line and `tv-v<version>` releases (`Ketvoice-TV-<version>.apk`).
 */
enum class Edition(private val assetPrefix: String, val assetSuffix: String, val tagPrefix: String) {
    Mobile("Ketvoice-", "", "v"), Quest("Ketvoice-", "-Quest", "v"), Lite("Ketvoice-", "-Lite", "v"),
    Tv("Ketvoice-TV-", "", "tv-v");

    /** The asset of [version] this edition installs, e.g. `Ketvoice-6.1.0-Lite.apk` or `Ketvoice-TV-1.0.0.apk`. */
    fun assetName(version: String): String = "$assetPrefix$version$assetSuffix.apk"

    /** Shown after the version in Settings and in a shared report: nothing for mobile, " · Quest", " · Lite", " · TV". */
    val label: String get() = when (this) {
        Mobile -> ""
        Tv -> " · TV"
        else -> " · $name"
    }

    companion object {
        val current: Edition = of(BuildConfig.IS_QUEST, BuildConfig.IS_LITE, BuildConfig.IS_TV)

        fun of(quest: Boolean, lite: Boolean, tv: Boolean = false): Edition = when {
            tv -> Tv
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
     * A modest phone or tablet (owner, 2026-11-18: « support complet pour les tablettes/téléphones avec peu de RAM
     * etc., mais qui garde les mêmes effets »): Android's own low-RAM flag, about 3 GB or less, or about 4 GB with
     * a modest graphics chip (no OpenGL ES 3.2). Every visible effect stays; what changes is how it is made
     * (smaller offscreen pictures, smaller decodes and caches, one Canvas at a time, separation on the quick
     * model away from the charger). Set once at start by [KetvoiceApp]; the budgets below read it. Since 6.6 it is
     * found in the mobile edition too, where it drives only the rules that change nothing one sees (decoders, one
     * Canvas at a time and its size, images let go early, small blurred pictures); never on Quest.
     */
    @Volatile var modest: Boolean = false
        internal set

    /** What [KetvoiceApp] found about this device at start (shown in Settings → À propos in Lite). */
    @Volatile var device: Device = Device(modest = false, why = Why.Roomy, gigabytes = 0)
        internal set

    /** Why a device is modest, or not. */
    enum class Why { LowRamFlag, Memory, MemoryAndGraphics, Roomy }

    data class Device(val modest: Boolean, val why: Why, val gigabytes: Int)

    /** About 3 GB: phones sold with 3 GB report 2.7 to 3.0 GB to apps. */
    const val SMALL_TOTAL_BYTES = 3_300_000_000L

    /** About 4 GB (3.6 to 3.9 GB reported). */
    const val MODEST_TOTAL_BYTES = 4_400_000_000L

    /** OpenGL ES 3.2 (`ConfigurationInfo.reqGlEsVersion`): graphics chips without it are the older, slower ones. */
    const val GLES_3_2 = 0x30002

    fun device(lowRamDevice: Boolean, totalMemBytes: Long, glEsVersion: Int = 0): Device {
        val gigabytes = marketedGigabytes(totalMemBytes)
        val known = totalMemBytes > 0
        return when {
            lowRamDevice -> Device(true, Why.LowRamFlag, gigabytes)
            known && totalMemBytes < SMALL_TOTAL_BYTES -> Device(true, Why.Memory, gigabytes)
            known && totalMemBytes < MODEST_TOTAL_BYTES && glEsVersion < GLES_3_2 -> Device(true, Why.MemoryAndGraphics, gigabytes)
            else -> Device(false, Why.Roomy, gigabytes)
        }
    }

    /** [glEsVersion] 0 (unknown) counts as a modest chip, as before 6.5 every 4 GB phone was modest. */
    fun modest(lowRamDevice: Boolean, totalMemBytes: Long, glEsVersion: Int = 0): Boolean =
        device(lowRamDevice, totalMemBytes, glEsVersion).modest

    /** The memory a phone is sold with (2, 3, 4, 6 GB…) from what Android reports, which is always a little less. */
    fun marketedGigabytes(totalMemBytes: Long): Int {
        if (totalMemBytes <= 0) return 0
        val reported = totalMemBytes / 1e9
        return listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32).firstOrNull { it >= reported } ?: kotlin.math.ceil(reported).toInt()
    }

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

    /**
     * Covers decoded at the same time: fewer large bitmaps in memory at once in Lite and, since 6.6, on a modest
     * phone in the mobile edition too. Null keeps Coil's default.
     */
    fun imageDecoders(lite: Boolean = this.lite, modest: Boolean = this.modest): Int? = if (lite || modest) 2 else null

    /** Opaque pictures (covers) in RGB_565 on a modest device: half the memory, the same look for a photo. */
    fun imageRgb565(lite: Boolean = this.lite, modest: Boolean = this.modest): Boolean = lite && modest

    /**
     * The largest side a picture is decoded at: the screen's longer side. A request without a size (an image in an
     * unbounded row, a full-size request) is bounded by it too. Lite on a modest device only until 6.5; since 6.6
     * every edition, as nothing is ever shown larger than the screen, and a full-size cover (up to 3000 px, 36 MB)
     * took its decode and its upload to the graphics chip in the middle of a scroll. Null: no bound (Coil's size).
     */
    fun decodeBoundPx(screenLongSidePx: Int): Int? = screenLongSidePx.takeIf { it > 0 }

    /** A requested decode of [width] × [height] (≤ 0: no size given), bounded to [bound] on its longer side. */
    fun boundedDecode(width: Int, height: Int, bound: Int): Pair<Int, Int> {
        if (width <= 0 && height <= 0) return bound to bound
        if (width <= 0) return bound to minOf(height, bound)
        if (height <= 0) return minOf(width, bound) to bound
        val longer = maxOf(width, height)
        if (longer <= bound) return width to height
        val scale = bound.toDouble() / longer
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

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

    // The three Canvas rules below hold on a modest device in Lite and, since 6.6, in the mobile edition too
    // ([modest] is only ever found in those two): one video at a time already shows the same, the newest one.

    /** Canvas videos playing at once: one on a modest device (one video decoder), the newest shown. */
    fun canvasAtOnce(modest: Boolean = this.modest): Int = if (modest) 1 else Int.MAX_VALUE

    /**
     * The largest Canvas rendition chosen on a modest device (720 × 1280, what every hardware decoder plays), and
     * a fallback decoder when the hardware one refuses a file. Null keeps Media3's choice.
     */
    fun canvasMaxVideo(modest: Boolean = this.modest): Pair<Int, Int>? = if (modest) 720 to 1280 else null

    /** A Canvas lets its decoder go while the app is out of sight on a modest device (it starts again on return). */
    fun canvasReleasedWhenHidden(modest: Boolean = this.modest): Boolean = modest

    // ------------------------------------------------------------------ separation

    /**
     * The separation model setting actually used (`ketmixStemsQuality`: 0 automatic, 1 always the best, 2 always
     * quick): away from the charger a modest device separates with the quick model only, whatever the setting.
     */
    fun separationSetting(setting: Int, charging: Boolean, lite: Boolean = this.lite, modest: Boolean = this.modest): Int =
        if (lite && modest && !charging) 2 else setting

    // ------------------------------------------------------------------ memory warnings

    /**
     * Whether the pictures kept in memory go at this `onTrimMemory` level: on a modest device from the first
     * warning (running moderate), elsewhere when memory runs low (see MemoryRelief).
     */
    @Suppress("DEPRECATION")
    fun releaseImagesAt(level: Int, modest: Boolean = this.modest): Boolean =
        if (modest) level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
        else level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    // ------------------------------------------------------------------ Settings → À propos

    /**
     * The line shown in Settings → À propos in Lite: which mode is active and why. French source text, read
     * through AppText.t (the `{0}` templates are translated).
     */
    fun aboutLine(device: Device = this.device, tablet: Boolean = false): String {
        val gb = device.gigabytes
        return when (device.why) {
            Why.LowRamFlag -> "Android indique peu de mémoire sur cet appareil : effets allégés, identiques à l'œil"
            Why.Memory -> if (tablet) "Cette tablette a $gb Go de mémoire : effets allégés, identiques à l'œil"
                else "Ce téléphone a $gb Go de mémoire : effets allégés, identiques à l'œil"
            Why.MemoryAndGraphics -> if (tablet) "Cette tablette a $gb Go de mémoire et une puce graphique modeste : effets allégés, identiques à l'œil"
                else "Ce téléphone a $gb Go de mémoire et une puce graphique modeste : effets allégés, identiques à l'œil"
            Why.Roomy -> if (gb <= 0) "Mémoire suffisante : tous les effets, budgets de Ketvoice Lite"
                else if (tablet) "Cette tablette a $gb Go de mémoire : tous les effets, budgets de Ketvoice Lite"
                else "Ce téléphone a $gb Go de mémoire : tous les effets, budgets de Ketvoice Lite"
        }
    }

    /** The second line on Android 11, which has no system blur. */
    const val ANDROID_11_LINE = "Android 11 : le flou du verre est calculé par Ketvoice, même rendu"

    // ------------------------------------------------------------------ packaged images

    /**
     * The release banner of the update dialog, packaged as an asset: a 1.8 MB PNG in mobile and Quest
     * (src/standard/assets), a high-quality WebP of it in Lite (src/lite/assets, tools/lite/make_lite_resources.py).
     */
    fun bannerAsset(lite: Boolean = this.lite): String = if (lite) "release_banner.webp" else "release_banner.png"

    // ------------------------------------------------------------------ start

    /**
     * Work that need not run before the first screen: in Lite it starts a few seconds later, once the
     * first frame is drawn (a placed widget is brought up to date then).
     */
    enum class StartWork { StemLibrary, DiscoverySchedule, NowPlayingWidget, Widgets }

    const val DEFERRED_START_MS = 4_000L

    /** Mobile and Quest start the same work too, sooner (6.2.2): the first frame comes first everywhere. */
    const val QUICK_DEFERRED_START_MS = 1_500L

    fun deferred(lite: Boolean = this.lite): Set<StartWork> = StartWork.entries.toSet()

    fun deferredStartMs(lite: Boolean = this.lite): Long = if (lite) DEFERRED_START_MS else QUICK_DEFERRED_START_MS
}
