package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.LyricsPane
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
 * Since 6.0.0 Cascade is the only way lyrics move, wherever they are shown with motion (player,
 * full-screen lyrics, Quest's pane all draw [LyricsPane]): the sung line stands forward, the lines
 * still to come step back, and with reduced motion they keep their size and place. On the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CascadeLyricsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>
    @Suppress("UNCHECKED_CAST")
    private val position get() = Player.positionMs as MutableStateFlow<Long>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
    }
    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        lyrics.value = LyricsRepo.State()
        position.value = 0L
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

    // The line's own text, inside the layer that scales it (the line's clickable sits outside that layer).
    private fun width(text: String): Float = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width

    @Test fun theSungLineStandsForwardAndTheOthersStepBackUnlessMotionIsReduced() {
        val track = Track("qa:cascade", "Cascade", "Artiste", "Album")
        lyrics.value = LyricsRepo.State(lines = listOf(
            MusicApi.LyricLine(0, "La ligne chantée"),
            MusicApi.LyricLine(4_000, "La ligne qui suit"),
            MusicApi.LyricLine(8_000, "Et celle d'après")), isTimed = true)
        position.value = 500L
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1820)).padding(24.dp)) {
                LyricsPane(track, true, {}, Modifier.fillMaxSize())
            }
        } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("La ligne chantée").assertIsDisplayed()
        val sungFull = width("La ligne chantée")
        val stepped = width("La ligne qui suit")
        image("lyrics-cascade")
        // Reduced motion: no line changes size, only colour tells the sung line apart.
        compose.runOnIdle { AppSettings.reduceAnimations.value = true }
        compose.waitForIdle()
        val calm = width("La ligne qui suit")
        assertEquals("a line still to come steps back to Cascade's scale", .955f, stepped / calm, .01f)
        image("lyrics-cascade-reduced")
        // When its time comes, the next line stands forward at full size.
        compose.runOnIdle { AppSettings.reduceAnimations.value = false; position.value = 4_500L }
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(calm, width("La ligne qui suit"), 1f)
        assertEquals("the line just sung steps back too", .955f, width("La ligne chantée") / sungFull, .01f)
    }
}
