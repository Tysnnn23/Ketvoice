package com.ketvoice.app.stems

import android.content.Context
import android.net.Uri
import com.ketvoice.app.data.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Separates the title being heard, whole, when the listener asks for its voice level (6.0.0 Beta 3,
 * owner: the slider is on every title). The sound is read from the downloaded file when there is one,
 * otherwise from the address the player streams it from; the quick model is used when it is there,
 * for it is about ten times faster. The result is the same whole-title separation the library makes
 * ([StemLibrary.separateWhole]), stored once and used by the voice level and by every Ketmix mix.
 * Since 6.1 the work itself runs in the work process ([StemWork]); here only its state is kept.
 */
internal object VoiceSeparation {
    sealed interface State {
        data object Idle : State
        /** [fraction] of [trackId] is separated. */
        data class Working(val trackId: String, val fraction: Float) : State
        data class Ready(val trackId: String) : State
        /** [trackId] cannot be separated here: its sound cannot be read (a stream of several files, a mono title). */
        data class Unavailable(val trackId: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    /** Each title whose separation was just stored: the playback service takes it up at once. */
    private val _separated = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val separated: SharedFlow<String> = _separated

    /** The addresses of the last titles the players loaded, by title (set by the playback service). */
    private val addresses = object : LinkedHashMap<String, Uri>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Uri>?) = size > 12
    }

    /** The playback service loaded [trackId] from [uri]. */
    fun heard(trackId: String, uri: Uri) { synchronized(addresses) { addresses[trackId] = uri } }

