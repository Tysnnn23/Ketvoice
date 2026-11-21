package com.ketvoice.desktop.ketmix

import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.AnalysisTap
import com.ketvoice.app.automix.BeatModel
import com.ketvoice.app.automix.MixContext
import com.ketvoice.app.automix.MixFacts
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixSettings
import com.ketvoice.app.automix.MixShape
import com.ketvoice.app.automix.MixShaping
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.MixVariety
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.TransitionPlanner
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.Deck
import com.ketvoice.desktop.audio.FfmpegAudioEngine
import com.ketvoice.desktop.audio.FfmpegDecoder
import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Ketmix on Windows: the shared planning (analysis, [TransitionPlanner], [MixShaping], the style, length
 * and analysis settings) played by [FfmpegAudioEngine]'s two decks. As the current title nears its end the
 * ending and the next title's start are decoded straight from their sources (no silent deck playing fast as
 * on Android: the desktop decoder reads faster than real time), measured with the shared [AnalysisTap], and
 * the plan goes to the engine, which runs the incoming deck sample-locked to the outgoing one. « Suivant »
 * mixes too when [AppSettings.ketmixOnSkip] is on, with the phone's quick filter blend.
 */
object DesktopAutomix {
    enum class Phase { Idle, Preparing, Ready, Mixing }
    data class Ui(val phase: Phase = Phase.Idle, val from: Track? = null, val to: Track? = null, val promoted: Boolean = false, val label: String = "")

    private val _ui = MutableStateFlow(Ui())
    /** What the bar shows: the « Mix en cours » badge while two titles play together. */
    val ui: StateFlow<Ui> = _ui

    private const val PREP_MS = 25_000L
    private const val SKIP_OVERLAP_MS = 3_200L
    private const val SKIP_LEAD_MS = 600L

    @Volatile private var engine: FfmpegAudioEngine? = null
    private var scope: CoroutineScope? = null
    private var jobs: List<Job> = emptyList()
    /** The pair (current entry > next entry) already planned or given up on. */
    @Volatile private var doneKey: String? = null
    @Volatile private var pair: Pair<Track, Track>? = null
    @Volatile private var bypass = false
    private var lastVariant = -1
    private var lastExit = 0
    private var recentKinds: List<MixKind> = emptyList()

    fun attach(engine: FfmpegAudioEngine, scope: CoroutineScope) {
        jobs.forEach { it.cancel() }
        this.engine = engine
        this.scope = scope
        runCatching { AnalysisStore.init(android.content.Context.app) }
        Player.skipHook = { !bypass && skipMix() }
        jobs = listOf(
            scope.launch { watch() },
            scope.launch { engine.mixStatus.collect(::publish) },
        )
    }

    private fun publish(status: FfmpegAudioEngine.MixStatus) {
        // A transition set and then dropped (a seek, a queue change) may be planned again.
        if (status.phase == FfmpegAudioEngine.MixPhase.Idle && _ui.value.phase == Phase.Ready) doneKey = null
        val p = pair
        _ui.value = when (status.phase) {
            FfmpegAudioEngine.MixPhase.Idle -> if (_ui.value.phase == Phase.Preparing) _ui.value else Ui()
            FfmpegAudioEngine.MixPhase.Scheduled -> Ui(Phase.Ready, p?.first, p?.second)
            FfmpegAudioEngine.MixPhase.Mixing -> Ui(Phase.Mixing, p?.first, p?.second, status.promoted,
                status.plan?.let { com.ketvoice.app.automix.MixWords.label(it) }.orEmpty())
        }
    }

