package com.ketvoice.desktop.stems

import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.stems.Resampler
import com.ketvoice.app.stems.StemDsp
import com.ketvoice.app.stems.StemEngine
import com.ketvoice.app.stems.StemMask
import com.ketvoice.app.stems.StemModel
import com.ketvoice.app.stems.StemStore
import com.ketvoice.app.stems.VoiceLevel
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.Deck
import com.ketvoice.desktop.audio.FfmpegAudioEngine
import com.ketvoice.desktop.audio.FfmpegDecoder
import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The voice level on the desktop (as « Niveau de la voix » on the phone): a title is separated whole once, on a
 * background thread, by the shared pipeline ([StemEngine] through ONNX Runtime), the result is stored by
 * [StemStore], and the mixer's voice stage plays `instrumental + level × voice` from it. Which source is which
 * title is learnt as the Player resolves them ([remember]).
 */
object DesktopVoice {
    sealed interface State {
        data object Idle : State
        /** [fraction] is null when the model does not say how far it is. */
        data class Working(val trackId: String, val fraction: Float?) : State
        data class Failed(val trackId: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val ids = ConcurrentHashMap<AudioSource, String>()
    private val masks = ConcurrentHashMap<String, StemMask>()
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    /** The longest title separated whole (memory: about 90 MB of samples). */
    private const val MAX_MS = 15 * 60_000L

    fun attach(engine: FfmpegAudioEngine, scope: CoroutineScope) {
        this.scope = scope
        VoiceLevel.level = AppSettings.voiceLevel.value / 100f
        engine.mixer.voiceMask = ::maskOf
        scope.launch(Dispatchers.IO) {
            Player.state.map { s -> listOfNotNull(s.current?.id, s.queue.getOrNull(s.index + 1)?.id).toSet() }.distinctUntilChanged().collect { wanted ->
                masks.keys.retainAll(wanted)
                for (id in wanted) if (!masks.containsKey(id)) runCatching { StemStore.loadWhole(id) }.getOrNull()?.let { masks[id] = it }
            }
        }
    }

    /** The Player resolved [source] for the title [trackId]. */
    fun remember(source: AudioSource, trackId: String) {
        if (ids.size > 64) ids.clear()
        ids[source] = trackId
    }

    /** The audio thread's question: the whole separation of what [deck] plays, when there is one in memory. */
    internal fun maskOf(deck: Deck): StemMask? = ids[deck.source]?.let { masks[it] }

    /** Whether [trackId] can have its voice turned down now. */
    fun ready(trackId: String?): Boolean = trackId != null && (masks.containsKey(trackId) || StemStore.hasWhole(trackId))

    /** Separates [track] whole (when a model is there and it never was); the voice stage takes it up live. */
    fun request(track: Track) {
        if (ready(track.id)) {
            if (!masks.containsKey(track.id)) scope.launch(Dispatchers.IO) { StemStore.loadWhole(track.id)?.let { masks[track.id] = it } }
            return
        }
        if (job?.isActive == true || !StemModel.anyReady) return
        _state.value = State.Working(track.id, 0f)
        job = scope.launch(Dispatchers.Default) {
            val ok = runCatching { separateWhole(track) }.onFailure { AppLog.w("stems", "separation failed: $it") }.getOrDefault(false)
            _state.value = if (ok) State.Idle else State.Failed(track.id)
        }
    }

    private suspend fun separateWhole(track: Track): Boolean {
        val source = Player.loadedSource()?.takeIf { ids[it] == track.id }
            ?: ids.entries.firstOrNull { it.value == track.id }?.key
            ?: withContext(Dispatchers.IO) { com.ketvoice.desktop.player.DesktopStreams.source(track) } ?: return false
        val quality = StemEngine.forMix() ?: return false
        val ctx = kotlinx.coroutines.currentCoroutineContext()
        val mask = withContext(Dispatchers.IO) { decode(source, 0L, MAX_MS) }?.let { pcm ->
            separatePcm(pcm, quality, { f -> _state.value = State.Working(track.id, f) }) { !ctx.isActive }
        } ?: return false
        StemStore.saveWhole(track.id, mask)
        masks[track.id] = mask
        return true
    }

    /**
     * [pcm] (the decks' 48 kHz interleaved stereo, a whole title) separated with the model of [quality]; [progress]
     * hears the share done (null when the model does not say), [cancelled] stops it. Null when it was stopped.
     */
    internal fun separatePcm(pcm: ShortArray, quality: Int, progress: (Float?) -> Unit, cancelled: () -> Boolean): StemMask? {
        val left = Resampler.resample(pcm, 0, 2, FfmpegDecoder.OUT_RATE, StemDsp.RATE)
        val right = Resampler.resample(pcm, 1, 2, FfmpegDecoder.OUT_RATE, StemDsp.RATE)
        val frames = StemDsp.frames(left.size).coerceAtLeast(1)
        var blocks = 0
        return StemEngine.separate(left, right, 0L, quality) {
            // The quick model asks once per block of frames: that is how far it is.
            progress(if (quality == StemMask.FAST) (++blocks * StemDsp.NET_FRAMES / frames.toFloat()).coerceAtMost(1f) else null)
            cancelled()
        }
    }

    /** A title separated by the library's preparation: the voice level takes it up if it is playing or next. */
    internal fun adopt(trackId: String, mask: StemMask) {
        val s = Player.state.value
        if (trackId == s.current?.id || trackId == s.queue.getOrNull(s.index + 1)?.id) masks[trackId] = mask
    }

    /**
     * [fromMs]..[untilMs] of [source] as the decks decode it (48 kHz interleaved 16-bit stereo), or null when it
     * cannot be read. For whole titles and the parts of a Ketmix transition.
     */
    internal fun decode(source: AudioSource, fromMs: Long, untilMs: Long): ShortArray? {
        val bytes = runCatching { Deck.openSource(source) }.getOrNull() ?: return null
        return try {
            FfmpegDecoder(bytes).use { d ->
                val end = if (d.durationMs > 0) minOf(untilMs, d.durationMs) else untilMs
                if (end <= fromMs) return null
                if (fromMs > 0) d.seek(fromMs)
                val wanted = ((end - fromMs) * FfmpegDecoder.OUT_RATE / 1000).toInt()
                val out = ShortArray(wanted * 2)
                val chunk = FloatArray(4_096 * 2)
                var at = 0
                while (at < wanted) {
                    val n = d.read(chunk, 0, minOf(4_096, wanted - at))
                    if (n < 0) break
                    for (i in 0 until n * 2) out[at * 2 + i] = (chunk[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    at += n
                }
                if (at < FfmpegDecoder.OUT_RATE) null else if (at == wanted) out else out.copyOf(at * 2)
            }
        } catch (t: Throwable) {
            AppLog.w("stems", "decode failed: $t"); null
        } finally { runCatching { bytes.close() } }
    }

    /** Test seam. */
    internal fun resetForTest() { job?.cancel(); ids.clear(); masks.clear(); _state.value = State.Idle }
}
