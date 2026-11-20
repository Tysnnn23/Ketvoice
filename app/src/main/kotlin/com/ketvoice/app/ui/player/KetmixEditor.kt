package com.ketvoice.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.GridSnap
import com.ketvoice.app.automix.MixEditor
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixShape
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.TransitionPreview
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.automix.WaveStore
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetProgressBar
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.turnedBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * « Éditeur Ketmix » (6.1, redone in 6.9 after the owner's « rends-le plus simple d'utilisation avec preview »):
 * one timeline where the outgoing title's ending lies over the incoming title's start on the same clock, the
 * transition a box to drag and resize, the curves Ketmix will play drawn over the waves, presets, the details of
 * the blend, and « Écouter » that plays the transition alone. Choices are kept with « Enregistrer ».
 * Opened from a long press on the Ketmix switch (the transition about to play) and from a title's menu
 * (« Point de mix… »). Drawn once, at the root of the app.
 */
internal object KetmixEditor {
    /** [to] null: a title alone, its ending and its start. */
    data class Request(val from: Track, val to: Track?)

    val request = mutableStateOf<Request?>(null)

    /** The transition about to play: the title playing into the next one. */
    fun openNext() {
        val current = Player.state.value.current ?: return
        request.value = Request(current, Player.automixNext()?.track)
    }

    /** From a title's menu: that title into the one after it in the queue, or the title alone. */
    fun openFor(track: Track) {
        val state = Player.state.value
        if (state.current?.id == track.id) { openNext(); return }
        val index = state.queue.indexOfFirst { it.id == track.id }
        request.value = Request(track, if (index >= 0) state.queue.getOrNull(index + 1) else null)
    }

    fun close() {
        request.value = null
        TransitionPreview.engine.stop()
        // The transition about to play is planned again with what was chosen.
        Automix.pointsChanged()
    }
}

/** One end of a title in the editor, and how far it is from being drawn. */
internal class EditorSide(val track: Track, val tail: Boolean, durationMs: Long) {
    var durationMs by mutableLongStateOf(durationMs)
    var wave by mutableStateOf<Wave?>(null)
    var analysis by mutableStateOf<SegmentAnalysis?>(null)
    /** 0..1 while its wave is being read, null otherwise. */
    var progress by mutableStateOf<Float?>(null)
    /** Why it could not be read (a French literal), or null. */
    var problem by mutableStateOf<String?>(null)
    /** Where somebody sings in it, from its separated voice ([com.ketvoice.app.stems.VoiceActivity]); null when unknown. */
    var sung by mutableStateOf<List<LongRange>?>(null)
    /** Where its synced lyric lines start, when they are on the device. */
    var lyricStarts by mutableStateOf<List<Long>>(emptyList())

    /** The length the planner is given: the real one once known, as Ketmix gives an unknown next title 10 min. */
    val planDurationMs: Long get() = durationMs.takeIf { it > 0 } ?: if (tail) 0L else 600_000L
    fun side(): MixEditor.Side = MixEditor.Side(track.id, planDurationMs, wave, analysis)
    val window: LongRange get() = if (tail) (durationMs - MixEditor.TAIL_MS).coerceAtLeast(0L)..durationMs.coerceAtLeast(0L)
        else 0L..minOf(MixEditor.HEAD_MS, planDurationMs)
}

/**
 * Everything the editor shows; built by [KetmixEditorHost] from the stores, or by a test. What the listener changes
 * is [choice]; [saved] is what the stores hold, written by [save] (« Enregistrer »).
 */
