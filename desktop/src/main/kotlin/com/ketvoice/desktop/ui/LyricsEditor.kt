package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.LyricsDraft
import com.ketvoice.app.data.LyricsExport
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.TapMode
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Draws the editor that was asked for, over the whole window. Placed once, in the shell. */
@Composable
fun LyricsEditorHost() {
    val track = LyricsEditorDesk.request.value ?: return
    val model = remember(track.id) { LyricsEditorModel(track) }
    LyricsEditorScreen(model, onClose = { LyricsEditorDesk.close() })
}

/**
 * The PC's lyrics editor: paste, type or open the text, then time each line (or each word) by tapping in rhythm on what
 * is heard — Space or the round button — nudge the last time by ±50 ms, undo, preview as the lyrics view shows it, and
 * save for the title (enhanced LRC when words are timed) or export a .lrc file.
 */
@Composable
fun LyricsEditorScreen(model: LyricsEditorModel, onClose: () -> Unit) {
    val ket = LocalKet.current
    val feed = LocalLyricsFeed.current
    val clock = rememberLyricsClock()
    var asking by remember { mutableStateOf(false) }
    val close = { if (model.dirty) asking = true else onClose() }
    val save = { if (model.canSave && model.save(LyricsEditorDesk.io)) feed.load(model.track, refresh = true) }
    LyricsEditorKeys.target = model; LyricsEditorKeys.heard = { clock.value }
    LyricsEditorKeys.save = save; LyricsEditorKeys.close = close
    DisposableEffect(model) { onDispose { if (LyricsEditorKeys.target === model) LyricsEditorKeys.target = null } }
    // Timing: the editor holds the keyboard (a text field that was typed in is gone), so Space and the arrows reach it.
    val focus = remember { FocusRequester() }
    LaunchedEffect(model.editingText) { if (!model.editingText) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(if (ket.acrylic) ket.material.copy(alpha = 1f) else ket.ground)
        // Clicks stop here (the window under it is not reached) and give the keyboard back to the editor.
        .pointerInput(Unit) { detectTapGestures { runCatching { focus.requestFocus() } } }
        .focusRequester(focus).focusable().testTag("lyrics-editor")) {
        EditorBar(model, close, save)
        Column(Modifier.weight(1f).widthIn(max = 820.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 32.dp)) {
            if (model.editingText) TextStep(model) else TimingStep(model, clock.value)
        }
    }
    if (asking) SaveOrDropDialog(model, onSave = { save(); if (!model.dirty) onClose() }, onDrop = onClose, onDismiss = { asking = false })
}

@Composable
private fun EditorBar(model: LyricsEditorModel, onClose: () -> Unit, onSave: () -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorButton("Fermer", onClose, tag = "lyrics-editor-close")
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            KText("Éditeur de paroles", Ket27Desk.Type.headline, weight = FontWeight.SemiBold)
            KText("${model.track.title} — ${model.track.artist}", Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
        EditorButton("Enregistrer", onSave, enabled = model.canSave, primary = true, tag = "lyrics-editor-save")
    }
    Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline))
}

@Composable
private fun ColumnScope.TextStep(model: LyricsEditorModel) {
    val ket = LocalKet.current
    val clipboard = LocalClipboardManager.current
    val current by LocalLyricsFeed.current.state.collectAsState()
    val existing = current.lines.takeIf { current.trackId == model.track.id && it.isNotEmpty() }
    KText("Une ligne par ligne chantée. Collez ou saisissez le texte, partez des paroles actuelles ou ouvrez un fichier, puis calez chaque ligne en tapant en rythme.",
        Ket27Desk.Type.footnote, Modifier.padding(top = 16.dp, bottom = 10.dp), color = ket.secondary, maxLines = 4)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorButton("Coller", { clipboard.getText()?.text?.let { model.text = it.take(200_000) } }, tag = "lyrics-editor-paste")
        EditorButton("Paroles actuelles", { existing?.let { model.take(LyricsDraft.fromLines(it)) } }, enabled = existing != null, tag = "lyrics-editor-current")
        EditorButton("Ouvrir un fichier", { model.openFile(LyricsEditorDesk.io) }, tag = "lyrics-editor-open")
        AiTimingButton(model)
    }
    Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp).clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card).padding(14.dp)) {
        if (model.text.isEmpty()) KText("Texte des paroles", Ket27Desk.Type.body, color = ket.tertiary)
        BasicTextField(model.text, { model.text = it.take(200_000) }, Modifier.fillMaxSize().textEntry().testTag("lyrics-editor-text"),
            textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.body).copy(color = ket.label),
            cursorBrush = SolidColor(ket.accent))
    }
    model.message?.let { KText(it, Ket27Desk.Type.footnote, color = ket.secondary, maxLines = 2) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
        EditorButton("Caler les temps", { model.startTiming() }, enabled = model.text.isNotBlank(), primary = true, tag = "lyrics-editor-start")
    }
}

