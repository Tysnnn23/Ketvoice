package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import com.ketvoice.app.ui.tablet.SidebarPrefs
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
 * Ketvoice on tablets, foldables and desktop windows, drawn in software on the host (not a device) at
 * several window sizes: the sidebar and the rail, the docked mini-player, the two-pane pages, the
 * player with its lyrics and its queue, a sheet floating as a panel. The phone keeps its own renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1366dp-h1024dp-xlarge-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletLayoutHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>

    private val tracks = (0 until 24).map { Track("qa:t$it", "Titre $it", "Artiste ${it % 4}", "Album QA", durationMs = 180_000L + it * 7_000L) }

    @Before fun setup() {
        // Quest keeps its own landscape window: no large-screen layout is ever drawn there (LargeScreen.isLarge).
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        // Static glass: the host's software canvas draws a live backdrop's first panel a second time at the window's
        // origin (seen on any glass panel that does not start at 0,0); a device does not. The layout is what is checked here.
        AppSettings.reduceDynamicBlur.value = true
        SidebarPrefs.resetForTests()
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.themeMode.value = 0
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
        player.value = PlayerUiState()
        SidebarPrefs.resetForTests()
        PlaylistStore.resetForTest()
        AppUi.lastTouch = null
        AppUi.trackMenuTrack.value = null
        @Suppress("UNCHECKED_CAST")
        (com.ketvoice.app.data.LyricsRepo.state as MutableStateFlow<com.ketvoice.app.data.LyricsRepo.State>).value = com.ketvoice.app.data.LyricsRepo.State()
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/tablet/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun playing() {
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 2, durationMs = 201_000L)
    }

    private fun playlists() {
        PlaylistStore.resetForTest()
        kotlinx.coroutines.runBlocking { PlaylistStore.init(compose.activity) }
        val lists = listOf(
            LocalPlaylist(id = "qa:p1", name = "Matin calme", pinned = true, entries = tracks.take(6).map { PlaylistEntry(title = it.title, artist = it.artist, track = it) }),
            LocalPlaylist(id = "qa:p2", name = "Route de nuit", entries = tracks.take(12).map { PlaylistEntry(title = it.title, artist = it.artist, track = it) }),
            LocalPlaylist(id = "qa:p3", name = "Sport", entries = tracks.drop(4).map { PlaylistEntry(title = it.title, artist = it.artist, track = it) }))
        @Suppress("UNCHECKED_CAST")
        (PlaylistStore.lists as MutableStateFlow<List<LocalPlaylist>>).value = lists
    }

    /** The stretch overscroll is left out of these still renders. */
    @Composable
    private fun NoOverscroll(content: @Composable () -> Unit) =
        CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null, content = content)

    private fun root(dark: Boolean = false) {
        AppSettings.themeMode.value = if (dark) 2 else 1
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
    }

    /** A page drawn alone with the large-screen context the root would give it. */
    private fun page(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            KetvoiceTheme(useDark = dark) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                val width = config.screenWidthDp.toFloat(); val height = config.screenHeightDp.toFloat()
                val info = LargeScreen.info(width, height, false, null)
                CompositionLocalProvider(LocalLargeScreen provides info,
                    com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout provides com.ketvoice.app.ui.adaptive.AdaptiveLayout(width, height,
                        navigationWidthDp = info?.navigationWidthDp)) { NoOverscroll(content) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun landscapeTabletShowsTheSidebarAndADockedMiniPlayer() {
        playlists(); playing()
        root()
        compose.onNodeWithTag("tablet-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("tablet-mini-player").assertIsDisplayed()
        val sidebar = compose.onNodeWithTag("tablet-sidebar").fetchSemanticsNode().boundsInRoot
        val mini = compose.onNodeWithTag("tablet-mini-player").fetchSemanticsNode().boundsInRoot
        assertTrue("the mini-player is docked beside the sidebar, not under it (${mini.left} vs ${sidebar.right})", mini.left >= sidebar.right)
        compose.onNode(hasText("Matin calme") and hasAnyAncestor(hasTestTag("tablet-sidebar"))).assertIsDisplayed()
        image("home-sidebar-light")
    }

    @Test fun theSidebarFoldsIntoARailAndRemembersIt() {
        playlists(); playing()
        root(dark = true)
        compose.onNodeWithTag("sidebar-fold").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tablet-rail").assertIsDisplayed()
        assertEquals(true, SidebarPrefs.collapsed.value)
        assertEquals(true, compose.activity.getSharedPreferences("large-screen", 0).getBoolean("sidebar_collapsed", false))
        image("home-rail-dark")
        compose.onNodeWithTag("sidebar-unfold").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tablet-sidebar").assertIsDisplayed()
    }

    @Test fun theLibraryFromTheSidebar() {
        playlists(); playing()
        root()
        compose.onNodeWithTag("sidebar-playlists").performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("sidebar-playlists") and isSelected()).assertExists()
        image("library-playlists-light")
    }

    private fun lyrics() {
        @Suppress("UNCHECKED_CAST")
        (com.ketvoice.app.data.LyricsRepo.state as MutableStateFlow<com.ketvoice.app.data.LyricsRepo.State>).value =
            com.ketvoice.app.data.LyricsRepo.State(lines = listOf(
                com.ketvoice.app.data.MusicApi.LyricLine(0, "Sous les lumières de la ville"),
                com.ketvoice.app.data.MusicApi.LyricLine(4_000, "On marche sans savoir où"),
                com.ketvoice.app.data.MusicApi.LyricLine(8_000, "Et la nuit nous appartient"),
                com.ketvoice.app.data.MusicApi.LyricLine(12_000, "Jusqu'au matin, jusqu'au bout"),
                com.ketvoice.app.data.MusicApi.LyricLine(16_000, "Les étoiles comme témoins"),
                com.ketvoice.app.data.MusicApi.LyricLine(20_000, "Rien ne pourra nous arrêter")), isTimed = true)
    }

    private fun player(page: com.ketvoice.app.ui.player.PlayerPage) {
        playing()
        page(true) { com.ketvoice.app.ui.player.FullPlayerScreen(initialPage = page, onDismiss = {}) }
        lyrics()
        compose.waitForIdle()
    }

    @Test fun landscapePlayerShowsTheLyricsBesideTheArtwork() {
        player(com.ketvoice.app.ui.player.PlayerPage.ARTWORK)
        compose.onNodeWithTag("tablet-player").assertIsDisplayed()
        val art = compose.onNodeWithTag("tablet-player-artwork").fetchSemanticsNode().boundsInRoot
        val lyrics = compose.onNodeWithTag("tablet-player-lyrics-pane").fetchSemanticsNode().boundsInRoot
        assertTrue("the lyrics stand beside the artwork", lyrics.left > art.right)
        image("player-landscape-lyrics")
    }

    @Test fun landscapePlayerShowsTheQueueNextToTheLyrics() {
        player(com.ketvoice.app.ui.player.PlayerPage.QUEUE)
        val lyrics = compose.onNodeWithTag("tablet-player-lyrics-pane").fetchSemanticsNode().boundsInRoot
        val queue = compose.onNodeWithTag("tablet-player-queue-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("1366 dp hold the lyrics and the queue side by side", queue.left > lyrics.right)
        image("player-landscape-lyrics-queue")
        // The lyrics button hides them: the artwork and the queue remain.
        compose.onNodeWithTag("tablet-player-lyrics", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tablet-player-lyrics-pane").assertDoesNotExist()
        image("player-landscape-queue")
    }

    @Test fun withoutLyricsTheArtworkTakesTheStage() {
        player(com.ketvoice.app.ui.player.PlayerPage.ARTWORK)
        @Suppress("UNCHECKED_CAST")
        (com.ketvoice.app.data.LyricsRepo.state as MutableStateFlow<com.ketvoice.app.data.LyricsRepo.State>).value = com.ketvoice.app.data.LyricsRepo.State()
        compose.waitForIdle()
        compose.onNodeWithTag("tablet-player-lyrics-pane").assertDoesNotExist()
        val art = compose.onNodeWithTag("tablet-player-artwork").fetchSemanticsNode().boundsInRoot
        assertEquals("centred", 683f, art.center.x, 2f)
        image("player-landscape-artwork")
    }

    @Test @Config(qualifiers = "w1024dp-h1366dp-xlarge-port-mdpi")
    fun portraitPlayerPutsTheLyricsBelow() {
        player(com.ketvoice.app.ui.player.PlayerPage.ARTWORK)
        val art = compose.onNodeWithTag("tablet-player-artwork").fetchSemanticsNode().boundsInRoot
        val lyrics = compose.onNodeWithTag("tablet-player-lyrics-pane").fetchSemanticsNode().boundsInRoot
        assertTrue("standing, the lyrics run below the artwork", lyrics.top > art.bottom)
        image("player-portrait-lyrics")
    }

    @Test @Config(qualifiers = "w1024dp-h1366dp-xlarge-port-mdpi")
    fun portraitPlayerWithQueue() {
        player(com.ketvoice.app.ui.player.PlayerPage.QUEUE)
        compose.onNodeWithTag("tablet-player-queue-panel").assertIsDisplayed()
        image("player-portrait-lyrics-queue")
    }

    @Test fun albumPageInTwoPanes() {
        val album = tracks.take(14)
        page(true) {
            com.ketvoice.app.ui.sub.AlbumPage(com.ketvoice.app.data.CatalogRequest("album", "fixture:tablet", "Nuits d'été"),
                com.ketvoice.app.data.CatalogPage("Nuits d'été", "Artiste 1", null, album, "2026"), album, false, {})
        }
        compose.onNodeWithTag("album-list-pane").assertIsDisplayed()
        val play = compose.onNodeWithText("Lire").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Titre 0").fetchSemanticsNode().boundsInRoot
        assertTrue("the actions stay on the left, the titles on the right", play.right < first.left)
        image("album-two-pane-dark")
    }

    @Test fun playlistPageInTwoPanes() {
        playlists()
        AppUi.playlistId.value = "qa:p2"
        page(false) { com.ketvoice.app.ui.library.PlaylistScreen() }
        compose.onNodeWithTag("playlist-tracks-pane").assertIsDisplayed()
        image("playlist-two-pane-light")
    }

    @Test fun artistPageInTwoPanes() {
        val page = com.ketvoice.app.data.ArtistPage(com.ketvoice.app.data.MusicArtist("qa:artist-tablet", "Artiste 1", null),
            "Artiste de test, créé pour les rendus sur tablette.", tracks.take(8),
            (0 until 6).map { com.ketvoice.app.data.Album("qa:a$it", "Album $it", "Artiste 1") }, emptyList())
        kotlinx.coroutines.runBlocking { com.ketvoice.app.data.ArtistCache.put("qa:artist-tablet", "Artiste 1", page) }
        page(true) { com.ketvoice.app.ui.sub.ArtistScreen(AppUi.ArtistRequest("qa:artist-tablet", "Artiste 1")) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("artist-list-pane").fetchSemanticsNodes().isNotEmpty() }
        image("artist-two-pane-dark")
    }

    @Test fun searchKeepsTheQueryBesideTheResults() {
        com.ketvoice.app.data.SearchHistory.init(compose.activity)
        listOf("Daft Punk", "Stromae", "Nuits d'été").forEach { com.ketvoice.app.data.SearchHistory.record(it) }
        page(false) { com.ketvoice.app.ui.overlay.SearchScreen() }
        compose.onNodeWithTag("search-query-pane").assertIsDisplayed()
        compose.onNodeWithTag("search-results-pane").assertIsDisplayed()
        val query = compose.onNodeWithTag("search-query-pane").fetchSemanticsNode().boundsInRoot
        val results = compose.onNodeWithTag("search-results-pane").fetchSemanticsNode().boundsInRoot
        assertTrue(results.left >= query.right)
        image("search-two-pane-light")
    }

    @Test fun searchResultsSitBesideTheQuery() {
        playlists()
        AppUi.searchSeed.value = "Titre"
        page(true) { com.ketvoice.app.ui.overlay.SearchScreen() }
        compose.onNode(hasText("Bibliothèque") and hasAnyAncestor(hasTestTag("search-query-pane"))).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Titre 5") and hasAnyAncestor(hasTestTag("search-results-pane"))).fetchSemanticsNodes().isNotEmpty() }
        image("search-results-two-pane-dark")
    }

    @Test fun aMenuFloatsAsAPopoverInsteadOfABottomSheet() {
        playlists(); playing()
        root()
        compose.runOnIdle {
            AppUi.lastTouch = androidx.compose.ui.geometry.Offset(900f, 300f)
            AppUi.trackMenuTrack.value = tracks[3]
        }
        compose.waitForIdle()
        compose.onNodeWithTag("floating-sheet").assertIsDisplayed()
        val sheet = compose.onNodeWithTag("floating-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue("a panel, not the whole width (${sheet.width})", sheet.width <= 541f)
        assertTrue("it does not rest on the bottom edge (${sheet.bottom})", sheet.bottom < 1024f - 20f)
        image("sheet-popover-light")
    }

    @Test fun aFormFloatsCentred() {
        page(false) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                com.ketvoice.app.ui.common.SpringSheet({}, startFull = true) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth()
                        .background(com.ketvoice.app.theme.LocalTheme.current.card).padding(24.dp)) {
                        com.ketvoice.app.ui.common.Text("Renommer la playlist", fontSize = 20.sp)
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(160.dp))
                    }
                }
            }
        }
        val sheet = compose.onNodeWithTag("floating-sheet").fetchSemanticsNode().boundsInRoot
        assertEquals("centred", 683f, sheet.center.x, 2f)
        assertEquals("centred", 512f, sheet.center.y, 2f)
        image("sheet-centred-light")
    }

    @Test fun shelvesHoldMoreWholeCardsOnAWidePage() {
        val albums = (0 until 12).map { com.ketvoice.app.data.CatalogAlbum("qa:al$it", "Album $it", "Artiste ${it % 4}", null) }
        page(true) {
            // The page beside the sidebar of a 1366 dp window: 1366 − 272 − 2 × 16.
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(1062.dp).fillMaxSize()
                .background(com.ketvoice.app.theme.LocalTheme.current.background).padding(horizontal = 16.dp, vertical = 24.dp)) {
                com.ketvoice.app.ui.explorer.ShelfHeading("Titres du moment")
                com.ketvoice.app.ui.explorer.ChartTracksShelf(tracks)
                com.ketvoice.app.ui.explorer.ShelfHeading("Albums du moment")
                com.ketvoice.app.ui.explorer.AlbumShelf(albums)
            }
        }
        val cards = compose.onAllNodes(hasAnyAncestor(hasTestTag("album-shelf")) and hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.right <= 1062f - 16f + 1f }
        assertTrue("at least six whole albums fit beside the sidebar (${cards.size})", cards.size >= 6)
        image("explorer-shelves-wide-dark")
    }

    @Test fun thePlayerUnfoldsFromTheDockedMiniPlayer() {
        playlists(); playing()
        AppSettings.reduceAnimations.value = false
        root(dark = true)
        val dock = compose.onNodeWithTag("tablet-mini-player").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { AppUi.showPlayer.value = true }
        compose.mainClock.advanceTimeBy(80)
        image("player-unfold-from-dock-early")
        compose.mainClock.advanceTimeBy(120)
        image("player-unfold-from-dock-middle")
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("tablet-player-artwork").assertExists()
        image("player-unfold-from-dock-open")
        compose.mainClock.autoAdvance = true
        assertTrue("the dock is at the bottom of the page", dock.top > 900f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theKeyboardSearchesGoesBackAndFoldsTheSidebar() {
        playlists(); playing()
        root()
        compose.onRoot().performKeyInput { keyDown(androidx.compose.ui.input.key.Key.CtrlLeft); pressKey(androidx.compose.ui.input.key.Key.F)
            keyUp(androidx.compose.ui.input.key.Key.CtrlLeft) }
        compose.waitForIdle()
        assertEquals("Ctrl F opens the search", AppUi.SEARCH, AppUi.overlay.intValue)
        // Esc lets the field go, then goes back.
        repeat(2) { compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }; compose.waitForIdle() }
        assertEquals("Esc closes the search", AppUi.NONE, AppUi.overlay.intValue)
        compose.onRoot().performKeyInput { keyDown(androidx.compose.ui.input.key.Key.CtrlLeft); keyDown(androidx.compose.ui.input.key.Key.ShiftLeft)
            pressKey(androidx.compose.ui.input.key.Key.S); keyUp(androidx.compose.ui.input.key.Key.ShiftLeft); keyUp(androidx.compose.ui.input.key.Key.CtrlLeft) }
        compose.waitForIdle()
        assertEquals("Ctrl ⇧ S folds the sidebar", true, SidebarPrefs.collapsed.value)
        compose.onNodeWithTag("tablet-rail").assertIsDisplayed()
        // Ctrl L opens the player on the lyrics.
        compose.onRoot().performKeyInput { keyDown(androidx.compose.ui.input.key.Key.CtrlLeft); pressKey(androidx.compose.ui.input.key.Key.L)
            keyUp(androidx.compose.ui.input.key.Key.CtrlLeft) }
        compose.waitForIdle()
        assertTrue(AppUi.showPlayer.value)
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        compose.waitForIdle()
        assertFalse("Esc closes the player", AppUi.showPlayer.value)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aSecondaryClickOpensTheRowsMenuAndRowsLightUnderThePointer() {
        val album = tracks.take(10)
        page(false) {
            com.ketvoice.app.ui.sub.AlbumPage(com.ketvoice.app.data.CatalogRequest("album", "fixture:mouse", "Nuits d'été"),
                com.ketvoice.app.data.CatalogPage("Nuits d'été", "Artiste 1", null, album, "2026"), album, false, {})
        }
        compose.onNodeWithText("Titre 2").performMouseInput { moveTo(center) }
        compose.waitForIdle()
        image("album-row-hover-light")
        compose.onNodeWithText("Titre 4").performMouseInput { rightClick(center) }
        compose.waitForIdle()
        assertEquals("the glass context menu opens on the title", "qa:t4", com.ketvoice.app.ui.common.ContextMenus.request.value?.track?.id)
        com.ketvoice.app.ui.common.ContextMenus.request.value = null
    }

    @Test fun explorerShelvesHoldMoreCards() {
        playing()
        root(dark = true)
        compose.onNodeWithTag("sidebar-explorer").performClick()
        compose.waitForIdle()
        image("explorer-sidebar-dark")
    }

    @Test @Config(qualifiers = "w1024dp-h1366dp-xlarge-port-mdpi")
    fun portraitTabletOpensOnTheRail() {
        playlists(); playing()
        root()
        compose.onNodeWithTag("tablet-rail").assertIsDisplayed()
        compose.onNodeWithTag("tablet-mini-player").assertIsDisplayed()
        image("home-portrait-rail-light")
    }

    @Test @Config(qualifiers = "w840dp-h1200dp-mdpi")
    fun mediumWindowKeepsARailWithoutSidebar() {
        playlists(); playing()
        root()
        compose.onNodeWithTag("tablet-rail").assertIsDisplayed()
        compose.onNodeWithTag("sidebar-unfold").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-library").assertDoesNotExist()
        image("home-medium-rail-light")
    }

    @Test @Config(qualifiers = "w400dp-h900dp-mdpi")
    fun aPhoneKeepsItsTabBar() {
        playlists(); playing()
        root()
        compose.onNodeWithTag("tablet-rail").assertDoesNotExist()
        compose.onNodeWithTag("tablet-sidebar").assertDoesNotExist()
        compose.onNodeWithTag("tablet-mini-player").assertDoesNotExist()
        // The phone's root is not drawn here: its fused glass chrome cannot be drawn by the host's software canvas.
        // Its pages are, unchanged, by the other host tests.
    }
}
