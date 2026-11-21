package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.FfmpegAudioEngine
import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * « Écouter » in the Studio: the transition alone, played by the desktop mixer itself ([FfmpegAudioEngine.mixIntoNext],
 * the same two sample-locked decks and shared `DeckDsp` automation as a real Ketmix transition) on an engine of its
 * own, from a few seconds before the blend to a few after it. The music playing pauses meanwhile and comes back after.
 */
object StudioPreview {
    sealed interface State {
        data object Idle : State
        data object Preparing : State
        /** [clockMs] is the playhead on the outgoing title's clock (the Studio's timeline). */
        data class Playing(val clockMs: Long) : State
        data object Failed : State
    }

    const val LEAD_MS = 5_000L
    const val TAIL_MS = 4_000L
    private const val PRE_ROLL_MS = 1_000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Test seam: the engine the preview plays on (a capture sink in tests). */
    internal var engineFactory: () -> FfmpegAudioEngine = { FfmpegAudioEngine() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: FfmpegAudioEngine? = null
    private var job: Job? = null
    @Volatile private var resume = false

    /** Plays [plan] from [outSource] into [inSource]; the separated parts make the voice moves real when given. */
    internal fun start(plan: MixPlan, outSource: AudioSource, inSource: AudioSource,
              stems: (suspend () -> Pair<com.ketvoice.app.stems.StemAudio?, com.ketvoice.app.stems.StemAudio?>?)? = null) {
        stop()
        if (!plan.mixes) { _state.value = State.Failed; return }
        resume = Player.state.value.isPlaying
        if (resume) Player.pause()
        _state.value = State.Preparing
        job = scope.launch {
            val parts = stems?.let { runCatching { it() }.getOrNull() }
            val e = runCatching { engineFactory() }.getOrElse { AppLog.w("studio", "preview engine: $it"); _state.value = State.Failed; return@launch }
            engine = e
            val lead = LEAD_MS.coerceAtMost(plan.outMs)
            e.load(outSource, plan.outMs - lead, play = true)
            e.preloadNext(inSource)
            val set = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
            e.mixIntoNext(plan, PRE_ROLL_MS.coerceAtMost(lead), parts) { ok -> set.set(ok) }
            val end = plan.outMs + plan.overlapMs + TAIL_MS
            val deadline = System.nanoTime() + (lead + plan.overlapMs + TAIL_MS + 15_000L) * 1_000_000L
            var promoted = false
            while (isActive && System.nanoTime() < deadline) {
                delay(40)
                if (set.get() == false) { _state.value = State.Failed; break }
                if (e.mixStatus.value.promoted) promoted = true
                val clock = clockOf(plan, e.positionMs(), promoted)
                if (clock >= end) break
                _state.value = State.Playing(clock)
            }
            finish(e, failed = _state.value == State.Failed)
        }
    }

    /** The outgoing title's clock from the heard deck's position: after the hand-over the incoming one is heard. */
    internal fun clockOf(plan: MixPlan, positionMs: Long, promoted: Boolean): Long =
        if (!promoted) positionMs else plan.outMs + ((positionMs - plan.inMs) / plan.rate.coerceAtLeast(.5f).toDouble()).toLong()

    private fun finish(e: FfmpegAudioEngine, failed: Boolean) {
        if (engine === e) engine = null
        runCatching { e.release() }
        _state.value = if (failed) State.Failed else State.Idle
        if (resume) { resume = false; Player.play() }
    }

    fun stop() {
        job?.cancel(); job = null
        engine?.let { e -> engine = null; runCatching { e.release() } }
        if (_state.value != State.Idle) _state.value = State.Idle
        if (resume) { resume = false; Player.play() }
    }
}
