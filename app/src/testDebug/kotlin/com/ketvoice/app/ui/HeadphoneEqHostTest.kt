package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioDeviceInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.AudioOutput
import com.ketvoice.app.player.HeadphoneEq
import com.ketvoice.app.player.HeadphoneProfiles
import com.ketvoice.app.player.OutputKind
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.HeadphoneEqContent
import com.ketvoice.app.ui.player.HeadphoneEqView
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

/** « Égaliseur par casque » (9.0) drawn on the host: the output and its applied correction, the list, links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeadphoneEqHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun french() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.headphoneProfiles.value = ""; AppSettings.headphoneLinks.value = ""
        HeadphoneEq.compare.value = false
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

    @Test fun theLinkedCorrectionIsShownAppliedAndTheListSearched() {
        val sony = HeadphoneEq.add(HeadphoneProfiles.parse("Preamp: -4 dB\nFilter 1: ON LSC Fc 105 Hz Gain 4 dB Q 0.7\nFilter 2: ON PK Fc 3000 Hz Gain -3 dB Q 2", "Sony WH-1000XM4"))
        HeadphoneEq.add(HeadphoneProfiles.parse("Filter 1: ON PK Fc 1000 Hz Gain -2 dB Q 1", "Sennheiser HD 600"))
        val headset = AudioOutput(5, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, OutputKind.Bluetooth, "WH-1000XM4", "aa:bb:cc:dd:ee:ff")
        HeadphoneEq.link(headset, sony)
        val view = HeadphoneEqView(headset, HeadphoneEq.resolve(headset, true, false, HeadphoneEq.profiles(), HeadphoneEq.links()),
            HeadphoneEq.profiles(), HeadphoneEq.links(), enabled = true, comparing = false)
        assertTrue(view.status.applied)
        compose.setContent {
            KetvoiceTheme(useDark = true) {
                Column(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background).verticalScroll(rememberScrollState()).padding(20.dp)) { HeadphoneEqContent(view) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Égaliseur par casque").assertIsDisplayed()
        compose.onNodeWithText("Correction appliquée · Sony WH-1000XM4").assertIsDisplayed()
        compose.onNodeWithTag("headphone-curve").assertIsDisplayed()
        compose.onNodeWithText("Sorties liées").assertExists()
        assertEquals(2, compose.onAllNodesWithTag("headphone-profile").fetchSemanticsNodes().size)
        image("headphone-eq")
        compose.onNodeWithText("Rechercher un casque ou une marque…").performTextInput("senn")
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithTag("headphone-profile").fetchSemanticsNodes().size)
        compose.onNodeWithText("Sennheiser HD 600").assertIsDisplayed()
        // A touch on another profile links it to the headset playing.
        compose.onNodeWithText("Sennheiser HD 600").performClick()
        compose.waitForIdle()
        assertEquals("Sennheiser HD 600", HeadphoneEq.profiles().first { it.id == HeadphoneEq.links().values.single().profileId }.name)
    }

    @Test fun theSpeakerIsToldItIsNotCorrected() {
        val speaker = AudioOutput(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, OutputKind.Phone, "Phone")
        val view = HeadphoneEqView(speaker, HeadphoneEq.resolve(speaker, true, false, emptyList(), emptyMap()), emptyList(), emptyMap(), true, false)
        compose.setContent { KetvoiceTheme(useDark = false) { Column(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background).padding(20.dp)) { HeadphoneEqContent(view) } } }
        compose.waitForIdle()
        compose.onNodeWithText("Cette sortie n'est pas un casque : aucune correction").assertIsDisplayed()
        compose.onNodeWithText("Coller un profil").assertIsDisplayed()
        image("headphone-eq-speaker")
    }
}
