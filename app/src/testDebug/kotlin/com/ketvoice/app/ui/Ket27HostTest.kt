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

/** Ket 27, Ketvoice's only interface since 6.0.0, rendered in software on the host: not a phone. */
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
    @After fun reset() { AppUi.tab.value = AppUi.HOME }

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

    @Test fun theSettingsAreAnIosListWithoutAnInterfaceSwitch() {
        compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        // There is no other interface to switch to: the root has its large title from the start.
        compose.onNodeWithTag("ket27-large-title").assertIsDisplayed()
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.waitForIdle()
        // No switch, no section and no word of the former mode, nor any choice of lyrics style.
        for (gone in listOf("Ket 27", "Alpha", "Style des paroles", "Cascade")) {
            assertEquals(gone, 0, compose.onAllNodes(hasText(gone, substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
        }
        // Headers in sentence case, the uniform bar with the page's title.
        assertEquals("the bar title and the section header", 2, compose.onAllNodesWithText("Apparence").fetchSemanticsNodes().size)
        compose.onNodeWithTag("ket27-settings-bar").assertIsDisplayed()
        image("ket27-appearance-light") { bitmap ->
            // The grouped background of iOS (#F2F2F7) at the edge of the page, under the bar.
            val edge = bitmap.getPixel(4, bitmap.height / 2)
            assertEquals(0xF2, android.graphics.Color.red(edge)); assertEquals(0xF7, android.graphics.Color.blue(edge))
        }
    }

    @Test fun noPageOfTheSettingsOffersTheFormerInterfaceOrALyricsStyle() {
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        val gone = listOf("Ket 27", "(Alpha)", "Style des paroles", "Cascade", "Signature Ketvoice", "Menus unifiés",
            "Dépoli", "Opaque")
        fun assertNothingGone(page: String) = gone.forEach { word ->
            assertEquals("$word on $page", 0, compose.onAllNodes(hasText(word, substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
        }
        assertNothingGone("the root")
        for (category in listOf("Appearance", "Playback", "Lyrics", "Canvas", "Sources", "Downloads", "LocalMusic", "Storage", "About")) {
            compose.onNodeWithTag("settings-category-$category").performScrollTo().performClick()
            compose.waitForIdle()
            assertNothingGone(category)
            if (category == "Appearance") {
                // The design choices and the glass preview: only what still changes something.
                compose.onNodeWithText("DESIGN KETVOICE").performScrollTo().performClick()
                compose.waitForIdle()
                assertNothingGone("Design")
                compose.onNodeWithText("Typographie harmonisée").assertExists()
                compose.onNodeWithContentDescription("Retour").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("Aperçu du verre").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("glass-tint-slider").assertExists()
                assertNothingGone("the glass preview")
                compose.onNodeWithText("Annuler").performClick()
                compose.waitForIdle()
            }
            compose.onNodeWithContentDescription("Retour").performClick()
            compose.waitForIdle()
        }
    }

    @Test fun theRootOfTheSettingsHasALargeTitleAndColouredTiles() {
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
        // The styles of iOS, always: a 34 dp bold large title, a 17 dp body.
        assertEquals(34f, T.hugeTitle.fontSize.value, 0f); assertEquals(17f, T.body.fontSize.value, 0f)
        assertEquals(34f, T.huge.fontSize.value, 0f); assertEquals(22f, T.h2.fontSize.value, 0f)
        assertEquals(26f, Ket27.Metrics.groupRadius.value, 0f); assertEquals(62f, Ket27.Metrics.tabBar.value, 0f)
        // The regular glass of iOS 27: a surface at 70 %, from 55 % (clearest) to 85 % (most tinted), with its shadow.
        assertEquals(.70f, com.ketvoice.app.glass.Ket27Glass.surface(0f), 1e-4f); assertEquals(.70f, com.ketvoice.app.glass.Ket27Glass.surface(.5f), 1e-4f)
        assertEquals(.55f, com.ketvoice.app.glass.Ket27Glass.surface(.001f), .01f); assertEquals(.85f, com.ketvoice.app.glass.Ket27Glass.surface(1f), 1e-4f)
        val glass = com.ketvoice.app.glass.Ket27Glass.regular(com.ketvoice.app.glass.GlassParams.pill(dark = true), 0f)
        assertTrue(glass.shadow); assertFalse(glass.innerShadow); assertEquals(.45f, glass.shadowAlpha, 0f); assertEquals(8f, glass.elevationDp, 0f)
        assertEquals(.25f, com.ketvoice.app.glass.Ket27Glass.regular(com.ketvoice.app.glass.GlassParams.pill(dark = false), 0f).shadowAlpha, 0f)
    }

    @Test fun theLiquidGlassAppIconIsDrawn() {
        val context = compose.activity
        val icon = context.getDrawable(com.ketvoice.app.R.mipmap.ic_launcher_glass)!!
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432); icon.draw(Canvas(bitmap))
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/app-icon-liquid-glass.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The owner's glass K on its black ground: dark corners, light glass in the middle of the stem.
        val corner = bitmap.getPixel(20, 20)
        val lightOnStem = (80 until 200).count { android.graphics.Color.green(bitmap.getPixel(it, 216)) > 120 }
        assertTrue("black ground", android.graphics.Color.red(corner) < 20 && android.graphics.Color.green(corner) < 20 && android.graphics.Color.blue(corner) < 20)
        assertTrue("the glass catches light across the stem", lightOnStem > 20)
    }
}
