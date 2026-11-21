package com.ketvoice.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.stems.VoiceLevel
import com.ketvoice.desktop.audio.DesktopEqualizer
import com.ketvoice.desktop.audio.FakeAudioEngine
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The voice slider beside the lyrics, the equalizer page and the separation settings, rendered and driven. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class StemsScreenshots {
    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    private fun shoot(name: String, height: Int = 900, content: @Composable () -> Unit) {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = true
        // The scene's own thread, as the window's (work coming back from other threads runs between frames).
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val ui = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scene = ImageComposeScene(1440, height, Density(1f), coroutineContext = ui, content = content)
        try {
            var t = 0L
            repeat(30) { while (true) (queue.poll() ?: break).run(); scene.render(t); t += 50_000_000L; Thread.sleep(5) }
            while (true) (queue.poll() ?: break).run()
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    private fun both(name: String, route: Route, panel: Panel = Panel.NONE, full: Boolean = false, height: Int = 900) {
        for (dark in listOf(true, false)) shoot("$name-${if (dark) "dark" else "light"}", height) {
            CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark,
                    navigator = remember { Navigator(route) }, initialPanel = panel, initialFullPlayer = full)
            }
        }
    }

    @Test fun renders() {
        val level = AppSettings.voiceLevel.value
        AppSettings.voiceLevel.value = 60
        AppSettings.equalizerEnabled.value = true
        AppSettings.equalizerGains.value = listOf(5, 4, 2, 0, -1, -1, 1, 3, 4, 4)
        try {
            both("voice-panel", Route.Home, panel = Panel.LYRICS)
            FullPlayerLyrics.shown.value = true
            both("voice-player", Route.Home, full = true)
            FullPlayerLyrics.shown.value = false
            both("equalizer", Route.Equalizer)
            both("settings-stems", Route.Settings, height = 2000)
        } finally {
            AppSettings.voiceLevel.value = level; AppSettings.equalizerEnabled.value = false
            AppSettings.equalizerGains.value = List(10) { 0 }; FullPlayerLyrics.shown.value = false
        }
        assertTrue(File(out, "equalizer-dark.png").length() > 10_000)
    }

    @Test fun sliderAndBandsDriveTheSound() = runComposeUiTest {
        val engine = FakeAudioEngine()
        DesktopEqualizer.engine = engine
        AppSettings.voiceLevel.value = 100; VoiceLevel.level = 1f
        AppSettings.equalizerEnabled.value = true; AppSettings.equalizerGains.value = List(10) { 0 }
        try {
            // The slider and the page on their own (the lyrics beside the slider never let the test go idle).
            setContent {
                CompositionLocalProvider(com.ketvoice.desktop.ui.theme.LocalKet provides com.ketvoice.desktop.ui.theme.KetColors.of(true)) {
                    androidx.compose.foundation.layout.Row {
                        DeskVoiceSlider(Fixtures.albumTracks(0)[0], onDark = true)
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { EqualizerPage() }
                    }
                }
            }
            // A click on the microphone takes the voice out; another brings it back.
            onNodeWithTag("voice-slider").performMouseInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height * .85f)) }
            waitForIdle()
            assertEquals(0f, VoiceLevel.level); assertEquals(0, AppSettings.voiceLevel.value)
            // No separation model yet: the first touch offers to download it.
            onNodeWithTag("confirm-dialog").assertExists()
            onNodeWithTag("confirm-cancel").performClick()
            onNodeWithTag("voice-slider").performMouseInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height * .85f)) }
            waitForIdle()
            assertEquals(1f, VoiceLevel.level)
            onNodeWithTag("confirm-cancel").performClick()
            // A preset, then the top of the 1 kHz band: the engine plays each setting.
            onNodeWithTag("eq-preset:Graves").performClick()
            assertEquals(listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0), AppSettings.equalizerGains.value)
            assertEquals(6f, engine.equalizer!![0])
            onNodeWithTag("eq-band:1000").performMouseInput { click(androidx.compose.ui.geometry.Offset(width / 2f, 1f)) }
            // A single click waits out the double click (which sets the band back to 0).
            mainClock.advanceTimeBy(600); waitForIdle()
            assertEquals(12, AppSettings.equalizerGains.value[5])
            assertEquals(12f, engine.equalizer!![5])
            onNodeWithTag("toggle:equalizer").performClick()
            assertEquals(null, engine.equalizer)
        } finally {
            DesktopEqualizer.engine = null; VoiceLevel.level = 1f; AppSettings.voiceLevel.value = 100
            AppSettings.equalizerEnabled.value = false; AppSettings.equalizerGains.value = List(10) { 0 }
        }
    }
}
