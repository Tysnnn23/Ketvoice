package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import com.ketvoice.app.ui.common.ContextMenuHost
import com.ketvoice.app.ui.common.ContextMenus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * 7.4 host renders (software Skia, not a phone): Downloads and Favourites with their bigger cover that scrolls away
 * without fading, the Downloads menus, the airier song rows and Home without the week's discoveries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Pages74HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val known get() = ListeningSeam.live(ListeningHistory.knownTracks)
    private val recent get() = ListeningSeam.live(ListeningHistory.tracks)
    private val counts get() = ListeningSeam.live(ListeningHistory.playCounts)
    private val colours = listOf(0xFFB8473A, 0xFF2F6F8F, 0xFFD9A441, 0xFF4F7A4A, 0xFF6B4C9A, 0xFF1F1F24).map { it.toInt() }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        clear()
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).allowHardware(false).crossfade(false).build())
    }

    @After fun clear() {
        coil.Coil.reset()
        saved.value = emptyList(); known.value = emptyList(); recent.value = emptyList(); counts.value = emptyMap()
        AppSettings.favoriteIds.value = emptySet()
        ContextMenus.request.value = null
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun cover(index: Int): String {
        val file = File(compose.activity.cacheDir, "p74-cover-$index.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colours[index % colours.size]) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return file.toURI().toString()
    }

    private fun track(i: Int) = Track("fixture:p$i", listOf("Nuit blanche", "Aube", "Kilomètres", "Retour", "Sable", "Marée",
        "Lumière", "Orage", "Silence", "Vertige", "Écume", "Minuit")[i % 12], listOf("Ayla", "Noé", "Mira", "Iris")[i % 4],
        "Album $i", durationMs = 214_000, coverUrl = cover(i))

    private fun downloads(n: Int) = (0 until n).map { i ->
        val t = track(i)
        Downloader.Item(t, File(compose.activity.cacheDir, t.id.replace(':', '_') + ".bin").apply {
            writeText("host fixture"); setLastModified(1_700_000_000_000L - i * 1000L)
        }, if (i == 0) "FLAC · Sans perte" else "AAC · 256 kbps", 8_388_608)
    }

    private fun image(name: String) {
        repeat(4) { Thread.sleep(150); compose.waitForIdle() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/pages-74/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    private fun show(dark: Boolean = true, large: Boolean = false, content: @Composable () -> Unit) = compose.setContent {
        KetvoiceTheme(useDark = dark) {
            CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null,
                LocalLargeScreen provides (if (large) LargeScreen.info(1280f, 800f, false, false) else null)) {
                Box(Modifier.fillMaxSize()) { content(); ContextMenuHost() }
            }
        }
    }

    private fun widthDp(tag: String): Float {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return node.boundsInRoot.width / compose.activity.resources.displayMetrics.density
    }

    /** A short drag up the list: the header moves with it. */
    private fun dragUp(list: String, by: Float) {
        compose.onNodeWithTag(list).performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, centerY + by / 2), androidx.compose.ui.geometry.Offset(centerX, centerY - by / 2), 600)
        }
        compose.waitForIdle()
    }

    @Test fun theRowMoreOpensTheTitlesGlassMenuAndTheBarMoreThePagesMenu() {
        saved.value = downloads(4)
        show { com.ketvoice.app.ui.sub.DownloadsScreen() }
        compose.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-more"))
        compose.onAllNodesWithTag("download-more")[0].performClick()
        compose.waitForIdle()
        // The same glass menu as a long press: the title's own actions, « Sélectionner » and removing the file.
        assertEquals(saved.value[0].track, ContextMenus.request.value?.track)
        compose.onNodeWithTag("download-select").assertExists()
        compose.onNodeWithTag("download-delete").assertExists()
        compose.onNodeWithText("Lire ensuite").assertExists()
        image("downloads-row-more-dark")
        ContextMenus.request.value = null
        compose.waitForIdle()
        compose.onNodeWithTag("downloads-list").performScrollToIndex(0)
        compose.onNodeWithTag("downloads-menu").performClick()
        compose.onNodeWithTag("downloads-menu-select").assertIsDisplayed()
        compose.onNodeWithTag("downloads-sort:Title").assertIsDisplayed()
        compose.onAllNodesWithText("Préparer une capsule hors ligne").assertCountEquals(2)
        assertNull("the bar's « … » is the page's menu, not a title's", ContextMenus.request.value)
        image("downloads-bar-more-dark")
        compose.onNodeWithTag("downloads-menu-select").performClick()
        compose.onNodeWithTag("downloads-selection-count").assertExists()
    }

    @Test fun theDownloadsCoverIsBigAndScrollsAwayWithoutShrinkingOrFading() {
        saved.value = downloads(12)
        show { com.ketvoice.app.ui.sub.DownloadsScreen() }
        assertEquals("two thirds of a 400 dp phone", 264f, widthDp("downloads-cover"), 1.5f)
        val before = compose.onNodeWithTag("downloads-cover").fetchSemanticsNode().boundsInRoot
        image("downloads-top-dark")
        dragUp("downloads-list", 240f)
        val after = compose.onNodeWithTag("downloads-cover").fetchSemanticsNode().boundsInRoot
        assertTrue("the header moved with the list", after.top < before.top - 50f)
        assertEquals("no shrink while scrolling", before.width, after.width, 1f)
        image("downloads-scrolled-dark")
        compose.onNodeWithTag("downloads-list").performScrollToNode(hasText("Minuit"))
        image("downloads-rows-dark")
    }

    @Test fun theFavouritesCoverIsBigAndScrollsAwayWithoutShrinking() {
        val tracks = (0 until 12).map(::track)
        AppSettings.favoriteIds.value = tracks.map { it.id }.toCollection(LinkedHashSet())
        show(dark = false) { com.ketvoice.app.ui.library.LikedSongsScreen(tracks) }
        assertEquals(264f, widthDp("liked-cover"), 1.5f)
        val before = compose.onNodeWithTag("liked-cover").fetchSemanticsNode().boundsInRoot
        image("favourites-top-light")
        dragUp("liked-list", 240f)
        val after = compose.onNodeWithTag("liked-cover").fetchSemanticsNode().boundsInRoot
        assertTrue(after.top < before.top - 50f)
        assertEquals("no shrink while scrolling", before.width, after.width, 1f)
        image("favourites-scrolled-light")
        compose.onNodeWithTag("liked-list").performScrollToIndex(8)
        image("favourites-rows-light")
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun onATabletTheCoverSitsBesideTheTextAndStaysBounded() {
        val tracks = (0 until 8).map(::track)
        AppSettings.favoriteIds.value = tracks.map { it.id }.toCollection(LinkedHashSet())
        show(large = true) { com.ketvoice.app.ui.library.LikedSongsScreen(tracks) }
        assertEquals(320f, widthDp("liked-cover"), 1.5f)
        image("favourites-tablet-dark")
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun onATabletDownloadsKeepABoundedCover() {
        saved.value = downloads(8)
        show(large = true) { com.ketvoice.app.ui.sub.DownloadsScreen() }
        assertTrue(widthDp("downloads-cover") <= 320.5f)
        image("downloads-tablet-dark")
    }

    @Test fun homeHasNoWeeklyDiscoveries() {
        val tracks = (0 until 8).map(::track)
        known.value = tracks; recent.value = tracks.take(6); counts.value = tracks.associate { it.id to 5 }
        show { com.ketvoice.app.ui.home.HomeScreen() }
        compose.onNodeWithText("Découvertes de la semaine").assertDoesNotExist()
        compose.onNodeWithTag("home-discoveries").assertDoesNotExist()
        image("home-dark")
    }
}