internal class EditorState(val from: Track, to: Track?, outDurationMs: Long, inDurationMs: Long) {
    /** A pair of titles, or a title alone (its ending and its start). */
    val pair: Boolean = to != null && to.id != from.id
    val out = EditorSide(from, tail = true, outDurationMs)
    val into = EditorSide(to ?: from, tail = false, if (to == null) outDurationMs else inDurationMs)
    var choice by mutableStateOf(MixEditor.Choice())
    var saved by mutableStateOf(MixEditor.Choice())
    var dragExit by mutableStateOf<Long?>(null)
    var dragEntry by mutableStateOf<Long?>(null)
    var dragOverlap by mutableStateOf<Long?>(null)
    var vocalEndMs by mutableStateOf<Long?>(null)
    var intensity by mutableIntStateOf(AppSettings.automixIntensity.value)
    var defaultStyle by mutableStateOf(MixStyle.of(AppSettings.ketmixStyle.value))
    /** How far the timeline is zoomed in (1 = the stretch [MixEditor.view] gives). */
    var zoom by mutableFloatStateOf(1f)
    /** « A/B »: « Écouter » plays the same moment as a plain cut. */
    var plainCut by mutableStateOf(false)
    /** 7.6 (voice76): the volume keyframe whose sheet is open, and the one being dragged (its index and new time). */
    var keyOpen by mutableStateOf<Int?>(null)
    var dragKey by mutableStateOf<Pair<Int, Long>?>(null)
    /** The keyframe under the finger that went down on the timeline, or -1. */
    var keyGrabbed = -1
    /** Whether each title is separated whole: its voice and instruments can then be set at a keyframe. */
    var outSeparated by mutableStateOf(false)
    var inSeparated by mutableStateOf(false)

    val dirty: Boolean get() = choice != saved
    val effective: MixEditor.Choice get() = choice.copy(exitMs = dragExit ?: choice.exitMs, entryMs = dragEntry ?: choice.entryMs,
        overlapMs = dragOverlap ?: choice.overlapMs,
        keys = dragKey?.let { (i, at) -> com.ketvoice.app.automix.MixKeys.move(choice.keys, i, at, MixEditor.MAX_BLEND_MS) } ?: choice.keys)
    fun plan(): MixPlan = MixEditor.plan(out.side(), into.side(), effective, intensity, defaultStyle, vocalEndMs)
    fun automatic(): MixPlan = MixEditor.automatic(out.side(), into.side(), intensity, defaultStyle, vocalEndMs)

    fun recall() {
        choice = MixEditor.remembered(from.id, out.planDurationMs, into.track.id, into.planDurationMs, pair)
        saved = choice
    }

    fun commitExit(ms: Long) { choice = choice.copy(exitMs = ms) }
    fun commitEntry(ms: Long) { choice = choice.copy(entryMs = ms) }
    fun commitOverlap(ms: Long) { choice = choice.copy(overlapMs = ms) }
    /** A preset; null = « Auto », Ketmix decides (even when the settings name another style). */
    fun commitStyle(style: MixStyle?) {
        choice = choice.copy(style = style ?: MixStyle.Auto.takeIf { defaultStyle != MixStyle.Auto })
    }
    fun commitShape(shape: MixShape) { choice = choice.copy(shape = shape) }
    /** « Revenir au choix de Ketmix »: kept once saved. */
    fun reset() { choice = MixEditor.Choice() }

    /** « Enregistrer »: the exit with the outgoing title, the entry with the incoming one, the rest with the pair. */
    fun save() {
        if (choice.exitMs != saved.exitMs) MixPointStore.setExit(from.id, out.durationMs, choice.exitMs)
        if (choice.entryMs != saved.entryMs) MixPointStore.setEntry(into.track.id, into.planDurationMs, choice.entryMs)
        if (pair) MixPointStore.setPair(from.id, into.track.id, choice.pairChoice)
        saved = choice
    }
}

