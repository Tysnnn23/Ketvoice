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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LayoutSettings
import com.ketvoice.app.data.ListeningHistory
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
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.player.PlayerBottomBar
import com.ketvoice.app.ui.player.PlayerLayout
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
 * 9.0 « Disposition » drawn on the host (software Skia, not a device): the listener's tabs in both glass looks and on a
 * tablet's sidebar, Home's sections, the player's and mini-player's buttons, the title menu, the swipes, the library's
 * views, the settings page — and the defaults, which are the 8.4 layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutCustomizationHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST")
    private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST")
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    private val song = Track("qa:layout", "Titre Disposition", "Artiste QA", "Album QA", durationMs = 200_000)

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.glassLook.value = GlassLook.LIQUID_27
        LayoutSettings.resetAll()
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    @After fun reset() {
        LayoutSettings.resetAll()
        AppSettings.reduceAnimations.value = false
        AppSettings.glassLook.value = GlassLook.LIQUID_27
        AppSettings.favoriteIds.value = emptySet()
        ContextMenus.request.value = null
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
        AppUi.catalogRequest.value = null
        player.value = PlayerUiState()
        recent.value = emptyList(); known.value = emptyList()
        com.ketvoice.app.ui.tablet.SidebarPrefs.resetForTests()
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/layout/$name.png").apply {
                parentFile?.mkdirs()
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    private fun chrome(dark: Boolean = true) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            val ground = if (dark) Color(0xFF101014) else Color(0xFFF2F2F7)
            val backdrop = rememberGlassBackdrop(ground)
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().background(ground)) {
                        repeat(9) { i -> Box(Modifier.fillMaxWidth().height(96.dp).padding(8.dp).background(Brush.horizontalGradient(
                            listOf(Color(0xFFFF4060 - i * 0x101000), Color(0xFF3080FF + i * 0x1010))))) }
                    }
                }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    Box(Modifier.align(Alignment.BottomCenter)) { FloatingChrome(false, {}) }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun left(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left
    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test fun liquidTabsAreTheListenersInTheirOrder() {
        chrome()
        listOf("Accueil", "Explorer", "Bibliothèque").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertTrue("the default bar keeps its search bubble", left("Accueil") < left("Explorer") && left("Explorer") < left("Bibliothèque"))
        compose.onNodeWithTag("search-bubble").assertExists()
        image("liquid-tabs-default")
        compose.runOnIdle { LayoutSettings.tabs.value = "library,favorites,search,home,-explore,-downloads" }
        compose.waitForIdle()
        compose.onNodeWithText("Explorer").assertDoesNotExist()
        assertTrue(left("Bibliothèque") < left("Favoris") && left("Favoris") < left("Rechercher") && left("Rechercher") < left("Accueil"))
        // Search is a tab: no bubble beside the bar at rest.
        compose.onNodeWithTag("search-bubble").assertDoesNotExist()
        image("liquid-tabs-custom")
        compose.onNodeWithText("Favoris").performClick()
        compose.runOnIdle {
            assertEquals(AppUi.CATALOG, AppUi.overlay.intValue)
            assertEquals("favorites", AppUi.catalogRequest.value?.kind)
        }
        compose.onNode(hasText("Favoris") and isSelectable()).assertIsSelected()
    }

    @Test fun cristalTabsAreTheListenersThenSearch() {
        AppSettings.glassLook.value = GlassLook.CRISTAL
        chrome(dark = false)
        listOf("Accueil", "Explorer", "Bibliothèque", "Rechercher").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        image("cristal-tabs-default")
        compose.runOnIdle { LayoutSettings.tabs.value = "favorites,library,downloads,-home,-explore,-search" }
        compose.waitForIdle()
        compose.onNodeWithText("Accueil").assertDoesNotExist()
        compose.onNodeWithText("Explorer").assertDoesNotExist()
        assertTrue(left("Favoris") < left("Bibliothèque") && left("Bibliothèque") < left("Téléchargements") &&
            left("Téléchargements") < left("Rechercher"))
        image("cristal-tabs-custom")
        compose.onNodeWithText("Téléchargements").performClick()
        compose.runOnIdle { assertEquals(AppUi.DOWNLOADS, AppUi.overlay.intValue) }
    }

    @Test @Config(qualifiers = "w1366dp-h1024dp-xlarge-land-mdpi")
    fun theTabletSidebarFollowsTheSameOrder() {
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        com.ketvoice.app.ui.tablet.SidebarPrefs.resetForTests()
        LayoutSettings.tabs.value = "library,favorites,home,-explore,-search,-downloads"
        compose.setContent { KetvoiceTheme(useDark = false) {
            val info = com.ketvoice.app.ui.adaptive.LargeScreen.info(1366f, 1024f, false, null)!!
            com.ketvoice.app.ui.tablet.TabletNavigation(info, info.navigationWidthDp.dp)
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("tablet-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("sidebar-explorer").assertDoesNotExist()
        assertTrue(top("sidebar-library") < top("sidebar-favorites") && top("sidebar-favorites") < top("sidebar-home"))
        assertEquals("Favourites move up from the library's list", 1, compose.onAllNodesWithTag("sidebar-favorites").fetchSemanticsNodes().size)
        compose.onNodeWithTag("sidebar-downloads").assertExists()
        image("tablet-sidebar-custom")
    }

    @Test @Config(qualifiers = "w400dp-h1600dp-mdpi")
    fun homeSectionsFollowTheirOrderAndVisibility() {
        val titles = (1..8).map { Track("qa:home$it", "Titre $it", "Artiste $it", "Album $it", durationMs = 180_000) }
        recent.value = titles; known.value = titles
        AppSettings.favoriteIds.value = setOf(titles.first().id)
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.home.HomeScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-library"))
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-recent-carousel"))
        assertTrue("8.4 order: recent titles before the library shelf", top("home-recent-carousel") < top("home-library"))
        compose.runOnIdle { LayoutSettings.home.value = "library,recent,-featured,-forYou,-moods" }
        compose.waitForIdle()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-recent-carousel"))
        assertTrue(top("home-library") < top("home-recent-carousel"))
        image("home-sections-custom")
        compose.runOnIdle { LayoutSettings.home.value = "-library" }
        compose.waitForIdle()
        compose.onNodeWithTag("home-library").assertDoesNotExist()
        // A long press on the greeting opens the editor, the same as in Settings.
        compose.onNodeWithTag("home-list").performScrollToIndex(0)
        compose.onNodeWithTag("home-greeting").performTouchInput { longClick() }
        compose.onNodeWithTag("layout-editor-home").assertExists()
        compose.onNodeWithTag("layout-reset-home").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", LayoutSettings.home.value) }
        compose.runOnIdle { com.ketvoice.app.ui.home.HomeSections.editing.value = false }
    }

    @Test fun thePlayersBottomRowHasTheChosenButtons() {
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                PlayerBottomBar(false, {}, {}, {}, {}, Modifier.padding(16.dp),
                    actions = PlayerLayout.bar(LayoutSettings.player.value), track = song)
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("player-bottom-row").assertDoesNotExist()
        compose.onNodeWithContentDescription("Paroles").assertIsDisplayed()
        compose.onNodeWithContentDescription("File d'attente").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sortie audio").assertIsDisplayed()
        image("player-row-default")
        compose.runOnIdle { LayoutSettings.player.value = "like,sleep,share,queue,-lyrics,-output,-account" }
        compose.waitForIdle()
        compose.onNodeWithTag("player-bottom-row").assertIsDisplayed()
        compose.onNodeWithContentDescription("Paroles").assertDoesNotExist()
        listOf("like", "sleep", "share", "queue").forEach { compose.onNodeWithTag("player-action-$it").assertIsDisplayed() }
        image("player-row-custom")
        compose.onNodeWithTag("player-action-like").performClick()
        compose.runOnIdle { assertTrue(song.id in AppSettings.favoriteIds.value) }
        compose.onNodeWithTag("player-action-sleep").performClick()
        compose.onNodeWithText("À la fin du titre").assertIsDisplayed()
    }

    @Test fun theMiniPlayerHasTheChosenButtonsInBothLooks() {
        player.value = PlayerUiState(queue = listOf(song), queueEntryIds = listOf(song.id), index = 0)
        chrome()
        compose.onNodeWithTag("mini-next", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("mini-like", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { LayoutSettings.mini.value = "previous,like,-next" }
        compose.waitForIdle()
        compose.onNodeWithTag("mini-next", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("mini-previous", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("mini-like", useUnmergedTree = true).assertExists()
        image("mini-liquid-custom")
        compose.onNodeWithTag("mini-like", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(song.id in AppSettings.favoriteIds.value) }
        compose.runOnIdle { AppSettings.glassLook.value = GlassLook.CRISTAL }
        compose.waitForIdle()
        compose.onNodeWithTag("cristal-now-playing").assertExists()
        compose.onNodeWithTag("mini-previous", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("mini-like", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("mini-next", useUnmergedTree = true).assertDoesNotExist()
        image("mini-cristal-custom")
    }

    private var menuShown = false

    private fun menu() {
        if (!menuShown) compose.setContent { KetvoiceTheme(useDark = true) {
            val backdrop = rememberGlassBackdrop(Color(0xFF101014))
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Color(0xFF202030))) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) { ContextMenuHost() }
            }
        } }
        menuShown = true
        compose.runOnIdle { ContextMenus.open(song, null) }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    private fun menuTop(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("context-menu-actions")))
        .fetchSemanticsNode().boundsInRoot.top

    @Test fun theTitleMenuFollowsTheChosenOrderAndKeepsPlusLast() {
        menu()
        assertTrue(menuTop("Lire ensuite") < menuTop("Partager"))
        assertTrue(menuTop("Partager") < menuTop("Plus"))
        compose.runOnIdle { ContextMenus.request.value = null }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        compose.runOnIdle { LayoutSettings.menu.value = "share,about,playNext,-radio,-mixPoint" }
        menu()
        assertTrue(menuTop("Partager") < menuTop("À propos de ce titre") && menuTop("À propos de ce titre") < menuTop("Lire ensuite"))
        compose.onNode(hasText("Lancer la radio") and hasAnyAncestor(hasTestTag("context-menu-actions"))).assertDoesNotExist()
        compose.onNode(hasText("Point de mix…") and hasAnyAncestor(hasTestTag("context-menu-actions"))).assertDoesNotExist()
        val plus = menuTop("Plus")
        listOf("Partager", "Lire ensuite", "Ajouter à la file", "Télécharger").forEach { assertTrue(it, menuTop(it) < plus) }
        image("title-menu-custom")
    }

    private fun swipeRow() {
        compose.setContent { KetvoiceTheme(useDark = false) {
            com.ketvoice.app.ui.common.SwipeTrackActions(song, Modifier.padding(top = 100.dp)) {
                Text("Titre Disposition", localize = false, modifier = Modifier.fillMaxWidth().height(72.dp))
            }
        } }
        compose.waitForIdle()
    }

    private fun swipeActions(): List<String> = compose.onNode(hasText("Titre Disposition").not() and
        SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode()
        .config[SemanticsActions.CustomActions].map { it.label }

    @Test fun swipesDoWhatTheListenerChose() {
        swipeRow()
        assertEquals(listOf("Lire ensuite", "Ajouter à la file"), swipeActions())
        compose.runOnIdle { LayoutSettings.swipeEnd.value = "favorite"; LayoutSettings.swipeStart.value = "none" }
        compose.waitForIdle()
        assertEquals(listOf("Ajouter aux favoris ou les retirer"), swipeActions())
        compose.onNodeWithText("Titre Disposition").performTouchInput { swipeRight(startX = 10f, endX = right - 4f) }
        compose.runOnIdle { assertTrue(song.id in AppSettings.favoriteIds.value) }
        val queued = Player.state.value.queue.size
        compose.onNodeWithText("Titre Disposition").performTouchInput { swipeLeft(startX = right - 10f, endX = 4f) }
        compose.runOnIdle { assertEquals("no action towards the start", queued, Player.state.value.queue.size) }
    }

    @Test fun libraryAlbumsOpenAsAListWhenChosen() {
        val titles = listOf(Track("qa:la", "Un", "Artiste A", "Album A", durationMs = 1), Track("qa:lb", "Deux", "Artiste B", "Album B", durationMs = 1))
        recent.value = titles
        compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.library.LibraryScreen() } }
        compose.waitForIdle()
        compose.onNode(hasText("Albums") and hasClickAction()).performClick()
        compose.waitForIdle()
        fun albumTop(name: String) = compose.onAllNodesWithText(name).fetchSemanticsNodes().maxOf { it.boundsInRoot.top }
        assertEquals("8.4: a grid, side by side", albumTop("Album A"), albumTop("Album B"), 1f)
        compose.runOnIdle { LayoutSettings.setLibraryView("albums", "list") }
        compose.waitForIdle()
        assertTrue("one album a line", albumTop("Album B") > albumTop("Album A") + 40f)
        image("library-albums-list")
    }

    @Test fun thePersonalizationPageEditsTheLayout() {
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Personalization").performScrollTo().performClick()
        compose.waitForIdle()
        listOf("Onglets à la carte", "Accueil modulable", "Boutons du lecteur", "Menu des titres").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        image("settings-personalization")
        compose.onNodeWithText("Onglets à la carte").performScrollTo().performClick()
        compose.onNodeWithTag("layout-editor-tabs").assertExists()
        image("settings-tabs-editor")
        compose.onNodeWithTag("layout-item-search").performScrollTo().performClick()
        compose.runOnIdle { assertTrue("search" in com.ketvoice.app.ui.chrome.TabLayout.current().map { it.id }) }
        compose.onNodeWithTag("layout-item-search").assertIsOn()
        compose.onNodeWithTag("layout-reset-tabs").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", LayoutSettings.tabs.value) }
    }

    @Test fun theTabEditorNeverLeavesFewerThanTwoTabs() {
        LayoutSettings.tabs.value = "home,library,-explore,-search,-downloads,-favorites"
        compose.setContent { KetvoiceTheme(useDark = false) {
            com.ketvoice.app.ui.settings.LayoutListContent(com.ketvoice.app.ui.settings.LayoutEditors.tabs,
                LayoutSettings.tabList.parse(LayoutSettings.tabs.value), {}, {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("layout-item-home").assertIsNotEnabled()
        compose.onNodeWithTag("layout-item-library").assertIsNotEnabled()
        compose.onNodeWithTag("layout-item-explore").assertIsEnabled()
        compose.onNodeWithText("De 2 à 5 onglets. L'Accueil ou la Bibliothèque reste toujours dans la barre.").assertIsDisplayed()
        image("tabs-editor-limits")
    }
}
