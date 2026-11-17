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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.CoverAmbience
import com.ketvoice.app.ui.player.KaraokeLine
import com.ketvoice.app.ui.player.LyricsMesh
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
 * Glowing lyrics (6.0.0, after Apple Music) on the host: the cover's mesh behind the lyrics page and the
 * glow of a held note only (6.1: no halo, no blur on the words).
 * Software renders under build/host-ui-preview; not a device GPU result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h420dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlowingLyricsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val position get() = Player.positionMs as MutableStateFlow<Long>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.meshBackground.value = true
    }
    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.highPerformanceMode.value = false
        position.value = 0L
    }

    private fun image(name: String): Bitmap {
        lateinit var out: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/lyrics-glow/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out = bitmap
        }
        return out
    }

    private fun light(image: Bitmap): Long {
        var sum = 0L
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            val c = image.getPixel(x, y)
            sum += android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)
        }
        return sum
    }

    private val line = MusicApi.LyricLine(0, "Lumière du soir", listOf(
        MusicApi.LyricWord(0, "Lumière ", 600), MusicApi.LyricWord(600, "du ", 900), MusicApi.LyricWord(900, "soir", 2_600)))

    /** Bumped to compose the line afresh (it decides its drawing once per line). */
    private val generation = androidx.compose.runtime.mutableIntStateOf(0)
    private fun karaoke() {
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1622)).padding(28.dp)) {
                androidx.compose.runtime.key(generation.intValue) { KaraokeLine(line, Modifier.fillMaxWidth()) }
            }
        } }
    }

    @Test fun aShortSungWordHasNoHaloNorBlur() {
        // 6.1 (owner: « supprime tout effet de blur sur les paroles sauf quand une note est maintenue »):
        // the words of « Lumière du » are short, so the line draws the same with or without the effects.
        position.value = 700L
        karaoke()
        compose.mainClock.advanceTimeBy(600)
        val normal = light(image("short-word-sung"))
        compose.runOnIdle { AppSettings.highPerformanceMode.value = true }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(normal.toDouble(), light(image("short-word-performance")).toDouble(), normal * .002)
    }

    @Test fun reducedMotionDrawsThePlainWords() {
        AppSettings.reduceAnimations.value = true
        position.value = 1_200L
        karaoke()
        compose.mainClock.advanceTimeBy(600)
        val reduced = light(image("halo-reduced-motion"))
        // The karaoke line decides its drawing once per line: a fresh composition with motion back on.
        compose.runOnIdle { AppSettings.reduceAnimations.value = false; generation.intValue++ }
        compose.mainClock.advanceTimeBy(600)
        assertTrue(light(image("halo-motion")) > reduced)
    }

    @Test fun onlyAHeldNoteGlows() {
        assertEquals("a short word never glows", 0f, LyricsWords.glow(.5f, 600), 0f)
        assertEquals(0f, LyricsWords.glow(0f, 2_000), 0f)
        assertEquals("gone once the note is over", 0f, LyricsWords.glow(1f, 2_000), 0f)
        assertTrue("a held note glows in its middle", LyricsWords.glow(.5f, 2_000) > .5f)
        assertTrue(LyricsWords.glow(.5f, 2_000) > LyricsWords.glow(.1f, 2_000))
    }

    private fun mesh(visible: Boolean) {
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LyricsMesh(visible, null, CoverAmbience(Color(0xFF5A1E3A), accent = Color(0xFFF2A33A)), playing = false)
            }
        } }
    }

    @Test fun theLyricsPageGlowsInTheCoversColours() {
        mesh(visible = true)
        compose.mainClock.advanceTimeBy(1_500)
        val image = image("lyrics-mesh")
        val a = image.getPixel(40, 40); val b = image.getPixel(360, 380); val centre = image.getPixel(200, 210)
        assertNotEquals("different regions lean to different colours", a, b)
        assertTrue("the cover's colour, not black", android.graphics.Color.red(centre) + android.graphics.Color.green(centre) +
            android.graphics.Color.blue(centre) > 30)
        // The performance mode keeps the plain background; so does the artwork page.
        compose.runOnIdle { AppSettings.highPerformanceMode.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(android.graphics.Color.BLACK, image("lyrics-mesh-performance").getPixel(200, 210))
    }

    @Test fun theMeshLeavesWithTheLyricsPage() {
        mesh(visible = false)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithTag("lyrics-mesh").assertCountEquals(0)
    }
}
