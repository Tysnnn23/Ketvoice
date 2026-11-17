package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.ViceCityEdition
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
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
 * The Vice City edition (6.1) drawn on the host in software, not on a device: the « GTA VI » welcome on a phone,
 * a tablet, a landscape window and with large text, and Home, the player and Settings with the theme on.
 * The glass blur is flat on the host; the layout, the colours and the semantics are what is checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViceCityHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    private val paris = ZoneId.of("Europe/Paris")

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
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

    private var enjoyed = 0
    private var kept = 0

    private fun welcome() {
        // Static glass: the host draws a live backdrop's panel a second time at the window's origin (see
        // TabletLayoutHostTest); a device does not, and the blur is flat on the host anyway.
        AppSettings.reduceDynamicBlur.value = true
        compose.setContent { KetvoiceTheme(viceCity = true) {
            ViceCityWelcome(onEnjoy = { enjoyed++ }, onKeep = { kept++ }, animate = false)
        } }
        compose.waitForIdle()
    }

    private fun assertWelcomeReads() {
        compose.onNodeWithTag("vicecity-title").assertIsDisplayed()
            .assertTextEquals("Profitez de GTA VI en écoutant vos musiques sur Ketvoice !")
        compose.onNodeWithText("Nouveau : le thème Vice City, en édition limitée jusqu'au 10 décembre.").assertExists()
        compose.onNodeWithText("Ketvoice n'est pas affilié à Rockstar Games.").assertExists()
        compose.onNodeWithTag("vicecity-enjoy").assertHasClickAction()
        compose.onNodeWithTag("vicecity-keep").assertHasClickAction()
        // TalkBack reads the title, the line, then the two actions, then the small print.
        val order = listOf("vicecity-title", "vicecity-enjoy", "vicecity-keep").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(order.sorted(), order)
    }

    @Test fun welcomeOnAPhone() {
        welcome()
        assertWelcomeReads()
        val panel = compose.onNodeWithTag("vicecity-panel").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the panel sits at the bottom", panel.bottom > root.bottom - 80 * compose.density.density)
        image("welcome-phone")
        compose.onNodeWithTag("vicecity-enjoy").performClick()
        compose.runOnIdle { assertEquals(1, enjoyed); assertEquals(0, kept) }
        compose.onNodeWithTag("vicecity-keep").performClick()
        compose.runOnIdle { assertEquals(1, kept) }
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
        welcome()
        assertWelcomeReads()
        image("welcome-landscape")
    }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 1.6f)
    fun welcomeWithLargeText() {
        welcome()
        compose.onNodeWithTag("vicecity-enjoy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vicecity-keep").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vicecity-title").performScrollTo()
        image("welcome-large-text")
    }

    @Test fun homeAndTheTabBarWithTheTheme() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return // Quest keeps its own landscape window, rendered elsewhere.
        AppSettings.viceCityTheme.value = true
        AppSettings.reduceDynamicBlur.value = true
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
        compose.onNodeWithTag("vicecity-palms").assertExists()
        image("home")
    }

    @Test fun homeWithoutTheThemeHasNoPalms() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        AppSettings.viceCityTheme.value = false
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
        compose.onNodeWithTag("vicecity-palms").assertDoesNotExist()
    }

    @Test fun thePlayerWithTheTheme() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        val tracks = (0 until 5).map { Track("qa:v$it", "Ocean Drive $it", "Artiste $it", "Album", durationMs = 200_000) }
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0, durationMs = 200_000)
        compose.setContent { NoOverscroll { KetvoiceTheme(viceCity = true) {
            com.ketvoice.app.ui.player.FullPlayerScreen(onDismiss = {})
        } } }
        compose.waitForIdle()
        image("player")
    }

    @Test fun settingsOffersTheThemeWithAPreview() {
        AppSettings.viceCityTheme.value = true
        compose.setContent { NoOverscroll { KetvoiceTheme(viceCity = true) { com.ketvoice.app.ui.settings.SettingsScreen() } } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vicecity-theme-row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vicecity-preview", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Thème Vice City").assertIsDisplayed()
        compose.onNodeWithText("Édition limitée, jusqu'au 10 décembre").assertIsDisplayed()
        image("settings-appearance")
        compose.onNodeWithTag("vicecity-cover-note").performScrollTo().assertIsDisplayed()
        image("settings-cover-colours")
        compose.onNodeWithTag("vicecity-theme-row").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(AppSettings.viceCityTheme.value) }
        compose.onNodeWithTag("vicecity-cover-note").assertDoesNotExist()
    }

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
        val windows = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
        @Suppress("UNCHECKED_CAST")
        val views = ReflectionHelpers.getField<java.util.ArrayList<android.view.View>>(windows, "mViews")
        compose.runOnIdle {
            val view = views.last()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/vicecity/icon-picker.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
