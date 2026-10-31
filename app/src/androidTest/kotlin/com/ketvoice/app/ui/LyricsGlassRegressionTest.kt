package com.ketvoice.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.*
import com.ketvoice.app.ui.sub.AlbumPage
import com.ketvoice.app.ui.sub.ArtistHeader
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real-device rendering with synthetic lyrics/artwork, in the separate .qa app. */
class LyricsGlassRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> set(flow: kotlinx.coroutines.flow.StateFlow<T>, value: T) { (flow as MutableStateFlow<T>).value = value }
    private val words = MusicApi.LyricLine(0, "Stay (oh yeah) with me", listOf(
        MusicApi.LyricWord(0, "Stay ", 1800), MusicApi.LyricWord(900, "(oh ", 1400),
        MusicApi.LyricWord(1400, "yeah) ", 1800), MusicApi.LyricWord(2000, "with ", 2300),
        MusicApi.LyricWord(2300, "me", 3400)))
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.getExternalFilesDir(null), "qa-lyrics-glass").apply { mkdirs() }
        File(root, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.liquidGlass.value = true
        set(Player.positionMs, 0L)
        set(Player.state, PlayerUiState())
        AppUi.closeOverlays()
    }
    @Before fun prepare() = reset()
    @Test fun activeAndInactiveLyricsNeverRepeatTheBacksInTheLead() {
        var active by mutableStateOf(true)
        set(Player.positionMs, 1200L)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF282330)).padding(28.dp)) {
                LyricVoices(words, active, timed = true)
            }
        } }
        compose.onNodeWithText("Stay with me").assertIsDisplayed()
        compose.onNodeWithText("Stay (oh yeah) with me").assertDoesNotExist()
        compose.onNodeWithText("oh yeah").assertIsDisplayed()
        screenshot("lyrics-active-backs")
        compose.runOnIdle { active = false }
        compose.onNodeWithText("Stay with me").assertIsDisplayed()
        compose.onNodeWithText("Stay (oh yeah) with me").assertDoesNotExist()
        screenshot("lyrics-inactive-backs")
    }
    @Test fun heldWordRemainsVisibleAcrossTheEntireNativeWindowAndAfterIt() {
        set(Player.positionMs, 500L)
        val line = MusicApi.LyricLine(0,"Long held word",listOf(
            MusicApi.LyricWord(0,"Long ",1800),MusicApi.LyricWord(2000,"held ",2400),MusicApi.LyricWord(2400,"word",3400)))
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF29252F)).padding(28.dp)) {
                KaraokeLine(line, Modifier.fillMaxWidth().testTag("karaoke"))
            }
        } }
        for ((label,position) in listOf("start" to 500L,"sustain" to 1100L,"tail" to 1780L,"after" to 1900L)) {
            compose.runOnIdle { set(Player.positionMs,position) }
            compose.onNodeWithText("Long held word").assertIsDisplayed()
            val pixels = compose.onNodeWithTag("karaoke").captureToImage().toPixelMap()
            var brightGlyphPixels = 0
            for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width step 2) {
                val pixel = pixels[x, y]
                if (pixel.red > .8f && pixel.green > .8f && pixel.blue > .8f) brightGlyphPixels++
            }
            assertTrue("Sung glyphs must actually be painted at $label", brightGlyphPixels > 20)
            screenshot("glow-$label")
        }
    }
    @Test fun reducedMotionKeepsReadableKaraokeAndSingleBacks() {
        AppSettings.reduceAnimations.value = true
        set(Player.positionMs,1200L)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF25252D)).padding(28.dp)) { LyricVoices(words,true,true) }
        } }
        compose.onNodeWithText("Stay with me").assertIsDisplayed()
        compose.onAllNodesWithText("oh yeah").assertCountEquals(1)
        screenshot("lyrics-reduced-motion")
    }
    @Test fun glassShaderRendersLightDarkAndStaticWithoutDestroyingTheContents() {
        var dark by mutableStateOf(false)
        var dynamic by mutableStateOf(true)
        compose.setContent { KetvoiceTheme(useDark = dark) {
            val backdrop = rememberGlassBackdrop(if(dark) Color(0xFF14141C) else Color(0xFFE9EDF5))
            CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                Box(Modifier.fillMaxSize()) {
                    BackdropCapture(backdrop,Modifier.fillMaxSize()) {
                        Column { repeat(8) { i -> Box(Modifier.fillMaxWidth().height(90.dp)
                            .background(if(i%2==0) Color(0xFF45669B) else Color(0xFFAC6480))) } }
                    }
                    GlassPanel(Modifier.padding(24.dp).fillMaxWidth().height(64.dp),RoundedCornerShape(32.dp),
                        GlassParams.pill(dark=dark),enabled=dynamic) {
                        com.ketvoice.app.ui.common.Text("Glass remains readable",localize=false,
                            color=if(dark)Color.White else Color.Black,modifier=Modifier.padding(20.dp))
                    }
                }
            }
        } }
        compose.onNodeWithText("Glass remains readable").assertIsDisplayed();screenshot("glass-light")
        compose.runOnIdle { dark=true };screenshot("glass-dark")
        compose.runOnIdle { dynamic=false };screenshot("glass-static")
    }
    @Test fun albumHeroScrollsAwayAndTrackActionsRemainAccessible() {
        val tracks=(1..12).map { Track("qa:$it","Album track $it","QA artist","QA album",durationMs=180000) }
        val page=CatalogPage("A deliberately long album title for narrow screens","QA artist",null,tracks,"2026")
        compose.setContent { KetvoiceTheme(useDark=false) {
            AlbumPage(CatalogRequest("album","qa","QA album"),page,tracks,false,{})
        } }
        compose.onNodeWithText(page.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Partager").assertIsDisplayed()
        screenshot("album-hero")
        compose.onNodeWithTag("album-list").performScrollToNode(hasText("Album track 10"))
        compose.onNodeWithText("Album track 10").assertIsDisplayed()
        screenshot("album-scrolled")
    }
    @Test fun glassSliderRespondsToTapsDragsAndAccessibilityProgress() {
        var value by mutableFloatStateOf(.2f)
        var finished=0
        compose.setContent { KetvoiceTheme(useDark=false) {
            Box(Modifier.fillMaxSize().padding(30.dp)) {
                com.ketvoice.app.ui.common.GlassSlider(value,{value=it},Modifier.testTag("qa-slider"),
                    onValueChangeFinished={finished++})
            }
        } }
        compose.onNodeWithTag("qa-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.75f) }
        compose.runOnIdle { assertEquals(.75f,value,.001f);assertEquals(1,finished) }
        compose.onNodeWithTag("qa-slider").performTouchInput { click(centerLeft + androidx.compose.ui.geometry.Offset(width*.3f,0f)) }
        compose.runOnIdle { assertTrue(value in .2f.. .4f) }
        compose.onNodeWithTag("qa-slider").performTouchInput { swipe(start=centerLeft+androidx.compose.ui.geometry.Offset(width*.3f,0f),end=centerRight-androidx.compose.ui.geometry.Offset(width*.1f,0f)) }
        compose.runOnIdle { assertTrue(value>.65f);assertTrue(finished>=3) }
    }
    @Test fun disabledGlassSliderNeverChangesTheValue() {
        var changes = 0
        var finished = 0
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize().padding(30.dp)) {
                com.ketvoice.app.ui.common.GlassSlider(.4f, { changes++ }, Modifier.testTag("disabled-slider"),
                    valueRange = 0f..10f, enabled = false, onValueChangeFinished = { finished++ })
            }
        } }
        val slider = compose.onNodeWithTag("disabled-slider")
        slider.assertIsNotEnabled()
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { assertFalse(it(8f)) }
        slider.performTouchInput { click(centerRight); swipe(centerLeft, centerRight) }
        compose.runOnIdle { assertEquals(0, changes); assertEquals(0, finished) }
    }
    @Test fun panoramicArtistPhotoStaysVisibleAndPlaybackActionsRemainAccessible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "qa-panoramic-artist.png")
        val bitmap = Bitmap.createBitmap(1200, 500, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(35, 65, 115))
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(240, 70, 120) }
            drawRect(420f, 80f, 780f, 420f, paint)
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val track = Track("qa:artist", "Artist track", "QA panoramic artist", "QA album")
        val page = ArtistPage(MusicArtist("qa:artist", "QA panoramic artist", photo.absolutePath),
            "", listOf(track), emptyList(), emptyList(), "123 auditeurs/mois", 2.4f)
        AppUi.openArtist(page.artist.id, page.artist.name)
        compose.setContent { KetvoiceTheme(useDark = false) {
            // Both modes use the same single photo; no blurred duplicate or AGSL header.
            Box(Modifier.fillMaxSize().background(Color.White)) {
                key(AppSettings.reduceDynamicBlur.value) { ArtistHeader(page) }
            }
        } }
        val image = compose.onNodeWithContentDescription(page.artist.name)
        compose.waitUntil(timeoutMillis = 5_000) {
            val pixels = image.captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            center.red > .8f && center.green < .4f && center.blue < .6f
        }
        compose.onNodeWithText(page.listeners).assertIsDisplayed()
        screenshot("artist-panoramic")
        compose.runOnIdle { AppSettings.reduceDynamicBlur.value = true }
        image.assertIsDisplayed()
        screenshot("artist-panoramic-reduced-blur")
        compose.onNodeWithText("Lecture").assertIsDisplayed()
        compose.onNodeWithText("Aléatoire").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(AppUi.ARTIST, AppUi.overlay.intValue)
            assertEquals(page.artist.name, AppUi.artistRequest.value?.name)
        }
    }
}
