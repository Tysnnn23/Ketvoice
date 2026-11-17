package com.ketvoice.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.GridSnap
import com.ketvoice.app.automix.MixEditor
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.MixWords
import com.ketvoice.app.automix.PairChoice
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.automix.WaveStore
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.GlassSlider
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetProgressBar
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Ket27PageBar
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TrackActionFeedback
import com.ketvoice.app.ui.common.turnedBack
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * « Éditeur Ketmix » (6.1, owner: « formes d'onde des deux titres, et tu choisis toi-même le point d'entrée
 * et de sortie, mémorisés pour la prochaine fois »): the outgoing title's ending over the incoming title's
 * start, aligned on the moment the mix starts, with their beat grids, the exit, the entry and the blend.
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

    /** The length the planner is given: the real one once known, as Ketmix gives an unknown next title 10 min. */
    val planDurationMs: Long get() = durationMs.takeIf { it > 0 } ?: if (tail) 0L else 600_000L
    fun side(): MixEditor.Side = MixEditor.Side(track.id, planDurationMs, wave, analysis)
    val window: LongRange get() = if (tail) (durationMs - MixEditor.TAIL_MS).coerceAtLeast(0L)..durationMs.coerceAtLeast(0L)
        else 0L..minOf(MixEditor.HEAD_MS, planDurationMs)
}

/** Everything the editor shows; built by [KetmixEditorHost] from the stores, or by a test. */
internal class EditorState(val from: Track, to: Track?, outDurationMs: Long, inDurationMs: Long) {
    /** A pair of titles, or a title alone (its ending and its start). */
    val pair: Boolean = to != null && to.id != from.id
    val out = EditorSide(from, tail = true, outDurationMs)
    val into = EditorSide(to ?: from, tail = false, if (to == null) outDurationMs else inDurationMs)
    var choice by mutableStateOf(MixEditor.Choice())
    var dragExit by mutableStateOf<Long?>(null)
    var dragEntry by mutableStateOf<Long?>(null)
    var dragOverlap by mutableStateOf<Long?>(null)
    var vocalEndMs by mutableStateOf<Long?>(null)
    var intensity by mutableIntStateOf(AppSettings.automixIntensity.value)
    var defaultStyle by mutableStateOf(MixStyle.of(AppSettings.ketmixStyle.value))

    val effective: MixEditor.Choice get() = choice.copy(exitMs = dragExit ?: choice.exitMs, entryMs = dragEntry ?: choice.entryMs,
        overlapMs = dragOverlap ?: choice.overlapMs)
    fun plan(): MixPlan = MixEditor.plan(out.side(), into.side(), effective, intensity, defaultStyle, vocalEndMs)
    fun automatic(): MixPlan = MixEditor.automatic(out.side(), into.side(), intensity, defaultStyle, vocalEndMs)

    fun recall() {
        choice = MixEditor.remembered(from.id, out.planDurationMs, into.track.id, into.planDurationMs, pair)
    }

