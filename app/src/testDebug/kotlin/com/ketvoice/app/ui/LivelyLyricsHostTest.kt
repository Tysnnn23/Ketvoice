package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.KaraokeLine
import com.ketvoice.app.ui.player.LyricsPane
import com.ketvoice.app.ui.player.LyricsWords
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

/**
 * 8.3 lyrics on the host: a held note swells and glows while it is held and leaves nothing behind, breaks of 4 s or
 * more show the dots, and the distant lines' blur follows its setting. Software renders under
 * build/host-ui-preview; not a device GPU result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivelyLyricsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val position get() = Player.positionMs as MutableStateFlow<Long>
    @Suppress("UNCHECKED_CAST")
    private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.lyricsBlur.value = true
    }
    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.lyricsBlur.value = true
        lyrics.value = LyricsRepo.State()
        position.value = 0L
    }

    private fun image(name: String): IntArray {
        compose.waitForIdle()
        var pixels = IntArray(0)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/lyrics-83/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
        return pixels
    }

    private val held = MusicApi.LyricLine(0, "Oh love", listOf(MusicApi.LyricWord(0, "Oh ", 400), MusicApi.LyricWord(400, "love", 2_400)))

    private fun karaoke() = compose.setContent { KetvoiceTheme(useDark = true) {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) { KaraokeLine(held, Modifier.fillMaxWidth()) }
    } }

    @Test fun aHeldNoteSwellsWhileHeldAndLeavesNoCopyBehind() {
        assertTrue(LyricsWords.holds("love", 2_000))
        position.value = 1_400L
        karaoke()
        val holding = image("held-note-swelling")
        compose.runOnIdle { position.value = 2_400L + LyricsWords.REST_MS + 10L }
        val atRest = image("held-note-at-rest")
        compose.runOnIdle { position.value = 6_000L }
        val later = image("held-note-later")
        assertArrayEquals("No swollen letter or glow copy may survive the hold", atRest, later)
        assertFalse("the letters swell (and glow) while the note is held", holding.contentEquals(atRest))
        // The performance mode keeps the swell with the lower-cost glow, and also comes back to the same rest.
        compose.runOnIdle { AppSettings.highPerformanceMode.value = true; position.value = 1_400L }
        image("held-note-performance")
        compose.runOnIdle { position.value = 7_000L }
        assertArrayEquals(atRest, image("held-note-performance-rest"))
    }

    private fun pane(lines: List<MusicApi.LyricLine>) {
        lyrics.value = LyricsRepo.State(lines = lines, isTimed = true)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1820)).padding(24.dp)) {
                LyricsPane(Track("qa:lively", "Lively", "Artiste", "Album"), true, {}, Modifier.fillMaxSize())
            }
        } }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun sung(start: Long, end: Long, text: String) = MusicApi.LyricLine(start, text, listOf(MusicApi.LyricWord(start, text, end)))

    @Test fun anInstrumentalBreakShowsTheDotsUnderTheLineBeforeIt() {
        // The dots draw on every frame while they are shown: the test drives the clock itself.
        compose.mainClock.autoAdvance = false
        position.value = 1_500L
        pane(listOf(sung(0, 2_000, "Avant la pause"), sung(8_000, 9_000, "Après la pause"), sung(9_200, 10_000, "Et la suite")))
        compose.onAllNodesWithTag("intro-dots", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { position.value = 4_000L }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithTag("intro-dots", useUnmergedTree = true).assertCountEquals(1)
        image("break-dots")
        compose.runOnIdle { position.value = 8_500L }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithTag("intro-dots", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun distantLinesWithTheBlurSettingStillDrawEveryLine() {
        // Robolectric's software renderer does not apply RenderEffect (the radius rules are in LivelyLyricsTest):
        // this only checks that the blurred layers compose and draw every line, on and off.
        position.value = 200L
        pane((0 until 8).map { sung(it * 3_000L, it * 3_000L + 2_000L, "Ligne numéro $it") })
        compose.mainClock.advanceTimeBy(1_000)
        image("lines-blurred")
        compose.onAllNodesWithText("Ligne numéro 4", useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle { AppSettings.lyricsBlur.value = false }
        compose.mainClock.advanceTimeBy(1_000)
        image("lines-sharp")
        compose.onAllNodesWithText("Ligne numéro 4", useUnmergedTree = true).assertCountEquals(1)
    }
}
