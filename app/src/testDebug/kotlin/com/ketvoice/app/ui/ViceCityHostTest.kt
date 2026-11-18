package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.ViceCityEdition
import com.ketvoice.app.data.ViceFm
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.ViceCityTheme
import com.ketvoice.app.ui.vicecity.ViceCityWelcome
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Vice City edition drawn on the host in software, not on a device: the « GTA VI » welcome (phone light and dark,
 * tablet, landscape window, large text), Home with its Vice FM card (phone light and dark, tablet landscape), the
 * player, the card alone and unanswered, and Settings. The theme keeps the listener's light or dark look and draws
 * no neon: no floor, no chrome, no flicker, no glow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViceCityHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST")
    private val position get() = Player.positionMs as MutableStateFlow<Long>
    private val paris = ZoneId.of("Europe/Paris")
    private val tracks = (0 until 5).map { Track("qa:v$it", "Ocean Drive $it", "Artiste $it", "Album", durationMs = 200_000) }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        // Static glass: the host draws a live backdrop's panel a second time at the window's origin (see
        // TabletLayoutHostTest); a device does not, and the blur is flat on the host anyway.
        AppSettings.reduceDynamicBlur.value = true
        ViceCityEdition.zone = { paris }
        ViceCityEdition.clock = { LocalDateTime.parse("2026-11-18T10:00").atZone(paris).toInstant().toEpochMilli() }
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.viceCityTheme.value = false
        AppSettings.themeMode.value = 0
        AppSettings.appIconVariant.value = 1
        ViceCityEdition.clock = { System.currentTimeMillis() }
        ViceCityEdition.zone = { ZoneId.systemDefault() }
        com.ketvoice.app.ui.vicecity.ViceCityWelcomeGate.visible.value = false
        player.value = PlayerUiState()
        position.value = 0L
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/vicecity/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Composable
    private fun NoOverscroll(content: @Composable () -> Unit) =
        CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null, content = content)

    private fun quest() = com.ketvoice.app.BuildConfig.IS_QUEST

    // ------------------------------------------------------------------ welcome

    private var enjoyed = 0
    private var kept = 0

    private fun welcome(dark: Boolean = false) {
        compose.setContent { KetvoiceTheme(useDark = dark, viceCity = true) {
            ViceCityWelcome(onEnjoy = { enjoyed++ }, onKeep = { kept++ })
        } }
        compose.waitForIdle()
    }

    private fun assertWelcomeReads() {
        compose.onNodeWithTag("vicecity-title").assertIsDisplayed()
            .assertTextEquals("Profitez de GTA VI en écoutant vos musiques sur Ketvoice !")
        compose.onNodeWithText("Nouveau : le thème Vice City, en édition limitée jusqu'au 10 décembre.").assertExists()
        compose.onNodeWithText("Ketvoice n'est pas affilié à Rockstar Games.").assertExists()
        compose.onNodeWithTag("vicecity-welcome-scene", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("vicecity-enjoy").assertHasClickAction()
        compose.onNodeWithTag("vicecity-keep").assertHasClickAction()
        // TalkBack reads the title, the line, then the two actions, then the small print.
        val order = listOf("vicecity-title", "vicecity-enjoy", "vicecity-keep").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(order.sorted(), order)
    }

    @Test fun welcomeOnALightPhone() {
        welcome()
        assertWelcomeReads()
        val panel = compose.onNodeWithTag("vicecity-panel").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the actions sit at the bottom", panel.bottom > root.bottom - 80 * compose.density.density)
        image("welcome-phone-light")
        compose.onNodeWithTag("vicecity-enjoy").performClick()
        compose.runOnIdle { assertEquals(1, enjoyed); assertEquals(0, kept) }
        compose.onNodeWithTag("vicecity-keep").performClick()
        compose.runOnIdle { assertEquals(1, kept) }
    }

    @Test fun welcomeOnADarkPhone() {
        welcome(dark = true)
        assertWelcomeReads()
        image("welcome-phone-dark")
    }

    @Test fun theBackGestureKeepsTheTheme() {
        welcome()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, kept); assertEquals(0, enjoyed) }
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun welcomeOnATablet() {
        welcome()
        assertWelcomeReads()
        val panel = compose.onNodeWithTag("vicecity-panel").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("at most 520 dp wide", panel.width <= 521 * compose.density.density)
        assertEquals("centred", root.center.x, panel.center.x, 2f)
        image("welcome-tablet")
    }

    /** A landscape window (a tablet held sideways, or Quest's 1280 × 800 window). */
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun welcomeInALandscapeWindow() {
        welcome(dark = true)
        assertWelcomeReads()
        compose.onNodeWithTag("vicecity-enjoy").assertIsDisplayed()
        image("welcome-landscape")
    }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 1.6f)
    fun welcomeWithLargeText() {
        welcome()
        compose.onNodeWithTag("vicecity-enjoy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vicecity-keep").performScrollTo().assertIsDisplayed()
        image("welcome-large-text")
    }

    // ------------------------------------------------------------------ Home

    private fun home(themeMode: Int, name: String) {
        AppSettings.viceCityTheme.value = true
        AppSettings.themeMode.value = themeMode
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
        compose.onNodeWithTag("home-greeting").assertIsDisplayed()
        compose.onNodeWithTag("vicefm-card").assertIsDisplayed()
        compose.onNodeWithTag("vicecity-scene", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("vicefm-play", useUnmergedTree = true).assertHasClickAction()
        // Nothing of the old neon night.
        listOf("vicecity-floor", "vicecity-wordmark", "vicecity-palms").forEach {
            compose.onNodeWithTag(it, useUnmergedTree = true).assertDoesNotExist()
        }
        image(name)
    }

    @Test fun homeOnALightPhone() { if (!quest()) home(themeMode = 1, name = "home-phone-light") }

    @Test fun homeOnADarkPhone() { if (!quest()) home(themeMode = 2, name = "home-phone-dark") }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun homeOnALandscapeTablet() {
        if (quest()) return
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0, durationMs = 200_000)
        position.value = 80_000L
        home(themeMode = 2, name = "home-tablet-landscape")
    }

    @Test fun homeWithoutTheThemeHasNoCard() {
        if (quest()) return
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
        compose.onNodeWithTag("vicefm-card").assertDoesNotExist()
    }

    @Test fun afterTheEditionHomeIsAsBefore() {
        if (quest()) return
        AppSettings.viceCityTheme.value = true
        ViceCityEdition.clock = { LocalDateTime.parse("2026-12-11T09:00").atZone(paris).toInstant().toEpochMilli() }
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
        compose.onNodeWithTag("vicefm-card").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ player, card, settings

    @Test fun thePlayerFollowsTheAccentOnly() {
        if (quest()) return
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0, durationMs = 200_000)
        position.value = 64_000L
        compose.setContent { NoOverscroll { KetvoiceTheme(useDark = true, viceCity = true) {
            com.ketvoice.app.ui.player.FullPlayerScreen(onDismiss = {})
        } } }
        compose.waitForIdle()
        compose.onNodeWithTag("scrub-elapsed", useUnmergedTree = true).assertExists()
        image("player")
    }

    /** Vice FM with no answer from anywhere: a line says so, and the button stays to try again. */
    @Test fun viceFmSaysWhenNothingAnswers() {
        val silent = object : ViceFm.Sources {
            override suspend fun radio(artist: String) = emptyList<Track>()
            override suspend fun top(artist: String) = emptyList<Track>()
            override suspend fun search(query: String) = emptyList<Track>()
        }
        compose.setContent { KetvoiceTheme(useDark = false, viceCity = true) {
            Box(Modifier.fillMaxSize().background(LocalTheme.current.background).padding(top = 40.dp)) {
                com.ketvoice.app.ui.vicecity.ViceFmCard(sources = silent)
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Synthwave et tubes des années 80, pris dans le catalogue.").assertIsDisplayed()
        compose.onNodeWithTag("vicefm-name").assertTextEquals("Vice FM")
        image("vicefm-card-light")
        compose.onNodeWithTag("vicefm-play", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("vicefm-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("vicefm-play", useUnmergedTree = true).assertHasClickAction()
        image("vicefm-card-unanswered")
    }

    @Test fun viceFmOnADarkPage() {
        compose.setContent { KetvoiceTheme(useDark = true, viceCity = true) {
            Box(Modifier.fillMaxSize().background(LocalTheme.current.background).padding(top = 40.dp)) {
                com.ketvoice.app.ui.vicecity.ViceFmCard()
            }
        } }
        compose.waitForIdle()
        image("vicefm-card-dark")
    }

    @Test fun viceFmIsOnlyThereWithTheTheme() {
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.vicecity.ViceFmCard() } }
        compose.waitForIdle()
        compose.onNodeWithTag("vicefm-card").assertDoesNotExist()
    }

    private fun settings(dark: Boolean, name: String) {
        AppSettings.viceCityTheme.value = true
        compose.setContent { NoOverscroll { KetvoiceTheme(useDark = dark, viceCity = true) { com.ketvoice.app.ui.settings.SettingsScreen() } } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vicecity-theme-row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vicecity-preview", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vicecity-tile", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Thème Vice City").assertIsDisplayed()
        compose.onNodeWithText("Édition limitée, jusqu'au 10 décembre").assertIsDisplayed()
        image(name)
    }

    @Test fun settingsOffersTheThemeWithItsIllustration() {
        settings(dark = false, name = "settings-appearance-light")
        compose.onNodeWithTag("vicecity-cover-note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vicecity-theme-row").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(AppSettings.viceCityTheme.value) }
        compose.onNodeWithTag("vicecity-cover-note").assertDoesNotExist()
    }

    @Test fun settingsOnADarkPage() = settings(dark = true, name = "settings-appearance-dark")

    @Test fun afterTheEditionSettingsNoLongerOffersIt() {
        ViceCityEdition.clock = { LocalDateTime.parse("2026-12-11T00:00").atZone(paris).toInstant().toEpochMilli() }
        compose.setContent { NoOverscroll { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vicecity-theme-row").assertDoesNotExist()
        assertFalse(com.ketvoice.app.ui.settings.AppIcons.options.any { it.id == ViceCityEdition.ICON_VARIANT })
    }

    @Test fun theIconPickerOffersTheEditionIcon() {
        var picked = -1
        compose.setContent { KetvoiceTheme(useDark = true) {
            com.ketvoice.app.ui.settings.AppIconPicker(1, onPick = { picked = it }, onClose = {})
        } }
        compose.onNodeWithTag("icon-option-${ViceCityEdition.ICON_VARIANT}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ViceCityEdition.ICON_VARIANT, picked) }
    }

    @Test fun theThemeIsTheListenersWithThePinkAccent() {
        compose.setContent { KetvoiceTheme(useDark = false, viceCity = true) {
            val theme = LocalTheme.current
            assertFalse(theme.isDark); assertTrue(theme.viceCity); assertNull(theme.season)
            assertEquals(ViceCityTheme.accentLight, theme.accent)
        } }
        compose.waitForIdle()
    }
}