    fun commitExit(ms: Long) { MixPointStore.setExit(from.id, out.durationMs, ms); choice = choice.copy(exitMs = ms) }
    fun commitEntry(ms: Long) { MixPointStore.setEntry(into.track.id, into.planDurationMs, ms); choice = choice.copy(entryMs = ms) }
    fun commitOverlap(ms: Long) {
        choice = choice.copy(overlapMs = ms)
        if (pair) MixPointStore.setPair(from.id, into.track.id, PairChoice(choice.style, ms))
    }
    fun commitStyle(style: MixStyle) {
        choice = choice.copy(style = style)
        if (pair) MixPointStore.setPair(from.id, into.track.id, PairChoice(style, choice.overlapMs))
    }
    fun reset() {
        MixPointStore.reset(from.id, if (pair) into.track.id else from.id)
        if (!pair) MixPointStore.setEntry(from.id, out.durationMs, -1)
        choice = MixEditor.Choice()
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
    }
    androidx.activity.compose.BackHandler { KetmixEditor.close() }
    KetmixEditorScreen(state, onClose = { KetmixEditor.close() }, modifier = modifier)
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
private class WavePalette(val low: Color, val mid: Color, val high: Color)

@Composable
internal fun KetmixEditorScreen(state: EditorState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    BoxWithConstraints(modifier.fillMaxSize().background(theme.background).testTag("ketmix-editor")) {
        val wide = LargeScreen.isLarge(maxWidth.value, maxHeight.value, BuildConfig.IS_QUEST)
        val tallLanes = ((maxHeight - 380.dp) / 2).coerceIn(132.dp, 260.dp)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Ket27PageBar("Éditeur Ketmix", onClose)
            val plan = state.plan()
            if (wide) {
                // A wide window: the waves take the room, the controls stand in a column beside them.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        Lanes(state, plan, wide = true, laneHeight = tallLanes)
                    }
                    Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        Controls(state, plan)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = Ket27.Metrics.margin)) {
                        Lanes(state, plan, wide = false, laneHeight = 96.dp)
                        Spacer(Modifier.height(18.dp))
                        Controls(state, plan)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** What a point is on, said in a word beside its time. */
private fun gridWord(kind: GridSnap.Kind): String? = when (kind) {
    GridSnap.Kind.Phrase -> "phrase"
    GridSnap.Kind.Bar -> "mesure"
    GridSnap.Kind.Beat -> "temps"
    GridSnap.Kind.None -> null
}

@Composable
private fun Lanes(state: EditorState, plan: MixPlan, wide: Boolean, laneHeight: Dp) {
    val theme = LocalTheme.current
    val auto = state.automatic()
    val view = MixEditor.view(plan, wide)
    val palette = if (theme.isDark) WavePalette(Color(0xFF4F8DFF), Color(0xFFFFA24C), Color(0xFFF2F2F7))
        else WavePalette(Color(0xFF2F6BEA), Color(0xFFE5822A), Color(0xFF3A3A3C))
    val exitMs = if (plan.mixes) plan.outMs else (state.effective.exitMs.takeIf { it >= 0 } ?: (state.out.durationMs - 12_000L).coerceAtLeast(0L))
    val entryMs = if (plan.mixes) plan.inMs else state.effective.entryMs.coerceAtLeast(0L)
    val rate = if (plan.mixes) plan.rate else 1f
    val haptic = rememberHaptics()
    fun tick(kind: GridSnap.Kind) {
        if (kind == GridSnap.Kind.None || !AppSettings.ketmixHaptics.value) return
        haptic(if (kind == GridSnap.Kind.Phrase) Haptics.Kind.Confirm else Haptics.Kind.Tick)
    }

    PointHeader("Sortie", state.out.track, exitMs, GridSnap.kindAt(state.out.analysis, exitMs),
        listenerSet = state.effective.exitMs >= 0,
        onStep = { forward ->
            val next = MixEditor.step(state.out.analysis, exitMs, forward, MixEditor.exitRange(state.out.durationMs))
            state.commitExit(next); tick(GridSnap.kindAt(state.out.analysis, next))
        })
    Spacer(Modifier.height(8.dp))
    Overview(state.out, pointMs = exitMs, autoMs = auto.outMs.takeIf { auto.mixes }, visible = (exitMs - view.beforeMs)..(exitMs + view.afterMs),
        range = MixEditor.exitRange(state.out.durationMs), palette = palette, tag = "ketmix-overview-out",
        onMove = { ms, kind -> state.dragExit = ms; tick(kind) }, onEnd = { state.dragExit?.let(state::commitExit); state.dragExit = null })
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.onBackground.copy(alpha = if (theme.isDark) .06f else .045f))) {
        Column {
            DetailLane(state.out, anchorMs = exitMs, rate = 1f, view = view, plan = plan, outgoing = true, autoMs = auto.outMs.takeIf { auto.mixes && it != exitMs },
                height = laneHeight, palette = palette, sungUntilMs = state.vocalEndMs?.plus(300L),
                range = MixEditor.exitRange(state.out.durationMs), tag = "ketmix-lane-out",
                onMove = { ms, kind -> state.dragExit = ms; tick(kind) }, onEnd = { state.dragExit?.let(state::commitExit); state.dragExit = null })
            Box(Modifier.fillMaxWidth().height(1.dp).background(theme.hairline))
            DetailLane(state.into, anchorMs = entryMs, rate = rate, view = view, plan = plan, outgoing = false, autoMs = auto.inMs.takeIf { auto.mixes && it != entryMs },
                height = laneHeight, palette = palette, sungUntilMs = null,
                range = MixEditor.entryRange(state.into.planDurationMs), tag = "ketmix-lane-in",
                onMove = { ms, kind -> state.dragEntry = ms; tick(kind) }, onEnd = { state.dragEntry?.let(state::commitEntry); state.dragEntry = null })
        }
        // The start of the mix and the blend, across both titles.
        MixLine(view, plan, Modifier.matchParentSize())
    }
    Spacer(Modifier.height(10.dp))
    Overview(state.into, pointMs = entryMs, autoMs = auto.inMs.takeIf { auto.mixes },
        visible = (entryMs - (view.beforeMs * rate).toLong())..(entryMs + (view.afterMs * rate).toLong()),
        range = MixEditor.entryRange(state.into.planDurationMs), palette = palette, tag = "ketmix-overview-in",
        onMove = { ms, kind -> state.dragEntry = ms; tick(kind) }, onEnd = { state.dragEntry?.let(state::commitEntry); state.dragEntry = null })
    Spacer(Modifier.height(8.dp))
    PointHeader("Entrée", state.into.track, entryMs, GridSnap.kindAt(state.into.analysis, entryMs),
        listenerSet = state.effective.entryMs >= 0,
        onStep = { forward ->
            val next = MixEditor.step(state.into.analysis, entryMs, forward, MixEditor.entryRange(state.into.planDurationMs))
            state.commitEntry(next); tick(GridSnap.kindAt(state.into.analysis, next))
        })
}

