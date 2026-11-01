package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.*
import com.ketvoice.app.ui.player.*
import com.ketvoice.app.ui.sub.ArtistIdentity
import com.ketvoice.app.ui.sub.SignatureInk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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

/** Software host render/clock fixtures, not a phone, video decoder or 120 Hz benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPolishHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.animatedArtwork.value = true
        AppSettings.artistSignatures.value = false
        AppSettings.highRefreshRate.value = false
        AppSettings.reverbAmount.value = 0
        com.ketvoice.app.player.ReverbControl.amount = 0f
        (Downloader.busy as MutableStateFlow<Set<String>>).value = emptySet()
        (Downloader.progress as MutableStateFlow<Map<String, Int>>).value = emptyMap()
        @Suppress("UNCHECKED_CAST")
        (Player.positionMs as MutableStateFlow<Long>).value = 0
    }

    private fun image(name: String, verify: (Bitmap) -> Unit = {}) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            verify(bitmap)
            bitmap.recycle()
        }
    }

    @Test fun darkAppearanceOptionsKeepNativeDarkSurfacesAndReadableLabels() {
        AppSettings.reduceAnimations.value = true
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.onNodeWithText("Logos d'artistes").assertIsDisplayed()
        compose.onNodeWithText("Fluidité 120 Hz").assertIsDisplayed()
        image("settings-appearance-charcoal")
    }

    @Test fun darkTrackMenuRetainsContrastWhenOpenedAboveALightPage() {
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.BottomCenter) {
                TrackMenuSheet(Track("qa:menu", "A readable menu", "QA artist", "QA album"), {})
            }
        } }
        compose.onNodeWithText("A readable menu").assertIsDisplayed()
        compose.onNodeWithText("Lire ensuite").assertIsDisplayed()
        compose.onNodeWithText("Partager").assertIsDisplayed()
        image("track-menu-light-page-fixed") { bitmap ->
            val color = bitmap.getPixel(200, 865)
            assertTrue("Dark explicit sheet must not inherit white card", android.graphics.Color.red(color) < 60)
        }
    }

    @Test fun playerReverbControlsChangeTheActualAudioControlAndCanBeDisabled() {
        AppSettings.reduceAnimations.value = true
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                TrackMenuSheet(Track("qa:reverb", "Reverb track", "QA artist", "Album"), {})
            }
        } }
        compose.onNodeWithText("Reverb").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Réverbération").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(65f) }
        compose.runOnIdle {
            assertEquals(65, AppSettings.reverbAmount.value)
            assertEquals(.65f, com.ketvoice.app.player.ReverbControl.amount, .001f)
        }
        image("player-reverb-controls")
        compose.onNodeWithText("Désactiver").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0f, com.ketvoice.app.player.ReverbControl.amount, 0f) }
    }

    @Test fun artworkProgressRingTracksTheRealDownloaderState() {
        val track = Track("qa:ring", "Progress", "QA artist", "Album")
        (Downloader.busy as MutableStateFlow<Set<String>>).value = setOf(track.id)
        (Downloader.progress as MutableStateFlow<Map<String, Int>>).value = mapOf(track.id to 42)
        compose.setContent { KetvoiceTheme(useDark = true) {
            com.ketvoice.app.ui.common.DownloadCover(track, null, 156.dp)
        } }
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo(.42f, 0f..1f))).assertIsDisplayed()
        image("home-cover-download-ring")
        compose.runOnIdle { (Downloader.busy as MutableStateFlow<Set<String>>).value = emptySet() }
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
    }

    @Test fun controlExitHasIntermediateOpacityAndRemainsMountedUntilItFinishes() {
        var visible by mutableStateOf(true)
        var opacity = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PlayerChrome(visible, onOpacity = { opacity = it }) { Box(Modifier.size(160.dp).background(Color.Cyan).testTag("controls")) }
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("controls").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("controls").assertExists()
        // Host View.draw does not composite RenderNode alpha; PixelCopy also
        // has no hardware window here. Assert the real transition value,
        // not a fictitious device pixel/performance measurement.
        compose.runOnIdle { assertTrue("Intermediate opacity=$opacity", opacity > 0f && opacity < 1f) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("controls").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("controls").assertIsDisplayed()
    }

    @Test fun fullscreenCanvasTapsToggleChromeRatherThanExitAndCloseStillWorks() {
        AppSettings.animatedArtwork.value = false
        AppSettings.reduceAnimations.value = true
        var exits = 0
        val track = Track("qa:focus", "Canvas title", "QA artist", "QA album", durationMs = 180_000)
        compose.setContent { KetvoiceTheme(useDark = true) {
            CanvasFocusPlayer(track, "host-static-fallback.mp4", false, track.durationMs, { exits++ }, {})
        } }
        compose.onNodeWithContentDescription("Quitter le plein écran").assertIsDisplayed()
        compose.onNodeWithTag("canvas-focus-image").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("Quitter le plein écran").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithTag("canvas-focus-image").performTouchInput { click(center) }
        compose.onNodeWithText(track.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Quitter le plein écran").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun backingVocalsAreAbsentBeforeTheirTimeAndAfterTheNextLeadWithStableHeight() {
        var active by mutableStateOf(true)
        val line = MusicApi.LyricLine(0, "Go (yeah) now", listOf(
            MusicApi.LyricWord(0, "Go ", 700), MusicApi.LyricWord(1000, "(yeah) ", 1800), MusicApi.LyricWord(1900, "now", 2800)))
        @Suppress("UNCHECKED_CAST") val position = Player.positionMs as MutableStateFlow<Long>
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color.Black)) { LyricVoices(line, active, true) }
        } }
        val height = compose.onNodeWithTag("backing-vocal", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithText("yeah").assertDoesNotExist()
        compose.runOnIdle { position.value = 1100 }
        compose.onNodeWithText("yeah").assertIsDisplayed()
        image("backing-vocal-active")
        compose.runOnIdle { active = false; position.value = 2000 }
        compose.onNodeWithText("yeah").assertDoesNotExist()
        assertEquals(height, compose.onNodeWithTag("backing-vocal", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height)
        image("backing-vocal-next-line-hidden")
    }

    @Test fun pngLogoDecodesAndItsFallbackNameNeverLeavesABlankHero() {
        val file = File(compose.activity.cacheDir, "qa-artist-logo.png")
        val fixture = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        Canvas(fixture).apply {
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.RED }
            drawRect(20f, 30f, 170f, 80f, paint)
            paint.color = android.graphics.Color.WHITE
            drawRect(210f, 30f, 380f, 80f, paint)
        }
        file.outputStream().use { fixture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fixture.recycle()
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF0B0D10)).padding(top = 100.dp)) {
                ArtistIdentity("QA artist", ArtistSignature(file.toURI().toString(), "png", "https://fanart.tv/", "fanart.tv", monochrome = false))
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Visuel d'artiste · fanart.tv").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(400)
        image("artist-logo-png-fixture") { bitmap ->
            val pixel = bitmap.getPixel(75, 150)
            assertTrue("Brand red must not be replaced by a flat white mask", android.graphics.Color.red(pixel) > 200 && android.graphics.Color.green(pixel) < 60)
        }
        file.delete()
    }

    @Test fun rasterSignatureRemovesWhitePaperWithoutErasingInk() = runBlocking {
        val input = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, android.graphics.Color.WHITE); setPixel(1, 0, android.graphics.Color.BLACK)
        }
        val output = SignatureInk.transform(input, coil.size.Size.ORIGINAL)
        assertEquals(0, android.graphics.Color.alpha(output.getPixel(0, 0)))
        assertEquals(255, android.graphics.Color.alpha(output.getPixel(1, 0)))
        input.recycle(); output.recycle()
        val whiteInk = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, android.graphics.Color.TRANSPARENT); setPixel(1, 0, android.graphics.Color.WHITE)
        }
        val mask = SignatureInk.transform(whiteInk, coil.size.Size.ORIGINAL)
        assertEquals(0, android.graphics.Color.alpha(mask.getPixel(0, 0)))
        assertEquals(255, android.graphics.Color.alpha(mask.getPixel(1, 0)))
        whiteInk.recycle(); mask.recycle()
    }

    @Test fun unsupportedHostDisplayNeverGetsAFake120HzWindowRequestAndObserversDispose() {
        AppSettings.highRefreshRate.value = true
        compose.activity.window.attributes = compose.activity.window.attributes.apply {
            preferredDisplayModeId = 123; preferredRefreshRate = 60f
        }
        var observe by mutableStateOf(true)
        compose.setContent { if (observe) ObserveDisplayRefresh() }
        compose.runOnIdle {
            assertFalse(DisplayRefresh.status.supported)
            assertFalse(DisplayRefresh.status.requested)
            assertEquals(123, compose.activity.window.attributes.preferredDisplayModeId)
            observe = false
        }
        compose.runOnIdle { assertEquals(60f, compose.activity.window.attributes.preferredRefreshRate) }
    }

    @Test fun nativeWindowPreferencesAreActuallyAppliedAndRestored() {
        val window = compose.activity.window
        window.attributes = window.attributes.apply { preferredDisplayModeId = 4; preferredRefreshRate = 60f }
        val lease = RefreshWindowLease(window)
        lease.request(RefreshMode(8, 1080, 2400, 119.88f))
        assertEquals(8, window.attributes.preferredDisplayModeId)
        assertEquals(119.88f, window.attributes.preferredRefreshRate)
        lease.release()
        assertEquals(4, window.attributes.preferredDisplayModeId)
        assertEquals(60f, window.attributes.preferredRefreshRate)
    }

    @Test fun releasingRefreshPreferenceDoesNotOverwriteAnotherWindowsOwner() {
        val window = compose.activity.window
        val lease = RefreshWindowLease(window)
        lease.request(RefreshMode(8, 1080, 2400, 120f))
        window.attributes = window.attributes.apply { preferredDisplayModeId = 9; preferredRefreshRate = 90f }
        lease.release()
        assertEquals(9, window.attributes.preferredDisplayModeId)
        assertEquals(90f, window.attributes.preferredRefreshRate)
    }
}
