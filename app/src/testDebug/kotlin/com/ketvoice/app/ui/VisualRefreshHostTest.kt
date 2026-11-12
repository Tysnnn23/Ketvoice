package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.*
import com.ketvoice.app.ui.sub.AlbumPage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualRefreshHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>
    private val oldLyricsMode = AppSettings.lyricsMode.value

    @Before fun setup() {
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.albumHeroCanvas.value = false
        AppSettings.reduceAnimations.value = true
    }
    @After fun reset() {
        coil.Coil.reset()
        AppUi.selectTab(AppUi.HOME)
        state.value = PlayerUiState()
        AppSettings.reduceAnimations.value = false
        AppSettings.techStats.value = false
        AppSettings.glassStyle.value = 0
        AppSettings.albumHeroCanvas.value = true
        AppSettings.lyricsMode.value = oldLyricsMode
    }
    private fun image(name: String): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val out = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
            out.parentFile?.mkdirs()
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    @Test fun albumStartsAtWindowTopInLightModeWithoutBleachingItsLowerHalf() {
        val fixture = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(35, 75, 130))
        }
        val drawable = android.graphics.drawable.BitmapDrawable(compose.activity.resources, fixture)
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                coil.request.SuccessResult(drawable, chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build())
        val track = Track("qa:visual", "Fixture", "Artist", "Album")
        compose.setContent { KetvoiceTheme(useDark = false) {
            AlbumPage(CatalogRequest("album", "qa:visual", "Album"), CatalogPage("Album", "Artist", "fixture:blue-cover", listOf(track), "2026"), listOf(track), false, {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-artwork-loaded").fetchSemanticsNodes().isNotEmpty() }
        val bounds = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, bounds.top, 1f)
        assertEquals(0f, bounds.left, 1f)
        assertEquals(400f, bounds.width, 1f)
        val b = image("album-edge-light-no-white-veil")
        val sample = b.getPixel(200, (bounds.height * .82f).toInt())
        assertTrue("Lower image must not be washed white", android.graphics.Color.red(sample) < 220 || android.graphics.Color.blue(sample) < 220)
    }

    @Test fun albumLoadingAndScrollKeepOneSurface() {
        var loading by mutableStateOf(true)
        val tracks = List(30) { Track("qa:scroll:$it", "Track $it", "Artist", "Album") }
        compose.setContent { KetvoiceTheme(useDark = true) {
            AlbumPage(CatalogRequest("album", "qa:scroll", "Scroll album"),
                if (loading) null else CatalogPage("Scroll album", "Artist", null, tracks, "2026"),
                if (loading) emptyList() else tracks, loading, {})
        } }
        compose.onNodeWithTag("album-hero-visual").assertExists()
        image("album-loading-dark")
        compose.runOnIdle { loading = false }
        compose.onNodeWithTag("album-list").performScrollToIndex(15)
        compose.onNodeWithText("Track 14").assertExists()
        image("album-scroll-dark")
    }

    @Test fun navigationRetainsOutgoingRequestsAndSurvivesRapidBackReversal() {
        AppSettings.reduceAnimations.value = false
        AppUi.selectTab(AppUi.HOME)
        compose.mainClock.autoAdvance = false
        fun advance(ms: Long) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(ms)
            compose.waitForIdle()
        }
        compose.setContent { KetvoiceTheme(useDark = true) {
            NavigationPresentation(AppUi.tab.intValue to AppUi.snapshot()) { _, entry ->
                Box(Modifier.fillMaxSize().background(Color(0xFF202020))) {
                    com.ketvoice.app.ui.common.Text(entry.artistRequest?.name ?: entry.catalogRequest?.title ?: "Root fixture")
                }
            }
        } }
        compose.runOnIdle { AppUi.openArtist("a", "Artist A", Rect(20f, 200f, 160f, 340f)) }
        advance(800)
        assertTrue("Initial artist missing; route=${AppUi.snapshot()}\n${compose.onRoot().printToString()}",
            compose.onAllNodesWithText("Artist A", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { AppUi.openArtist("b", "Artist B", Rect(200f, 400f, 300f, 500f)) }
        advance(32)
        assertTrue("Outgoing request missing; reduced=${com.ketvoice.app.ui.motion.Motion.reduced}\n${compose.onRoot().printToString()}",
            compose.onAllNodesWithText("Artist A", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Artist B").assertExists()
        compose.runOnIdle { AppUi.closeOverlay() }
        advance(80)
        compose.runOnIdle { AppUi.openCatalog(CatalogRequest("album", "c", "Album C"), Rect(12f, 300f, 160f, 450f)) }
        advance(80)
        compose.runOnIdle { AppUi.closeOverlay() }
        advance(1200)
        compose.onNodeWithText("Artist A").assertExists()
        compose.onNodeWithText("Album C").assertDoesNotExist()
        compose.runOnIdle { AppUi.closeOverlay() }
        advance(1200)
        compose.onNodeWithText("Root fixture").assertExists()
    }

    @Test fun tabScrollPositionSurvivesNavigationAndReducedMotion() {
        AppUi.selectTab(AppUi.HOME)
        compose.setContent { KetvoiceTheme(useDark = false) {
            NavigationPresentation(AppUi.tab.intValue to AppUi.snapshot()) { tab, _ ->
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("tab-list:$tab"), state = list) {
                    items(100) { row -> com.ketvoice.app.ui.common.Text("Tab $tab row $row", modifier = Modifier.height(48.dp)) }
                }
            }
        } }
        compose.onNodeWithTag("tab-list:0").performScrollToIndex(35)
        compose.runOnIdle { AppUi.selectTab(AppUi.EXPLORER) }
        compose.onNodeWithTag("tab-list:1").performScrollToIndex(12)
        compose.runOnIdle { AppUi.selectTab(AppUi.HOME) }
        compose.onNodeWithText("Tab 0 row 35").assertIsDisplayed()
        compose.onNodeWithText("Tab 0 row 0").assertDoesNotExist()
    }

    @Test fun presentationIsReversibleAndUncomposesAfterClosing() {
        AppSettings.reduceAnimations.value = false
        AppSettings.lyricsMode.value = 2
        val track = Track("qa:presented", "New player presentation", "Ketvoice", "Preview", durationMs = 180_000)
        state.value = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("fixture"), index = 0, durationMs = 180_000)
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { PlayerPresentation(visible, Rect(10f, 740f, 390f, 794f), Modifier.fillMaxSize()) {
            KetvoiceTheme(useDark = true) {
                Box(Modifier.fillMaxSize().testTag("presented-player")) {
                    FullPlayerScreen(onDismiss = { visible = false })
                }
            }
        } }
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(220)
        image("player-reveal-half")
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("presented-player").assertExists()
        image("player-reveal-open")
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("presented-player").assertDoesNotExist()
    }

    @Test fun revealBoundsHaveExactEndpointsAndMonotonicExpansion() {
        val origin = Rect(12f, 740f, 388f, 794f)
        assertEquals(origin, presentationBounds(origin, 400f, 900f, 0f))
        assertEquals(Rect(0f,0f,400f,900f), presentationBounds(origin, 400f,900f,1f))
        val a = presentationBounds(origin,400f,900f,.2f)
        val b = presentationBounds(origin,400f,900f,.8f)
        assertTrue(b.top < a.top && b.width > a.width && b.height > a.height)
    }

    @Test fun statsInfoIsStrictlyOptIn() {
        compose.setContent { KetvoiceTheme(useDark = true) { AudioStatsInfo() } }
        compose.onNodeWithText("Stats info").assertDoesNotExist()
        compose.runOnIdle { AppSettings.techStats.value = true }
        compose.onNodeWithText("Stats info").assertExists()
    }

    @Test fun undoRemovesOnlyTheInsertedOccurrence() {
        val track = Track("qa:repeat", "Repeat", "Artist", "Album")
        state.value = PlayerUiState(queue = listOf(track,track), queueEntryIds = listOf("current","duplicate"), index = 0)
        val added = Player.queueWithUndo(track, true)!!
        assertEquals(3, state.value.queue.size)
        assertTrue(Player.undoQueuedEntry(added))
        assertEquals(listOf("current","duplicate"),state.value.queueEntryIds)
        assertFalse(Player.undoQueuedEntry("current"))
    }

    @Test fun horizontalSwipeOffersUndoAndVerticalScrollDoesNotQueue() {
        val track = Track("qa:swipe", "Swipe title", "Artist", "Album")
        state.value = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("current"), index = 0)
        compose.setContent { KetvoiceTheme(useDark = false) {
            Column {
                com.ketvoice.app.ui.common.SwipeTrackActions(track, Modifier.testTag("swipe-row")) {
                    Box(Modifier.fillMaxWidth().height(80.dp)) { com.ketvoice.app.ui.common.Text("Swipe title") }
                }
                androidx.compose.material3.SnackbarHost(com.ketvoice.app.ui.common.TrackActionFeedback.snackbar)
            }
        } }
        compose.onNodeWithTag("swipe-row").performTouchInput { swipeUp() }
        assertEquals(1,state.value.queue.size)
        compose.onNodeWithTag("swipe-row").performTouchInput { swipeRight() }
        compose.waitUntil(3000) { state.value.queue.size == 2 }
        compose.onNodeWithText("Annuler").performClick()
        compose.waitUntil(3000) { state.value.queue.size == 1 }
        assertEquals(listOf("current"),state.value.queueEntryIds)
    }

    @Test fun glassChoicePreviewDoesNotPersistUntilApplied() {
        compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.settings.GlassAppearancePreview {} } }
        compose.onNodeWithText("Dépoli").performClick()
        assertEquals(0, AppSettings.glassStyle.value)
        compose.onNodeWithText("Appliquer").performClick()
        assertEquals(1, AppSettings.glassStyle.value)
    }

    @Test fun downloadPausedStateAndMenuRenderReadableActions() {
        val track = Track("qa:menu", "A real title", "Artist", "Album")
        compose.setContent { KetvoiceTheme(useDark = false) {
            Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F8))) {
                com.ketvoice.app.ui.sub.DownloadTaskControls(track, DownloadTask(DownloadPhase.Paused, 30_000, 80_000), true)
                TrackMenuSheet(track) {}
            }
        } }
        compose.onNodeWithText("Reprendre").assertExists()
        compose.onNodeWithText("Lire ensuite").assertExists()
        compose.onNodeWithText("Télécharger").assertExists()
        image("download-paused-and-quick-actions")
    }
}