/** Draws the editor that was asked for, over everything else. Place it once, at the root. */
@Composable
internal fun KetmixEditorHost(modifier: Modifier = Modifier) {
    val request = KetmixEditor.request.value ?: return
    val context = LocalContext.current
    val state = remember(request) {
        val playing = Player.state.value
        val outLength = if (playing.current?.id == request.from.id && playing.durationMs > 0) playing.durationMs else request.from.durationMs
        EditorState(request.from, request.to, outLength, request.to?.durationMs ?: 0L)
    }
    LaunchedEffect(request) {
        withContext(Dispatchers.IO) {
            AnalysisStore.init(context); WaveStore.init(context); MixPointStore.init(context)
        }
        state.recall()
        // Where the outgoing title stops singing, as Ketmix reads it (lyrics of the title playing).
        if (Player.state.value.current?.id == state.from.id)
            state.vocalEndMs = com.ketvoice.app.automix.Vocals.endMs(com.ketvoice.app.data.LyricsRepo.state.value.lines, state.out.durationMs)
        for (side in listOf(state.out, state.into)) load(side)
        state.recall()
        for (side in listOf(state.out, state.into)) voices(side)
    }
    // 7.6: voice and instruments at a keyframe need both separations; one done meanwhile is taken up here.
    LaunchedEffect(request) {
        state.readSeparations()
        com.ketvoice.app.stems.VoiceSeparation.separated.collect { state.readSeparations() }
    }
    KetmixEditorScreen(state, onClose = { KetmixEditor.close() }, modifier = modifier)
}

/** Where each title sings: its separated voice when it was separated, its synced lyrics when they are on the device. */
private suspend fun voices(side: EditorSide) {
    val length = side.durationMs.takeIf { it > 0 } ?: return
    side.sung = withContext(Dispatchers.IO) {
        runCatching { com.ketvoice.app.stems.StemStore.load(side.track.id, length, !side.tail)?.let(com.ketvoice.app.stems.VoiceActivity::passages) }.getOrNull()
    }
    val lyrics = com.ketvoice.app.data.LyricsRepo.state.value
    val lines = if (lyrics.trackId == side.track.id && lyrics.isTimed) lyrics.lines
        else withContext(Dispatchers.IO) { runCatching { com.ketvoice.app.data.Downloader.localLyrics(side.track.id)?.second }.getOrNull() }
    side.lyricStarts = lines.orEmpty().filter { it.text.isNotBlank() && it.timeMs in side.window }.map { it.timeMs }
}

/** Takes what is kept of [side]; reads it on a silent deck when its wave or its analysis is missing (titles of before 6.1). */
private suspend fun load(side: EditorSide) {
    fun kept() {
        val length = side.durationMs
        val entry = WaveStore.read(side.track.id, length) ?: side.track.durationMs.takeIf { !side.tail && it > 0 && it != length }?.let { WaveStore.read(side.track.id, it) }
        side.wave = if (side.tail) entry?.tail else entry?.head
        side.analysis = if (side.tail) AnalysisStore.outro(side.track.id, length, AnalysisDepth.Light)
            else AnalysisStore.intro(side.track.id, side.track.durationMs, AnalysisDepth.Light) ?: AnalysisStore.intro(side.track.id, length, AnalysisDepth.Light)
                ?: AnalysisStore.intro(side.track.id, 600_000L, AnalysisDepth.Light)
    }
    withContext(Dispatchers.IO) { kept() }
    val window = side.window
    val complete = side.durationMs > 0 && side.analysis != null && side.wave?.covers(window.first, window.last) == true
    if (complete) return
    side.progress = 0f
    val result = Automix.loadForEditor(side.track, side.tail) { side.progress = it }
    side.progress = null
    when (result) {
        is Automix.EditorLoad.Ready -> { side.durationMs = if (side.tail || side.durationMs <= 0) result.durationMs else side.durationMs; withContext(Dispatchers.IO) { kept() } }
        Automix.EditorLoad.NoPlayer -> side.problem = "Lancez la lecture d'un titre pour dessiner les ondes"
        Automix.EditorLoad.Exact -> side.problem = "Ondes indisponibles en sortie bit-perfect"
        Automix.EditorLoad.Unavailable -> side.problem = "Ce titre n'a pas pu être lu"
    }
}

/** The colours of the three bands of a wave: bass, mids, treble. */
internal class WavePalette(val low: Color, val mid: Color, val high: Color)