    /** 7.6 (voice76): DASH manifests the players loaded, and the audio rendition each one plays (its format id). */
    private val manifests = object : LinkedHashMap<String, Uri>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Uri>?) = size > 12
    }
    private val renditions = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The playback service loaded [trackId] from the DASH manifest [uri]. */
    fun heardDash(trackId: String, uri: Uri) { synchronized(addresses) { manifests[trackId] = uri } }
    /** The player of [trackId] plays its audio rendition [formatId]. */
    fun rendition(trackId: String, formatId: String?) { if (formatId != null) { if (renditions.size > 24) renditions.clear(); renditions[trackId] = formatId } }

    /** Where the title is being heard, for a stream separated from the playhead on; 0 for another title. */
    internal var playheadOf: (String) -> Long = { id ->
        runCatching { val p = com.ketvoice.app.player.Player; if (p.state.value.current?.id == id) p.positionMs.value else 0L }.getOrDefault(0L)
    }
    /** While true the library's own work waits: the listener is waiting for this one. */
    @Volatile var busy = false
        private set

    /** One background thread for this work alone: it never waits behind the library's queue. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ketvoice-voice")
            .apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var job: Job? = null
    @Volatile private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext; StemWork.init(context) }

    /** The model a title is separated with here: the quick one when it is there. Null when none is downloaded. */
    fun quality(): Int? = when {
        StemModel.fast.ready -> StemMask.FAST
        StemModel.hq.ready -> StemMask.HIGH
        else -> null
    }

    val modelReady: Boolean get() = quality() != null

    /**
     * The better model, when it is wanted now and better than [quality] (6.0.0 Beta 4): a title separated
     * by the quick model is separated again by the high-quality one while the phone charges, or always
     * when the listener chose "Always the best", and its voice level is then taken up live.
     */
    private fun upgrade(quality: Int): Int? = StemEngine.wanted()?.takeIf { it > quality && StemModel.hq.ready && !com.ketvoice.app.automix.BackgroundThrift.active() }

    /**
     * Separates [trackId] whole now (6.0.0 Beta 4: as soon as its lyrics are shown, so that the slider is
     * ready when touched), unless it already is or is being separated; a separation by the quick model is
     * then improved by the better one when that one is wanted.
     */
    @Synchronized fun request(trackId: String) {
        when (val now = _state.value) {
            is State.Working -> if (now.trackId == trackId) return
            is State.Unavailable -> if (now.trackId == trackId) return
            is State.Ready -> if (now.trackId == trackId && job?.isActive == true) return
            else -> Unit
        }
        val stored = StemStore.wholeQuality(trackId)
        val first = if (stored != null) null else quality() ?: return
        val better = upgrade(stored ?: first ?: return)
        if (first == null && better == null) { _state.value = State.Ready(trackId); return }
        job?.cancel()
        lastRequested = trackId
        _state.value = if (first != null) State.Working(trackId, 0f) else State.Ready(trackId)
        job = scope.launch {
            busy = true
            try {
                val context = coroutineContext
                if (first != null) {
                    val outcome = runCatching { separate(trackId, first, false) }.getOrDefault(StemWork.Outcome.Failed)
                    if (!context.isActive) return@launch
                    if (outcome == StemWork.Outcome.Lost) { _state.value = State.Idle; retryLater(trackId); return@launch }
                    val ok = outcome == StemWork.Outcome.Done
                    _state.value = if (ok) State.Ready(trackId) else State.Unavailable(trackId)
                    if (!ok) { android.util.Log.w("KetvoiceStems", "voice separation unavailable for this title"); return@launch }
                    retries.remove(trackId)
                    _separated.tryEmit(trackId)
                }
                // The better separation, in the background: the slider keeps working on the quick one meanwhile.
                if (better != null && runCatching { separate(trackId, better, true) }.getOrNull() == StemWork.Outcome.Done && context.isActive)
                    _separated.tryEmit(trackId)
            } finally { busy = false; StemLibrary.wake() }
        }
    }

    /** The title most recently asked for: a separation lost with the work process is asked again only for it. */
    @Volatile private var lastRequested: String? = null
    private val retries = HashMap<String, Int>()

    /**
     * The work process was stopped by Android while it separated [trackId] (6.1): the music went on; the
     * separation is asked again a little later, twice at most, while the listener has not moved on.
     */
    private fun retryLater(trackId: String) {
        val count = synchronized(retries) { (retries[trackId] ?: 0) + 1 }.also { n -> synchronized(retries) { retries[trackId] = n } }
        if (count > MAX_RETRIES) return
        scope.launch {
            kotlinx.coroutines.delay(RETRY_MS)
            if (lastRequested == trackId && _state.value == State.Idle) request(trackId)
        }
    }

    private const val MAX_RETRIES = 2
    private const val RETRY_MS = 20_000L

    /** Where the sound of [trackId] can be read: its downloaded file, or the address it is streamed from. */
    internal fun source(trackId: String): Uri? {
        Downloader.itemFor(trackId)?.takeIf { it.dashManifestUrl == null && it.file.isFile }?.let { return Uri.fromFile(it.file) }
        return synchronized(addresses) { addresses[trackId] }
    }

    /** Asks the work process ([StemWork]) for the whole separation of [trackId]; [quiet] keeps the slider's progress as it is. */
    private suspend fun separate(trackId: String, quality: Int, quiet: Boolean): StemWork.Outcome {
        val depth = runCatching { com.ketvoice.app.automix.AnalysisDepth.of(com.ketvoice.app.data.AppSettings.ketmixAnalysis.value) }
            .getOrDefault(com.ketvoice.app.automix.AnalysisDepth.Balanced)
        val startAt = if (quiet) 0L else playheadOf(trackId)
        fun shown(fraction: Float) { if (!quiet) _state.value = State.Working(trackId, fraction.coerceIn(0f, 1f)) }
        // 7.6: each piece done ahead of the playhead is taken up by the voice stage at once.
        val partial = { if (!quiet) _separated.tryEmit(trackId) }
        source(trackId)?.let { uri ->
            return StemWork.whole(trackId, uri, quality, ::shown, StemWork.StreamWork(startAt, 0L, depth)) { partial() }
        }
        // A stream of several files (DASH): its audio rendition, read in place or copied in order first.
        val manifest = synchronized(addresses) { manifests[trackId] } ?: return StemWork.Outcome.Failed
        val context = appContext ?: return StemWork.Outcome.Failed
        val ctx = coroutineContext
        val dash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                StreamSeparation.dash(StreamSeparation.factoryFor(context, trackId), manifest, renditions[trackId], StemWork.pcmDir(context),
                    { shown(it * StreamSeparation.COPY_SHARE) }, { ctx.isActive })
            }.getOrNull()
        } ?: return StemWork.Outcome.Failed
        val copied = dash.file != null
        val uri = dash.remote ?: Uri.fromFile(dash.file ?: return StemWork.Outcome.Failed)
        return try {
            StemWork.whole(trackId, uri, quality, { f -> shown(if (copied) StreamSeparation.COPY_SHARE + f * (1f - StreamSeparation.COPY_SHARE) else f) },
                StemWork.StreamWork(startAt, dash.durationMs, depth)) { partial() }
        } finally { dash.file?.delete() }
    }
}
