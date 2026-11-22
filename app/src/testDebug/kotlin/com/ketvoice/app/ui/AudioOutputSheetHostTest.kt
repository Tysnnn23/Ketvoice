package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.AudioOutputs
import com.ketvoice.app.player.OutputKind
import com.ketvoice.app.theme.KetvoiceTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** « Sortie audio » (8.4): the phone's real outputs listed, picked, and followed when one connects. Drawn on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioOutputSheetHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun french() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() { AudioOutputs.choose(null); AppSettings.reduceAnimations.value = false }

    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

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

    @Test fun theRealOutputsAreListedAndFollowConnections() {
        val audio = shadowOf(compose.activity.getSystemService(AudioManager::class.java))
        audio.setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.AudioOutputSheet(onClose = {}) } }
        compose.waitForIdle()
        val phone = if (com.ketvoice.app.BuildConfig.IS_QUEST) "Ce casque" else "Ce téléphone"
        compose.onNodeWithText(phone).assertIsDisplayed()
        compose.onNodeWithTag("audio-output-Bluetooth").assertDoesNotExist()
        // Headphones plugged in while the sheet is open: they appear, and play (Android's route).
        compose.runOnIdle { audio.addOutputDevice(device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), true) }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("audio-output-Wired").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Écouteurs filaires").assertIsDisplayed()
        compose.onNodeWithText("Filaire · En lecture ici").assertIsDisplayed()
        // Cast and Air Ket entries, then the volume, stay below.
        compose.onNodeWithText("Le volume règle la lecture de Ketvoice sur cet appareil.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(phone).performScrollTo()
        image("audio-output-picker")
        compose.onNodeWithTag("audio-output-Phone").performClick()
        compose.waitForIdle()
        val chosen = AudioOutputs.chosen.value
        assertNotNull(chosen)
        assertEquals(OutputKind.Phone, AudioOutputs.read(compose.activity).first { it.id == chosen }.kind)
    }
}
