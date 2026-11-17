package com.ketvoice.app.stems

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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

    fun init(context: Context) { appContext = context.applicationContext }

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
        _state.value = if (first != null) State.Working(trackId, 0f) else State.Ready(trackId)
        job = scope.launch {
            busy = true
            try {
                val context = coroutineContext
                if (first != null) {
                    val ok = runCatching { separate(trackId, first, false) { context.isActive } }.getOrDefault(false)
                    if (!context.isActive) return@launch
                    _state.value = if (ok) State.Ready(trackId) else State.Unavailable(trackId)
                    if (!ok) { android.util.Log.w("KetvoiceStems", "voice separation unavailable for this title"); return@launch }
                    _separated.tryEmit(trackId)
                }
                // The better separation, in the background: the slider keeps working on the quick one meanwhile.
                if (better != null && runCatching { separate(trackId, better, true) { context.isActive } }.getOrDefault(false) && context.isActive)
                    _separated.tryEmit(trackId)
            } finally { busy = false; StemLibrary.wake() }
        }
    }

    /** Where the sound of [trackId] can be read: its downloaded file, or the address it is streamed from. */
    internal fun source(trackId: String): Uri? {
        Downloader.itemFor(trackId)?.takeIf { it.dashManifestUrl == null && it.file.isFile }?.let { return Uri.fromFile(it.file) }
        return synchronized(addresses) { addresses[trackId] }
    }

    private fun separate(trackId: String, quality: Int, quiet: Boolean, goOn: () -> Boolean): Boolean {
        val context = appContext ?: return false
        val uri = source(trackId) ?: return false
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(index)
            if (StemLibrary.channels(format) != 2) return false
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else return false
            if (durationMs <= 0) return false
            extractor.selectTrack(index)
            return StemLibrary.separateWhole(extractor, format, trackId, durationMs, quality, goOn) { fraction ->
                if (!quiet) _state.value = State.Working(trackId, fraction.coerceIn(0f, 1f))
            }
        } finally {
            runCatching { extractor.release() }
        }
    }
}
