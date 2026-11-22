package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CustomLyrics
import com.ketvoice.app.data.LyricsDraft
import com.ketvoice.app.data.LyricsExport
import com.ketvoice.app.data.LyricsOffsets
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.TapMode
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.GlassSlider
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.KetTextField
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** « Éditeur de paroles » (9.0): which title is being timed; opened from the lyrics sources and from a title's menu. */
internal object LyricsEditor {
    val request = mutableStateOf<Track?>(null)
    fun open(track: Track) { request.value = track }
    fun close() { request.value = null }

    /** Where the sound heard is in the title: the player's position less the voice stage's delay (as the lyrics view reads it). */
    fun heardMs(): Long = (Player.currentPositionMs() - com.ketvoice.app.stems.VoiceLevel.delayMs.value).coerceAtLeast(0L)

    /** The text kept for the title: enhanced LRC when any word was timed, LRC otherwise. */
    fun fileText(draft: LyricsDraft, track: Track): String = if (draft.lines.any { it.wordsTimed })
        LyricsExport.enhanced(draft, track.title, track.artist, track.album) else LyricsExport.lrc(draft, track.title, track.artist, track.album)
}

/** What the editor holds while it is open; built by [LyricsEditorHost], or by a test. */
internal class LyricsEditorState(val track: Track, initial: LyricsDraft = LyricsDraft()) {
    var draft by mutableStateOf(initial)
    var text by mutableStateOf(initial.text)
    var editingText by mutableStateOf(initial.lines.isEmpty())
    var preview by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    private var saved = initial.lines
    private val initialText = initial.text
    val dirty: Boolean get() = draft.lines != saved || (editingText && text != initialText)

    /** The text typed or pasted becomes the lines to time (lines unchanged keep their times). */
    fun startTiming() {
        draft = if (draft.lines.isEmpty()) LyricsDraft.fromText(text).copy(mode = draft.mode) else draft.retext(text)
        editingText = draft.lines.isEmpty()
        message = if (draft.lines.isEmpty()) "Aucune ligne à caler" else null
    }

    fun take(next: LyricsDraft) { draft = next; text = next.text; editingText = next.lines.isEmpty() }

    fun tap() { draft = draft.tap(LyricsEditor.heardMs()) }

    fun markSaved() { saved = draft.lines }

    /** The draft as the lyrics view shows it. */
    fun previewState(): LyricsRepo.State {
        val lines = draft.toLyricLines()
        return LyricsRepo.State(source = "Éditeur", sourceKey = LyricsRepo.SOURCE_CUSTOM, lines = lines, isTimed = lines.isNotEmpty(),
            wordTimed = lines.any { it.words.size >= 2 }, trackId = track.id)
    }
}

/** Draws the editor that was asked for, over everything else. Place it once, at the root. */
@Composable
internal fun LyricsEditorHost(modifier: Modifier = Modifier) {
    val track = LyricsEditor.request.value ?: return
    val state = remember(track.id) { LyricsEditorState(track) }
    LyricsEditorScreen(state, onClose = { LyricsEditor.close() }, modifier = modifier)
}

@Composable
internal fun LyricsEditorScreen(state: LyricsEditorState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val close = { if (state.dirty) asking = true else onClose() }
    androidx.activity.compose.BackHandler { close() }
    fun save(then: () -> Unit) {
        val track = state.track
        val text = LyricsEditor.fileText(state.draft, track)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CustomLyrics.saveText(context, track.id, track.title.take(60).ifBlank { "Ketvoice" } + ".lrc", text)
                    // The new times are on the sound itself: an offset made for other lyrics no longer applies.
                    LyricsOffsets.init(context); LyricsOffsets.set(track.id, 0)
                }
            }
            result.onSuccess {
                state.markSaved(); state.message = "Paroles enregistrées pour ce titre"
                LyricsRepo.load(track, force = true); then()
            }.onFailure { state.message = it.message ?: "Enregistrement impossible" }
        }
    }
    Column(modifier.fillMaxSize().background(theme.background).statusBarsPadding().navigationBarsPadding().testTag("lyrics-editor")) {
        EditorBar(state, onClose = close, onSave = { save {} })
        Column(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = Ket27.Metrics.margin)) {
            if (state.editingText) TextStep(state) else TimingStep(state)
        }
    }
    if (asking) AppAlertDialog(onDismissRequest = { asking = false },
        confirmButton = { KetTextButton("Enregistrer", onClick = { asking = false; save(onClose) }, primary = true, enabled = state.draft.timedLines > 0) },
        dismissButton = { KetTextButton("Ne pas enregistrer", onClick = { asking = false; onClose() }, destructive = true) },
        title = { Text("Enregistrer ces paroles ?") },
        text = { Text("Les temps calés seront perdus si vous fermez sans enregistrer.") })
}