@Composable
internal fun wavePalette(): WavePalette = if (LocalTheme.current.isDark) WavePalette(Color(0xFF4F8DFF), Color(0xFFFFA24C), Color(0xFFF2F2F7))
    else WavePalette(Color(0xFF2F6BEA), Color(0xFFE5822A), Color(0xFF3A3A3C))

@Composable
internal fun KetmixEditorScreen(state: EditorState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    var asking by remember { mutableStateOf(false) }
    val close = { if (state.dirty) asking = true else onClose() }
    androidx.activity.compose.BackHandler { close() }
    DisposableEffect(Unit) { onDispose { if (TransitionPreview.engine.state.value.running) TransitionPreview.engine.stop() } }
    BoxWithConstraints(modifier.fillMaxSize().background(theme.background).testTag("ketmix-editor")) {
        val wide = LargeScreen.isLarge(maxWidth.value, maxHeight.value, BuildConfig.IS_QUEST)
        val timelineHeight = if (wide) (maxHeight - 330.dp).coerceIn(220.dp, 420.dp) else 232.dp
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            EditorTopBar(state, onClose = close)
            val plan = state.plan()
            if (wide) {
                // A wide window: the timeline takes the room, the controls stand in a column beside it.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        TimelineBlock(state, plan, wide = true, height = timelineHeight)
                    }
                    Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 24.dp)) {
                        Settings(state, plan, wide = true)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = Ket27.Metrics.margin)) {
                        TimelineBlock(state, plan, wide = false, height = timelineHeight)
                        Spacer(Modifier.height(20.dp))
                        Settings(state, plan, wide = false)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        state.keyOpen?.let { KeyframeSheet(state, it) }
    }
    if (asking) AppAlertDialog(onDismissRequest = { asking = false },
        confirmButton = { KetTextButton("Enregistrer", onClick = { asking = false; state.save(); onClose() }, primary = true) },
        dismissButton = { KetTextButton("Ne pas enregistrer", onClick = { asking = false; onClose() }, destructive = true) },
        title = { Text("Enregistrer cette transition ?") },
        text = { Text("Vos réglages seront perdus si vous fermez sans enregistrer.") })
}

/** What a point is on, said in a word beside its time. */
internal fun gridWord(kind: GridSnap.Kind): String? = when (kind) {
    GridSnap.Kind.Phrase -> "phrase"
    GridSnap.Kind.Bar -> "mesure"
    GridSnap.Kind.Beat -> "temps"
    GridSnap.Kind.None -> null
}

/** Where the exit and the entry stand for [plan], as drawn (the listener's points when nothing is mixed). */
internal fun EditorState.exitOf(plan: MixPlan): Long =
    if (plan.mixes) plan.outMs else (effective.exitMs.takeIf { it >= 0 } ?: (out.durationMs - 12_000L).coerceAtLeast(0L))
internal fun EditorState.entryOf(plan: MixPlan): Long = if (plan.mixes) plan.inMs else effective.entryMs.coerceAtLeast(0L)

