package com.ketvoice.desktop.ketmix

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.EditHistory
import com.ketvoice.app.automix.LevelKey
import com.ketvoice.app.automix.Levels
import com.ketvoice.app.automix.MixEditor
import com.ketvoice.app.automix.MixKeys
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixPreset
import com.ketvoice.app.automix.MixPresetStore
import com.ketvoice.app.automix.MixShare
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.StudioSnap
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.automix.WaveBuilder
import com.ketvoice.app.automix.WaveStore
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.stems.DesktopVoice

/** One end of a title in the Studio: its wave and beat grid once read from its source. */
internal class StudioSide(val track: Track, val tail: Boolean, durationMs: Long) {
    var durationMs by mutableLongStateOf(durationMs)
    var wave by mutableStateOf<Wave?>(null)
    var analysis by mutableStateOf<SegmentAnalysis?>(null)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    @Volatile var source: AudioSource? = null

    /** The length the planner is given: the real one once known, as Ketmix gives an unknown next title 10 min. */
    val planDurationMs: Long get() = durationMs.takeIf { it > 0 } ?: if (tail) 0L else 600_000L
    fun side(): MixEditor.Side = MixEditor.Side(track.id, planDurationMs, wave, analysis)
}

/**
 * Ketmix Studio on the PC: the phone's editor state (`EditorState` of the Android `KetmixEditor.kt`) restated over
 * the shared code only. [choice] is what the listener changes ([MixEditor.Choice]); every change goes through
 * [change], so « Annuler » / « Rétablir » ([EditHistory]) see it; [save] writes it to [MixPointStore], which is
 * what [DesktopAutomix] plays. The plan drawn is [MixEditor.plan], the planner's own, so what is drawn is what plays.
 */
