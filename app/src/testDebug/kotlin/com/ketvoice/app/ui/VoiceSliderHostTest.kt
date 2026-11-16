package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.stems.VoiceLevel
import com.ketvoice.app.ui.player.VoiceSlider
import com.ketvoice.app.ui.player.VoiceSliderLogic
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** The voice slider of the lyrics screen, rendered in software on the host (not a phone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceSliderHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun reset() { AppSettings.voiceLevel.value = 100; VoiceLevel.level = 1f }

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

    @Test fun dragAndTapSetTheVoiceLevel() {
        AppSettings.voiceLevel.value = 30
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF3A2A25)), contentAlignment = Alignment.Center) { VoiceSlider(null) }
        }
        image("voice-slider-30")
        // A tap on it takes the voice out; another one brings it back where it was.
        compose.onNodeWithTag("voice-slider").performClick()
        compose.runOnIdle { assertEquals(0f, VoiceLevel.level, 1e-3f); assertEquals(0, AppSettings.voiceLevel.value) }
        image("voice-slider-0")
        compose.onNodeWithTag("voice-slider").performClick()
        compose.runOnIdle { assertEquals(.3f, VoiceLevel.level, 1e-3f) }
        // Dragging up raises it.
        compose.onNodeWithTag("voice-slider").performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f) }
        compose.runOnIdle { assertTrue("level ${VoiceLevel.level}", VoiceLevel.level > .9f); assertTrue(AppSettings.voiceLevel.value > 90) }
        image("voice-slider-full")
    }

    @Test fun theLyricsScreenShowsItWithItsControlsOnEveryTitle() {
        val dir = java.nio.file.Files.createTempDirectory("voice-ui").toFile()
        com.ketvoice.app.stems.StemStore.init(dir)
        val separated = com.ketvoice.app.data.Track("qa:sing", "Titre séparé", "Artiste", "Album")
        val plain = com.ketvoice.app.data.Track("qa:plain", "Titre entier", "Artiste", "Album")
        val frames = 8
        com.ketvoice.app.stems.StemStore.saveWhole(separated.id,
            com.ketvoice.app.stems.StemMask(0L, frames, ByteArray(frames * com.ketvoice.app.stems.StemDsp.NET_BINS / 2)))
        var track by androidx.compose.runtime.mutableStateOf(separated)
        var expanded by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            com.ketvoice.app.theme.KetvoiceTheme(useDark = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFF3A2A25))) {
                    com.ketvoice.app.ui.player.LyricsPane(track, expanded, onSource = {}, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp))
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice-slider").fetchSemanticsNodes().isNotEmpty() }
        val slider = compose.onNodeWithTag("voice-slider").fetchSemanticsNode().boundsInRoot
        val width = compose.activity.window.decorView.width
        assertTrue("at the right of the lyrics ($slider)", slider.right > width * .8f)
        image("voice-slider-lyrics")
        // Full-screen lyrics: no slider.
        expanded = true
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice-slider").fetchSemanticsNodes().isEmpty() }
        // A title that was never separated whole: the slider is there too (6.0.0 Beta 3); without the
        // separation model, a touch asks once to download it.
        expanded = false; track = plain
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice-slider").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voice-slider").performClick()
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Régler la voix")).assertIsDisplayed()
        image("voice-slider-ask-model")
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Télécharger")).assertExists()
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Annuler")).performClick()
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Régler la voix")).assertDoesNotExist()
        dir.deleteRecursively()
    }

    @Test fun logic() {
        assertEquals(.5f, VoiceSliderLogic.dragged(1f, 50f, 100f), 1e-4f)
        assertEquals(0f, VoiceSliderLogic.dragged(.1f, 80f, 100f), 1e-4f)
        assertEquals(1f, VoiceSliderLogic.dragged(.9f, -40f, 100f), 1e-4f)
        assertEquals(0f, VoiceSliderLogic.tapped(.6f, .6f), 0f)
        assertEquals(.6f, VoiceSliderLogic.tapped(0f, .6f), 0f)
        assertEquals(1f, VoiceSliderLogic.tapped(0f, 0f), 0f)
        assertEquals(5, VoiceSliderLogic.step(.52f))
    }
}
