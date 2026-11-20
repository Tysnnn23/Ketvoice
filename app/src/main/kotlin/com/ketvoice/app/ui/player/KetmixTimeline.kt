package com.ketvoice.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.automix.DeckAutomation
import com.ketvoice.app.automix.GridSnap
import com.ketvoice.app.automix.MixEditor
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.TransitionPreview
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.KetTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/** The stretch the timeline shows, on the outgoing title's clock. */
internal class TimelineWindow(val fromMs: Long, val untilMs: Long) {
    val spanMs: Long get() = (untilMs - fromMs).coerceAtLeast(1L)

    companion object {
        const val ZOOM_MIN = .5f
        const val ZOOM_MAX = 4f

        fun of(plan: MixPlan, exitMs: Long, wide: Boolean, zoom: Float): TimelineWindow {
            val view = MixEditor.view(plan, wide)
            val z = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
            return TimelineWindow(exitMs - (view.beforeMs / z).toLong(), exitMs + (view.afterMs / z).toLong())
        }
    }
}

/** The colours of what the timeline draws: data in fixed hues, the box (what is moved) in the app's colour. */
internal class TimelineInk(val volume: Color, val bass: Color, val filter: Color, val voice: Color, val wave: Color, val box: Color, val text: Color) {
    companion object {
        fun of(theme: KetTheme): TimelineInk = if (theme.isDark)
            TimelineInk(theme.onBackground, Color(0xFFFFB340), Color(0xFF5AC8FA), Color(0xFFC987F5), theme.onBackground, theme.accent, theme.secondary)
        else TimelineInk(theme.onBackground, Color(0xFFB25000), Color(0xFF0A6E9E), Color(0xFF8A3FB8), theme.onBackground, theme.accent, theme.secondary)
    }
}

/** How the curves are drawn apart without colour: volume solid, bass dashed, filter dotted. */
internal enum class CurveLine { Solid, Dashed, Dotted }

internal fun DrawScope.curveEffect(line: CurveLine): PathEffect? = when (line) {
    CurveLine.Solid -> null
    CurveLine.Dashed -> PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))
    CurveLine.Dotted -> PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx()))
}

/** What the box of the transition is grabbed by. */
private enum class Grip { Box, Start, End, Out, In, Key }

/**
 * One gesture on the timeline: the box moves the exit and the entry together, its edges change how long both are
 * heard, a wave outside it slides its own title under it. Points land on the grid unless held before moving.
 */
private class TimelineDrag(val state: EditorState, plan: MixPlan, val grip: Grip, val free: Boolean, val msPerPx: Float,
                           val pullPx: Float, val tick: (GridSnap.Kind) -> Unit) {
    private val exit0 = state.exitOf(plan)
    private val entry0 = state.entryOf(plan)
    private val overlap0 = plan.overlapMs
    private val rate = if (plan.mixes) plan.rate else 1f
    private var last = Long.MIN_VALUE

    private fun place(analysis: SegmentAnalysis?, ms: Long, range: LongRange): GridSnap.Result =
        MixEditor.place(analysis, ms, MixEditor.tolerance(analysis, msPerPx, pullPx), free, range)

    private fun exitAt(ms: Long, range: LongRange = MixEditor.exitRange(state.out.durationMs)) = place(state.out.analysis, ms, range)
    private fun entryAt(ms: Long) = place(state.into.analysis, ms, MixEditor.entryRange(state.into.planDurationMs))

    /** 7.6: the keyframe being dragged, and where it was. */
    var key = -1
    private val key0 = state.choice.keys.getOrNull(state.keyGrabbed)?.atMs ?: 0L

    fun move(dxPx: Float) {
        val dt = (dxPx * msPerPx).roundToLong()
        if (grip == Grip.Key) {
            // A keyframe moves in time inside the blend, freely: a level may change off the beat.
            key = state.keyGrabbed
            state.dragKey = key to (key0 + dt).coerceIn(0L, overlap0)
            return
        }
        val placed = when (grip) {
            // The wave follows the finger: moved right, the mix starts earlier in that title.
            Grip.Out -> exitAt(exit0 - dt).also { state.dragExit = it.ms }
            Grip.In -> entryAt(entry0 - (dt * rate).toLong()).also { state.dragEntry = it.ms }
            Grip.Box -> exitAt(exit0 + dt).also { e ->
                state.dragExit = e.ms
                state.dragEntry = entryAt(entry0 + ((e.ms - exit0) * rate).toLong()).ms
            }
            Grip.End -> exitAt(exit0 + overlap0 + dt, (exit0 + MIN_MS)..(exit0 + MAX_MS)).also { state.dragOverlap = it.ms - exit0 }
            Grip.Start -> exitAt(exit0 + dt, MixEditor.exitRange(state.out.durationMs).first..(exit0 + overlap0 - MIN_MS)).also { e ->
                state.dragExit = e.ms
                state.dragOverlap = (exit0 + overlap0 - e.ms).coerceIn(MIN_MS, MAX_MS)
                state.dragEntry = entryAt(entry0 + ((e.ms - exit0) * rate).toLong()).ms
            }
            Grip.Key -> return
        }
        if (placed.ms != last) { last = placed.ms; tick(placed.kind) }
    }

    fun end() {
        state.dragKey?.let { (i, at) -> state.choice = state.choice.copy(keys = com.ketvoice.app.automix.MixKeys.move(state.choice.keys, i, at, overlap0)) }
        state.dragKey = null
        state.dragExit?.let(state::commitExit)
        state.dragEntry?.let(state::commitEntry)
        state.dragOverlap?.let(state::commitOverlap)
        state.dragExit = null; state.dragEntry = null; state.dragOverlap = null
    }

    companion object {
        const val MIN_MS = 1_000L
        const val MAX_MS = 30_000L
    }
}

