package com.ketvoice.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.ketvoice.app.data.CustomLyrics
import com.ketvoice.app.data.LyricsDraft
import com.ketvoice.app.data.LyricsExport
import com.ketvoice.app.data.LyricsOffsets
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.Track

/**
 * « Éditeur de paroles » on the PC (9.0): which title is being timed. Opened from a title's menu and from the lyrics'
 * source menu; the editor covers the window until it is closed.
 */
object LyricsEditorDesk {
    val request = mutableStateOf<Track?>(null)
    fun open(track: Track) { request.value = track }
    fun close() { request.value = null }

    /** Where the editor reads and writes files; tests replace it. */
    var io: LyricsEditorIo = DesktopLyricsIo

    /** The text kept for the title: enhanced LRC when any word was timed, LRC otherwise (as on the phone). */
    fun fileText(draft: LyricsDraft, track: Track): String = if (draft.lines.any { it.wordsTimed })
        LyricsExport.enhanced(draft, track.title, track.artist, track.album) else LyricsExport.lrc(draft, track.title, track.artist, track.album)

    /** A file name for an export: « Artiste - Titre.lrc » without the characters Windows refuses. */
    fun exportName(track: Track): String =
        "${track.artist} - ${track.title}".replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).trim().ifBlank { "paroles" } + ".lrc"
}

/** Files the editor touches: the title's own lyrics, an exported copy, a file opened to start from. */
interface LyricsEditorIo {
    /** Keeps [text] as [track]'s own lyrics (they then come first, as on the phone) and clears its lyrics offset. */
    fun save(track: Track, text: String)
    /** Asks where to write [name] and writes [text] there; false when the listener cancelled. */
    fun export(name: String, text: String): Boolean
    /** Asks for a lyrics file (LRC, enhanced LRC, TTML, text…) and returns its text, or null when cancelled. */
    fun open(): String?
}

object DesktopLyricsIo : LyricsEditorIo {
    override fun save(track: Track, text: String) {
        val context = android.content.Context.app
        CustomLyrics.saveText(context, track.id, track.title.take(60).ifBlank { "Ketvoice" } + ".lrc", text)
        // The new times are on the sound itself: an offset made for other lyrics no longer applies.
        LyricsOffsets.init(context); LyricsOffsets.set(track.id, 0)
    }

    override fun export(name: String, text: String): Boolean {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, com.ketvoice.app.data.AppText.t("Exporter les paroles"), java.awt.FileDialog.SAVE)
        dialog.file = name; dialog.isVisible = true
        val file = dialog.file ?: return false
        java.io.File(dialog.directory, if (file.endsWith(".lrc", ignoreCase = true)) file else "$file.lrc").writeText(text)
        return true
    }

    override fun open(): String? {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, com.ketvoice.app.data.AppText.t("Ouvrir un fichier"), java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file?.let { java.io.File(dialog.directory, it) } ?: return null
        require(file.length() <= CustomLyrics.MAX_BYTES) { "Fichier trop volumineux (2 Mo maximum)" }
        return CustomLyrics.decode(file.readBytes())
    }
}

/** What the editor holds while it is open (the phone's `LyricsEditorState`, with the PC's keys). */
class LyricsEditorModel(val track: Track, initial: LyricsDraft = LyricsDraft()) {
    var draft by mutableStateOf(initial)
    var text by mutableStateOf(initial.text)
    var editingText by mutableStateOf(initial.lines.isEmpty())
    var preview by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    private var saved = initial.lines
    private val initialText = initial.text
    val dirty: Boolean get() = draft.lines != saved || (editingText && text != initialText)
    val canSave: Boolean get() = draft.timedLines > 0 && !editingText

    /** The text typed or pasted becomes the lines to time (lines unchanged keep their times). */
    fun startTiming() {
        draft = if (draft.lines.isEmpty()) LyricsDraft.fromText(text).copy(mode = draft.mode) else draft.retext(text)
        editingText = draft.lines.isEmpty()
        message = if (draft.lines.isEmpty()) "Aucune ligne à caler" else null
    }

    fun take(next: LyricsDraft) { draft = next; text = next.text; editingText = next.lines.isEmpty() }

    /** Times what comes next at [heardMs], the position heard. */
    fun tap(heardMs: Long) { if (!editingText) draft = draft.tap(heardMs) }
    fun nudge(deltaMs: Long) { draft = draft.nudge(deltaMs) }
    fun undo() { draft = draft.undo() }
    /** Moves the cursor one line up or down. */
    fun step(by: Int) { draft = draft.select((draft.line + by).coerceIn(0, draft.lines.size)) }

    fun editText() { text = draft.text; editingText = true }

    /** Saves the title's lyrics through [io]; false (and a message) when it failed. */
    fun save(io: LyricsEditorIo): Boolean = runCatching { io.save(track, LyricsEditorDesk.fileText(draft, track)) }
        .onSuccess { saved = draft.lines; message = "Paroles enregistrées pour ce titre" }
        .onFailure { message = it.message ?: "Enregistrement impossible" }.isSuccess

    fun export(io: LyricsEditorIo, enhanced: Boolean) {
        val body = if (enhanced) LyricsExport.enhanced(draft, track.title, track.artist, track.album)
            else LyricsExport.lrc(draft, track.title, track.artist, track.album)
        runCatching { io.export(LyricsEditorDesk.exportName(track), body) }.onFailure { message = "Export impossible" }
    }

    fun openFile(io: LyricsEditorIo) {
        runCatching { io.open()?.let(LyricsExport::import) }
            .onSuccess { found -> if (found != null) { if (found.lines.isEmpty()) message = "Aucune parole trouvée dans ce fichier" else take(found) } }
            .onFailure { message = it.message ?: "Fichier illisible" }
    }

    /** The draft as the lyrics view shows it. */
    fun previewState(): LyricsRepo.State {
        val lines = draft.toLyricLines()
        return LyricsRepo.State(source = "Éditeur", sourceKey = LyricsRepo.SOURCE_CUSTOM, lines = lines, isTimed = lines.isNotEmpty(),
            wordTimed = lines.any { it.words.size >= 2 }, trackId = track.id)
    }
}

/**
 * The editor's keys while it is open (the window's own shortcuts wait): Space times the next line or word, ← and →
 * move the last time by 50 ms (10 ms with Shift), ↑ and ↓ move the cursor, Ctrl+Z undoes, Ctrl+S saves, Escape closes.
 * A text field being typed in keeps its keys.
 */
internal object LyricsEditorKeys {
    var target: LyricsEditorModel? = null
    var heard: () -> Long = { 0L }
    var save: () -> Unit = {}
    var close: () -> Unit = {}

    fun handle(e: KeyEvent): Boolean {
        val m = target ?: return false
        if (e.type != KeyEventType.KeyDown || TextEntry.active) return false
        val fine = if (e.isShiftPressed) 10L else 50L
        return when {
            e.key == Key.Escape -> { close(); true }
            e.isCtrlPressed && e.key == Key.S -> { save(); true }
            m.editingText -> false
            e.isCtrlPressed && e.key == Key.Z -> { m.undo(); true }
            e.key == Key.Spacebar -> { m.tap(heard()); true }
            e.key == Key.DirectionLeft -> { m.nudge(-fine); true }
            e.key == Key.DirectionRight -> { m.nudge(fine); true }
            e.key == Key.DirectionUp -> { m.step(-1); true }
            e.key == Key.DirectionDown -> { m.step(1); true }
            else -> false
        }
    }
}
