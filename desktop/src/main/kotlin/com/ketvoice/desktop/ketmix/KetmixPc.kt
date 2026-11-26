package com.ketvoice.desktop.ketmix

import androidx.compose.runtime.snapshotFlow
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.stems.OrtAccel
import com.ketvoice.app.stems.StemEngine
import com.ketvoice.app.stems.StemLibrary
import com.ketvoice.app.stems.StemMask
import com.ketvoice.app.stems.StemStore
import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Ketmix's PC side of Windows 2.0, started once from `Main`: « Puissance de Ketmix », the large models and the GPU,
 * the library prepared while the PC is idle and « Ketmix apprend ».
 */
internal object KetmixPc {
    private const val START_DELAY_MS = 45_000L

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start(context: android.content.Context, scope: CoroutineScope) {
        KetmixPcSettings.load(context)
        KetmixPower.machine() // starts reading the graphics cards in the background
        PcModels.init(context.filesDir)
        OrtAccel.readCheck = { KetmixPcSettings.string("gpuCheck") }
        OrtAccel.writeCheck = { KetmixPcSettings.putString("gpuCheck", it) }
        OrtAccel.allowed = { KetmixPcSettings.gpu.value && KetmixPower.current() == KetmixPower.Tier.Maximum }
        // The large models only while the tier has them: « Équilibré » keeps today's.
        StemEngine.studioFile = { if (maximum()) PcModels.file(PcModel.VoiceStudio) else null }
        com.ketvoice.app.automix.BeatModel.fullFile = { if (maximum()) PcModels.file(PcModel.BeatFull) else null }
        scope.launch {
            snapshotFlow { Triple(KetmixPcSettings.power.value, KetmixPcSettings.gpu.value, KetmixPcSettings.learns.value) }
                .drop(1).debounce(300).collect { KetmixPcSettings.save(); StemEngine.close(); scope.launch(Dispatchers.IO) { prepareModels() } }
        }
        // Ketmix or the voices switched on later: what « Maximum » needs is fetched then.
        scope.launch {
            snapshotFlow { AppSettings.automix.value || AppSettings.ketmixStems.value }.drop(1).debounce(1_000)
                .collect { on -> if (on) scope.launch(Dispatchers.IO) { prepareModels() } }
        }
        scope.launch(Dispatchers.IO) { delay(START_DELAY_MS); prepareModels() }
        LibraryPrep.start(scope)
    }

    fun maximum(): Boolean = KetmixPower.current() == KetmixPower.Tier.Maximum

    /** What « Maximum » needs when Ketmix is in use: missing models, then the GPU runtime and its check. */
    private val preparing = kotlinx.coroutines.sync.Mutex()

    suspend fun prepareModels() = preparing.withLock { prepareNow() }

    private suspend fun prepareNow() {
        val machine = KetmixPower.machine()
        val tier = KetmixPower.current()
        // Only once Ketmix or « Séparer les voix » is switched on (both off by default): 1.1 GB is not fetched for nothing.
        val inUse = AppSettings.automix.value || AppSettings.ketmixStems.value
        if (tier != KetmixPower.Tier.Maximum || !inUse) return
        runCatching { PcModels.ensure(KetmixPower.models(tier, machine)) }.onFailure { AppLog.w("ketmix", "large models: $it") }
        if (KetmixPcSettings.gpu.value && KetmixPower.wantsGpu(tier, machine))
            runCatching { OrtAccel.prepare(android.content.Context.app.filesDir, download = true) }.onFailure { AppLog.w("ketmix", "GPU: $it") }
    }
}

/**
 * « Bibliothèque préparée au repos » (Windows 2.0): while the PC is idle ([IdleWatch]) and the tier is « Maximum », the
 * downloaded titles get their whole-title facts ([FactsAnalysis]: rhythm, sections, key, energy, sound) and their studio
 * separation, one title at a time, most recent downloads first. Any input, a full-screen app or leaving mains power
 * stops the title in progress at once (ONNX Runtime's terminate flag); it is taken up again at the next idle time.
 * Follows « Voix prêtes à l'avance » ([AppSettings.ketmixLibrary]).
 */
