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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.SmartOffline
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.settings.SmartOfflineRows
import com.ketvoice.app.ui.settings.settingsGroup
import com.ketvoice.app.ui.sub.DownloadsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** « Hors ligne intelligent » (7.0): its Settings section and the « Auto » badge of Downloads, rendered on the host, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartOfflineHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        SmartOffline.resetForTest()
    }

    @After fun clear() {
        saved.value = emptyList()
        SmartOffline.resetForTest()
        AppSettings.smartOffline.value = false
        AppSettings.smartOfflineGb.value = 2
        AppUi.closeOverlays()
    }

    private fun item(id: String, title: String, artist: String, bytes: Long, age: Long): Downloader.Item {
        val file = File(compose.activity.cacheDir, "$id.mp3").apply { writeText("host fixture"); setLastModified(1_700_000_000_000L + age) }
        return Downloader.Item(Track(id, title, artist, "Album de $artist", durationMs = 200_000), file, "MP3 · Haute qualité", bytes)
    }

    private fun image(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theSectionShowsItsRowsOnlyWhenOnAndClearsTheAutomaticDownloadsAlone() {
        val auto = item("auto-1", "Titre auto", "Artiste", 9_000_000, 1)
        val mine = item("mine-1", "Mon titre", "Artiste", 7_000_000, 2)
        saved.value = listOf(auto, mine)
        SmartOffline.markAuto("auto-1")
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize().background(Color(0xFFF2F2F7)).padding(top = 24.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().settingsGroup()) { SmartOfflineRows { } }
                }
            }
        } }
        compose.onNodeWithText(AppText.t("Hors ligne intelligent")).assertIsDisplayed()
        compose.onNodeWithText(AppText.t("Espace maximum")).assertDoesNotExist()
        compose.onNodeWithText(AppText.t("Hors ligne intelligent")).performClick()
        assertTrue(AppSettings.smartOffline.value)
        compose.onNodeWithText(AppText.t("Espace maximum")).assertIsDisplayed()
        compose.onNodeWithText(AppText.t("Seulement en charge")).assertIsDisplayed()
        compose.onNodeWithText(AppText.t("Voir ce qui a été téléchargé automatiquement")).assertIsDisplayed()
        compose.onNodeWithText("1 titre", substring = true).assertIsDisplayed()
        // Charging-only is on by default.
        assertTrue(AppSettings.smartOfflineCharging.value)
        image("smart-offline-settings")
        compose.onNodeWithText(AppText.t("Supprimer les téléchargements automatiques")).performClick()
        compose.onNodeWithText(AppText.t("Supprimer les téléchargements automatiques ?")).assertIsDisplayed()
        image("smart-offline-clear-dialog")
        compose.onNodeWithText(AppText.t("Supprimer")).performClick()
        compose.waitUntil(5_000) { "auto-1" !in SmartOffline.autoIds.value }
        assertFalse(auto.file.exists())
        assertTrue("A download made by hand is never removed", mine.file.exists())
    }

    @Test @Config(qualifiers = "w400dp-h1500dp-mdpi")
    fun downloadsBadgeTheAutomaticTitlesAndCanListThemAlone() {
        saved.value = listOf(item("a1", "Auto un", "Zed", 9_000_000, 3), item("m1", "Manuel", "Abel", 8_000_000, 2),
            item("a2", "Auto deux", "Abel", 7_000_000, 1))
        SmartOffline.markAuto("a1"); SmartOffline.markAuto("a2")
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadsScreen() } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("download-auto", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Manuel").assertIsDisplayed()
        image("downloads-auto-badge")
        SmartOffline.showAutoOnly.value = true
        compose.waitForIdle()
        compose.onNodeWithTag("downloads-auto-only").assertIsDisplayed()
        compose.onNodeWithText("Manuel").assertDoesNotExist()
        compose.onAllNodesWithTag("saved-download").assertCountEquals(2)
        image("downloads-auto-only")
        compose.onNodeWithText(AppText.t("Tous")).performClick()
        compose.waitForIdle()
        assertEquals(false, SmartOffline.showAutoOnly.value)
        compose.onNodeWithText("Manuel").assertIsDisplayed()
    }
}
