package com.ketvoice.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.PersonalBackup
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.GlassStyles
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.chrome.ChromeCollapse
import com.ketvoice.app.ui.chrome.FloatingChrome
import com.ketvoice.app.ui.chrome.FloatingSearch
import com.ketvoice.app.ui.motion.CoverZoom
import com.ketvoice.app.ui.motion.CoverZoomLayer
import com.ketvoice.app.ui.motion.LocalNavigationPageKey
import com.ketvoice.app.ui.motion.rememberZoomSource
import com.ketvoice.app.ui.motion.rememberZoomTarget
import com.ketvoice.app.ui.motion.zoomSource
import com.ketvoice.app.ui.motion.zoomTarget
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
 * Ket 27 navigation and animations (6.0.0) on the host: the optional folding tab bar, the floating search
 * bubble of phones that stretches into the field, and the zoom of a cover into its page and back.
 * Software renders under build/host-ui-preview; not a device GPU result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationAnimationsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.glassStyle.value = GlassStyles.LIQUID_27
        AppSettings.liquidGlass.value = true
        AppSettings.reduceAnimations.value = false
        AppUi.selectTab(AppUi.EXPLORER)
        FloatingSearch.query.value = ""
        CoverZoom.resetForTests()
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState(
            queue = listOf(Track("qa:nav", "Titre en cours", "Artiste", "Album")), index = 0)
    }

    @After fun reset() {
        AppSettings.glassStyle.value = 0
        AppSettings.reduceAnimations.value = false
        AppSettings.collapsingTabBar.value = false
        AppUi.closeOverlays()
        AppUi.selectTab(AppUi.HOME)
        FloatingSearch.query.value = ""
        CoverZoom.resetForTests()
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState()
    }

    private fun image(name: String): Bitmap {
        lateinit var out: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/nav/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out = bitmap
        }
        return out
    }

    /** The phone chrome over a busy background, as AppRoot places it; it follows the real navigation state. */
    private fun chrome(folded: () -> Boolean = { false }) {
        compose.setContent { KetvoiceTheme(useDark = true) {
            val backdrop = rememberGlassBackdrop(Color(0xFF3A2250))
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFF5B2A86), Color(0xFF14325C), Color(0xFF0B0B12)))))
                }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        FloatingChrome(folded(), {}, searching = AppUi.overlay.value == AppUi.SEARCH)
                    }
                }
            }
        } }
    }

    @Test fun theFoldingTabBarIsAStoredBackedUpSettingOffByDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        prefs.edit().remove("collapsingTabBar").commit()
        SettingsStore.load(context)
        assertFalse("off by default (owner: optional in the settings)", AppSettings.collapsingTabBar.value)
        try {
            AppSettings.collapsingTabBar.value = true
            SettingsStore.save()
            AppSettings.collapsingTabBar.value = false
            SettingsStore.load(context)
            assertTrue(AppSettings.collapsingTabBar.value)
            assertEquals(true, PersonalBackup.collect(context).settings["collapsingTabBar"])
        } finally {
            AppSettings.collapsingTabBar.value = false
            SettingsStore.save()
        }
        // Off, scrolling never folds the bar; on, only a tab page does.
        assertFalse(ChromeCollapse.folded(enabled = false, scrolledDown = true, overlay = AppUi.NONE))
        assertTrue(ChromeCollapse.folded(enabled = true, scrolledDown = true, overlay = AppUi.NONE))
        assertFalse(ChromeCollapse.folded(enabled = true, scrolledDown = true, overlay = AppUi.CATALOG))
        assertFalse(ChromeCollapse.folded(enabled = true, scrolledDown = false, overlay = AppUi.NONE))
    }

    @Test fun aTouchOnTheFoldedPillUnfoldsItAndTheNextFoldNeedsANewScroll() {
        AppSettings.collapsingTabBar.value = true
        AppUi.bottomCollapsed.value = true
        chrome { ChromeCollapse.folded(AppSettings.collapsingTabBar.value, AppUi.bottomCollapsed.value, AppUi.overlay.value) }
        compose.mainClock.advanceTimeBy(1_500)
        // The folded pill shows the tab you are on, the mini-player docked beside it keeps its title.
        compose.onNodeWithTag("folded-tab-pill").assertIsDisplayed()
        compose.onNodeWithContentDescription("Explorer").assertIsDisplayed()
        compose.onNodeWithText("Titre en cours").assertExists()
        image("tab-bar-folded")
        val fold = ChromeCollapse()
        assertFalse(fold.update(0, 0))
        assertTrue(fold.update(0, 200))
        compose.onNodeWithTag("folded-tab-pill").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle {
            assertFalse(AppUi.bottomCollapsed.value)
            assertEquals("a touch never changes the tab", AppUi.EXPLORER, AppUi.tab.intValue)
        }
        compose.onAllNodesWithTag("folded-tab-pill").assertCountEquals(0)
        // The page's fold restarts: a small move keeps the bar open, a real scroll down folds it again.
        assertFalse(fold.update(0, 210))
        assertTrue(fold.update(0, 260))
        image("tab-bar-unfolded")
    }

    @Test fun theSearchBubbleStretchesIntoTheFieldAndBack() {
        chrome()
        compose.mainClock.advanceTimeBy(1_500)
        val rest = compose.onNodeWithTag("search-bubble").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("a circle at rest", rest.width, rest.height, 1f)
        assertTrue("at the right, beside the tab bar", rest.left > 300f)
        image("search-bubble-rest")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Rechercher").performClick()
        compose.mainClock.advanceTimeBy(130)
        val midway = compose.onNodeWithTag("search-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue("it is stretching leftwards", midway.width > rest.width + 20f && midway.left < rest.left)
        image("search-bubble-stretching")
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle { assertEquals(AppUi.SEARCH, AppUi.overlay.intValue) }
        val field = compose.onNodeWithTag("search-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue("the field spans the bar beside the folded tabs", field.width > 280f && field.left in 60f..90f)
        assertEquals("its right edge stays where the bubble was", rest.right, field.right, 6f)
        compose.onNodeWithTag("floating-search-field").performTextInput("daft punk")
        compose.runOnIdle { assertEquals("daft punk", FloatingSearch.query.value) }
        image("search-bubble-field")
        // The tabs, folded into a pill at the left, close the search and the field shrinks back.
        compose.onNodeWithContentDescription("Explorer").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle { assertEquals(AppUi.NONE, AppUi.overlay.intValue); assertEquals(AppUi.EXPLORER, AppUi.tab.intValue) }
        val back = compose.onNodeWithTag("search-bubble").fetchSemanticsNode().boundsInRoot
        assertEquals(rest.width, back.width, 1.5f)
        assertEquals(rest.left, back.left, 1.5f)
    }

    @Test fun withReducedMotionTheBubbleSnapsOpen() {
        AppSettings.reduceAnimations.value = true
        chrome()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Rechercher").performClick()
        compose.mainClock.advanceTimeBy(64)
        val field = compose.onNodeWithTag("search-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue("no stretch to watch: the field is there at once", field.width > 280f)
    }

    @Test fun bubbleGeometryKeepsItsRightEdgeAndOvershootsLikeADrop() {
        val rest = FloatingSearch.bubble(0f, 400f, 60f, 324f, pillEnd = 72f, margin = 16f)
        assertEquals(FloatingSearch.Bubble(324f, 60f, 60f), rest)
        val open = FloatingSearch.bubble(1f, 400f, 60f, 324f, pillEnd = 72f, margin = 16f)
        assertEquals(72f, open.x, .01f); assertEquals(312f, open.width, .01f); assertEquals(52f, open.height, .01f)
        assertEquals(rest.x + rest.width, open.x + open.width, .01f)
        assertTrue(FloatingSearch.bubble(1.05f, 400f, 60f, 324f, 72f, 16f).width > open.width)
        assertTrue(FloatingSearch.applies(hasRail = false, quest = false))
        assertFalse(FloatingSearch.applies(hasRail = true, quest = false))
        assertFalse(FloatingSearch.applies(hasRail = false, quest = true))
    }

    /** A card on one "page" and the artwork of the page it opens, in a host that swaps them like the navigation. */
    private var page by mutableStateOf("tab:0")
    private fun zoomHost() {
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                CompositionLocalProvider(LocalNavigationPageKey provides page) {
                    if (page == "tab:0") {
                        val source = rememberZoomSource(radius = 12.dp) { place -> Box(place.background(Color(0xFFE0403A))) }
                        Box(Modifier.offset(40.dp, 560.dp).size(120.dp).zoomSource(source).background(Color(0xFFE0403A))
                            .testTag("card").clickable { source.arm(); page = "catalog:album:a1" })
                    } else {
                        val target = rememberZoomTarget(24.dp)
                        Box(Modifier.offset(80.dp, 120.dp).size(240.dp).zoomTarget(target)
                            .graphicsLayer { alpha = target.alpha() }.background(Color(0xFF3A7BE0)).testTag("hero"))
                    }
                }
                CoverZoomLayer()
            }
        } }
    }

    @Test fun aCoverGrowsIntoItsPageAndShrinksBackIntoItsCard() {
        zoomHost()
        compose.mainClock.advanceTimeBy(500)
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("card").performClick()
        compose.runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val flying = compose.onNodeWithTag("cover-zoom", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val hero = Rect(80f, 120f, 320f, 360f)
        assertTrue("between the card and the page's artwork: $flying", flying.width > card.width && flying.width < hero.width)
        assertTrue(flying.top < card.top && flying.top > hero.top)
        compose.runOnIdle { assertEquals("the page's own artwork waits under the flying cover", 0f, CoverZoom.heroAlpha("catalog:album:a1"), 0f) }
        val opening = image("cover-zoom-opening")
        assertEquals("the page's artwork is hidden under the flying cover",
            0xFF101014.toInt(), opening.getPixel(310, 130))
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithTag("cover-zoom", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(1f, CoverZoom.heroAlpha("catalog:album:a1"), 0f) }
        image("cover-zoom-opened")

        // Back: the artwork shrinks into the card it came from.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            CoverZoom.close("catalog:album:a1"); page = "tab:0"
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val returning = compose.onNodeWithTag("cover-zoom", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("on its way back: $returning", returning.width < hero.width && returning.width > card.width)
        compose.runOnIdle { assertEquals("the card waits for its cover", 0f, CoverZoom.sourceAlpha(card), 0f) }
        val closing = image("cover-zoom-closing")
        assertEquals("only one cover: the card stays hidden while its cover comes back",
            0xFF101014.toInt(), closing.getPixel(card.center.x.toInt(), (card.bottom - 10f).toInt()))
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithTag("cover-zoom", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(1f, CoverZoom.sourceAlpha(card), 0f) }
    }

    @Test fun nothingFliesWithReducedMotionOrAfterATabJump() {
        AppSettings.reduceAnimations.value = true
        zoomHost()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("card").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag("cover-zoom", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("hero").assertExists()
        compose.runOnIdle { CoverZoom.close("catalog:album:a1") }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag("cover-zoom", useUnmergedTree = true).assertCountEquals(0)
        // A page opened long after the touch (it loaded late, or the touch armed nothing) takes no flight.
        CoverZoom.arm({ }, Rect(0f, 0f, 10f, 10f), 0f)
        assertNull(CoverZoom.takeArmed())
        AppSettings.reduceAnimations.value = false
        CoverZoom.arm({ }, Rect(0f, 0f, 10f, 10f), 0f)
        assertNull("too late", CoverZoom.takeArmed(now = android.os.SystemClock.uptimeMillis() + CoverZoom.ARM_WINDOW_MS + 50))
        CoverZoom.arm({ }, Rect(0f, 0f, 10f, 10f), 0f)
        AppUi.selectTab(AppUi.LIBRARY)
        assertNull("a tab jump forgets it", CoverZoom.takeArmed())
    }

    @Test fun flightFramesInterpolateFromCardToArtwork() {
        val flight = CoverZoom.Flight(1, "p", { }, Rect(0f, 500f, 100f, 600f), 12f, 24f, Rect(50f, 100f, 290f, 340f), opening = true)
        val hero = Rect(50f, 100f, 290f, 340f)
        assertEquals(Rect(0f, 500f, 100f, 600f) to 12f, CoverZoom.frame(flight, hero, 0f))
        assertEquals(hero to 24f, CoverZoom.frame(flight, hero, 1f))
        val (mid, radius) = CoverZoom.frame(flight, hero, .5f)
        assertEquals(Rect(25f, 300f, 195f, 470f), mid)
        assertEquals(18f, radius, .01f)
        val back = flight.copy(opening = false)
        assertEquals(Rect(0f, 500f, 100f, 600f) to 12f, CoverZoom.frame(back, hero, 1f))
        // A circle stays a circle while it grows (its radius never exceeds half its side).
        val circle = flight.copy(originRadiusPx = 999f, heroRadiusPx = 999f)
        assertEquals(50f, CoverZoom.frame(circle, hero, 0f).second, .01f)
        assertEquals(0f, CoverZoom.handover(.5f), 0f)
        assertEquals(1f, CoverZoom.handover(1f), 0f)
    }
}
