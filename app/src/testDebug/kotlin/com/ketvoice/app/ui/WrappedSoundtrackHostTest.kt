package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.sub.ClipDeck
import com.ketvoice.app.ui.sub.ListenerPlayback
import com.ketvoice.app.ui.sub.RecapSoundtrack
import com.ketvoice.app.ui.sub.WrappedStoryContent
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

/** « Bande-son du récap annuel » (8.4): the story's mute button, drawn on the host with clip decks that only record. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WrappedSoundtrackHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun french() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.wrappedSound.value = true
    }

    @After fun reset() { AppSettings.reduceAnimations.value = false; AppSettings.wrappedSound.value = true }

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

    private val played = mutableListOf<String>()
    private val deck = object : ClipDeck {
        override fun play(track: Track, startMs: Long, volume: Float) { played += track.id }
        override fun volume(value: Float) {}
        override fun stop() { played += "stop" }
        override fun release() { played += "release" }
    }
    private val listener = object : ListenerPlayback {
        override var playing = true
        var resumed = 0
        override fun pause() { playing = false }
        override fun resume() { playing = true; resumed++ }
    }

    @Test fun theStoryPlaysItsClipAndTheMuteButtonGivesTheMusicBack() {
        val song = Track("yt:clip00000001", "Song 1", "Nova", "Sunrise", 200_000)
        val recap = Wrapped.Recap("2026", 1200, 300, 40, listOf(Wrapped.Artist("Nova", 90, null)), listOf(song to 50))
        compose.setContent { KetvoiceTheme(useDark = true) {
            WrappedStoryContent(recap, onClose = {}, autoAdvance = false, soundtrack = { clips, scope ->
                RecapSoundtrack(scope, clips, listener, { deck }, { 0L })
            })
        } }
        compose.waitForIdle()
        assertEquals(listOf(song.id), played)
        assertFalse(listener.playing)
        compose.onNodeWithContentDescription("Couper le son").assertIsDisplayed()
        image("wrapped-soundtrack")
        compose.onNodeWithTag("wrapped-sound").performClick()
        compose.waitForIdle()
        assertFalse(AppSettings.wrappedSound.value)
        assertEquals(1, listener.resumed)
        compose.onNodeWithContentDescription("Activer le son").assertIsDisplayed()
    }
}
