package com.ketvoice.app.automix

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.playbackMediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What « Écouter » of the DJ editor is doing; [positionMs] is on the outgoing title's clock, -1 before it plays. */
internal data class PreviewState(val running: Boolean = false, val loading: Boolean = false, val positionMs: Long = -1L,
                                 val plain: Boolean = false, val problem: String? = null)

/** Plays one transition apart from the queue, for the DJ editor. [DeckPreview] in the app; a fake in tests. */
internal interface PreviewEngine {
    val state: StateFlow<PreviewState>
    /** True when a transition can be played now (decks to borrow, not bit-perfect, no mix being heard). */
    fun available(): Boolean
    /** Plays [plan] from [from] into [to]; [plain] plays the same moment as a plain cut (« A/B »). */
    fun start(from: Track, to: Track, plan: MixPlan, plain: Boolean): Boolean
    fun stop()
}

internal object TransitionPreview {
    /** How much is heard before the transition starts and after it ends. */
    const val LEAD_MS = 5_000L
    const val AFTER_MS = 4_000L
    @Volatile var engine: PreviewEngine = DeckPreview

    /** The stretch of the outgoing title a preview of [plan] plays. */
    fun span(plan: MixPlan): LongRange = maxOf(0L, plan.outMs - plan.buildMs - LEAD_MS)..(plan.outMs + plan.overlapMs + AFTER_MS)

    /** The same moment without the mix: the outgoing title stops at the exit, the incoming one starts there at once. */
    fun cutOut(plan: MixPlan) = DeckAutomation(Curve(longArrayOf(plan.outMs * 1000L, plan.outMs * 1000L + 6_000L), floatArrayOf(1f, 0f)), Curve.flat(0f))
    fun cutIn(plan: MixPlan) = DeckAutomation(Curve(longArrayOf(plan.inMs * 1000L - 6_000L, plan.inMs * 1000L), floatArrayOf(0f, 1f)), Curve.flat(0f))
    /**
     * A deck's volume while « Écouter » plays: full once its processors carry the levels, else (a format that
     * bypasses them, or before they join, which on a phone is only once the deck plays) its level on the volume.
     * Set on every step: the incoming deck waits at its level before its entry (0) until its processors join,
     * and was left silent for the whole preview when nothing gave it its volume back.
     */
    fun volume(processorsOn: Boolean, automation: DeckAutomation, positionMs: Long): Float =
        if (processorsOn) 1f else automation.gain.at(positionMs * 1000L).coerceIn(0f, 1f)
}

/**
 * « Écouter » (6.9): the transition alone, on two decks borrowed from the playback service, whatever plays.
 * What was playing is paused and left where it was, then played again afterwards if it was playing. Each deck
 * follows its own [DeckAutomation] (tied to its title's positions, as in a real mix); the incoming one is kept
 * in time with the outgoing one the way [Automix] keeps it ([DeckSync]).
 */
internal object DeckPreview : PreviewEngine {
    private val scope = MainScope()
    private var job: Job? = null
    private val _state = MutableStateFlow(PreviewState())
    override val state: StateFlow<PreviewState> = _state
    /** Main thread. Whether what played before « Écouter » plays again after it; kept when « A/B » restarts a preview. */
    private var resumeMain = false
    /** Counts the starts: a preview replaced by the next one (« A/B » while it plays) leaves the state and the main title to it. */
    private var generation = 0

    override fun available(): Boolean = Automix.previewHost() != null

    override fun start(from: Track, to: Track, plan: MixPlan, plain: Boolean): Boolean {
        val h = Automix.previewHost() ?: return false
        if (!plan.mixes) return false
        val previous = job?.takeIf { it.isActive }
        if (previous == null) resumeMain = Player.state.value.playWhenReady
        previous?.cancel()
        generation++
        _state.value = PreviewState(running = true, loading = true, plain = plain)
        // The decks of the one before are given back before this one borrows its own.
        job = scope.launch { previous?.join(); play(h, from, to, plan, plain) }
        return true
    }

    override fun stop() { job?.cancel(); job = null; _state.value = PreviewState() }

    private suspend fun item(track: Track): MediaItem? {
        val source = Player.resolveDeckSource(track) ?: return null
        return playbackMediaItem(track, source.url, source.cacheKey, source.stream, source.offlineDash)
    }

    internal suspend fun play(h: AutomixHost, from: Track, to: Track, plan: MixPlan, plain: Boolean) {
        val mine = generation
        if (Player.state.value.playWhenReady) Player.pause()
        val out = h.standby()
        val into = h.standby()
        try {
            val problem = run(h, out, into, from, to, plan, plain)
            _state.value = PreviewState(problem = problem)
        } finally {
            h.release(out); h.release(into)
            if (mine == generation) {
                if (_state.value.running) _state.value = PreviewState()
                if (resumeMain && !Player.state.value.playWhenReady) Player.toggle()
                resumeMain = false
            }
        }
    }

