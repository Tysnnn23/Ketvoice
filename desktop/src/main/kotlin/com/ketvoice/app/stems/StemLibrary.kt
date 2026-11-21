package com.ketvoice.app.stems

import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * « Voix prêtes à l'avance » (Windows 1.1): the phone's library preparation on the PC. While the computer is on mains
 * power ([StemModel.charging], `GetSystemPowerStatus`), the downloaded titles are separated whole ahead of being
 * played, with the phone's rules: the most recent downloads first and no more than [MAX_TITLES], one title at a time
 * on a thread of low priority, held while Ketmix prepares or plays a transition or the listener waits for a voice
 * (that voice goes first, the title in progress is stopped and taken up again later), stopped when the computer
 * leaves mains power. The quick model goes first over every title, so all of them get a voice level soon; the
 * high-quality model then improves them where Settings asks for it ([StemEngine.wanted]).
 *
 * The phone's rule asks for Ketmix to be on; on the PC the voice level beside the lyrics uses these separations as
 * much as Ketmix does, so only the setting ([AppSettings.ketmixLibrary]) and the mains power count.
 */
internal object StemLibrary {
    data class Progress(val done: Int = 0, val total: Int = 0, val running: Boolean = false)
    /** One downloaded file: its title and where it is. */
    internal data class LibraryItem(val id: String, val path: String, val trackDurationMs: Long)

    const val MAX_TITLES = 300
    private const val START_DELAY_MS = 20_000L
    private const val POWER_POLL_MS = 60_000L
    private const val MIN_TITLE_MS = 30_000L

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ketvoice-library").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var job: Job? = null
    @Volatile private var started = false
    /** Files that could not be read: not tried again by this process. */
    private val unreadable = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The rule the shared tests read, unchanged from the phone. */
    internal fun allowed(ketmix: Boolean, library: Boolean, charging: Boolean): Boolean = ketmix && library && charging

    // Test seams: what the work reads, and how it separates.
    internal var items: () -> List<LibraryItem> = {
        com.ketvoice.desktop.library.DesktopDownloader.items.value.filter { it.file.isFile }
            .map { LibraryItem(it.track.id, it.file.absolutePath, it.track.durationMs) }
    }
    internal var charging: () -> Boolean = { StemModel.charging() }
    /** A title the listener is waiting for, or a Ketmix transition being made: the library waits. */
    internal var held: () -> Boolean = {
        com.ketvoice.desktop.stems.DesktopVoice.state.value is com.ketvoice.desktop.stems.DesktopVoice.State.Working ||
            com.ketvoice.desktop.ketmix.DesktopAutomix.ui.value.phase != com.ketvoice.desktop.ketmix.DesktopAutomix.Phase.Idle
    }
    internal var separate: (LibraryItem, Int, () -> Boolean) -> StemMask? = { item, quality, cancelled ->
        com.ketvoice.desktop.stems.DesktopVoice.decode(com.ketvoice.desktop.audio.AudioSource.LocalFile(item.path), 0L, 15 * 60_000L)
            ?.takeIf { it.size / 2 >= com.ketvoice.desktop.audio.FfmpegDecoder.OUT_RATE * (MIN_TITLE_MS / 1000) }
            ?.let { com.ketvoice.desktop.stems.DesktopVoice.separatePcm(it, quality, {}, cancelled) }
    }
    internal var modelReady: () -> Boolean = { StemModel.anyReady }
    internal var holdPollMs = 2_000L

    private fun goAhead(): Boolean = allowed(ketmix = true, library = AppSettings.ketmixLibrary.value, charging = charging()) && modelReady()

    /** Starts watching: the downloads changing, the setting turned on, mains power coming back. */
    fun start() {
        if (started) return
        started = true
        scope.launch { com.ketvoice.desktop.library.DesktopDownloader.items.map { it.size }.distinctUntilChanged().drop(1).collect { wake() } }
        scope.launch { while (isActive) { if (goAhead()) wake(); delay(POWER_POLL_MS) } }
    }

    fun wake(delayMs: Long = START_DELAY_MS) {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(delayMs)
            runCatching { run() }.onFailure { com.ketvoice.desktop.platform.AppLog.w("stems", "library preparation stopped: ${it.javaClass.simpleName}") }
            _progress.value = _progress.value.copy(running = false)
        }
    }

    /** Test seam: waits for the work in progress. */
    internal suspend fun join() { job?.join() }

    internal suspend fun run() {
        try { prepare() } finally { _progress.value = _progress.value.copy(running = false) }
    }

    private suspend fun prepare() {
        if (!goAhead()) return
        val list = items().take(MAX_TITLES).filter { it.path !in unreadable }
        // First pass: the quick model on every title without a separation. Second: the best model where it is wanted.
        val passes = listOfNotNull(StemMask.FAST.takeIf { StemModel.fast.ready || testQuality != null },
            (testQuality ?: StemEngine.wanted())?.takeIf { it > StemMask.FAST }).distinct()
        for (quality in passes) {
            val todo = list.filter { (StemStore.wholeQuality(it.id) ?: -1) < quality }
            _progress.value = Progress(0, todo.size, running = todo.isNotEmpty())
            var done = 0
            for (item in todo) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive || !goAhead()) return
                while (held() && kotlinx.coroutines.currentCoroutineContext().isActive) delay(holdPollMs)
                // The best model only while it is still wanted (mains power, the setting).
                if (quality > StemMask.FAST && (testQuality ?: StemEngine.wanted()) != quality) return
                if ((StemStore.wholeQuality(item.id) ?: -1) < quality) {
                    val mask = runCatching { separate(item, quality) { held() || !goAhead() } }.getOrNull()
                    when {
                        mask != null -> { StemStore.saveWhole(item.id, mask); com.ketvoice.desktop.stems.DesktopVoice.adopt(item.id, mask) }
                        // Stopped for a voice the listener waits for, or for the power: taken up again next time.
                        held() || !goAhead() -> { _progress.value = Progress(done, todo.size, running = true); continue }
                        else -> unreadable += item.path
                    }
                }
                done++
                _progress.value = Progress(done, todo.size, running = true)
            }
        }
    }

    /** Test seam: the quality the second pass aims at (null = the settings'). */
    internal var testQuality: Int? = null
}