/** The headers, the overviews and the timeline, with « Écouter » under them. */
@Composable
private fun TimelineBlock(state: EditorState, plan: MixPlan, wide: Boolean, height: Dp) {
    val auto = state.automatic()
    val palette = wavePalette()
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    val tick: (GridSnap.Kind) -> Unit = { kind ->
        if (kind != GridSnap.Kind.None && AppSettings.ketmixHaptics.value)
            haptic(if (kind == GridSnap.Kind.Phrase) com.ketvoice.app.ui.motion.Haptics.Kind.Confirm else com.ketvoice.app.ui.motion.Haptics.Kind.Tick)
    }
    val exitMs = state.exitOf(plan)
    val entryMs = state.entryOf(plan)
    val rate = if (plan.mixes) plan.rate else 1f
    val window = TimelineWindow.of(plan, exitMs, wide, state.zoom)
    PointHeader("Sortie", state.out.track, exitMs, GridSnap.kindAt(state.out.analysis, exitMs), listenerSet = state.effective.exitMs >= 0,
        onStep = { forward ->
            val next = MixEditor.step(state.out.analysis, exitMs, forward, MixEditor.exitRange(state.out.durationMs))
            state.commitExit(next); tick(GridSnap.kindAt(state.out.analysis, next))
        })
    Spacer(Modifier.height(6.dp))
    Overview(state.out, pointMs = exitMs, autoMs = auto.outMs.takeIf { auto.mixes }, visible = window.fromMs..window.untilMs,
        range = MixEditor.exitRange(state.out.durationMs), palette = palette, tag = "ketmix-overview-out",
        onMove = { ms, kind -> state.dragExit = ms; tick(kind) }, onEnd = { state.dragExit?.let(state::commitExit); state.dragExit = null })
    Spacer(Modifier.height(4.dp))
    TimelineCaption(state)
    KetmixTimeline(state, plan, auto, window, height, tick)
    Spacer(Modifier.height(8.dp))
    Overview(state.into, pointMs = entryMs, autoMs = auto.inMs.takeIf { auto.mixes },
        visible = (entryMs + ((window.fromMs - exitMs) * rate).toLong())..(entryMs + ((window.untilMs - exitMs) * rate).toLong()),
        range = MixEditor.entryRange(state.into.planDurationMs), palette = palette, tag = "ketmix-overview-in",
        onMove = { ms, kind -> state.dragEntry = ms; tick(kind) }, onEnd = { state.dragEntry?.let(state::commitEntry); state.dragEntry = null })
    Spacer(Modifier.height(6.dp))
    PointHeader("Entrée", state.into.track, entryMs, GridSnap.kindAt(state.into.analysis, entryMs), listenerSet = state.effective.entryMs >= 0,
        onStep = { forward ->
            val next = MixEditor.step(state.into.analysis, entryMs, forward, MixEditor.entryRange(state.into.planDurationMs))
            state.commitEntry(next); tick(GridSnap.kindAt(state.into.analysis, next))
        })
    Spacer(Modifier.height(14.dp))
    Transport(state, plan)
}

/** « Sortie · title — artist » with the point's time, what it is on, and arrows that move it by a beat. */
@Composable
internal fun PointHeader(label: String, track: Track, pointMs: Long, kind: GridSnap.Kind, listenerSet: Boolean, onStep: (Boolean) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (listenerSet) theme.accent else theme.secondary, style = Ket27.Type.footnote, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Text("${track.title} — ${track.artist}", localize = false, color = theme.onBackground, style = Ket27.Type.subheadline,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val word = gridWord(kind)
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(MixEditor.clock(pointMs), localize = false, color = theme.onBackground, style = Ket27.Type.headline.copy(fontFeatureSettings = "tnum"))
            Text(word ?: if (listenerSet) "libre" else "automatique", color = theme.secondary, style = Ket27.Type.footnote)
        }
        StepButton(back = true, label = "Reculer d'un temps") { onStep(false) }
        StepButton(back = false, label = "Avancer d'un temps") { onStep(true) }
    }
}

@Composable
private fun StepButton(back: Boolean, label: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val description = AppText.t(label)
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(theme.onBackground.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            Icon(KetIcons.ChevronRight, null, tint = theme.onBackground, modifier = Modifier.size(16.dp).let { if (back) it.turnedBack() else it })
        }
    }
}

/** While a wave is read, or when it cannot be: said in the middle of its lane. */
@Composable
internal fun LaneState(side: EditorSide, modifier: Modifier) {
    val theme = LocalTheme.current
    val progress = side.progress
    val problem = side.problem
    if (side.wave != null && progress == null) return
    Column(modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(theme.background.copy(alpha = .86f)).padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            progress != null -> {
                Text(AppText.t("Lecture de l'onde… {0} %").replace("{0}", (progress * 100).toInt().toString()), localize = false,
                    color = theme.secondary, style = Ket27.Type.footnote)
                Spacer(Modifier.height(6.dp))
                KetProgressBar({ progress }, Modifier.fillMaxWidth())
            }
            problem != null -> Text(problem, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 2)
            else -> Text("Onde en attente", color = theme.secondary, style = Ket27.Type.footnote)
        }
    }
}

