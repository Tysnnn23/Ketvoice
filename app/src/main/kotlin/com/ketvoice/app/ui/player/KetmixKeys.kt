package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.LevelKey
import com.ketvoice.app.automix.Levels
import com.ketvoice.app.automix.MixEditor
import com.ketvoice.app.automix.MixKeys
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.data.Track
import com.ketvoice.app.stems.StemStore
import com.ketvoice.app.stems.VoiceSeparation
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Volume keyframes of the Ketmix editor (7.6, owner: « le keyframe se place, et après si tu recliques sur le keyframe tu as
 * le nom de la première musique, et si tu défiles tu as la deuxième, et tu règles le volume »). A touch inside the
 * transition places one; a touch on one opens its sheet: the outgoing title's page, then, swiped, the incoming one's,
 * each with « Voix », « Instruments » and « Musique ». Voice and instruments need the title's separation.
 */

/** The keyframes as drawn now (one being dragged at its new time). */
internal val EditorState.keys: List<LevelKey> get() = effective.keys

/** Places a keyframe [atMs] after the start of the blend; the index of the one there (new or already placed), or -1. */
internal fun EditorState.addKey(atMs: Long, overlapMs: Long): Int {
    val near = MixKeys.near(choice.keys, atMs, MixKeys.MIN_GAP_MS)
    if (near >= 0) return near
    val next = MixKeys.place(choice.keys, atMs, overlapMs)
    if (next.size == choice.keys.size) return -1
    choice = choice.copy(keys = next)
    return next.indexOfFirst { it.atMs == atMs.coerceIn(0L, overlapMs.coerceAtLeast(0L)) }
}

internal fun EditorState.setLevels(index: Int, outgoing: Boolean, levels: Levels) {
    val key = choice.keys.getOrNull(index) ?: return
    val next = if (outgoing) key.copy(out = levels.clamped()) else key.copy(into = levels.clamped())
    choice = choice.copy(keys = choice.keys.toMutableList().also { it[index] = next })
}

internal fun EditorState.removeKey(index: Int) {
    if (index !in choice.keys.indices) return
    choice = choice.copy(keys = choice.keys.filterIndexed { i, _ -> i != index })
    keyOpen = null
}

/** Reads whether both titles are separated whole. */
internal suspend fun EditorState.readSeparations() {
    val (a, b) = withContext(Dispatchers.IO) {
        runCatching { StemStore.hasWhole(from.id) to StemStore.hasWhole(into.track.id) }.getOrDefault(false to false)
    }
    outSeparated = a; inSeparated = b
}

/** « Séparer les voix » from the editor: each title not separated yet, one after the other, as the voice slider would. */
internal suspend fun EditorState.separateBoth() {
    for (id in listOf(from.id, into.track.id).distinct()) {
        if (withContext(Dispatchers.IO) { runCatching { StemStore.hasWhole(id) }.getOrDefault(false) }) continue
        if (!VoiceSeparation.modelReady) break
        VoiceSeparation.request(id)
        withTimeoutOrNull(15 * 60_000L) {
            VoiceSeparation.state.first { s ->
                (s is VoiceSeparation.State.Ready && s.trackId == id) || (s is VoiceSeparation.State.Unavailable && s.trackId == id) ||
                    s is VoiceSeparation.State.Idle
            }
        }
        readSeparations()
    }
    readSeparations()
}

