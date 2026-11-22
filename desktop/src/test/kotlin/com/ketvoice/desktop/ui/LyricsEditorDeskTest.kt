package com.ketvoice.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CustomLyrics
import com.ketvoice.app.data.LyricsDraft
import com.ketvoice.app.data.LyricsExport
import com.ketvoice.app.data.TapMode
import com.ketvoice.app.data.Track
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The PC's lyrics editor: the model, the keys through the window, saving and exporting through its file seam. */
@OptIn(ExperimentalTestApi::class)
class LyricsEditorDeskTest {
    private class FakeIo(var opened: String? = null) : LyricsEditorIo {
        val saved = mutableListOf<Pair<String, String>>()
        val exported = mutableListOf<Pair<String, String>>()
        override fun save(track: Track, text: String) { saved += track.id to text }
        override fun export(name: String, text: String): Boolean { exported += name to text; return true }
        override fun open(): String? = opened
    }

    private val io = FakeIo()
    private val track get() = Fixtures.albumTracks(0)[3]

    @BeforeTest fun setUp() { LyricsEditorDesk.io = io; AppSettings.reduceAnimations.value = true; com.ketvoice.app.data.AppText.init(android.content.Context.app) }
    @AfterTest fun tearDown() { LyricsEditorDesk.io = DesktopLyricsIo; LyricsEditorDesk.close() }

    @Test fun tapsNudgesUndoAndTheFileText() {
        val m = LyricsEditorModel(track)
        m.text = "[Couplet]\nPremière ligne ici\n\nDeuxième ligne"
        m.startTiming()
        assertFalse(m.editingText); assertEquals(2, m.draft.lines.size)
        m.tap(1_000); m.tap(4_000)
        assertTrue(m.draft.done); assertTrue(m.dirty); assertTrue(m.canSave)
        m.nudge(50)
        assertEquals(4_050, m.draft.lines[1].timeMs)
        m.undo(); assertEquals(4_000, m.draft.lines[1].timeMs)
        assertEquals("[00:01.00]Première ligne ici", LyricsEditorDesk.fileText(m.draft, track).lines().first { it.startsWith("[00:") })
        // Word mode: the file becomes enhanced LRC.
        m.draft = m.draft.select(0).withMode(TapMode.Word)
        m.tap(1_000); m.tap(1_400); m.tap(1_900)
        assertTrue("<00:01.40>" in LyricsEditorDesk.fileText(m.draft, track))
        assertTrue(m.save(io)); assertFalse(m.dirty)
        assertEquals("Paroles enregistrées pour ce titre", m.message)
        assertEquals(track.id, io.saved.single().first)
        // What was saved reads back as the same lines.
        assertEquals(m.draft.toLyricLines().map { it.timeMs }, CustomLyrics.parse(io.saved.single().second).second.map { it.timeMs })
    }

    @Test fun exportAndOpenGoThroughTheFileSeam() {
        val m = LyricsEditorModel(track, LyricsDraft.fromText("Une\nDeux"))
        m.editingText = false
        m.tap(500); m.tap(2_500)
        m.export(io, enhanced = false)
        assertEquals(LyricsEditorDesk.exportName(track), io.exported.single().first)
        assertTrue(io.exported.single().first.endsWith(".lrc") && "/" !in io.exported.single().first)
        assertTrue("[00:02.50]Deux" in io.exported.single().second)
        io.opened = LyricsExport.lrc(m.draft)
        val other = LyricsEditorModel(track)
        other.openFile(io)
        assertEquals(listOf(500L, 2_500L), other.draft.lines.map { it.timeMs })
        io.opened = "   "
        other.openFile(io)
        assertEquals("Aucune parole trouvée dans ce fichier", other.message)
    }