/** Which part of the timeline [x], [y] falls on. */
private fun gripAt(x: Float, y: Float, plan: MixPlan, toX: (Long) -> Float, height: Int, edgePx: Float, state: EditorState): Grip {
    if (plan.mixes) {
        // 7.6: a keyframe, on the line between the two titles.
        val key = state.keys.indices.filter { abs(toX(plan.outMs + state.keys[it].atMs) - x) <= edgePx && abs(y - height / 2f) <= edgePx * 1.6f }
            .minByOrNull { abs(toX(plan.outMs + state.keys[it].atMs) - x) }
        state.keyGrabbed = key ?: -1
        if (key != null) return Grip.Key
        val x0 = toX(plan.outMs)
        val x1 = toX(plan.outMs + plan.overlapMs)
        val sized = plan.kind !in setOf(MixKind.Echo, MixKind.Rise, MixKind.Trim) && x1 - x0 > edgePx * 2.5f
        if (sized && abs(x - x1) <= edgePx) return Grip.End
        if (sized && abs(x - x0) <= edgePx) return Grip.Start
        if (x in (x0 - if (sized) 0f else edgePx)..(x1 + if (sized) 0f else edgePx)) return Grip.Box
    }
    return if (y < height / 2f) Grip.Out else Grip.In
}

private fun Modifier.timelineGestures(state: EditorState, plan: () -> MixPlan, window: () -> TimelineWindow, edgePx: Float, pullPx: Float,
                                      onFreeze: (TimelineWindow?) -> Unit, tick: (GridSnap.Kind) -> Unit): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val p = plan(); val w = window()
        val width = size.width.coerceAtLeast(1)
        val toX = { t: Long -> (t - w.fromMs).toFloat() / w.spanMs * width }
        val grip = gripAt(down.position.x, down.position.y, p, toX, size.height, edgePx, state)
        var drag: TimelineDrag? = null
        var pinch = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2 && drag == null) {
                // Two fingers: more or less of the music in the same width.
                pinch = true
                state.zoom = (state.zoom * event.calculateZoom()).coerceIn(TimelineWindow.ZOOM_MIN, TimelineWindow.ZOOM_MAX)
                event.changes.forEach { it.consume() }
                continue
            }
            if (pinch) continue
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val d = change.position - down.position
            if (drag == null) {
                // A vertical move belongs to the page.
                if (abs(d.y) > viewConfiguration.touchSlop && abs(d.y) > abs(d.x)) return@awaitEachGesture
                if (abs(d.x) <= viewConfiguration.touchSlop) continue
                val free = change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                drag = TimelineDrag(state, p, grip, free, w.spanMs.toFloat() / width, pullPx, tick)
                // Dragging the box, the timeline stays still under the finger.
                if (grip == Grip.Box || grip == Grip.Start || grip == Grip.End || grip == Grip.Key) onFreeze(w)
            }
            drag.move(d.x)
            change.consume()
        }
        drag?.end()
        // 7.6: a touch that did not move: on a keyframe, its sheet; elsewhere inside the transition, a new keyframe.
        if (drag == null && !pinch && p.mixes && state.pair) {
            if (grip == Grip.Key && state.keyGrabbed >= 0) state.keyOpen = state.keyGrabbed
            else if (grip == Grip.Box || grip == Grip.Start || grip == Grip.End) {
                val rel = w.fromMs + (down.position.x / width * w.spanMs).toLong() - p.outMs
                if (rel in 0..p.overlapMs) state.addKey(rel, p.overlapMs).takeIf { it >= 0 }?.let { state.keyOpen = it }
            }
        }
        onFreeze(null)
    }
}