@Composable
private fun ColumnScope.TimingStep(model: LyricsEditorModel, heard: Long) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val playing by player.state.collectAsState()
    val here = playing.current?.id == model.track.id
    if (!here) Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.fill)
        .padding(horizontal = 14.dp, vertical = 8.dp).testTag("lyrics-editor-not-playing"), verticalAlignment = Alignment.CenterVertically) {
        KText("Ce titre n'est pas en lecture : lancez-le pour caler les temps.", Ket27Desk.Type.footnote, Modifier.weight(1f), maxLines = 2)
        EditorButton("Lire ce titre", { player.playNext(model.track); player.next() }, tag = "lyrics-editor-play")
    }
    ModeAndView(model)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (model.preview) EditorPreview(model) else DraftList(model)
    }
    EditorTransport(model, heard, enabled = here)
    TapRow(model, heard, enabled = here)
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        EditorButton("Modifier le texte", { model.editText() }, tag = "lyrics-editor-edit-text")
        KText("Espace : caler · ← → : ±50 ms · ↑ ↓ : ligne · Ctrl+Z : annuler", Ket27Desk.Type.caption,
            Modifier.weight(1f).padding(horizontal = 12.dp), color = ket.tertiary, maxLines = 2)
        ExportButton(model)
    }
    model.message?.let { KText(it, Ket27Desk.Type.footnote, Modifier.padding(bottom = 8.dp), color = ket.secondary, maxLines = 2) }
}

@Composable
private fun ModeAndView(model: LyricsEditorModel) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(TapMode.Line to "Lignes", TapMode.Word to "Mots").forEach { (mode, label) ->
            Chip(label, model.draft.mode == mode && !model.preview, "lyrics-mode-${mode.name}") { model.preview = false; model.draft = model.draft.withMode(mode) }
        }
        Spacer(Modifier.weight(1f))
        Chip("Aperçu", model.preview, "lyrics-editor-preview-toggle") { model.preview = !model.preview }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val ket = LocalKet.current
    Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (selected) ket.accent.copy(alpha = .16f) else ket.fill)
        .clickable(onClick = onClick).padding(horizontal = 14.dp).testTag(tag), contentAlignment = Alignment.Center) {
        KText(label, Ket27Desk.Type.subheadline, color = if (selected) ket.accent else ket.label, weight = FontWeight.Medium)
    }
}

