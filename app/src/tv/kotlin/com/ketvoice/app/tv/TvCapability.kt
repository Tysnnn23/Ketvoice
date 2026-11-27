package com.ketvoice.app.tv

/**
 * What this TV can afford (Ketvoice TV 1.0.0). Many TVs and boxes are modest: 2 GB of memory, four Cortex-A53/A55
 * cores and often a 32-bit system. The tier is a pure function of what Android reports, read once at start; it
 * chooses Ketmix's default analysis, the performance mode's default and how much the screens draw. A listener's
 * own saved choices always win over these defaults.
 */
enum class TvTier {
    /** 2 GB or less, Android's low-memory flag, or fewer than four cores: Ketmix's light analysis, lean drawing. */
    Light,
    /** About 3 GB, or a 32-bit system: every effect, Ketmix's balanced analysis. */
    Balanced,
    /** 4 GB or more on a 64-bit system with four cores or more. */
    Full;

    /**
     * `ketmixAnalysis` on a fresh install (and for each new profile): 0 « Légère » on light boxes, 1 « Équilibrée » on
     * balanced ones, 2 « Maximale » (longer reads, the key, sections) on powerful ones since 1.1.
     */
    val ketmixAnalysis: Int get() = when (this) { Light -> 0; Balanced -> 1; Full -> 2 }

    /** `highPerformanceMode` on a fresh install: on for light boxes (visible effects stay, the invisible ones go). */
    val performanceMode: Boolean get() = this == Light

    /** Focused cards grow with a spring everywhere; light boxes skip the soft shadow under them. */
    val focusShadow: Boolean get() = this != Light

    /** The animated background behind Now Playing breathes only where the processor has room for it. */
    val livingBackground: Boolean get() = this == Full

    /** How many cards a row composes ahead of the visible ones (lazy rows prefetch this many). */
    val rowPrefetch: Int get() = if (this == Light) 2 else 4
}

/** What Android reports about the TV, the only input of [TvCapability.tier]. */
data class TvDevice(
    val cores: Int,
    val totalMemBytes: Long,
    val lowRamFlag: Boolean,
    val sdk: Int,
    val is64Bit: Boolean,
)

object TvCapability {
    /** A box sold with 2 GB reports 1.6 to 2.0 GB to apps. */
    const val TWO_GB_REPORTED = 2_400_000_000L

    /** A box sold with 4 GB reports 3.4 to 3.9 GB. */
    const val FOUR_GB_REPORTED = 3_300_000_000L

    fun tier(device: TvDevice): TvTier = when {
        device.lowRamFlag -> TvTier.Light
        device.totalMemBytes in 1 until TWO_GB_REPORTED -> TvTier.Light
        device.cores in 1..3 -> TvTier.Light
        device.totalMemBytes in 1 until FOUR_GB_REPORTED || !device.is64Bit -> TvTier.Balanced
        else -> TvTier.Full
    }

    /** The line shown in Settings → À propos (French source, translated at display). */
    fun about(tier: TvTier): String = when (tier) {
        TvTier.Light -> "Téléviseur modeste : Ketmix en analyse légère, effets allégés"
        TvTier.Balanced -> "Téléviseur équilibré : tous les effets, Ketmix en analyse équilibrée"
        TvTier.Full -> "Téléviseur puissant : tous les effets, Ketmix en analyse maximale"
    }

    /** Reads this device (Android only; the tier itself is computed by [tier]). */
    fun read(context: android.content.Context): TvDevice {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
        return TvDevice(
            cores = Runtime.getRuntime().availableProcessors(),
            totalMemBytes = info.totalMem,
            lowRamFlag = am?.isLowRamDevice == true,
            sdk = android.os.Build.VERSION.SDK_INT,
            is64Bit = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && android.os.Process.is64Bit(),
        )
    }

    /** The tier of this TV, read once. */
    @Volatile var current: TvTier = TvTier.Balanced
        private set

    fun init(context: android.content.Context) { current = tier(read(context)) }

    /** For host renders and tests. */
    internal fun override(tier: TvTier) { current = tier }
}