    private suspend fun watch() {
        while (currentCoroutineContextActive()) {
            delay(250)
            val e = engine ?: continue
            val status = e.mixStatus.value
            if (!AppSettings.automix.value) {
                if (status.phase == FfmpegAudioEngine.MixPhase.Scheduled) e.cancelMix()
                doneKey = null
                continue
            }
            if (status.phase != FfmpegAudioEngine.MixPhase.Idle) continue
            val state = Player.state.value
            val current = state.current ?: continue
            if (!state.isPlaying) continue
            val next = Player.automixNext() ?: continue
            val key = (state.queueEntryIds.getOrNull(state.index) ?: continue) + ">" + next.entryId
            if (key == doneKey) continue
            val duration = e.durationMs().takeIf { it > 0 } ?: continue
            val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
            if (duration - e.positionMs() > depth.outroMs + PREP_MS) continue
            val inSource = Player.preloadedSource(next.entryId) ?: continue
            val outSource = Player.loadedSource() ?: continue
            doneKey = key
            try {
                prepare(e, key, current, duration, next.track, outSource, inSource, depth)
            } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) {
                AppLog.w("ketmix", "transition not prepared: $t")
                if (_ui.value.phase == Phase.Preparing) _ui.value = Ui()
            }
        }
    }

    private suspend fun currentCoroutineContextActive() = kotlinx.coroutines.currentCoroutineContext().isActive

    private fun stillPair(key: String): Boolean {
        val s = Player.state.value
        val next = Player.automixNext() ?: return false
        return AppSettings.automix.value && (s.queueEntryIds.getOrNull(s.index) ?: return false) + ">" + next.entryId == key
    }

    private suspend fun prepare(e: FfmpegAudioEngine, key: String, current: Track, duration: Long, next: Track,
                                outSource: AudioSource, inSource: AudioSource, depth: AnalysisDepth) {
        _ui.value = Ui(Phase.Preparing, current, next)
        val (outro, intro, nextDuration) = withContext(Dispatchers.IO) {
            val outro = AnalysisStore.outro(current.id, duration, depth)
                ?: Segments.read(outSource, tail = true, depth)?.analysis?.also { AnalysisStore.put(current.id, duration, false, it) }
            var nextDuration = next.durationMs
            val intro = (if (nextDuration > 0) AnalysisStore.intro(next.id, nextDuration, depth) else null)
                ?: Segments.read(inSource, tail = false, depth)?.also { if (it.durationMs > 0) nextDuration = it.durationMs }?.analysis
                    ?.also { AnalysisStore.put(next.id, nextDuration, true, it) }
            AnalysisStore.save()
            Triple(outro, intro, nextDuration.takeIf { it > 0 } ?: 600_000L)
        }
        if (!stillPair(key) || e.mixStatus.value.phase != FfmpegAudioEngine.MixPhase.Idle) { _ui.value = Ui(); return }
        val state = Player.state.value
        val shape = MixPointStore.pair(current.id, next.id)?.shape ?: MixShape.DEFAULT
        val outSpeed = e.speed()
        val context = MixContext(
            sameAlbumInOrder = !state.repeatOne && !state.shuffle && current.album.isNotBlank() &&
                current.album.equals(next.album, ignoreCase = true) && current.artist.equals(next.artist, ignoreCase = true),
            repeatOne = state.repeatOne,
            loop = state.repeatOne && AppSettings.ketmixLoops.value && next.id == current.id,
            skipSilence = AppSettings.skipSilence.value,
            albumsMixed = AppSettings.automixAlbums.value,
            speed = outSpeed,
            lastVariant = lastVariant, lastExit = lastExit, recentKinds = recentKinds,
            userOverlapMs = MixPointStore.pair(current.id, next.id)?.overlapMs ?: -1L,
            // The desktop decks have no bar loop (the « boucle » blend): told as if one had just been played, the planner picks another.
            loopedLast = true)
        val settings = MixSettings(AppSettings.automixIntensity.value,
            MixPointStore.pair(current.id, next.id)?.style ?: MixStyle.of(AppSettings.ketmixStyle.value), tempoMatch = shape.tempo)
        val out = MixFacts(duration, outro, userExitMs = MixPointStore.exitOf(current.id, duration))
        val incoming = MixFacts(nextDuration, intro, userEntryMs = MixPointStore.entryOf(next.id, nextDuration))
        fun planFor(beats: Boolean): MixPlan = TransitionPlanner.plan(out, incoming, context.copy(beats = beats), settings)
            .let { MixShaping.apply(it, shape, true) }
            .let { p -> if (p.mixes && p.kind != MixKind.Beatmatch && abs(outSpeed - 1f) > .0005f) p.copy(rate = 1f / outSpeed) else p }
        var plan = planFor(beats = true)
        if (plan.mixes && plan.beatTied && e.renderedMs() > plan.outMs - preRoll(plan) - 1_000L) plan = planFor(beats = false)
        if (!plan.mixes || e.renderedMs() > plan.outMs - preRoll(plan) - 300L) {
            AppLog.i("ketmix", "no mix: ${plan.reason.ifBlank { "late" }}")
            _ui.value = Ui(); return
        }
        pair = current to next
        e.mixIntoNext(plan, preRoll(plan)) { ok -> if (!ok) { _ui.value = Ui() } }
        if (plan.kind == MixKind.Beatmatch || plan.kind == MixKind.Filter) lastVariant = plan.variant
        if (plan.kind == MixKind.Echo) lastExit = 1
        if (plan.kind == MixKind.Rise) lastExit = 2
        recentKinds = MixVariety.remember(recentKinds, plan)
        AppLog.i("ketmix", "mix planned: ${plan.kind} at ${plan.outMs} ms into ${plan.inMs} ms, ${plan.overlapMs} ms, speed ${plan.rate}")
    }

    /** How long before the plan's exit the incoming deck starts, silent (as on Android). */
    internal fun preRoll(p: MixPlan): Long = if (p.beatTied) min(2_500.0, p.inMs / p.rate.toDouble()).toLong() else min(400L, p.inMs)

    /**
     * « Suivant » with Ketmix on: a short filter blend into the next title (the phone's 3.2 s skip mix) when
     * it is held ready, instead of a cut. False leaves the skip to the player.
     */
    fun skipMix(): Boolean {
        val e = engine ?: return false
        if (!AppSettings.automix.value || !AppSettings.ketmixOnSkip.value) return false
        if (e.mixStatus.value.phase == FfmpegAudioEngine.MixPhase.Mixing) return false
        val state = Player.state.value
        if (state.repeatOne || !state.isPlaying) return false
        val current = state.current ?: return false
        val next = Player.automixNext() ?: return false
        Player.preloadedSource(next.entryId) ?: return false
        val duration = e.durationMs()
        val position = e.renderedMs()
        if (duration - position < SKIP_OVERLAP_MS + 6_000L) return false
        val nextDuration = next.track.durationMs.takeIf { it > 0 } ?: 600_000L
        val intro = AnalysisStore.intro(next.track.id, nextDuration)
        val inMs = MixPointStore.entryOf(next.track.id, nextDuration).takeIf { it in 0..(nextDuration - SKIP_OVERLAP_MS - 8_000L) }
            ?: intro?.soundStartMs?.coerceAtLeast(0L) ?: 0L
        val outro = AnalysisStore.outro(current.id, duration)
        val gain = if (outro != null && intro != null) Math.pow(10.0, ((outro.loudnessDb - intro.loudnessDb) / 20f).toDouble()).toFloat().coerceIn(.5f, 1f) else 1f
        val variant = if (lastVariant == 0) 1 else 0
        val plan = MixPlan(MixKind.Filter, outMs = position + SKIP_LEAD_MS, inMs = inMs, overlapMs = SKIP_OVERLAP_MS,
            gain = gain, reason = "skip", variant = variant, hold = .2f)
        lastVariant = variant
        pair = current to next.track
        doneKey = (state.queueEntryIds.getOrNull(state.index) ?: "") + ">" + next.entryId
        e.mixIntoNext(plan, min(SKIP_LEAD_MS - 200L, inMs)) { ok ->
            // Too late after all (the title moved on meanwhile): an ordinary skip.
            if (!ok) scope?.launch { bypass = true; try { Player.next() } finally { bypass = false } }
        }
        return true
    }

    /** Test and render seam: shows [ui] as if the engine played it. */
    internal fun preview(ui: Ui) { _ui.value = ui }

    /** Test seam. */
    internal fun resetForTest() { jobs.forEach { it.cancel() }; jobs = emptyList(); engine = null; doneKey = null; pair = null; _ui.value = Ui(); Player.skipHook = null }
}