/**
 * The whole stretch the editor shows of a title, small: what the lanes show is outlined, the point is a line
 * that a finger moves directly (a tap puts it there).
 */
@Composable
internal fun Overview(side: EditorSide, pointMs: Long, autoMs: Long?, visible: LongRange, range: LongRange, palette: WavePalette, tag: String,
                     onMove: (Long, GridSnap.Kind) -> Unit, onEnd: () -> Unit) {
    val theme = LocalTheme.current
    val window = side.window
    val length = (window.last - window.first).coerceAtLeast(1L)
    var widthPx by remember { mutableIntStateOf(1) }
    val analysis by rememberUpdatedState(side.analysis)
    val windowStart by rememberUpdatedState(window.first)
    val lengthNow by rememberUpdatedState(length)
    var x by remember { mutableFloatStateOf(0f) }
    var last by remember { mutableLongStateOf(-1L) }
    var free by remember { mutableStateOf(false) }
    val limits by rememberUpdatedState(range)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    val pull = 10f * androidx.compose.ui.platform.LocalDensity.current.density
    fun place(px: Float) {
        val ms = windowStart + (px / widthPx.coerceAtLeast(1) * lengthNow).toLong()
        val placed = MixEditor.place(analysis, ms, MixEditor.tolerance(analysis, lengthNow.toFloat() / widthPx.coerceAtLeast(1), pull), free, limits)
        if (placed.ms != last) { last = placed.ms; move(placed.ms, placed.kind) }
    }
    Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp)).background(theme.onBackground.copy(alpha = .05f)).testTag(tag)
        .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
        .pointerInput(side) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var moved: PointerInputChange? = null
                val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    moved = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    true
                }
                free = decided == null
                last = -1L
                x = down.position.x
                if (decided != null && moved == null) {
                    // Lifted without moving: a tap puts the point there; a vertical move belongs to the page.
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (up != null && !up.pressed && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) { place(x); end() }
                    return@awaitEachGesture
                }
                place(moved?.position?.x ?: x)
                horizontalDrag(moved?.id ?: down.id) { change -> place(change.position.x); change.consume() }
                end()
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val toX = { t: Long -> (t - window.first).toFloat() / length * size.width }
            val wave = side.wave
            if (wave != null) {
                val middle = size.height / 2f
                val step = 3f
                var px = step / 2f
                while (px < size.width) {
                    val t = window.first + (px / size.width * length).toLong()
                    val c = wave.columnAt(t)
                    if (c >= 0) {
                        val r = waveShape(wave.level(c), 1.9f) * middle * .82f
                        val bass = (wave.lowAt(c) / wave.level(c).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
                        drawLine(lerp(palette.mid, palette.low, bass).copy(alpha = .7f), Offset(px, middle - r - .5f), Offset(px, middle + r + .5f), 1.6f)
                    }
                    px += step
                }
            }
            val v0 = toX(visible.first).coerceIn(0f, size.width)
            val v1 = toX(visible.last).coerceIn(0f, size.width)
            if (v1 > v0) drawRoundRect(theme.onBackground.copy(alpha = .35f), Offset(v0, 1f), Size(v1 - v0, size.height - 2f),
                CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            if (autoMs != null) {
                val ax = toX(autoMs)
                if (ax in 0f..size.width) drawLine(theme.secondary, Offset(ax, size.height * .2f), Offset(ax, size.height * .8f), 1.dp.toPx())
            }
            val px = toX(pointMs)
            if (px in -2f..size.width + 2f) drawLine(theme.accent, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

/** How tall a linear level is drawn (0..1): compressed so that quiet parts show, with room left for the kicks to stand out. */
internal fun waveShape(linear: Float, gain: Float): Float = (Math.pow(linear.coerceIn(0f, 1f).toDouble(), .7).toFloat() * gain).coerceAtMost(1f)