    @Test fun theWindowsKeysTimeTheLinesOnWhatIsHeard() = runComposeUiTest {
        Fixtures.installCovers()
        val player = RecordingPlayer().playing().also { it.toggle() } // paused: the heard position holds at 62 s
        setContent { KetvoiceShell(player, Fixtures.library(), FixtureCatalog, dark = true) }
        waitForIdle()
        LyricsEditorDesk.open(player.state.value.current!!)
        waitForIdle()
        onNodeWithTag("lyrics-editor").assertIsDisplayed()
        onNodeWithTag("lyrics-editor-text").performTextInput("Ligne un\nLigne deux\nLigne trois")
        onNodeWithTag("lyrics-editor-start").performClick()
        waitForIdle()
        val shell = onNodeWithTag("shell")
        val before = player.calls.size
        shell.performKeyInput { pressKey(Key.Spacebar) }
        // Space timed the first line and did not reach the player.
        onNodeWithTag("lyrics-editor-line:0").assertExists()
        assertEquals(before, player.calls.size)
        val model = LyricsEditorKeys.target!!
        assertEquals(62_000, model.draft.lines[0].timeMs)
        shell.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(62_050, model.draft.lines[0].timeMs)
        shell.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
        assertEquals(62_040, model.draft.lines[0].timeMs)
        shell.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        assertEquals(62_050, model.draft.lines[0].timeMs)
        onNodeWithTag("lyrics-editor-tap").performClick()
        assertEquals(62_000, model.draft.lines[1].timeMs)
        shell.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        waitForIdle()
        assertEquals(1, io.saved.size)
        shell.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(LyricsEditorDesk.request.value)
    }

    @Test fun thePreviewShowsTheDraftAsTheLyricsViewDoes() = runComposeUiTest {
        Fixtures.installCovers()
        val player = RecordingPlayer().playing().also { it.toggle() }
        val model = LyricsEditorModel(player.state.value.current!!, LyricsDraft.fromText("Une\nDeux"))
        model.editingText = false; model.tap(61_000); model.tap(63_000)
        setContent {
            com.ketvoice.desktop.ui.theme.KetDesktopTheme(true) {
                androidx.compose.runtime.CompositionLocalProvider(LocalPlayer provides player, LocalLyricsFeed provides FixedLyricsFeed(), LocalMenu provides MenuState()) {
                    LyricsEditorScreen(model, onClose = {})
                }
            }
        }
        onNodeWithTag("lyrics-editor-preview-toggle").performClick()
        waitForIdle()
        onNodeWithTag("lyrics-editor-preview").assertIsDisplayed()
        onNodeWithTag("lyric:0", useUnmergedTree = true).assertExists()
    }

    @Test fun closingWithUnsavedTimesAsksFirst() = runComposeUiTest {
        Fixtures.installCovers()
        val player = RecordingPlayer().playing().also { it.toggle() }
        setContent { KetvoiceShell(player, Fixtures.library(), FixtureCatalog, dark = false) }
        waitForIdle()
        LyricsEditorDesk.open(player.state.value.current!!)
        waitForIdle()
        onNodeWithTag("lyrics-editor-text").performTextInput("Une ligne")
        onNodeWithTag("lyrics-editor-start").performClick()
        onNodeWithTag("lyrics-editor-tap").performClick()
        onNodeWithTag("lyrics-editor-close").performClick()
        onNodeWithTag("confirm-dialog").assertIsDisplayed()
        onNodeWithTag("confirm-alternative").performClick()
        waitForIdle()
        assertNull(LyricsEditorDesk.request.value)
        assertTrue(io.saved.isEmpty())
    }

    @Test fun theTitleMenuAndTheLyricsSourceMenuOpenTheEditor() = runComposeUiTest {
        Fixtures.installCovers()
        val player = RecordingPlayer().playing().also { it.toggle() }
        val menu = MenuState()
        val feed = FixedLyricsFeed()
        setContent {
            com.ketvoice.desktop.ui.theme.KetDesktopTheme(true) {
                androidx.compose.runtime.CompositionLocalProvider(LocalPlayer provides player, LocalLyricsFeed provides feed, LocalMenu provides menu) {
                    CascadeLyrics(player.state.value.current!!, LyricsLook.Panel, androidx.compose.ui.Modifier)
                }
            }
        }
        onNodeWithTag("lyrics-source").performClick()
        menu.items.first { it.tag == "lyrics-open-editor" }.action()
        assertEquals(player.state.value.current!!.id, LyricsEditorDesk.request.value?.id)
    }
}
