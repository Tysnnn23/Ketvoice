package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.MixKeys
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.automix.WaveBuilder
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.FfmpegAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * The live mix view's data (Windows 2.0): while Ketmix prepares or plays a transition, both decks' waveform around
 * their playheads, their beat grids and bar lines, where each is within its beat, their levels and the voice level
 * of each, about 30 times a second. Positions follow the heard one ([FfmpegAudioEngine.positionMs]) through the plan
 * (the incoming deck runs at `plan.rate` from `plan.inMs`). `desktop/ui/LiveMixView.kt` draws a simple version.
 */
internal object LiveMix {
    /** One deck. [peaks]: [POINTS] columns of 0..1 from [BEFORE_MS] before the playhead to [AFTER_MS] after it;
     *  [beats]/[bars]: in that window, as fractions 0..1 of its width; [phase]: 0..1 within the current beat. */
    data class Deck(val title: String, val positionMs: Long, val bpm: Float, val peaks: FloatArray, val beats: List<Float>,
                    val bars: List<Float>, val phase: Float, val level: Float, val voice: Float)

    /** [crossfade]: 0 all outgoing .. 1 all incoming. */
    data class Frame(val outgoing: Deck, val incoming: Deck, val crossfade: Float, val mixing: Boolean, val label: String)

    const val BEFORE_MS = 4_000L
    const val AFTER_MS = 8_000L
    const val POINTS = 240

    private val _frame = MutableStateFlow<Frame?>(null)
    /** Null when no transition is being prepared or played. */
    val frame: StateFlow<Frame?> = _frame

    private class Planned(val plan: MixPlan, val from: Track, val to: Track, val outro: SegmentAnalysis?, val intro: SegmentAnalysis?) {
        @Volatile var outWave: Wave? = null
        @Volatile var inWave: Wave? = null
    }

    @Volatile private var pair: Planned? = null
    private var job: Job? = null

    fun start(engine: FfmpegAudioEngine, scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val status = engine.mixStatus.value
                val p = pair
                _frame.value = if (p == null || status.phase == FfmpegAudioEngine.MixPhase.Idle) null
                    else frame(p, engine.positionMs(), status.phase == FfmpegAudioEngine.MixPhase.Mixing, status.promoted)
                if (status.phase == FfmpegAudioEngine.MixPhase.Idle && p != null && _frame.value == null) pair = null
                delay(if (p == null) 250L else 33L)
            }
        }
    }

    /** A transition was planned: its waveforms are read in the background from the two sources. */
    fun planned(plan: MixPlan, from: Track, to: Track, outSource: AudioSource, inSource: AudioSource,
                outro: SegmentAnalysis?, intro: SegmentAnalysis?, scope: CoroutineScope?) {
        val p = Planned(plan, from, to, outro, intro)
        pair = p
        scope?.launch(Dispatchers.IO) {
            p.outWave = wave(outSource, plan.outMs - BEFORE_MS, plan.outMs + plan.overlapMs + AFTER_MS)
            p.inWave = wave(inSource, (plan.inMs - BEFORE_MS).coerceAtLeast(0), plan.inEndMs + AFTER_MS)
        }
    }

    private fun wave(source: AudioSource, fromMs: Long, untilMs: Long): Wave? = runCatching {
        val from = fromMs.coerceAtLeast(0)
        com.ketvoice.desktop.stems.DesktopVoice.decode(source, from, untilMs)?.let { pcm ->
            WaveBuilder(com.ketvoice.desktop.audio.FfmpegDecoder.OUT_RATE, 2, from).apply { accept(pcm) }.build()
        }
    }.getOrNull()

    private fun frame(p: Planned, heardMs: Long, mixing: Boolean, promoted: Boolean): Frame {
        val plan = p.plan
        // Before the promotion the heard title is the outgoing one; after it, the incoming one.
        val outPos = if (promoted) plan.outMs + ((heardMs - plan.inMs) / plan.rate.toDouble()).toLong() else heardMs
        val inPos = if (promoted) heardMs else plan.inMs + ((outPos - plan.outMs).coerceAtLeast(0) * plan.rate.toDouble()).toLong()
        val into = mixProgress(plan, outPos)
        val levels = plan.keys?.takeIf { it.isNotEmpty() }
        val rel = (outPos - plan.outMs).coerceIn(0, plan.overlapMs)
        val outVoice = levels?.let { MixKeys.levelsAt(it, plan.overlapMs, true, rel).voice / 100f } ?: if (plan.mashup && into > .5f) 0f else 1f
        val inVoice = levels?.let { MixKeys.levelsAt(it, plan.overlapMs, false, rel).voice / 100f } ?: 1f
        return Frame(
            deck(p.from.title, outPos, p.outro, p.outWave, 1f - into, outVoice),
            deck(p.to.title, inPos, p.intro, p.inWave, if (outPos < plan.outMs) 0f else into, inVoice),
            into, mixing, com.ketvoice.app.automix.MixWords.label(plan))
    }

    /** How far the crossing is, 0..1, by the outgoing title's position. */
    internal fun mixProgress(plan: MixPlan, outPos: Long): Float =
        if (plan.overlapMs <= 0) (if (outPos >= plan.outMs) 1f else 0f) else ((outPos - plan.outMs).toFloat() / plan.overlapMs).coerceIn(0f, 1f)

    internal fun deck(title: String, pos: Long, a: SegmentAnalysis?, wave: Wave?, level: Float, voice: Float): Deck {
        val from = pos - BEFORE_MS
        val span = (BEFORE_MS + AFTER_MS).toFloat()
        val peaks = FloatArray(POINTS) { i ->
            val at = from + (i * span / POINTS).toLong()
            wave?.columnAt(at)?.takeIf { it >= 0 }?.let { wave.peakAt(it) } ?: 0f
        }
        val beats = ArrayList<Float>(); val bars = ArrayList<Float>()
        var phase = 0f
        if (a != null && a.bpm > 0f) {
            val beat = a.beatMs.toDouble()
            val first = a.beatAtMs + Math.ceil((from - a.beatAtMs) / beat) * beat
            var t = first
            var k = Math.round((first - a.beatAtMs) / beat)
            while (t <= pos + AFTER_MS) {
                val x = ((t - from) / span).toFloat()
                if (a.barOffset >= 0 && Math.floorMod(k - a.barOffset, 4L) == 0L) bars += x else beats += x
                t += beat; k++
            }
            val q = (pos - a.beatAtMs) / beat
            phase = (q - floor(q)).toFloat()
        }
        return Deck(title, pos, if (a?.hasBeat == true) a.bpm else 0f, peaks, beats, bars, phase, level, voice)
    }

    /** Test seam. */
    internal fun resetForTest() { job?.cancel(); job = null; pair = null; _frame.value = null }
}
