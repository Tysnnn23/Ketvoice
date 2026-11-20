package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ketvoice.app.cast.CastState
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.CastDevicesList
import com.ketvoice.app.ui.player.LyricsPane
import com.ketvoice.app.ui.player.LyricsSourceSheet
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** 7.5: the Cast devices in « Sortie audio », and lyrics with their romanization, drawn on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Cast75HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>
    @Suppress("UNCHECKED_CAST")
    private val position get() = Player.positionMs as MutableStateFlow<Long>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
    }
    @After fun reset() {
        AppSettings.lyricsRomanization.value = false
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

    @Test fun castDevicesSearchingThenPlaying() {
        val devices = listOf(CastState.Device("a", "Salon", "Chromecast"), CastState.Device("b", "Cuisine", "Nest Audio"))
        var stopped = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF15131A)).padding(10.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1C1E)).padding(vertical = 12.dp)) {
                    CastDevicesList(emptyList(), null, null, searchedLong = false, onSelect = {}, onStop = {})
                    CastDevicesList(emptyList(), null, null, searchedLong = true, onSelect = {}, onStop = {})
                    CastDevicesList(devices, connecting = "b", connected = null, searchedLong = true, onSelect = {}, onStop = {})
                    CastDevicesList(devices, connecting = null, connected = "Salon", searchedLong = true, onSelect = {}, onStop = { stopped = true })
                }
            }
        } }
        compose.onNodeWithText("Aucun appareil Cast trouvé sur ce Wi-Fi").assertIsDisplayed()
        compose.onNodeWithTag("cast-effects-note").assertIsDisplayed()
        image("cast-devices")
        compose.onNodeWithText("Diffusion en cours · toucher pour arrêter").performClick()
        assertTrue(stopped)
    }

    @Test fun lyricsShowTheirRomanizationUnderEachLine() {
        AppSettings.lyricsRomanization.value = true
        val track = Track("qa:roman", "Romanisation", "Artiste", "Album")
        lyrics.value = LyricsRepo.State(lines = listOf(
            MusicApi.LyricLine(0, "さくら さくら"),
            MusicApi.LyricLine(4_000, "사랑해 너를"),
            MusicApi.LyricLine(8_000, "我爱你"),
            MusicApi.LyricLine(12_000, "Я тебя люблю"),
            MusicApi.LyricLine(16_000, "And a line in English")), isTimed = true, trackId = track.id)
        position.value = 500L
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1820)).padding(24.dp)) { LyricsPane(track, true, {}, Modifier.fillMaxSize()) }
        } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("sakura sakura")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("saranghae neoreul").assertIsDisplayed()
        image("lyrics-romanization")
        // A Chinese song (no kana anywhere): pinyin with tone marks.
        compose.runOnIdle { lyrics.value = LyricsRepo.State(lines = listOf(MusicApi.LyricLine(0, "月亮代表我的心"),
            MusicApi.LyricLine(4_000, "我爱你")), isTimed = true, trackId = track.id) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("wǒ ài nǐ")).fetchSemanticsNodes().isNotEmpty() }
        image("lyrics-romanization-zh")
    }

    @Test fun theSwitchIsFirstInTheLyricsSourceMenu() {
        val track = Track("qa:roman2", "Romanisation", "Artiste", "Album")
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1820))) { LyricsSourceSheet(track) {} }
        } }
        compose.onNodeWithText("Romanisation").assertIsDisplayed()
        compose.onNodeWithTag("lyrics-romanization").performClick()
        compose.runOnIdle { assertTrue(AppSettings.lyricsRomanization.value) }
        image("lyrics-source-romanization")
    }
}
