package com.ketvoice.desktop.ketmix

import androidx.compose.runtime.mutableStateOf
import com.ketvoice.desktop.platform.AppLog

/**
 * « Puissance de Ketmix » (Windows 2.0): which models Ketmix runs on this PC. Auto gives a desktop PC (no system
 * battery) with enough memory and cores the large models (« Maximum ») and keeps a laptop on today's models
 * (« Équilibré »); the listener may force either. The setting is per PC: it lives in its own preferences and is
 * never synced with the account (a laptop and a tower of the same listener want different answers).
 */
object KetmixPower {
    const val AUTO = 0
    const val BALANCED = 1
    const val MAXIMUM = 2

    enum class Tier { Balanced, Maximum }

    /** What decides the tier: read once per run ([Machine.detect]). */
    data class Machine(val battery: Boolean, val ramMb: Long, val cores: Int, val gpus: List<String> = emptyList()) {
        /** A system battery is what makes a laptop (the owner's rule). */
        val laptop: Boolean get() = battery
        /** A graphics card worth the GPU runtime: not the basic, remote or virtual display adapters. */
        val gpu: String? get() = gpus.firstOrNull { name -> SOFT_ADAPTERS.none { name.contains(it, ignoreCase = true) } }
    }

    /** Below these a desktop PC stays on today's models in Auto (the studio voice model alone needs about 3 GB). */
    const val MIN_RAM_MB = 12L * 1024
    const val MIN_CORES = 4
    /** The studio voice model needs this much memory even when « Maximum » is forced. */
    const val STUDIO_RAM_MB = 8L * 1024
    private val SOFT_ADAPTERS = listOf("Basic Display", "Basic Render", "Remote", "Virtual", "Parsec", "Hyper-V", "VMware", "VirtualBox")

    /** 0 Auto, 1 Équilibré, 2 Maximum ([KetmixPcSettings]). */
    fun tier(setting: Int, machine: Machine): Tier = when (setting) {
        BALANCED -> Tier.Balanced
        MAXIMUM -> Tier.Maximum
        else -> if (!machine.laptop && machine.ramMb >= MIN_RAM_MB && machine.cores >= MIN_CORES) Tier.Maximum else Tier.Balanced
    }

    /** The large models a tier asks for on this machine (they are downloaded only then). */
    fun models(tier: Tier, machine: Machine): Set<PcModel> = when (tier) {
        Tier.Balanced -> emptySet()
        Tier.Maximum -> buildSet {
            add(PcModel.BeatFull); add(PcModel.Key); add(PcModel.Sound)
            if (machine.ramMb >= STUDIO_RAM_MB) add(PcModel.VoiceStudio)
        }
    }

    /** Whether the GPU runtime is worth fetching: a large tier and a real graphics card. */
    fun wantsGpu(tier: Tier, machine: Machine): Boolean = tier == Tier.Maximum && machine.gpu != null

    @Volatile private var detected: Machine? = null
    /** Test seam. */
    @Volatile internal var machineOverride: Machine? = null

    /**
     * This PC, read once and never blocking: battery, memory and cores at once, the graphics cards (WMI, a few seconds)
     * on a background thread, filled in when known.
     */
    fun machine(): Machine = machineOverride ?: detected ?: synchronized(this) {
        detected ?: readMachine().also { base ->
            detected = base
            Thread({ val gpus = Gpus.names(); if (gpus.isNotEmpty()) detected = base.copy(gpus = gpus) }, "ketvoice-gpus").apply { isDaemon = true; start() }
        }
    }

    /** The tier this PC runs now. */
    fun current(): Tier = tier(KetmixPcSettings.power.value, machine())

    /** Short French description for Settings, e.g. « PC fixe · 32 Go · 8 cœurs · NVIDIA GeForce RTX 4070 ». */
    fun describe(m: Machine, t: (String) -> String = { it }): String = listOfNotNull(t(if (m.laptop) "PC portable" else "PC fixe"),
        "${(m.ramMb + 512) / 1024} ${t("Go")}", "${m.cores} ${t("cœurs")}", m.gpu).joinToString(" · ")

    private fun readMachine(): Machine {
        val ram = runCatching {
            (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize / (1024 * 1024)
        }.getOrDefault(0L)
        return Machine(com.ketvoice.app.stems.StemModel.hasBattery(), ram, Runtime.getRuntime().availableProcessors())
    }
}

/** The graphics adapters Windows knows (`Win32_VideoController`), read once; empty elsewhere or when WMI fails. */
internal object Gpus {
    fun names(): List<String> {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return emptyList()
        return runCatching {
            val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_VideoController | ForEach-Object { \$_.Name }").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); emptyList() } else out.map { it.trim() }.filter { it.isNotEmpty() }
        }.onFailure { AppLog.w("ketmix", "graphics adapters not read: $it") }.getOrDefault(emptyList())
    }
}

/** The large models of « Maximum », each published on the `ketmix-pc-v1` prerelease ([PcModels]). */
enum class PcModel { BeatFull, VoiceStudio, Key, Sound }

/**
 * Ketmix's settings that belong to this PC only (never synced): « Puissance de Ketmix », the GPU, « Ketmix apprend »
 * and its weights. Stored in the `ketvoice_ketmix_pc` preferences.
 */
object KetmixPcSettings {
    private const val NAME = "ketvoice_ketmix_pc"
    /** 0 Auto (default), 1 Équilibré, 2 Maximum. */
    val power = mutableStateOf(KetmixPower.AUTO)
    /** Run the models on the graphics card when its runtime is installed and passed its check (on by default). */
    val gpu = mutableStateOf(true)
    /** « Ketmix apprend de vos avis » (optional, off by default). */
    val learns = mutableStateOf(false)
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun load(context: android.content.Context = android.content.Context.app) {
        val p = context.getSharedPreferences(NAME, android.content.Context.MODE_PRIVATE)
        prefs = p
        power.value = p.getInt("power", KetmixPower.AUTO).takeIf { it in 0..2 } ?: KetmixPower.AUTO
        gpu.value = p.getBoolean("gpu", true)
        learns.value = p.getBoolean("learns", false)
    }

    fun save() {
        val p = prefs ?: return
        p.edit().putInt("power", power.value).putBoolean("gpu", gpu.value).putBoolean("learns", learns.value).apply()
    }

    /** Free text values (the learnt weights, the GPU check) in the same file. */
    fun string(key: String): String? = prefs?.getString(key, null)
    fun putString(key: String, value: String?) { prefs?.edit()?.putString(key, value)?.apply() }
}