/** « Sortie · title — artist » with the point's time, what it is on, and arrows that move it by a beat. */
@Composable
private fun PointHeader(label: String, track: Track, pointMs: Long, kind: GridSnap.Kind, listenerSet: Boolean, onStep: (Boolean) -> Unit) {
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

/**
 * A finger on a wave: it follows the finger and its point lands on the grid; held still first, it moves
 * freely (a light tick says so). Vertical moves are left to the page. [onDelta] gets each step in px.
 */
private fun Modifier.pointDrag(key: Any?, onStart: (free: Boolean) -> Unit, onDelta: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var over = 0f
            var moved: PointerInputChange? = null
            val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                moved = awaitHorizontalTouchSlopOrCancellation(down.id) { change, slop -> change.consume(); over = slop }
                true
            }
            val free = decided == null
            val pointer = when {
                free -> down.id
                moved != null -> moved!!.id
                else -> return@awaitEachGesture
            }
            onStart(free)
            if (over != 0f) onDelta(over)
            horizontalDrag(pointer) { change -> onDelta(change.positionChange().x); change.consume() }
            onEnd()
        }
    }

@Composable
private fun DetailLane(side: EditorSide, anchorMs: Long, rate: Float, view: MixEditor.View, plan: MixPlan, outgoing: Boolean, autoMs: Long?,
                       height: Dp, palette: WavePalette, sungUntilMs: Long?, range: LongRange, tag: String,
                       onMove: (Long, GridSnap.Kind) -> Unit, onEnd: () -> Unit) {
    val theme = LocalTheme.current
    val performance = AppSettings.highPerformanceMode.value
    var widthPx by remember { mutableIntStateOf(1) }
    val msPerPx = view.spanMs.toFloat() / widthPx.coerceAtLeast(1)
    // A bar or a phrase draws the point from about 14 dp away.
    val pull = 14f * androidx.compose.ui.platform.LocalDensity.current.density
    val current by rememberUpdatedState(anchorMs)
    val analysis by rememberUpdatedState(side.analysis)
    var raw by remember { mutableFloatStateOf(0f) }
    var free by remember { mutableStateOf(false) }
    var last by remember { mutableLongStateOf(-1L) }
    // The gesture outlives a composition: it reads the latest scale, range and callbacks.
    val scale by rememberUpdatedState(msPerPx * rate)
    val limits by rememberUpdatedState(range)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    val description = AppText.t(if (outgoing) "Onde de fin du titre sortant" else "Onde de début du titre entrant")
    Box(Modifier.fillMaxWidth().height(height).testTag(tag)
        .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
        .semantics { contentDescription = description; stateDescription = MixEditor.clock(anchorMs) }
        .pointDrag(side, onStart = { held -> free = held; raw = current.toFloat(); last = current },
            onDelta = { dx ->
                // The wave follows the finger: moved right, the start of the mix lands earlier in the title.
                raw -= dx * scale
                val placed = MixEditor.place(analysis, raw.roundToLong(), MixEditor.tolerance(analysis, scale, pull), free, limits)
                if (placed.ms != last) { last = placed.ms; move(placed.ms, placed.kind) }
            }, onEnd = { end() })) {
        Canvas(Modifier.fillMaxSize()) {
            val toX = { t: Long -> ((t - anchorMs) / rate + view.beforeMs) / view.spanMs * size.width }
            val fromT = (anchorMs - view.beforeMs * rate).toLong()
            val untilT = (anchorMs + view.afterMs * rate).toLong()
            // The parts of the title not heard in the mix are dimmed: after the blend for the outgoing title, before its entry for the incoming one.
            val heardFrom = if (outgoing) Long.MIN_VALUE else anchorMs
            val heardUntil = if (outgoing) anchorMs + plan.overlapMs else Long.MAX_VALUE
            // A build is heard over the bars before the cut.
            if (outgoing && plan.kind == MixKind.Rise && plan.buildMs > 0) {
                val x0 = toX(anchorMs - plan.buildMs)
                drawRect(theme.accent.copy(alpha = .10f), Offset(x0, 0f), Size(toX(anchorMs) - x0, size.height))
            }
            grid(side.analysis, fromT, untilT, toX, theme.onBackground, theme.accent)
            val wave = side.wave
            if (wave != null) drawWave(wave, fromT, untilT, rate, heardFrom, heardUntil, palette, peaks = !performance, step = if (performance) 3f else 2f)
            else {
                drawLine(theme.onBackground.copy(alpha = .18f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            }
            // Where Ketmix would have put the point.
            if (autoMs != null) {
                val x = toX(autoMs)
                if (x in 0f..size.width) drawLine(theme.secondary.copy(alpha = .9f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
            // Where the outgoing voice stops: a listener's exit may come before it.
            if (sungUntilMs != null) {
                val x = toX(sungUntilMs)
                if (x in 0f..size.width) drawLine(voiceEnd(theme.isDark), Offset(x, size.height * .1f), Offset(x, size.height * .9f), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            }
        }
        LaneState(side, Modifier.align(Alignment.Center))
        val name = if (outgoing) "Sortant" else "Entrant"
        Text(name, color = theme.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(if (outgoing) Alignment.TopStart else Alignment.BottomStart)
                .padding(horizontal = 6.dp, vertical = 5.dp).clip(RoundedCornerShape(6.dp))
                .background(theme.background.copy(alpha = .7f)).padding(horizontal = 5.dp, vertical = 1.dp))
        if (outgoing && sungUntilMs != null) {
            val fraction = ((sungUntilMs - anchorMs) / rate + view.beforeMs) / view.spanMs
            if (fraction in 0f..1f) Text("fin du chant", color = voiceEnd(theme.isDark), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 5.dp).besideLine(fraction, 5.dp))
        }
    }
}

/** Where the outgoing voice stops, in the lanes: apart from the app's colour, which marks the mix. */
private fun voiceEnd(dark: Boolean): Color = if (dark) Color(0xFFFFD60A) else Color(0xFF9A7400)

/** Puts a label just after the line at [fraction] of the width, or just before it when there is no room after. */
private fun Modifier.besideLine(fraction: Float, gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val width = constraints.maxWidth
    val x = (fraction * width).toInt()
    val space = gap.roundToPx()
    val left = if (x + space + placeable.width <= width) x + space else (x - space - placeable.width).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(left, 0) }
}

/** While a wave is read, or when it cannot be: said in the middle of its lane. */
@Composable
private fun LaneState(side: EditorSide, modifier: Modifier) {
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

/** The beat grid: beats faint, first beats of bars stronger, starts of phrases in the app's colour. */
private fun DrawScope.grid(analysis: SegmentAnalysis?, fromT: Long, untilT: Long, toX: (Long) -> Float, ink: Color, accent: Color) {
    for ((t, kind) in GridSnap.beats(analysis, fromT, untilT, limit = 400)) {
        val x = toX(t)
        when (kind) {
            GridSnap.Kind.Phrase -> drawLine(accent.copy(alpha = .55f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            GridSnap.Kind.Bar -> drawLine(ink.copy(alpha = .20f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            else -> drawLine(ink.copy(alpha = .08f), Offset(x, size.height * .3f), Offset(x, size.height * .7f), 1.dp.toPx())
        }
    }
}

/**
 * The wave between [fromT] and [untilT] of its title across the width: its level coloured by its bands, its
 * peaks lighter behind (left out in performance mode), dimmed outside [heardFrom, heardUntil].
 */
private fun DrawScope.drawWave(wave: Wave, fromT: Long, untilT: Long, rate: Float, heardFrom: Long, heardUntil: Long,
                               palette: WavePalette, peaks: Boolean, step: Float) {
    val middle = size.height / 2f
    val reach = middle * .92f
    val stroke = (step * .62f).coerceAtLeast(1f)
    val span = (untilT - fromT).toFloat()
    var x = step / 2f
    while (x < size.width) {
        val t0 = fromT + ((x - step / 2f) / size.width * span).toLong()
        val t1 = fromT + ((x + step / 2f) / size.width * span).toLong()
        val c0 = ((t0 - wave.startMs) / Wave.COLUMN_MS).toInt()
        val c1 = ((t1 - wave.startMs) / Wave.COLUMN_MS).toInt().coerceAtLeast(c0)
        if (c1 >= 0 && c0 < wave.columns) {
            var peak = 0f; var level = 0f; var low = 0f; var high = 0f; var n = 0
            for (c in maxOf(0, c0)..minOf(wave.columns - 1, c1)) {
                peak = maxOf(peak, wave.peakAt(c)); level += wave.level(c); low += wave.lowAt(c); high += wave.highAt(c); n++
            }
            if (n > 0) {
                level /= n; low /= n; high /= n
                val t = (t0 + t1) / 2
                val alpha = if (t in heardFrom..heardUntil) 1f else .28f
                // Shares of the level by band, by energy; the mids are what the bass and the treble leave.
                val total = level * level
                val lowShare = if (total > 0f) (low * low / total).coerceIn(0f, 1f) else 0f
                val highShare = if (total > 0f) (high * high / total).coerceIn(0f, 1f - lowShare) else 0f
                val midShare = (1f - lowShare - highShare).coerceAtLeast(0f)
                val colour = Color(
                    palette.low.red * lowShare + palette.mid.red * midShare + palette.high.red * highShare,
                    palette.low.green * lowShare + palette.mid.green * midShare + palette.high.green * highShare,
                    palette.low.blue * lowShare + palette.mid.blue * midShare + palette.high.blue * highShare)
                val p = shape(peak, 1.15f) * reach
                val r = shape(level, 1.9f) * reach
                if (peaks && p > r) drawLine(colour.copy(alpha = .30f * alpha), Offset(x, middle - p), Offset(x, middle + p), stroke, StrokeCap.Round)
                drawLine(colour.copy(alpha = alpha), Offset(x, middle - r.coerceAtLeast(.75f)), Offset(x, middle + r.coerceAtLeast(.75f)), stroke, StrokeCap.Round)
            }
        }
        x += step
    }
}

/** The start of the mix across both lanes, the blend after it, and where the bass changes hands. */
@Composable
private fun MixLine(view: MixEditor.View, plan: MixPlan, modifier: Modifier) {
    val accent = LocalTheme.current.accent
    Canvas(modifier) {
        val x0 = view.anchor * size.width
        val blend = (plan.overlapMs.toFloat() / view.spanMs * size.width).coerceAtLeast(0f)
        if (plan.mixes && blend > 0f) {
            drawRect(accent.copy(alpha = .13f), Offset(x0, 0f), Size(blend, size.height))
            drawLine(accent.copy(alpha = .55f), Offset(x0 + blend, 0f), Offset(x0 + blend, size.height), 1.dp.toPx())
        }
        if (plan.mixes && plan.swapMs >= 0) {
            val sx = x0 + (plan.swapMs - plan.outMs).toFloat() / view.spanMs * size.width
            val half = size.height / 2f
            val r = 5.dp.toPx()
            drawLine(accent, Offset(sx, half - r * 2), Offset(sx, half + r * 2), 2.dp.toPx(), StrokeCap.Round)
        }
        drawLine(accent, Offset(x0, 0f), Offset(x0, size.height), 2.5.dp.toPx(), StrokeCap.Round)
        // The two handles: the exit at the top, the entry at the bottom.
        val knob = 7.dp.toPx()
        drawCircle(accent, knob, Offset(x0, knob + 3.dp.toPx()))
        drawCircle(accent, knob, Offset(x0, size.height - knob - 3.dp.toPx()))
        drawCircle(Color.White, knob * .38f, Offset(x0, knob + 3.dp.toPx()))
        drawCircle(Color.White, knob * .38f, Offset(x0, size.height - knob - 3.dp.toPx()))
    }
}

/**
 * The whole stretch the editor shows of a title, small: what the lanes show is outlined, the point is a line
 * that a finger moves directly (a tap puts it there).
 */
@Composable
private fun Overview(side: EditorSide, pointMs: Long, autoMs: Long?, visible: LongRange, range: LongRange, palette: WavePalette, tag: String,
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
                        val r = shape(wave.level(c), 1.9f) * middle * .82f
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
private fun shape(linear: Float, gain: Float): Float = (Math.pow(linear.coerceIn(0f, 1f).toDouble(), .7).toFloat() * gain).coerceAtMost(1f)

/** The words of a transition's kind, for the summary under the waves. */
private fun kindWord(plan: MixPlan): String = if (!plan.mixes) AppText.t("Pas de mix") else AppText.t(MixWords.label(plan)).replaceFirstChar { it.uppercase() }

@Composable
private fun Controls(state: EditorState, plan: MixPlan) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val listener = plan.reason == "listener"
    // What will be played, and why it may differ from what Ketmix would do.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(kindWord(plan), localize = false, color = theme.onBackground, style = Ket27.Type.headline, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (listener) theme.accent.copy(alpha = .16f) else theme.onBackground.copy(alpha = .08f))
            .padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(if (listener) "Votre choix" else "Automatique", color = if (listener) theme.accent else theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    val measures = MixWords.measures(plan)
    val blend = if (plan.mixes) AppText.t("Mix de {0} s").replace("{0}", "%.1f".format(java.util.Locale.ROOT, plan.overlapMs / 1000f)) else ""
    Text(listOf(blend, measures).filter { it.isNotBlank() }.joinToString(" · "), localize = false, color = theme.secondary, style = Ket27.Type.footnote)
    val sung = state.vocalEndMs
    if (plan.mixes && sung != null && state.effective.exitMs >= 0 && plan.outMs < sung) {
        Spacer(Modifier.height(6.dp))
        Text("Votre sortie coupe le chant : Ketmix la respecte et baisse la voix sortante.", color = theme.secondary, style = Ket27.Type.footnote)
    }

    if (state.pair) {
        Spacer(Modifier.height(20.dp))
        val fixed = plan.kind == MixKind.Echo || plan.kind == MixKind.Rise || plan.kind == MixKind.Trim
        val overlapSeconds = ((state.dragOverlap ?: plan.overlapMs.takeIf { plan.mixes } ?: state.choice.overlapMs.coerceAtLeast(8_000L)) / 1000f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chevauchement", color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.weight(1f))
            val beats = if (plan.kind == MixKind.Beatmatch && plan.beatMs > 0f) AppText.t("{0} temps").replace("{0}", Math.round(plan.overlapMs / plan.beatMs).toString()) + " · " else ""
            Text(beats + "%.1f s".format(java.util.Locale.ROOT, overlapSeconds), localize = false, color = theme.secondary, style = Ket27.Type.body)
        }
        GlassSlider(overlapSeconds.coerceIn(1f, 30f), onValueChange = { state.dragOverlap = (it * 1000f).roundToLong() },
            valueRange = 1f..30f, enabled = !fixed && plan.mixes,
            onValueChangeFinished = { state.dragOverlap?.let(state::commitOverlap); state.dragOverlap = null },
            modifier = Modifier.fillMaxWidth().testTag("ketmix-overlap"))
        if (fixed) Text("Pour ce style, la longueur suit le tempo du titre.", color = theme.secondary, style = Ket27.Type.footnote)

        Spacer(Modifier.height(18.dp))
        Text("Style de cette transition", color = theme.onBackground, style = Ket27.Type.body)
        Spacer(Modifier.height(8.dp))
        val chosen = state.choice.style ?: state.defaultStyle
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (style in MixStyle.values()) {
                val label = when (style) {
                    MixStyle.Auto -> "Automatique"; MixStyle.Tempo -> "Calé sur le tempo"; MixStyle.Echo -> "Écho"
                    MixStyle.Filters -> "Filtres"; MixStyle.Fade -> "Fondu"; MixStyle.Rise -> "Montée"
                }
                val on = style == chosen
                Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (on) theme.accent else theme.onBackground.copy(alpha = .08f))
                    .testTag("ketmix-style-" + style.name)
                    .clickable(role = Role.RadioButton) { state.commitStyle(style); if (AppSettings.ketmixHaptics.value) haptic(Haptics.Kind.Tick) }
                    .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) com.ketvoice.app.ui.common.onAccent(theme.accent) else theme.onBackground,
                        fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }

    Spacer(Modifier.height(22.dp))
    val playing = Player.state.value.current?.id == state.from.id
    val nextMatches = Player.automixNext()?.track?.id == state.into.track.id
    val canPreview = state.pair && playing && nextMatches && AppSettings.automix.value && !AppSettings.bitPerfect.value && plan.mixes
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.pair) GlassCapsuleButton("Écouter", KetIcons.Play, onClick = {
            scope.launch {
                val ok = Automix.preview(state.plan())
                TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
                TrackActionFeedback.snackbar.showSnackbar(AppText.t(if (ok) "La transition va jouer dans quelques secondes" else "Ketmix ne peut pas jouer la transition maintenant"))
            }
        }, modifier = Modifier.weight(1f), prominent = true, enabled = canPreview, tag = "ketmix-preview", minHeight = 48.dp)
        GlassCapsuleButton("Réinitialiser", KetIcons.Refresh, onClick = {
            state.reset(); if (AppSettings.ketmixHaptics.value) haptic(Haptics.Kind.Confirm)
        }, modifier = Modifier.weight(1f), enabled = !state.choice.automatic, tag = "ketmix-reset", minHeight = 48.dp)
    }
    Spacer(Modifier.height(8.dp))
    val hint = when {
        !state.pair -> "Ce titre seul : sa sortie et son entrée valent pour toutes ses transitions."
        !AppSettings.automix.value -> "Activez Ketmix pour écouter la transition."
        !playing || !nextMatches -> "L'écoute est possible pour le titre en cours et le suivant."
        else -> null
    }
    if (hint != null) Text(hint, color = theme.secondary, style = Ket27.Type.footnote)
    if (canPreview) AutomixStatusLine(Modifier.padding(top = 4.dp), color = theme.secondary, fontSize = 13.sp)
    Spacer(Modifier.height(14.dp))
    Text("Glissez une onde pour déplacer son point : il se cale sur les temps et les phrases. Maintenez avant de glisser pour le placer librement.",
        color = theme.secondary, style = Ket27.Type.footnote)
}
