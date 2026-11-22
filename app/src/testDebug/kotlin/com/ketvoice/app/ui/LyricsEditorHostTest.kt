package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsDraft
import com.ketvoice.app.data.TapMode
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.LyricsEditorScreen
import com.ketvoice.app.ui.player.LyricsEditorState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** « Éditeur de paroles » (9.0) drawn on the host: the text step, timing by taps, word mode and the Cascade preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsEditorHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("qa:editor", "Titre QA", "Artiste QA", "Album QA", durationMs = 200_000)
    @Suppress("UNCHECKED_CAST") private val state get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val position get() = Player.positionMs as MutableStateFlow<Long>

    @Before fun setup() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        state.value = PlayerUiState(queue = listOf(track), index = 0, durationMs = 200_000)
        position.value = 0L
        com.ketvoice.app.ui.player.LyricsEditor.position = { position.value }
    }

    @After fun reset() {
        com.ketvoice.app.ui.player.LyricsEditor.position = { Player.currentPositionMs() }
        AppSettings.reduceAnimations.value = false
        state.value = PlayerUiState(); position.value = 0L
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun pastedTextIsTimedByTapsThenPreviewedInCascade() {
        val editor = LyricsEditorState(track)
        compose.setContent { KetvoiceTheme(useDark = true) { LyricsEditorScreen(editor, onClose = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Éditeur de paroles").assertIsDisplayed()
        compose.onNodeWithTag("lyrics-editor-save").assertIsNotEnabled()
        compose.runOnIdle { editor.text = "Première ligne chantée\nDeuxième ligne\nTroisième" }
        image("lyrics-editor-text")
        compose.onNodeWithTag("lyrics-editor-start").performClick()
        compose.waitForIdle()
        assertEquals(3, editor.draft.lines.size)
        assertEquals(3, compose.onAllNodesWithTag("lyrics-editor-line").fetchSemanticsNodes().size)
        compose.runOnIdle { position.value = 12_340L }
        compose.onNodeWithTag("lyrics-editor-tap").performClick()
        compose.runOnIdle { position.value = 15_000L }
        compose.onNodeWithTag("lyrics-editor-tap").performClick()
        compose.waitForIdle()
        assertEquals(listOf(12_340L, 15_000L, -1L), editor.draft.lines.map { it.timeMs })
        compose.onNodeWithText("00:12.34").assertIsDisplayed()
        compose.onNodeWithText("+50 ms").performClick()
        compose.waitForIdle()
        assertEquals(15_050L, editor.draft.lines[1].timeMs)
        compose.onNodeWithText("Annuler").performClick()
        compose.waitForIdle()
        assertEquals(15_000L, editor.draft.lines[1].timeMs)
        compose.onNodeWithTag("lyrics-editor-save").assertIsEnabled()
        image("lyrics-editor-timing")
        compose.onNodeWithTag("lyrics-editor-preview-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("lyrics-editor-preview").assertIsDisplayed()
        compose.onNodeWithText("Première ligne chantée").assertExists()
        image("lyrics-editor-preview")
        assertTrue(editor.dirty)
    }

    @Test fun wordModeShowsEachWord() {
        val editor = LyricsEditorState(track, LyricsDraft.fromText("un deux trois\nquatre").withMode(TapMode.Word))
        compose.setContent { KetvoiceTheme(useDark = false) { LyricsEditorScreen(editor, onClose = {}) } }
        compose.waitForIdle()
        compose.runOnIdle { position.value = 1_000L }
        compose.onNodeWithTag("lyrics-editor-tap").performClick()
        compose.runOnIdle { position.value = 1_500L }
        compose.onNodeWithTag("lyrics-editor-tap").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1_000L, 1_500L, -1L), editor.draft.lines[0].words.map { it.startMs })
        compose.onNodeWithText("deux").assertIsDisplayed()
        // « trois » is both a word of the line and the next word on the tap button.
        assertEquals(2, compose.onAllNodesWithText("trois").fetchSemanticsNodes().size)
        image("lyrics-editor-words")
    }

    @Test fun aTitleNotPlayingOffersToPlayIt() {
        state.value = PlayerUiState()
        val editor = LyricsEditorState(track, LyricsDraft.fromText("ligne"))
        compose.setContent { KetvoiceTheme(useDark = true) { LyricsEditorScreen(editor, onClose = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Lire ce titre").assertIsDisplayed()
        compose.onNodeWithTag("lyrics-editor-tap").assertIsNotEnabled()
    }
}
