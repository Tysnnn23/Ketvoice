package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.GlassLook
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.chrome.FloatingChrome
import com.ketvoice.app.ui.common.ContextMenuHost
import com.ketvoice.app.ui.common.ContextMenus
import com.ketvoice.app.ui.common.KetMenuButton
import com.ketvoice.app.ui.common.KetMenuItem
import com.ketvoice.app.ui.common.KetIcons
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
 * The Cristal look (8.4) rendered on the host (software Skia, not a device GPU): the tab bar's two layouts at their
 * sizes, the lifted selection while it travels, the « … » menu and a title's held menu, and Liquid 27 left as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CristalChromeRenderHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.glassLook.value = GlassLook.CRISTAL
        AppSettings.liquidGlass.value = true
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        AppUi.selectTab(AppUi.HOME)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState(
            queue = listOf(Track("qa:cristal", "Titre Cristal", "Artiste QA", "Album QA")), index = 0)
    }

    @After fun reset() {
        AppSettings.glassLook.value = GlassLook.LIQUID_27
        AppSettings.reduceAnimations.value = false
        ContextMenus.request.value = null
        AppUi.closeOverlays()
        AppUi.selectTab(AppUi.HOME)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState()
    }

    private fun page(dark: Boolean, collapsed: Boolean) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            val ground = if (dark) Color(0xFF101014) else Color(0xFFF2F2F7)
            val backdrop = rememberGlassBackdrop(ground)
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().background(ground)) {
                        repeat(9) { i ->
                            Box(Modifier.fillMaxWidth().height(96.dp).padding(8.dp).background(Brush.horizontalGradient(
                                listOf(Color(0xFFFF4060 - i * 0x101000), Color(0xFF3080FF + i * 0x1010)))))
                        }
                    }
                }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    Box(Modifier.align(Alignment.BottomCenter)) { FloatingChrome(collapsed, {}) }
                }
            }
        } }
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/cristal/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun theExpandedBarHasTheAccessoryOverTheCapsuleAtItsSizes() {
        page(dark = true, collapsed = false)
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithTag("cristal-chrome").assertExists()
        val tabs = bounds("cristal-tabs")
        val accessory = bounds("cristal-now-playing")
        assertEquals(19f, tabs.left, 1f); assertEquals(400f - 38f, tabs.width, 1f); assertEquals(62f, tabs.height, 1f)
        assertEquals(48f, accessory.height, 1f); assertEquals(tabs.width, accessory.width, 1f)
        assertEquals("8 dp between them", 8f, tabs.top - accessory.bottom, 1f)
        for (label in listOf("Accueil", "Explorer", "Bibliothèque", "Rechercher")) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("Titre Cristal").assertIsDisplayed()
        compose.onNodeWithTag("cristal-flat-pill", useUnmergedTree = true).assertExists()
        capture("bar-expanded-dark")
    }

    @Test fun aTapLiftsTheSelectionAsALensThatLandsFlat() {
        page(dark = false, collapsed = false)
        compose.mainClock.advanceTimeBy(1200)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Bibliothèque").performClick()
        // Frame by frame: the lens is up within a few frames of the tap.
        repeat(6) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        compose.onNodeWithTag("cristal-lens-pill", useUnmergedTree = true).assertExists()
        val lens = bounds("cristal-lens-pill")
        assertTrue("lifted, the pill stands past the bar: ${lens.height}", lens.height > 62f - 8f)
        capture("bar-pill-lifted-light")
        compose.mainClock.advanceTimeBy(2000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(AppUi.LIBRARY, AppUi.tab.intValue) }
        compose.onNodeWithTag("cristal-lens-pill", useUnmergedTree = true).assertDoesNotExist()
        val flat = bounds("cristal-flat-pill")
        val cell = (400f - 38f - 8f) / 4f
        assertEquals("on the third tab", 19f + 4f + cell * 2f, flat.left, 1.5f)
        assertEquals(cell, flat.width, 1.5f)
    }

    @Test fun reducedMotionLandsTheSelectionWithoutALens() {
        AppSettings.reduceAnimations.value = true
        page(dark = false, collapsed = false)
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Explorer").performClick()
        repeat(6) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
        compose.onNodeWithTag("cristal-lens-pill", useUnmergedTree = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun theFoldedRowHasThreeShapesFortyEightDpTall() {
        AppUi.selectTab(AppUi.EXPLORER)
        page(dark = false, collapsed = true)
        compose.mainClock.advanceTimeBy(1500)
        val tabs = bounds("cristal-tabs")
        val accessory = bounds("cristal-now-playing")
        val search = bounds("cristal-search")
        assertEquals(28f, tabs.left, 1f); assertEquals(48f, tabs.width, 1f); assertEquals(48f, tabs.height, 1f)
        assertEquals(28f + 48f + 14f, accessory.left, 1f); assertEquals(48f, accessory.height, 1f)
        assertEquals(400f - 28f - 48f, search.left, 1f)
        assertEquals("one line", tabs.top, search.top, 1f)
        compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed()
        capture("bar-folded-light")
        // The circle unfolds the bar again.
        compose.runOnIdle { AppUi.bottomCollapsed.value = true }
        compose.onNodeWithTag("cristal-tabs").performClick()
        compose.runOnIdle { assertFalse(AppUi.bottomCollapsed.value) }
    }

    @Test fun liquid27IsLeftAsItWas() {
        AppSettings.glassLook.value = GlassLook.LIQUID_27
        page(dark = true, collapsed = false)
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithTag("cristal-chrome").assertDoesNotExist()
        compose.onNodeWithTag("search-bubble").assertExists()
    }

    @Test fun aPageMenuIsFrostGrownFromItsButton() {
        var picked = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            val backdrop = rememberGlassBackdrop(Color(0xFF101014))
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    listOf(Color(0xFF7A2CFF), Color(0xFFFF7A2C))))) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 16.dp)) {
                        KetMenuButton(listOf(listOf(KetMenuItem("Trier par titre", KetIcons.Check, checked = true) {},
                            KetMenuItem("Trier par artiste", null) {}), listOf(KetMenuItem("Partager", KetIcons.Share) { picked = true })),
                            "Options", tag = "page-more")
                    }
                }
            }
        } }
        compose.onNodeWithTag("page-more").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("ket-menu").assertIsDisplayed()
        val panel = bounds("ket-menu")
        assertEquals(256f, panel.width, 1.5f)
        compose.onNodeWithText("Trier par artiste").assertIsDisplayed()
        capture("page-menu-dark")
        compose.onNodeWithText("Partager").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue(picked)
        compose.onNodeWithTag("ket-menu").assertDoesNotExist()
    }

    @Test fun aHeldTitleOpensItsMenuUnderThePlate() {
        val track = Track("qa:cristal-held", "Titre tenu", "Artiste tenu", "Album")
        compose.setContent { KetvoiceTheme(useDark = false) {
            val backdrop = rememberGlassBackdrop(Color(0xFFF2F2F7))
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Color(0xFFF2F2F7))) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) { ContextMenuHost() }
            }
        } }
        compose.runOnIdle { ContextMenus.open(track, androidx.compose.ui.geometry.Rect(0f, 240f, 400f, 308f)) }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("context-menu-preview").assertIsDisplayed()
        compose.onNodeWithTag("context-menu-actions").assertIsDisplayed()
        val plate = bounds("context-menu-preview")
        val menu = bounds("context-menu-actions")
        assertEquals(256f, menu.width, 1.5f)
        assertTrue("under the plate", menu.top >= plate.bottom - 1f)
        compose.onNodeWithText("Lire ensuite").assertIsDisplayed()
        capture("held-menu-light")
        compose.onNodeWithText("Plus").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("context-menu").assertDoesNotExist()
        assertEquals(track, AppUi.trackMenuTrack.value)
        compose.runOnIdle { AppUi.trackMenuTrack.value = null }
    }
}