@Composable
private fun EditorBar(state: LyricsEditorState, onClose: () -> Unit, onSave: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        KetTextButton("Fermer", onClose, Modifier.heightIn(min = 48.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Éditeur de paroles", color = theme.onBackground, style = Ket27.Type.headline, modifier = Modifier.semantics { heading() })
            Text("${state.track.title} — ${state.track.artist}", localize = false, color = theme.secondary, style = Ket27.Type.footnote,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        KetTextButton("Enregistrer", onSave, Modifier.heightIn(min = 48.dp).testTag("lyrics-editor-save"), primary = true,
            enabled = state.draft.timedLines > 0 && !state.editingText)
    }
}

@Composable
private fun ColumnScope.TextStep(state: LyricsEditorState) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val current by LyricsRepo.state.collectAsState()
    val existing = current.lines.takeIf { current.trackId == state.track.id && it.isNotEmpty() }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes().also { b -> require(b.size <= CustomLyrics.MAX_BYTES) { "Fichier trop volumineux (2 Mo maximum)" } } }
                        ?: throw IllegalArgumentException("Fichier illisible")
                    LyricsExport.import(CustomLyrics.decode(bytes))
                }
            }
            result.onSuccess { if (it.lines.isEmpty()) state.message = "Aucune parole trouvée dans ce fichier" else state.take(it) }
                .onFailure { state.message = it.message ?: "Fichier illisible" }
        }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("Une ligne par ligne chantée. Collez ou saisissez le texte, partez des paroles actuelles ou ouvrez un fichier, puis calez chaque ligne en tapant en rythme.",
            color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            KetTextButton("Coller", { clipboard.getText()?.text?.let { state.text = it.take(200_000) } }, Modifier.heightIn(min = 48.dp))
            KetTextButton("Paroles actuelles", { existing?.let { state.take(LyricsDraft.fromLines(it)) } }, Modifier.heightIn(min = 48.dp),
                enabled = existing != null)
            KetTextButton("Ouvrir un fichier", { picker.launch(arrayOf("*/*")) }, Modifier.heightIn(min = 48.dp))
        }
        KetTextField(state.text, { state.text = it.take(200_000) }, "Texte des paroles", singleLine = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).testTag("lyrics-editor-text"))
        state.message?.let { Text(it, color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 8.dp)) }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
        com.ketvoice.app.ui.settings.SettingsAction("Caler les temps", { state.startTiming() }, enabled = state.text.isNotBlank(), primary = true,
            modifier = Modifier.testTag("lyrics-editor-start"))
    }
}

@Composable
private fun ColumnScope.TimingStep(state: LyricsEditorState) {
    val theme = LocalTheme.current
    val playing by Player.state.collectAsState()
    val here = playing.current?.id == state.track.id
    if (!here) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(theme.onBackground.copy(alpha = .06f))
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Ce titre n'est pas en lecture : lancez-le pour caler les temps.", color = theme.onBackground, style = Ket27.Type.footnote, modifier = Modifier.weight(1f))
        KetTextButton("Lire ce titre", { Player.playNext(state.track); Player.next() })
    }
    ModeAndView(state)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (state.preview) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color(0xFF15151A)).testTag("lyrics-editor-preview")) {
            LyricsPane(state.track, expanded = false, onSource = {}, modifier = Modifier.fillMaxSize(), preview = state.previewState())
        } else DraftList(state)
    }
    Transport(state, enabled = here)
    TapRow(state, enabled = here)
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        KetTextButton("Modifier le texte", { state.text = state.draft.text; state.editingText = true }, Modifier.heightIn(min = 48.dp))
        ExportButton(state)
    }
    state.message?.let { Text(it, color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(bottom = 4.dp)) }
}