/** The lines and their times; the cursor's line is tinted, a click puts the cursor on a line. */
@Composable
private fun DraftList(model: LyricsEditorModel) {
    val ket = LocalKet.current
    val list = rememberLazyListState()
    val draft = model.draft
    LaunchedEffect(draft.line) { if (draft.lines.isNotEmpty()) list.animateScrollToItem((draft.line - 2).coerceIn(0, draft.lines.lastIndex)) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("lyrics-editor-lines")) {
        itemsIndexed(draft.lines) { i, line ->
            val cursor = i == draft.line
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(if (cursor) ket.accent.copy(alpha = .12f) else Color.Transparent)
                .clickable { model.draft = model.draft.select(i) }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("lyrics-editor-line:$i"),
                verticalAlignment = Alignment.Top) {
                KText(if (line.timeMs >= 0) LyricsExport.stamp(line.timeMs) else "—", Ket27Desk.Type.footnote.copy(fontFeatureSettings = "tnum"),
                    Modifier.width(72.dp).padding(top = 2.dp), color = if (line.timeMs >= 0) ket.accent else ket.secondary, translate = false)
                if (draft.mode == TapMode.Word) WordChips(line, if (cursor) draft.word else -1, Modifier.weight(1f))
                else KText(line.text, Ket27Desk.Type.body, Modifier.weight(1f), translate = false, maxLines = 3,
                    weight = if (cursor) FontWeight.SemiBold else null)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordChips(line: com.ketvoice.app.data.DraftLine, current: Int, modifier: Modifier) {
    val ket = LocalKet.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        line.words.forEachIndexed { j, w ->
            val ground = when { j == current -> ket.accent.copy(alpha = .28f); w.startMs >= 0 -> ket.accent.copy(alpha = .1f); else -> ket.fill }
            KText(w.text.trim(), Ket27Desk.Type.body, Modifier.clip(RoundedCornerShape(6.dp)).background(ground).padding(horizontal = 6.dp, vertical = 2.dp),
                translate = false)
        }
    }
}

/** The draft drawn by the lyrics view itself, on its own clock (the title must play for it to move). */
@Composable
private fun EditorPreview(model: LyricsEditorModel) {
    val ket = LocalKet.current
    val feed = remember(model) { PreviewFeed(model.previewState()) }
    LaunchedEffect(model.draft) { feed.show(model.previewState()) }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Ket27Desk.Radius.card)).background(ket.card).testTag("lyrics-editor-preview")) {
        CompositionLocalProvider(LocalLyricsFeed provides feed) { CascadeLyrics(model.track, LyricsLook.Panel, Modifier.fillMaxSize()) }
    }
}

/** A lyrics feed holding only the draft being edited. */
private class PreviewFeed(initial: LyricsRepo.State) : LyricsFeed {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<LyricsRepo.State> get() = flow
    override fun load(track: Track, refresh: Boolean) {}
    fun show(next: LyricsRepo.State) { flow.value = next }
}

@Composable
private fun EditorTransport(model: LyricsEditorModel, heard: Long, enabled: Boolean) {
    val player = LocalPlayer.current
    val playing by player.state.collectAsState()
    val duration = playing.durationMs.takeIf { it > 0 } ?: model.track.durationMs
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (enabled) Seek(heard, duration, compact = false)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            EditorButton("−5 s", { player.seekTo((heard - 5_000).coerceAtLeast(0)) }, enabled = enabled, translate = false, tag = "lyrics-editor-back")
            Spacer(Modifier.width(8.dp))
            EditorButton(if (playing.isPlaying && enabled) "Pause" else "Lecture", { player.toggle() }, enabled = enabled, primary = true, tag = "lyrics-editor-toggle")
            Spacer(Modifier.width(8.dp))
            EditorButton("+5 s", { player.seekTo(heard + 5_000) }, enabled = enabled, translate = false, tag = "lyrics-editor-forward")
        }
    }
}

@Composable
private fun TapRow(model: LyricsEditorModel, heard: Long, enabled: Boolean) {
    val ket = LocalKet.current
    val draft = model.draft
    val next = draft.lines.getOrNull(draft.line)?.let { l -> if (draft.mode == TapMode.Word) l.words.getOrNull(draft.word)?.text?.trim() else l.text }
    val canTap = enabled && !draft.done
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorButton("−50 ms", { model.nudge(-50) }, enabled = draft.lastTimed() != null, translate = false, tag = "lyrics-editor-minus")
            EditorButton("Annuler", { model.undo() }, enabled = draft.canUndo, tag = "lyrics-editor-undo")
        }
        Box(Modifier.size(120.dp).clip(CircleShape).background(if (canTap) ket.accent else ket.fill)
            .clickable(enabled = canTap) { model.tap(heard) }.testTag("lyrics-editor-tap"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                KText(if (draft.done) "Terminé" else "Taper", Ket27Desk.Type.title3, color = if (canTap) Color.White else ket.secondary, weight = FontWeight.Bold)
                if (next != null) KText(next, Ket27Desk.Type.footnote, color = if (canTap) Color.White.copy(alpha = .85f) else ket.tertiary, translate = false, maxLines = 2)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorButton("+50 ms", { model.nudge(50) }, enabled = draft.lastTimed() != null, translate = false, tag = "lyrics-editor-plus")
            KText("${draft.timedLines}/${draft.lines.size}", Ket27Desk.Type.footnote, color = ket.secondary, translate = false, modifier = Modifier.testTag("lyrics-editor-count"))
        }
    }
}

