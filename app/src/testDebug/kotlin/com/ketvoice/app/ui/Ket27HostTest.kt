package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.T
import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.ui.chrome.FloatingChrome
import com.ketvoice.app.ui.settings.ket27Heading
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

/** Ket 27 (Alpha) rendered in software on the host: not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ket27HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @org.junit.Before fun french() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
    }
    @After fun reset() { AppSettings.ket27.value = false; AppUi.tab.value = AppUi.HOME }

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

    @Test fun theSwitchTurnsTheSettingsIntoAnIosList() {
        compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        // Off: the usual page, without a large title of its own.
        compose.onNodeWithTag("ket27-large-title").assertDoesNotExist()
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.onNodeWithText("KET 27 (ALPHA)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ket 27 (Alpha)").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(AppSettings.ket27.value)
        // On: headers in sentence case, the uniform bar with the page's title.
        compose.onNodeWithText("Ket 27 (alpha)").assertExists()
        assertEquals("the bar title and the section header", 2, compose.onAllNodesWithText("Apparence").fetchSemanticsNodes().size)
        compose.onNodeWithTag("ket27-settings-bar").assertIsDisplayed()
        image("ket27-appearance-light") { bitmap ->
            // The grouped background of iOS (#F2F2F7) at the edge of the page, under the bar.
            val edge = bitmap.getPixel(4, bitmap.height / 2)
            assertEquals(0xF2, android.graphics.Color.red(edge)); assertEquals(0xF7, android.graphics.Color.blue(edge))
        }
    }

    @Test fun theRootOfTheSettingsHasALargeTitleAndColouredTiles() {
        AppSettings.ket27.value = true
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.onNodeWithTag("ket27-large-title").assertIsDisplayed()
        compose.onNodeWithTag("settings-category-Playback").assertIsDisplayed()
        image("ket27-settings-dark") { bitmap ->
            assertEquals("pure black page", 0xFF000000.toInt(), bitmap.getPixel(4, bitmap.height - 40))
        }
        compose.onNodeWithTag("settings-category-Playback").performClick()
        compose.onNodeWithText("Ketmix (bêta)").performScrollTo().assertIsDisplayed()
        image("ket27-playback-dark")
    }

    @Test fun theTabBarTintsTheSelectedTab() {
        AppSettings.ket27.value = true
        compose.setContent {
            KetvoiceTheme(useDark = false) {
                val theme = LocalTheme.current
                Box(Modifier.fillMaxSize().pageBackground(theme)) {
                    Column(Modifier.padding(20.dp).padding(top = 40.dp)) {
                        com.ketvoice.app.ui.common.Text("Bibliothèque", color = theme.onBackground, style = T.hugeTitle)
                        com.ketvoice.app.ui.common.Text("Titres, albums et playlists", color = theme.secondary, style = T.body)
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 21.dp)) { FloatingChrome(false, {}) }
                }
            }
        }
        compose.onNodeWithText("Explorer").assertIsDisplayed()
        image("ket27-tab-bar-light")
    }

    @Test fun headersAreWrittenInSentenceCase() {
        assertEquals("Apparence", ket27Heading("APPARENCE"))
        assertEquals("Ketmix (bêta)", ket27Heading("KETMIX (BÊTA)"))
        assertEquals("Ketvoice API · Canvas", ket27Heading("KETVOICE API · CANVAS"))
        assertEquals("YouTube Music", ket27Heading("YOUTUBE MUSIC"))
        assertEquals("already written that way", "Déjà écrit", ket27Heading("Déjà écrit"))
        // The styles of iOS: a 34 dp bold large title, a 17 dp body.
        AppSettings.ket27.value = true
        assertEquals(34f, T.hugeTitle.fontSize.value, 0f); assertEquals(17f, T.body.fontSize.value, 0f)
        assertEquals(26f, Ket27.Metrics.groupRadius.value, 0f); assertEquals(62f, Ket27.Metrics.tabBar.value, 0f)
        AppSettings.ket27.value = false
        assertEquals(40f, T.hugeTitle.fontSize.value, 0f)
    }
}
