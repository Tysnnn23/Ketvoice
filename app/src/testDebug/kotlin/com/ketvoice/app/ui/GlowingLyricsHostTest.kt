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
 * halo of a word as it is sung, both plain with reduced motion and in the performance mode.
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

    @Test fun aSungWordLightsAHaloThatTheCalmModesLeaveOut() {
        position.value = 2_000L
        karaoke()
        compose.mainClock.advanceTimeBy(600)
        val glowing = light(image("halo-sung"))
        compose.runOnIdle { AppSettings.highPerformanceMode.value = true }
        compose.mainClock.advanceTimeBy(600)
        val performance = light(image("halo-performance-mode"))
        assertTrue("the halo adds light around the sung word ($glowing vs $performance)", glowing > performance * 1.004)
        // Before the line is sung, there is no halo at all: both modes draw the same line.
        compose.runOnIdle { AppSettings.highPerformanceMode.value = false; position.value = 0L }
        compose.mainClock.advanceTimeBy(600)
        val before = light(image("halo-before"))
        compose.runOnIdle { AppSettings.highPerformanceMode.value = true }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(before.toDouble(), light(image("halo-before-performance")).toDouble(), before * .002)
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

    @Test fun haloRisesWithTheWordAndFadesBeforeTheLineStepsBack() {
        assertEquals(0f, LyricsWords.halo(-1, 0, 500), 0f)
        assertEquals(0f, LyricsWords.halo(Long.MIN_VALUE, 0, 500), 0f)
        assertTrue(LyricsWords.halo(20, 0, 500) in 0.01f..0.5f)
        val sung = LyricsWords.halo(250, 0, 500)
        assertEquals(.72f, LyricsWords.halo(250, 0, 300), .01f)
        assertEquals(.72f + .28f * 200f / 900f, sung, .01f)
        assertTrue("a held note shines more", LyricsWords.halo(800, 0, 1_600) > sung)
        assertTrue("it fades after the word", LyricsWords.halo(650, 0, 500) in 0.01f..sung)
        assertEquals("gone before the line steps back", 0f,
            LyricsWords.halo(500 + com.ketvoice.app.ui.player.LyricsPresentation.LEAD_TAIL_MS, 0, 500), 0f)
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