/** The levels of the keyframes drawn on each title's half of the timeline: music solid, voice dashed, instruments dotted. */
internal fun DrawScope.drawKeyCurves(keys: List<LevelKey>, plan: MixPlan, toX: (Long) -> Float, width: Float, top: Float, half: Float,
                                     bottom: Float, color: androidx.compose.ui.graphics.Color, selected: Int?) {
    if (keys.isEmpty() || !plan.mixes) return
    val from = plan.outMs; val span = plan.overlapMs
    for (outgoing in listOf(true, false)) {
        val y0 = if (outgoing) top else half + 4.dp.toPx()
        val y1 = if (outgoing) half - 4.dp.toPx() else bottom
        val picks: List<Pair<(Levels) -> Int, CurveLine>> = listOf({ l: Levels -> l.music } to CurveLine.Solid,
            { l: Levels -> l.voice } to CurveLine.Dashed, { l: Levels -> l.instruments } to CurveLine.Dotted)
        for ((pick, line) in picks) {
            val path = Path()
            val steps = 64
            // Over the blend and a little after it for the incoming title, which glides back to itself.
            val until = if (outgoing) span else span + MixKeys.RETURN_MS
            for (i in 0..steps) {
                val rel = until * i / steps
                val x = toX(from + rel).coerceIn(0f, width)
                val v = MixKeys.valueAt(keys, span, outgoing, rel, pick) / 100f
                val y = y1 - (y1 - y0) * v
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color.copy(alpha = if (line == CurveLine.Solid) .95f else .8f),
                style = Stroke(if (line == CurveLine.Solid) 2.2.dp.toPx() else 1.8.dp.toPx(), cap = StrokeCap.Round, pathEffect = curveEffect(line)))
        }
    }
    // The keyframes themselves, on the line between the two titles.
    for ((i, key) in keys.withIndex()) {
        val x = toX(from + key.atMs.coerceIn(0L, span))
        if (x !in -8f..width + 8f) continue
        drawLine(color.copy(alpha = .35f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
        val r = if (i == selected) 7.dp.toPx() else 6.dp.toPx()
        drawCircle(androidx.compose.ui.graphics.Color.White, r + 2.dp.toPx(), Offset(x, half))
        drawCircle(color, r, Offset(x, half))
    }
}

/** The sheet of one keyframe: the outgoing title's three levels, then, swiped, the incoming title's. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun KeyframeSheet(state: EditorState, index: Int, startPage: Int = 0) {
    val key = state.choice.keys.getOrNull(index) ?: return
    val pager = rememberPagerState(initialPage = startPage) { 2 }
    val scope = rememberCoroutineScope()
    val theme = LocalTheme.current
    val separation by VoiceSeparation.state.collectAsState()
    var separating by remember { mutableStateOf(false) }
    com.ketvoice.app.ui.common.SpringSheet(onDismiss = { state.keyOpen = null },
        insets = Modifier.statusBarsPadding().navigationBarsPadding(), startFull = true) { _ ->
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(Ket27.Metrics.alertRadius))
            .background(theme.card).pointerInput(Unit) { detectTapGestures { } }.padding(bottom = 12.dp).testTag("ketmix-key-sheet")) {
            com.ketvoice.app.ui.common.SheetGrabber(Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth().padding(horizontal = Ket27.Metrics.margin, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Point de volume", color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.weight(1f))
                Text(MixEditor.clock(key.atMs), color = theme.secondary, style = Ket27.Type.footnote, localize = false)
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().testTag("ketmix-key-pager")) { page ->
                val outgoing = page == 0
                val track = if (outgoing) state.from else state.into.track
                val separated = if (outgoing) state.outSeparated else state.inSeparated
                KeyPage(track, if (outgoing) key.out else key.into, separated, outgoing) { state.setLevels(index, outgoing, it) }
            }
            // Which title is shown: two dots, as pages of iOS.
            Row(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(2) { i ->
                    Box(Modifier.size(7.dp).clip(CircleShape).background(theme.onBackground.copy(alpha = if (pager.currentPage == i) .85f else .22f)))
                }
            }
            if (!state.outSeparated || !state.inSeparated) {
                val working = separation as? VoiceSeparation.State.Working
                Column(Modifier.fillMaxWidth().padding(horizontal = Ket27.Metrics.margin, vertical = 4.dp)) {
                    Text("Séparez les deux titres pour régler leur voix et leurs instruments.", color = theme.secondary, style = Ket27.Type.footnote)
                    if (separating && working != null) Text(com.ketvoice.app.data.AppText.t("Préparation de la voix…") + " ${Math.round(working.fraction * 100)} %",
                        color = theme.secondary, style = Ket27.Type.footnote, localize = false, modifier = Modifier.padding(top = 4.dp).testTag("ketmix-key-separating"))
                    else KetTextButton("Séparer les voix", onClick = {
                        separating = true
                        scope.launch { try { state.separateBoth() } finally { separating = false } }
                    }, enabled = VoiceSeparation.modelReady && !separating, modifier = Modifier.testTag("ketmix-key-separate"))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Ket27.Metrics.margin, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                KetTextButton("Supprimer le point", onClick = { state.removeKey(index) }, destructive = true, modifier = Modifier.testTag("ketmix-key-delete"))
                KetTextButton("OK", onClick = { state.keyOpen = null }, primary = true)
            }
        }
    }
}

@Composable
private fun KeyPage(track: Track, levels: Levels, separated: Boolean, outgoing: Boolean, onChange: (Levels) -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = Ket27.Metrics.margin).testTag(if (outgoing) "ketmix-key-page-out" else "ketmix-key-page-in")) {
        Text(if (outgoing) "Sortant" else "Entrant", color = theme.accent, style = Ket27.Type.caption, fontWeight = FontWeight.SemiBold)
        Text(track.title, color = theme.onBackground, style = Ket27.Type.title3, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, localize = false, modifier = Modifier.semantics { heading() })
        Text(track.artist, color = theme.secondary, style = Ket27.Type.subheadline, maxLines = 1, overflow = TextOverflow.Ellipsis, localize = false)
        Spacer(Modifier.height(10.dp))
        LevelRow("Voix", levels.voice, separated, "ketmix-key-voice") { onChange(levels.copy(voice = it)) }
        LevelRow("Instruments", levels.instruments, separated, "ketmix-key-instruments") { onChange(levels.copy(instruments = it)) }
        LevelRow("Musique", levels.music, true, "ketmix-key-music") { onChange(levels.copy(music = it)) }
    }
}

@Composable
private fun LevelRow(label: String, percent: Int, enabled: Boolean, tag: String, onChange: (Int) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (enabled) 1f else .45f), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.width(104.dp), maxLines = 1)
        com.ketvoice.app.ui.common.GlassSlider(value = percent / 100f, onValueChange = { onChange(Math.round(it * 100f)) },
            modifier = Modifier.weight(1f).testTag(tag), enabled = enabled, steps = 20)
        Text("$percent %", color = theme.secondary, style = Ket27.Type.footnote, localize = false,
            modifier = Modifier.width(52.dp).padding(start = 8.dp), maxLines = 1)
    }
}