@Composable
private fun ModeAndView(state: LyricsEditorState) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(TapMode.Line to "Lignes", TapMode.Word to "Mots").forEach { (mode, label) ->
            val selected = state.draft.mode == mode && !state.preview
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) theme.accent.copy(alpha = .16f) else theme.onBackground.copy(alpha = .06f))
                .selectable(selected, role = Role.RadioButton) { state.preview = false; state.draft = state.draft.withMode(mode) }
                .heightIn(min = 44.dp).padding(horizontal = 16.dp).testTag("lyrics-mode-" + mode.name), contentAlignment = Alignment.Center) {
                Text(label, color = if (selected) theme.accent else theme.onBackground, style = Ket27.Type.subheadline)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (state.preview) theme.accent.copy(alpha = .16f) else theme.onBackground.copy(alpha = .06f))
            .selectable(state.preview, role = Role.Switch) { state.preview = !state.preview }
            .heightIn(min = 44.dp).padding(horizontal = 16.dp).testTag("lyrics-editor-preview-toggle"), contentAlignment = Alignment.Center) {
            Text("Aperçu", color = if (state.preview) theme.accent else theme.onBackground, style = Ket27.Type.subheadline)
        }
    }
}

@Composable
private fun DraftList(state: LyricsEditorState) {
    val theme = LocalTheme.current
    val list = rememberLazyListState()
    val draft = state.draft
    LaunchedEffect(draft.line) { if (draft.lines.isNotEmpty()) list.animateScrollToItem((draft.line - 2).coerceIn(0, draft.lines.lastIndex)) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("lyrics-editor-lines")) {
        itemsIndexed(draft.lines) { i, line ->
            val cursor = i == draft.line
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (cursor) theme.accent.copy(alpha = .12f) else Color.Transparent)
                .clickable(role = Role.Button) { state.draft = state.draft.select(i) }
                .padding(horizontal = 10.dp, vertical = 9.dp).testTag("lyrics-editor-line"), verticalAlignment = Alignment.Top) {
                Text(if (line.timeMs >= 0) LyricsExport.stamp(line.timeMs) else "—", localize = false,
                    color = if (line.timeMs >= 0) theme.accent else theme.secondary,
                    style = Ket27.Type.footnote.copy(fontFeatureSettings = "tnum"), modifier = Modifier.width(66.dp).padding(top = 2.dp))
                if (draft.mode == TapMode.Word) WordChips(line, if (cursor) draft.word else -1, Modifier.weight(1f))
                else Text(line.text, localize = false, color = theme.onBackground, style = Ket27.Type.body,
                    fontWeight = if (cursor) FontWeight.SemiBold else null, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordChips(line: com.ketvoice.app.data.DraftLine, current: Int, modifier: Modifier) {
    val theme = LocalTheme.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        line.words.forEachIndexed { j, w ->
            val ground = when { j == current -> theme.accent.copy(alpha = .28f); w.startMs >= 0 -> theme.accent.copy(alpha = .1f); else -> theme.onBackground.copy(alpha = .05f) }
            Text(w.text.trim(), localize = false, color = theme.onBackground, style = Ket27.Type.body,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ground).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun Transport(state: LyricsEditorState, enabled: Boolean) {
    val theme = LocalTheme.current
    val playing by Player.state.collectAsState()
    val position by Player.positionMs.collectAsState()
    val duration = playing.durationMs.coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        GlassSlider(value = position.coerceIn(0L, duration).toFloat(), onValueChange = { Player.seekTo(it.toLong()) },
            valueRange = 0f..duration.toFloat(), enabled = enabled, modifier = Modifier.testTag("lyrics-editor-seek"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(LyricsExport.stamp(position), localize = false, color = theme.secondary, style = Ket27.Type.footnote.copy(fontFeatureSettings = "tnum"))
            Spacer(Modifier.weight(1f))
            KetTextButton("−5 s", { Player.seekBy(-5_000) }, enabled = enabled, localize = false)
            KetTextButton(if (playing.isPlaying) "Pause" else "Lecture", { Player.toggle() }, enabled = enabled, primary = true)
            KetTextButton("+5 s", { Player.seekBy(5_000) }, enabled = enabled, localize = false)
            Spacer(Modifier.weight(1f))
            Text(LyricsExport.stamp(playing.durationMs), localize = false, color = theme.secondary, style = Ket27.Type.footnote.copy(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
private fun TapRow(state: LyricsEditorState, enabled: Boolean) {
    val theme = LocalTheme.current
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    val draft = state.draft
    val next = draft.lines.getOrNull(draft.line)?.let { l -> if (draft.mode == TapMode.Word) l.words.getOrNull(draft.word)?.text?.trim() else l.text }
    val tapLabel = AppText.t(if (draft.mode == TapMode.Word) "Caler le mot suivant" else "Caler la ligne suivante")
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            KetTextButton("−50 ms", { state.draft = draft.nudge(-50) }, enabled = draft.lastTimed() != null, localize = false)
            KetTextButton("Annuler", { state.draft = draft.undo() }, enabled = draft.canUndo)
        }
        Box(Modifier.size(132.dp).clip(CircleShape).background(if (enabled && !draft.done) theme.accent else theme.onBackground.copy(alpha = .12f))
            .clickable(enabled = enabled && !draft.done, role = Role.Button) { haptic(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); state.tap() }
            .semantics { contentDescription = tapLabel }.testTag("lyrics-editor-tap"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Text(if (draft.done) "Terminé" else "Taper", color = Color.White, style = Ket27.Type.title3, fontWeight = FontWeight.Bold)
                if (next != null) Text(next, localize = false, color = Color.White.copy(alpha = .85f), style = Ket27.Type.footnote,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            KetTextButton("+50 ms", { state.draft = draft.nudge(50) }, enabled = draft.lastTimed() != null, localize = false)
            Text("${draft.timedLines}/${draft.lines.size}", localize = false, color = theme.secondary, style = Ket27.Type.footnote)
        }
    }
}

@Composable
private fun ExportButton(state: LyricsEditorState) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    KetTextButton("Exporter", { open = true }, Modifier.heightIn(min = 48.dp), enabled = state.draft.timedLines > 0)
    if (open) AppAlertDialog(onDismissRequest = { open = false },
        confirmButton = {
            KetTextButton("LRC enrichi (mots)", { open = false; share(context, state, enhanced = true) }, enabled = state.draft.hasWordTimes)
        },
        dismissButton = { KetTextButton("LRC", { open = false; share(context, state, enhanced = false) }, localize = false) },
        title = { Text("Exporter les paroles") },
        text = { Text("Le LRC enrichi garde le temps de chaque mot ; le LRC simple, celui de chaque ligne.") })
}

private fun share(context: android.content.Context, state: LyricsEditorState, enhanced: Boolean) {
    val track = state.track
    val text = if (enhanced) LyricsExport.enhanced(state.draft, track.title, track.artist, track.album)
        else LyricsExport.lrc(state.draft, track.title, track.artist, track.album)
    runCatching {
        val folder = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val name = "${track.artist} - ${track.title}".replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "paroles" }
        val file = java.io.File(folder, name + ".lrc").apply { writeText(text) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "${track.title} — ${track.artist}")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(send, AppText.t("Exporter les paroles")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { state.message = "Export impossible" }
}