internal object LibraryPrep {
    data class Progress(val done: Int = 0, val total: Int = 0, val running: Boolean = false, val paused: Boolean = false)
    private const val POLL_MS = 30_000L

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    // Seams.
    internal var items: () -> List<StemLibrary.LibraryItem> = { StemLibrary.items() }
    internal var enabled: () -> Boolean = { AppSettings.ketmixLibrary.value && KetmixPc.maximum() }
    internal var decode: (StemLibrary.LibraryItem) -> ShortArray? = { item ->
        com.ketvoice.desktop.stems.DesktopVoice.decode(com.ketvoice.desktop.audio.AudioSource.LocalFile(item.path), 0L, 15 * 60_000L)
    }
    internal var analyse: (ShortArray, Long, () -> Boolean) -> TrackFacts? = { pcm, duration, cancelled ->
        FactsAnalysis.analyse(pcm, com.ketvoice.desktop.audio.FfmpegDecoder.OUT_RATE, duration, cancelled)
    }
    internal var separate: (ShortArray, () -> Boolean) -> StemMask? = { pcm, cancelled ->
        com.ketvoice.desktop.stems.DesktopVoice.separatePcm(pcm, StemMask.STUDIO, {}, cancelled)
    }
    internal var studioReady: () -> Boolean = { StemEngine.studioReady() }
    internal var factsModels: () -> Boolean = { PcModels.file(PcModel.BeatFull) != null || PcModels.file(PcModel.Key) != null || PcModels.file(PcModel.Sound) != null }

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_MS)
                if (enabled() && IdleWatch.idle(IdleWatch.snapshot())) runCatching { runOnce() }.onFailure { AppLog.w("ketmix", "idle preparation: $it") }
            }
        }
    }

    private fun stop(): Boolean = !enabled() || IdleWatch.interrupted(IdleWatch.snapshot()) || StemLibrary.held()

    /** One pass over the library while the PC stays idle; returns how many titles were finished. */
    internal fun runOnce(stopped: () -> Boolean = ::stop): Int {
        val todo = items().take(StemLibrary.MAX_TITLES).filter { needsFacts(it) || needsStudio(it) }
        var done = 0
        _progress.value = Progress(0, todo.size, running = todo.isNotEmpty())
        try {
            for (item in todo) {
                if (stopped()) { _progress.value = _progress.value.copy(paused = true); break }
                val pcm = decode(item) ?: continue
                val duration = item.trackDurationMs.takeIf { it > 0 } ?: (pcm.size / 2 * 1000L / com.ketvoice.desktop.audio.FfmpegDecoder.OUT_RATE)
                if (needsFacts(item)) {
                    val facts = analyse(pcm, duration, stopped)
                    // A title too short or unreadable is not tried again by this process.
                    if (facts != null) TrackFactsStore.put(item.id, facts) else if (!stopped()) unusable += item.id
                }
                if (stopped()) { _progress.value = _progress.value.copy(paused = true); break }
                if (needsStudio(item)) separate(pcm, stopped)?.let { StemStore.saveWhole(item.id, it); runCatching { com.ketvoice.desktop.stems.DesktopVoice.adopt(item.id, it) } }
                if (!needsFacts(item) && !needsStudio(item)) done++
                _progress.value = Progress(done, todo.size, running = true)
            }
        } finally { _progress.value = _progress.value.copy(running = false) }
        return done
    }

    private val unusable = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun needsFacts(item: StemLibrary.LibraryItem) = factsModels() && item.id !in unusable && !TrackFactsStore.has(item.id)
    private fun needsStudio(item: StemLibrary.LibraryItem) = studioReady() && (StemStore.wholeQuality(item.id) ?: -1) < StemMask.STUDIO
}
