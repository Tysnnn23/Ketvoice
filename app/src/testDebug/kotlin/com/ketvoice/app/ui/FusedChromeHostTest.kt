package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.GlassStyles
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.chrome.FloatingChrome
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

/**
 * The fused Liquid 27 chrome on the host: with a real backdrop layer the three
 * controls report to one glass body instead of drawing their own, and they keep
 * their order, their touch targets and their actions. Not a device GPU result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FusedChromeHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.glassStyle.value = GlassStyles.LIQUID_27
        AppSettings.glassTint.value = .5f
        AppSettings.liquidGlass.value = true
        AppSettings.reduceAnimations.value = false
        AppUi.selectTab(AppUi.LIBRARY)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState(
            queue = listOf(Track("qa:fused", "Fused title", "QA artist", "QA album")), index = 0)
    }

    @After fun reset() {
        AppSettings.glassStyle.value = 0
        AppSettings.glassTint.value = 0f
        AppUi.closeOverlays()
        AppUi.selectTab(AppUi.HOME)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState()
    }

    private fun content(collapsed: Boolean) {
        compose.setContent { KetvoiceTheme(useDark = true) {
            val backdrop = rememberGlassBackdrop(Color.Magenta)
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    Box(Modifier.align(Alignment.BottomCenter)) { FloatingChrome(collapsed, {}) }
                }
            }
        } }
    }

    @Test fun collapsedFusedChromeKeepsOrderTargetsAndActions() {
        content(collapsed = true)
        compose.mainClock.advanceTimeBy(1200)
        val home = compose.onNodeWithContentDescription("Accueil").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Lecture").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(home.right <= play.left && play.right <= search.left)
        assertTrue(play.width >= 48f && play.height >= 48f)
        compose.onNodeWithContentDescription("Accueil").performClick()
        compose.runOnIdle { assertEquals(AppUi.HOME, AppUi.tab.intValue) }
        compose.onNodeWithContentDescription("Rechercher").performClick()
        compose.runOnIdle { assertEquals(AppUi.SEARCH, AppUi.overlay.intValue) }
    }

    @Test fun expandedFusedChromeStillSwitchesTabs() {
        content(collapsed = false)
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithContentDescription("Explorer").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(AppUi.EXPLORER, AppUi.tab.intValue) }
        compose.onNodeWithText("Fused title").assertIsDisplayed()
    }
}