/** Decodes a stretch of a title straight from its source and measures it with the shared analysis. */
internal object Segments {
    class Reading(val analysis: SegmentAnalysis?, val durationMs: Long)

    /** The ending ([tail]) or the start of [source], as long as [depth] asks for. Null when it cannot be read. */
    fun read(source: AudioSource, tail: Boolean, depth: AnalysisDepth): Reading? {
        val bytes = runCatching { Deck.openSource(source) }.getOrNull() ?: return null
        return try {
            FfmpegDecoder(bytes).use { d ->
                val duration = d.durationMs
                val window = if (tail) depth.outroMs else depth.introMs
                val from = if (tail) (duration - window).coerceAtLeast(0L) else 0L
                val until = if (duration > 0) minOf(duration, from + window) else from + window
                if (from > 0) d.seek(from)
                val tap = AnalysisTap(from * 1000L, until * 1000L, withKey = depth.key, thorough = depth.thorough,
                    beatTail = if (BeatModel.usable()) tail else null)
                feed(d, tap, from)
                Reading(tap.result(), duration)
            }
        } catch (t: Throwable) {
            AppLog.w("ketmix", "analysis read failed: $t"); null
        } finally { runCatching { bytes.close() } }
    }

    internal fun feed(d: FfmpegDecoder, tap: AnalysisTap, fromMs: Long) {
        val rate = FfmpegDecoder.OUT_RATE
        val chunk = FloatArray(4_096 * 2)
        val buffer = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var frame = fromMs * rate / 1000
        while (!tap.done) {
            val n = d.read(chunk, 0, 4_096)
            if (n < 0) break
            if (n == 0) continue
            buffer.clear()
            for (i in 0 until n * 2) buffer.putShort((chunk[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            buffer.flip()
            tap.pcm(buffer, frame * 1_000_000L / rate, rate, 2)
            frame += n
        }
    }
}