/**
 * The transition on one clock: the outgoing title's ending above, the incoming title's start below, the box of
 * the transition over both, the beat grid with its bar numbers, where each title sings, and the curves Ketmix
 * plays for this plan ([DeckAutomation], the very automation of the decks).
 */
@Composable
internal fun KetmixTimeline(state: EditorState, plan: MixPlan, auto: MixPlan, window: TimelineWindow, height: Dp, tick: (GridSnap.Kind) -> Unit) {
    val theme = LocalTheme.current
    val ink = TimelineInk.of(theme)
    val preview by TransitionPreview.engine.state.collectAsState()
    var frozen by remember { mutableStateOf<TimelineWindow?>(null) }
    val shown = frozen ?: window
    val planNow by rememberUpdatedState(plan)
    val windowNow by rememberUpdatedState(shown)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val curves = remember(plan) { plan.takeIf { it.mixes }?.let { DeckAutomation.outgoing(it) to DeckAutomation.incoming(it) } }
    val description = AppText.t("Transition : glissez la boîte pour déplacer la sortie et l'entrée, ses bords pour changer sa durée")
    val exitMs = state.exitOf(plan)
    val entryMs = state.entryOf(plan)
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(20.dp))
        .background(theme.onBackground.copy(alpha = if (theme.isDark) .06f else .045f)).testTag("ketmix-timeline")
        .semantics { contentDescription = description; stateDescription = "${MixEditor.clock(exitMs)} → ${MixEditor.clock(entryMs)}" }
        .timelineGestures(state, { planNow }, { windowNow }, with(density) { 18.dp.toPx() }, 14f * density.density, { frozen = it }, tick)) {
        Canvas(Modifier.fillMaxSize()) {
            val scene = TimelineScene(state, plan, shown, exitMs, entryMs, size.width, size.height)
            drawScene(scene, auto, curves, ink, measurer, preview.positionMs.takeIf { preview.running && !preview.loading })
        }
        // The two halves, for TalkBack and the tests: the gestures belong to the whole timeline.
        Box(Modifier.fillMaxWidth().fillMaxHeight(.5f).align(Alignment.TopStart).testTag("ketmix-lane-out"))
        Box(Modifier.fillMaxWidth().fillMaxHeight(.5f).align(Alignment.BottomStart).testTag("ketmix-lane-in"))
        HalfLabel("Sortant", Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 20.dp))
        HalfLabel("Entrant", Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 20.dp))
        LaneState(state.out, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        LaneState(state.into, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

@Composable
private fun HalfLabel(text: String, modifier: Modifier) {
    val theme = LocalTheme.current
    Text(text, color = theme.secondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(theme.background.copy(alpha = .72f)).padding(horizontal = 5.dp, vertical = 1.dp))
}

/** The geometry of one frame of the timeline. */
private class TimelineScene(val state: EditorState, val plan: MixPlan, val window: TimelineWindow, val exitMs: Long, val entryMs: Long,
                            val width: Float, val height: Float) {
    val rate: Float = if (plan.mixes) plan.rate else 1f
    val half: Float = height / 2f
    fun toX(t: Long): Float = (t - window.fromMs).toFloat() / window.spanMs * width
    fun toT(x: Float): Long = window.fromMs + (x / width * window.spanMs).toLong()
    /** The incoming title's position at [t] of the outgoing clock, and back. */
    fun inPos(t: Long): Long = entryMs + ((t - exitMs) * rate).toLong()
    fun outT(inPos: Long): Long = exitMs + ((inPos - entryMs) / rate).toLong()
    val endMs: Long get() = exitMs + plan.overlapMs
}

private fun DrawScope.drawScene(s: TimelineScene, auto: MixPlan, curves: Pair<DeckAutomation, DeckAutomation>?, ink: TimelineInk,
                                measurer: TextMeasurer, playheadMs: Long?) {
    val ruler = 16.dp.toPx()
    drawLine(ink.wave.copy(alpha = .14f), Offset(0f, s.half), Offset(s.width, s.half), 1.dp.toPx())
    if (s.plan.mixes) drawBox(s, ink)
    drawGrid(s, ink, measurer, top = true, ruler)
    drawGrid(s, ink, measurer, top = false, ruler)
    val performance = AppSettings.highPerformanceMode.value
    s.state.out.wave?.let { drawHalfWave(s, it, top = true, ink, if (performance) 3f else 2f) }
    s.state.into.wave?.let { drawHalfWave(s, it, top = false, ink, if (performance) 3f else 2f) }
    drawVoice(s, ink, top = true)
    drawVoice(s, ink, top = false)
    drawAutoPoints(s, auto, ink)
    if (curves != null) drawCurves(s, curves, ink, ruler)
    drawKeyCurves(s.state.keys, s.plan, s::toX, s.width, ruler, s.half, s.height - ruler, ink.box, s.state.keyOpen)
    if (playheadMs != null) {
        val x = s.toX(playheadMs)
        if (x in 0f..s.width) {
            drawLine(ink.volume, Offset(x, 0f), Offset(x, s.height), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(ink.volume, 4.5.dp.toPx(), Offset(x, 3.dp.toPx()))
        }
    }
}

/** The box of the transition over both titles, a build shown before it, and the handles of its edges. */
private fun DrawScope.drawBox(s: TimelineScene, ink: TimelineInk) {
    val x0 = s.toX(s.exitMs)
    val x1 = s.toX(s.endMs).coerceAtLeast(x0 + 3.dp.toPx())
    val inset = 3.dp.toPx()
    val corner = CornerRadius(14.dp.toPx())
    if (s.plan.kind == MixKind.Rise && s.plan.buildMs > 0) {
        val b = s.toX(s.exitMs - s.plan.buildMs)
        drawRoundRect(ink.box.copy(alpha = .07f), Offset(b, inset), Size(x0 - b, s.height - inset * 2), corner)
    }
    drawRoundRect(ink.box.copy(alpha = .12f), Offset(x0, inset), Size(x1 - x0, s.height - inset * 2), corner)
    drawRoundRect(ink.box.copy(alpha = .85f), Offset(x0, inset), Size(x1 - x0, s.height - inset * 2), corner, style = Stroke(1.5.dp.toPx()))
    val sized = s.plan.kind !in setOf(MixKind.Echo, MixKind.Rise, MixKind.Trim)
    if (!sized) return
    val grip = 26.dp.toPx()
    for (x in floatArrayOf(x0, x1)) drawLine(ink.box, Offset(x, s.half - grip / 2), Offset(x, s.half + grip / 2), 4.dp.toPx(), StrokeCap.Round)
}

/** Beats faint, bars stronger with their number counted from the mix (or the entry), phrases in the app's colour. */
private fun DrawScope.drawGrid(s: TimelineScene, ink: TimelineInk, measurer: TextMeasurer, top: Boolean, ruler: Float) {
    val analysis = if (top) s.state.out.analysis else s.state.into.analysis
    val from = if (top) s.window.fromMs else s.inPos(s.window.fromMs)
    val until = if (top) s.window.untilMs else s.inPos(s.window.untilMs)
    val y0 = if (top) ruler else s.half
    val y1 = if (top) s.half else s.height - ruler
    val anchor = if (top) s.exitMs else s.entryMs
    val barPx = (analysis?.beatMs ?: 0f) * 4f / (if (top) 1f else s.rate) / s.window.spanMs * s.width
    val every = when { barPx >= 22.dp.toPx() -> 1; barPx >= 11.dp.toPx() -> 2; else -> 4 }
    var bar = 0
    for ((pos, kind) in GridSnap.beats(analysis, from, until, limit = 500)) {
        val x = s.toX(if (top) pos else s.outT(pos))
        when (kind) {
            GridSnap.Kind.Phrase, GridSnap.Kind.Bar -> {
                drawLine(ink.wave.copy(alpha = if (kind == GridSnap.Kind.Phrase) .30f else .16f), Offset(x, y0), Offset(x, y1), 1.dp.toPx())
                if (pos >= anchor - 2) {
                    bar++
                    if ((bar - 1) % every == 0) barNumber(measurer, bar.toString(), x, if (top) 1.dp.toPx() else s.height - ruler + 1.dp.toPx(), ink.text)
                }
            }
            else -> drawLine(ink.wave.copy(alpha = .07f), Offset(x, y0 + (y1 - y0) * .3f), Offset(x, y1 - (y1 - y0) * .3f), 1.dp.toPx())
        }
    }
}

private fun DrawScope.barNumber(measurer: TextMeasurer, text: String, x: Float, y: Float, color: Color) {
    val layout = measurer.measure(text, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = color, fontFeatureSettings = "tnum"))
    val left = x + 3.dp.toPx()
    if (left + layout.size.width <= size.width) drawText(layout, topLeft = Offset(left, y))
}

/** One title's wave in its half, its level in ink, dimmed where it is not heard in the mix. */
private fun DrawScope.drawHalfWave(s: TimelineScene, wave: Wave, top: Boolean, ink: TimelineInk, step: Float) {
    val middle = if (top) s.half * .5f + 6.dp.toPx() else s.half * 1.5f - 6.dp.toPx()
    val reach = s.half * .34f
    val stroke = (step * .62f).coerceAtLeast(1f)
    var x = step / 2f
    while (x < s.width) {
        val t0 = s.toT(x - step / 2f); val t1 = s.toT(x + step / 2f)
        val p0 = if (top) t0 else s.inPos(t0); val p1 = if (top) t1 else s.inPos(t1)
        val c0 = ((p0 - wave.startMs) / Wave.COLUMN_MS).toInt()
        val c1 = ((p1 - wave.startMs) / Wave.COLUMN_MS).toInt().coerceAtLeast(c0)
        if (c1 >= 0 && c0 < wave.columns) {
            var level = 0f; var n = 0
            for (c in maxOf(0, c0)..minOf(wave.columns - 1, c1)) { level += wave.level(c); n++ }
            if (n > 0) {
                val t = (t0 + t1) / 2
                val heard = if (top) !s.plan.mixes || t <= s.endMs else !s.plan.mixes || t >= s.exitMs
                val r = (waveShape(level / n, 1.9f) * reach).coerceAtLeast(.75f)
                drawLine(ink.wave.copy(alpha = if (heard) .34f else .12f), Offset(x, middle - r), Offset(x, middle + r), stroke, StrokeCap.Round)
            }
        }
        x += step
    }
}

/** Where a title sings, as a bar along the middle of the timeline, and where its lyric lines start. */
private fun DrawScope.drawVoice(s: TimelineScene, ink: TimelineInk, top: Boolean) {
    val side = if (top) s.state.out else s.state.into
    val y = if (top) s.half - 4.dp.toPx() else s.half + 4.dp.toPx()
    val thick = 3.dp.toPx()
    fun x(pos: Long) = s.toX(if (top) pos else s.outT(pos))
    side.sung?.forEach { range ->
        val a = x(range.first).coerceIn(0f, s.width); val b = x(range.last).coerceIn(0f, s.width)
        if (b > a) drawLine(ink.voice.copy(alpha = .8f), Offset(a, y), Offset(b, y), thick, StrokeCap.Round)
    }
    for (start in side.lyricStarts) {
        val px = x(start)
        if (px in 0f..s.width) drawLine(ink.voice, Offset(px, y - 5.dp.toPx()), Offset(px, y + 5.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
    }
    // Where the outgoing voice stops, as Ketmix reads it from the lyrics of the title playing.
    val sungUntil = s.state.vocalEndMs?.plus(300L)
    if (top && sungUntil != null) {
        val px = s.toX(sungUntil)
        if (px in 0f..s.width) drawLine(ink.voice, Offset(px, s.half * .25f), Offset(px, s.half - 2.dp.toPx()), 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
    }
}

/** Where Ketmix would have put the points, once the listener moved them. */
private fun DrawScope.drawAutoPoints(s: TimelineScene, auto: MixPlan, ink: TimelineInk) {
    if (!auto.mixes) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    if (auto.outMs != s.exitMs) {
        val x = s.toX(auto.outMs)
        if (x in 0f..s.width) drawLine(ink.text, Offset(x, 0f), Offset(x, s.half), 1.5.dp.toPx(), pathEffect = dash)
    }
    if (auto.inMs != s.entryMs) {
        val x = s.toX(s.outT(auto.inMs))
        if (x in 0f..s.width) drawLine(ink.text, Offset(x, s.half), Offset(x, s.height), 1.5.dp.toPx(), pathEffect = dash)
    }
}

/** How much bass passes the high-pass at [hz], 0..1 (0 Hz = open). */
internal fun bassPresence(hz: Float): Float = when {
    hz <= 30f -> 1f
    hz >= DeckAutomation.BASS_HZ -> 0f
    else -> 1f - (ln(hz / 30f) / ln(DeckAutomation.BASS_HZ / 30f))
}

/** How open the filters leave the rest of the sound, 0..1: a high-pass above the bass, or a low-pass. */
internal fun filterOpening(highPassHz: Float, lowPassHz: Float): Float {
    val high = if (highPassHz > DeckAutomation.BASS_HZ) (ln(highPassHz / DeckAutomation.BASS_HZ) / ln(8f)).coerceIn(0f, 1f) else 0f
    val low = if (lowPassHz > 0f && lowPassHz < 18_000f) (ln(18_000f / lowPassHz) / ln(18_000f / 260f)).coerceIn(0f, 1f) else 0f
    return 1f - maxOf(high, low)
}

private fun DrawScope.drawCurves(s: TimelineScene, curves: Pair<DeckAutomation, DeckAutomation>, ink: TimelineInk, ruler: Float) {
    val from = (s.exitMs - s.plan.buildMs - 1_500L).coerceAtLeast(s.window.fromMs)
    val until = (s.endMs + 2_500L).coerceAtMost(s.window.untilMs)
    if (until <= from) return
    for (top in booleanArrayOf(true, false)) {
        val deck = if (top) curves.first else curves.second
        val y0 = if (top) ruler + 4.dp.toPx() else s.half + 8.dp.toPx()
        val y1 = if (top) s.half - 8.dp.toPx() else s.height - ruler - 4.dp.toPx()
        fun us(t: Long) = (if (top) t else s.inPos(t)) * 1000L
        curve(s, from, until, y0, y1, ink.bass, CurveLine.Dashed) { t -> bassPresence(deck.highPassHz.at(us(t))) }
        curve(s, from, until, y0, y1, ink.filter, CurveLine.Dotted) { t -> filterOpening(deck.highPassHz.at(us(t)), deck.lowPassHz.at(us(t))) }
        curve(s, from, until, y0, y1, ink.volume, CurveLine.Solid) { t -> (deck.gain.at(us(t)) * deck.dry.at(us(t))).coerceIn(0f, 1f) }
    }
}

private fun DrawScope.curve(s: TimelineScene, from: Long, until: Long, y0: Float, y1: Float, color: Color, line: CurveLine, value: (Long) -> Float) {
    val path = Path()
    val step = 3f
    var x = s.toX(from).coerceAtLeast(0f)
    val end = s.toX(until).coerceAtMost(s.width)
    var first = true
    while (x <= end) {
        val y = y1 - (y1 - y0) * value(s.toT(x))
        if (first) path.moveTo(x, y) else path.lineTo(x, y)
        first = false
        x += step
    }
    drawPath(path, color, style = Stroke(if (line == CurveLine.Solid) 2.5.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = curveEffect(line)))
}