internal class StudioState(val from: Track, val to: Track, outDurationMs: Long = from.durationMs, inDurationMs: Long = to.durationMs,
                           private val intensity: Int = AppSettings.automixIntensity.value,
                           private val defaultStyle: MixStyle = MixStyle.of(AppSettings.ketmixStyle.value)) {
    val out = StudioSide(from, tail = true, outDurationMs)
    val into = StudioSide(to, tail = false, inDurationMs)
    private val history = EditHistory<MixEditor.Choice>()
    var choice by mutableStateOf(MixEditor.remembered(from.id, outDurationMs, to.id, inDurationMs, pair = true)); private set
    var saved by mutableStateOf(choice); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var magnet by mutableStateOf(MixPresetStore.magnet)
    /** The keyframe whose levels are open, or -1. */
    var selectedKey by mutableStateOf(-1)

    val dirty: Boolean get() = choice != saved

    fun plan(): MixPlan = MixEditor.plan(out.side(), into.side(), choice, intensity, defaultStyle)
    fun automatic(): MixPlan = MixEditor.automatic(out.side(), into.side(), intensity, defaultStyle)

    fun change(next: MixEditor.Choice) {
        if (next == choice) return
        history.record(choice)
        choice = next
        refresh()
    }

    fun undo() { history.undo(choice)?.let { choice = it; selectedKey = -1 }; refresh() }
    fun redo() { history.redo(choice)?.let { choice = it; selectedKey = -1 }; refresh() }
    private fun refresh() { canUndo = history.canUndo; canRedo = history.canRedo }

    // ---- Points (on the timeline, in each title's own clock) ----

    private fun tolerance(analysis: SegmentAnalysis?, msPerPx: Float) = MixEditor.tolerance(analysis, msPerPx, 10f)

    /** The box dragged so that the blend starts at [rawExitMs] of the outgoing title. */
    fun moveExit(rawExitMs: Long, msPerPx: Float) {
        val placed = MixEditor.place(out.analysis, rawExitMs, tolerance(out.analysis, msPerPx), !magnet, MixEditor.exitRange(out.planDurationMs))
        change(choice.copy(exitMs = placed.ms))
    }

    /** The incoming wave dragged so that it enters from [rawEntryMs]. */
    fun moveEntry(rawEntryMs: Long, msPerPx: Float) {
        val placed = MixEditor.place(into.analysis, rawEntryMs, tolerance(into.analysis, msPerPx), !magnet, MixEditor.entryRange(into.planDurationMs))
        change(choice.copy(entryMs = placed.ms))
    }

    /** The box's right edge dragged: the blend lasts [rawMs]. */
    fun resize(rawMs: Long) = change(choice.copy(overlapMs = rawMs.coerceIn(MIN_BLEND_MS, MixEditor.MAX_BLEND_MS)))

    fun style(style: MixStyle?) = change(choice.copy(style = style))
    fun shape(shape: com.ketvoice.app.automix.MixShape) = change(choice.copy(shape = shape))

    /** Back to Ketmix's own transition (« Revenir au choix de Ketmix »). */
    fun reset() { change(MixEditor.Choice()); selectedKey = -1 }

    // ---- Volume keyframes ([MixKeys]) ----

    fun canAddKey(plan: MixPlan = plan()): Boolean = plan.mixes && choice.keys.size < MixKeys.MAX

    /** A keyframe [relMs] into the blend, on the outgoing title's grid while the magnet is on. */
    fun snapKey(plan: MixPlan, relMs: Long, msPerPx: Float): Long =
        if (!magnet) relMs.coerceIn(0L, plan.overlapMs)
        else StudioSnap.key(out.analysis, plan.outMs, relMs, plan.overlapMs, tolerance(out.analysis, msPerPx))

    /** « + Point »: a keyframe at [relMs] (the preview's playhead) or in the middle; its levels open. The index, or -1. */
    fun addKey(relMs: Long? = null, msPerPx: Float = 0f): Int {
        val plan = plan()
        if (!canAddKey(plan)) return -1
        val rel = snapKey(plan, relMs?.takeIf { it in 0..plan.overlapMs } ?: (plan.overlapMs / 2), msPerPx)
        val keys = MixKeys.place(choice.keys, rel, plan.overlapMs)
        if (keys !== choice.keys) change(choice.copy(keys = keys))
        val index = MixKeys.near(choice.keys, rel, MixKeys.MIN_GAP_MS)
        selectedKey = index
        return index
    }

    fun moveKey(index: Int, relMs: Long, msPerPx: Float) {
        val plan = plan()
        change(choice.copy(keys = MixKeys.move(choice.keys, index, snapKey(plan, relMs, msPerPx), plan.overlapMs)))
    }

    fun setLevels(index: Int, outgoing: Levels? = null, incoming: Levels? = null) {
        val key = choice.keys.getOrNull(index) ?: return
        val next = key.copy(out = (outgoing ?: key.out).clamped(), into = (incoming ?: key.into).clamped())
        change(choice.copy(keys = choice.keys.toMutableList().also { it[index] = next }))
    }

    fun removeKey(index: Int) {
        if (index !in choice.keys.indices) return
        change(choice.copy(keys = choice.keys.filterIndexed { i, _ -> i != index }))
        selectedKey = -1
    }

    // ---- Presets ([MixPresetStore], `ketmix-presets-v1`) and share codes ([MixShare], `KM1.`) ----

    /** Applies [preset] to this pair: one step of undo. */
    fun applyPreset(preset: MixPreset) {
        val base = choice.copy(style = preset.style, overlapMs = preset.overlapMs, shape = preset.shape, keys = emptyList())
        val plan = MixEditor.plan(out.side(), into.side(), base, intensity, defaultStyle)
        change(base.copy(keys = if (plan.mixes) preset.keysFor(plan.overlapMs) else emptyList()))
        selectedKey = -1
    }

    /** This pair's transition saved as [name]; null when the list is full or the name blank. */
    fun saveAsPreset(name: String): MixPreset? = MixPresetStore.save(MixPreset.of("", name, choice.pairChoice, plan().overlapMs))

    fun shared(): MixShare.Shared = MixShare.Shared(MixShare.End(from.id, from.title, from.artist), MixShare.End(to.id, to.title, to.artist),
        choice.exitMs, choice.entryMs, choice.pairChoice)

    /** The text « Partager ce mix » copies: a sentence and the `KM1.` code. */
    fun shareText(): String = com.ketvoice.app.data.AppText.t("Mon mix Ketmix de « {0} » vers « {1} ». Dans Ketvoice, ouvrez Ketmix Studio sur ces deux titres, puis … › Importer un mix :")
        .replace("{0}", from.title).replace("{1}", to.title) + "\n" + MixShare.encode(shared())

    /** Takes a friend's mix: all of it on the same two titles, its transition alone on others. True for the same titles. */
    fun importShared(s: MixShare.Shared): Boolean {
        val same = s.from.id == from.id && s.to.id == to.id
        val c = s.choice
        change(choice.copy(exitMs = if (same) s.exitMs else choice.exitMs, entryMs = if (same) s.entryMs else choice.entryMs,
            overlapMs = c.overlapMs, style = c.style, shape = c.shape, keys = c.keys))
        selectedKey = -1
        return same
    }

    /** « Enregistrer »: the points belong to each title, the rest to the pair; Ketmix plans the pair again. */
    fun save() {
        val c = choice
        MixPointStore.setExit(from.id, out.planDurationMs, c.exitMs)
        MixPointStore.setEntry(to.id, into.planDurationMs, c.entryMs)
        MixPointStore.setPair(from.id, to.id, c.pairChoice)
        saved = c
        DesktopAutomix.pointsChanged()
    }

    // ---- Reading both ends (waves and beat grids) ----

    /** Reads the outgoing title's ending and the incoming title's start (decoding: call it on a background dispatcher). */
    suspend fun load(resolve: suspend (Track) -> AudioSource?) {
        for (side in listOf(out, into)) {
            runCatching { read(side, resolve) }.onFailure { side.failed = true }
            side.loading = false
        }
    }

    private suspend fun read(side: StudioSide, resolve: suspend (Track) -> AudioSource?) {
        val source = side.source ?: resolve(side.track) ?: run { side.failed = true; return }
        side.source = source
        val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
        val known = side.durationMs.takeIf { it > 0 }
        side.analysis = known?.let { if (side.tail) AnalysisStore.outro(side.track.id, it, depth) else AnalysisStore.intro(side.track.id, it, depth) }
        val kept = known?.let { WaveStore.read(side.track.id, it) }?.let { if (side.tail) it.tail else it.head }
        if (kept != null) side.wave = kept
        if (side.analysis == null) Segments.read(source, side.tail, depth)?.let { r ->
            if (r.durationMs > 0) side.durationMs = r.durationMs
            side.analysis = r.analysis
            r.analysis?.let { AnalysisStore.put(side.track.id, side.durationMs, !side.tail, it) }
        }
        if (side.wave == null) {
            val length = side.durationMs.takeIf { it > 0 } ?: return
            val from = if (side.tail) (length - MixEditor.TAIL_MS).coerceAtLeast(0L) else 0L
            val until = if (side.tail) length else minOf(length, MixEditor.HEAD_MS)
            val pcm = DesktopVoice.decode(source, from, until) ?: run { side.failed = true; return }
            side.wave = wave(pcm, from)?.also { WaveStore.put(side.track.id, length, it) }
        }
    }

    companion object {
        const val MIN_BLEND_MS = 1_000L
        /** A wave of 16-bit stereo at the decks' rate that starts at [startMs] of its title. */
        fun wave(pcm: ShortArray, startMs: Long): Wave? =
            WaveBuilder(com.ketvoice.desktop.audio.FfmpegDecoder.OUT_RATE, 2, startMs).apply { accept(pcm) }.build().takeIf { it.columns >= 20 }

        /** The pair the Studio opens on for [track]: it and the title after it in [queue]; null without one. */
        fun pairFor(track: Track, queue: List<Track>): Pair<Track, Track>? {
            val i = queue.indexOfFirst { it.id == track.id }
            val next = queue.getOrNull(i + 1)?.takeIf { i >= 0 && it.id != track.id } ?: return null
            return track to next
        }
    }
}

/** The Studio window's request: what it is open on, from the bar, a title's menu or Settings. */
object KetmixStudioDesk {
    data class Request(val from: Track, val to: Track)
    val request = mutableStateOf<Request?>(null)
    /** Test and render seam: the state the window opens on for its request, already set up. */
    internal var prepared: StudioState? = null
    /** Where the Studio reads a title from: the source the Player has for it, else a download, else a stream. */
    var resolve: suspend (Track) -> AudioSource? = { t ->
        Player.loadedSource()?.takeIf { Player.state.value.current?.id == t.id } ?: com.ketvoice.desktop.player.DesktopStreams.source(t)
    }

    /** [track] into the title after it in the queue; false when there is none. */
    fun openFor(track: Track, queue: List<Track>): Boolean {
        val (from, to) = StudioState.pairFor(track, queue) ?: return false
        request.value = Request(from, to); return true
    }

    /** The transition about to play. */
    fun openNext(): Boolean {
        val s = Player.state.value
        val current = s.current ?: return false
        val next = Player.automixNext()?.track ?: s.queue.getOrNull(s.index + 1) ?: return false
        request.value = Request(current, next); return true
    }

    fun close() { request.value = null; StudioPreview.stop() }
}