@Composable
private fun ExportButton(model: LyricsEditorModel) {
    val menu = LocalMenu.current
    var at by remember { mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }
    Box(Modifier.onGloballyPositioned { val p = it.positionInWindow(); at = androidx.compose.ui.unit.IntOffset(p.x.toInt() - 160, p.y.toInt() - 84) }) {
        EditorButton("Exporter", {
            menu.open(listOf(
                MenuItem("LRC", null, "lyrics-export-lrc", translate = false) { model.export(LyricsEditorDesk.io, enhanced = false) },
                MenuItem("LRC enrichi (mots)", null, "lyrics-export-enhanced") { if (model.draft.hasWordTimes) model.export(LyricsEditorDesk.io, enhanced = true) },
            ), at)
        }, enabled = model.draft.timedLines > 0, tag = "lyrics-editor-export")
    }
}

@Composable
private fun SaveOrDropDialog(model: LyricsEditorModel, onSave: () -> Unit, onDrop: () -> Unit, onDismiss: () -> Unit) {
    val text = com.ketvoice.app.data.AppText
    if (model.canSave) ConfirmDialog(text.t("Enregistrer ces paroles ?"), text.t("Les temps calés seront perdus si vous fermez sans enregistrer."),
        "Enregistrer", onConfirm = onSave, onDismiss = onDismiss, destructive = false, alternative = "Ne pas enregistrer" to onDrop)
    else ConfirmDialog(text.t("Fermer l'éditeur ?"), text.t("Les temps calés seront perdus si vous fermez sans enregistrer."),
        "Ne pas enregistrer", onConfirm = onDrop, onDismiss = onDismiss)
}

/** A text button of the editor: filled with the accent when [primary], tinted otherwise. */
@Composable
private fun EditorButton(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false, translate: Boolean = true, tag: String = label) {
    val ket = LocalKet.current
    val (hover, hovered) = rememberHover()
    val ground = when { !enabled -> ket.fill.copy(alpha = ket.fill.alpha * .5f); primary -> ket.accent; else -> ket.fill }
    Box(Modifier.height(30.dp).clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(if (hovered && enabled) ground.copy(alpha = ground.alpha * .85f) else ground)
        .hoverable(hover).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp).testTag(tag),
        contentAlignment = Alignment.Center) {
        KText(label, Ket27Desk.Type.subheadline, color = when { !enabled -> ket.tertiary; primary -> com.ketvoice.app.theme.CoverLegibility.onFill(ket.accent); else -> ket.label },
            weight = FontWeight.Medium, translate = translate)
    }
}

/**
 * Windows 2.0: lyrics timed word by word by the AI go back to their original timing (or come back) from here; to correct
 * them, « Paroles actuelles » starts from the AI's timing and « Enregistrer » keeps the corrected lyrics as the title's own.
 */
@Composable
private fun AiTimingButton(model: LyricsEditorModel) {
    com.ketvoice.desktop.lyrics.AiLyrics.changes.collectAsState().value
    val entry = com.ketvoice.desktop.lyrics.AiLyrics.entry(model.track.id)?.takeIf { it.file != null } ?: return
    EditorButton(if (entry.reverted) "Reprendre la synchro IA" else "Revenir à la synchro d'origine", {
        com.ketvoice.desktop.lyrics.AiLyrics.revert(model.track.id, back = !entry.reverted)
        com.ketvoice.app.data.LyricsRepo.load(model.track, force = true)
    }, tag = "lyrics-editor-ai")
}
