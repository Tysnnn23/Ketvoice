package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Seasons
import com.ketvoice.app.data.ViceCityEdition
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.seasons.SeasonLooks
import com.ketvoice.app.ui.seasons.SeasonWelcome
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
 * The seasonal themes (6.2) drawn on the host in software, not on a device: Home with each season on, the welcome
 * card (phone, tablet) and the Settings row. The glass blur is flat on the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeasonsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val paris = ZoneId.of("Europe/Paris")

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.reduceDynamicBlur.value = true
        ViceCityEdition.zone = { paris }
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.viceCityTheme.value = false
        AppSettings.seasonTheme.value = ""
        ViceCityEdition.clock = { System.currentTimeMillis() }
        ViceCityEdition.zone = { ZoneId.systemDefault() }
        com.ketvoice.app.ui.seasons.SeasonWelcomeGate.visible.value = null
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun on(day: String) {
        ViceCityEdition.clock = { LocalDateTime.parse("${day}T10:00").atZone(paris).toInstant().toEpochMilli() }
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/seasons/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Composable
    private fun NoOverscroll(content: @Composable () -> Unit) =
        CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null, content = content)

    private fun home(id: String, day: String) {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return // Quest keeps its own landscape window.
        on(day)
        val season = Seasons.byId(id)!!
        assertEquals(id, Seasons.active()?.id)
        Seasons.choose(season, true)
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
        val tag = if (id == "vicecity") "vicecity-palms" else "season-decor-$id"
        compose.onNodeWithTag(tag).assertExists()
        image("home-$id")
    }

    @Test fun homeViceCity() = home("vicecity", "2026-11-25")
    @Test fun homeNoel() = home("noel", "2026-12-20")
    @Test fun homeNouvelAn() = home("nouvelan", "2026-12-31")
    @Test fun homeSaintValentin() = home("saintvalentin", "2027-02-12")
    @Test fun homePrintemps() = home("printemps", "2027-04-02")
    @Test fun homeEte() = home("ete", "2027-07-14")
    @Test fun homeHalloween() = home("halloween", "2027-10-31")

    @Test fun homeOutsideAnySeasonHasNoDecor() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        on("2027-05-10")
        AppSettings.seasonTheme.value = "noel"
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
        compose.onNodeWithTag("season-decor-noel").assertDoesNotExist()
    }

    private var tried = 0
    private var later = 0

    private fun card(id: String) {
        val season = Seasons.byId(id)!!
        val look = SeasonLooks.of(id)!!
        compose.setContent { KetvoiceTheme(useDark = false) {
            androidx.compose.foundation.layout.Box {
                com.ketvoice.app.ui.home.HomeScreen()
                KetvoiceTheme(season = look) { SeasonWelcome(season, look, onTry = { tried++ }, onLater = { later++ }) }
            }
        } }
        compose.waitForIdle()
    }

    @Test fun theWelcomeCardOnAPhone() {
        on("2026-12-11")
        card("noel")
        compose.onNodeWithTag("season-headline").assertIsDisplayed().assertTextEquals("Le thème Noël est arrivé")
        compose.onNodeWithText("Édition limitée, jusqu'au 31 décembre").assertIsDisplayed()
        image("welcome-noel-phone")
        compose.onNodeWithTag("season-try").performClick()
        compose.onNodeWithTag("season-later").performClick()
        compose.runOnIdle { assertEquals(1, tried); assertEquals(1, later) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(2, later) }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun theWelcomeCardInALandscapeWindow() {
        on("2027-10-20")
        card("halloween")
        val panel = compose.onNodeWithTag("season-card").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("at most 440 dp wide", panel.width <= 441 * compose.density.density)
        assertEquals("centred", root.center.x, panel.center.x, 2f)
        image("welcome-halloween-landscape")
    }

    @Test fun settingsOffersTheActiveSeasonOnly() {
        on("2027-02-10")
        compose.setContent { NoOverscroll { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vicecity-theme-row").assertDoesNotExist()
        compose.onNodeWithTag("season-theme-row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Thème Saint-Valentin").assertIsDisplayed()
        image("settings-saintvalentin")
        compose.onNodeWithTag("season-theme-row").performClick()
        compose.runOnIdle { assertEquals("saintvalentin", AppSettings.seasonTheme.value) }
        compose.onNodeWithTag("season-theme-row").performClick()
        compose.runOnIdle { assertEquals("", AppSettings.seasonTheme.value) }
    }
}