    /** Null when played to its end, or why it could not be (a French literal). */
    private suspend fun run(h: AutomixHost, out: ExoPlayer, into: ExoPlayer, from: Track, to: Track, plan: MixPlan, plain: Boolean): String? {
        val outItem = item(from) ?: return "Ce titre n'a pas pu être lu"
        val inItem = item(to) ?: return "Ce titre n'a pas pu être lu"
        val outControl = h.control(out) ?: return "Écoute indisponible en sortie bit-perfect"
        val inControl = h.control(into) ?: return "Écoute indisponible en sortie bit-perfect"
        val outAuto = if (plain) TransitionPreview.cutOut(plan) else DeckAutomation.outgoing(plan)
        val inAuto = if (plain) TransitionPreview.cutIn(plan) else DeckAutomation.incoming(plan)
        outControl.automation = outAuto
        inControl.automation = inAuto
        val span = TransitionPreview.span(plan)
        val rate = if (plain) 1f else plan.rate
        // Where the incoming title is when the outgoing one is at the start of the stretch (it may be before its start).
        val inFrom = plan.inMs - ((plan.outMs - span.first) * rate).toLong()
        out.setMediaItem(outItem, span.first)
        into.setMediaItem(inItem, inFrom.coerceAtLeast(0L))
        val speed = if (plain) 1f else DeckSync.speedOf(plan, 1f)
        into.playbackParameters = if (speed == 1f) PlaybackParameters.DEFAULT else PlaybackParameters(speed)
        out.prepare(); into.prepare()
        val readyBy = SystemClock.elapsedRealtime() + 15_000L
        while (out.playbackState != androidx.media3.common.Player.STATE_READY || into.playbackState != androidx.media3.common.Player.STATE_READY) {
            if (out.playerError != null || into.playerError != null || SystemClock.elapsedRealtime() > readyBy) return "Ce titre n'a pas pu être lu"
            delay(40)
        }
        out.volume = TransitionPreview.volume(outControl.active, outAuto, span.first)
        into.volume = TransitionPreview.volume(inControl.active, inAuto, inFrom.coerceAtLeast(0L))
        out.play()
        // The incoming title waits for its own start when the stretch begins before it.
        val waitMs = if (inFrom < 0) (-inFrom / rate).toLong() else 0L
        if (waitMs == 0L) into.play()
        follow(out, into, outControl, inControl, outAuto, inAuto, plan, plain, span, waitMs)
        return null
    }

    private suspend fun follow(out: ExoPlayer, into: ExoPlayer, outControl: DeckControl, inControl: DeckControl,
                               outAuto: DeckAutomation, inAuto: DeckAutomation, plan: MixPlan, plain: Boolean, span: LongRange, waitMs: Long) {
        var reseeked = false
        var smoothed = Double.NaN
        val base = into.playbackParameters.speed
        var endedAt = 0L
        while (true) {
            delay(40)
            // Past its last sample (an echo rings on) the outgoing clock runs on in real time.
            val outEnded = out.playbackState == androidx.media3.common.Player.STATE_ENDED
            if (outEnded && endedAt == 0L) endedAt = SystemClock.elapsedRealtime()
            val outPosition = if (outEnded) out.duration.coerceAtLeast(0L) + SystemClock.elapsedRealtime() - endedAt else out.currentPosition
            _state.value = PreviewState(running = true, positionMs = outPosition, plain = plain)
            if (!into.playWhenReady && outPosition >= span.first + waitMs) into.play()
            out.volume = TransitionPreview.volume(outControl.active, outAuto, outPosition)
            into.volume = TransitionPreview.volume(inControl.active, inAuto, into.currentPosition)
            if (!plain && plan.beatTied && into.isPlaying && !outEnded) {
                val error = DeckSync.errorMs(plan, outPosition, into.currentPosition)
                smoothed = if (smoothed.isNaN()) error else smoothed * .6 + error * .4
                val settled = DeckSync.settled(plan, into.currentPosition)
                if (!settled && !reseeked && abs(smoothed) > 160.0) {
                    reseeked = true; smoothed = Double.NaN
                    into.seekTo((plan.inMs + (outPosition - plan.outMs + 90L) * plan.rate.toDouble()).toLong().coerceAtLeast(0L))
                } else {
                    val wanted = if (settled) base else DeckSync.speed(base, smoothed, audible = false)
                    if (wanted != into.playbackParameters.speed) into.playbackParameters = PlaybackParameters(wanted)
                }
            }
            if (outPosition >= span.last || into.playbackState == androidx.media3.common.Player.STATE_ENDED || out.playerError != null) break
        }
    }
}
