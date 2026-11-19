package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
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
 * Library › Playlists, a playlist and Downloads in the manner of Apple Music, and the content side of a
 * large window, drawn in software on the host (not on a device): phone 390 × 844 dark and light, tablet
 * standing up (834 × 1194, rail) and lying down (1366 × 1024, sidebar). Covers are made up by the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppleLibraryHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>

    private val names = listOf("Matin calme", "Route de nuit", "Sport", "Pluie d'été", "Concentration", "Dimanche",
        "Soirée", "Voyage", "Années 2000")
    private val tracks = (0 until 24).map {
        Track("qa:apple:$it", "Titre $it", "Artiste ${it % 5}", "Album ${it % 6}", durationMs = 171_000L + it * 9_000L,
            coverUrl = "fixture:cover:${it % 8}")
    }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.reduceDynamicBlur.value = true
        SidebarPrefs.resetForTests()
        pictures()
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.themeMode.value = 0
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
        player.value = PlayerUiState()
        saved.value = emptyList(); recent.value = emptyList()
        PlaylistStore.resetForTest()
        SidebarPrefs.resetForTests()
        coil.Coil.reset()
    }

    // ---- Fixtures ----

    private fun playlists() {
        PlaylistStore.resetForTest()
        kotlinx.coroutines.runBlocking { PlaylistStore.init(compose.activity) }
        val lists = names.mapIndexed { i, name ->
            LocalPlaylist(id = "qa:pl$i", name = name, pinned = i == 0, createdAt = 1_000L * (names.size - i),
                entries = tracks.drop(i).take(4 + i * 2).map { PlaylistEntry(id = "e$i:${it.id}", title = it.title, artist = it.artist, track = it) })
        } + LocalPlaylist(id = PlaylistRules.sharedLocalId("qa-shared"), name = "Road trip", createdAt = 1L,
            entries = tracks.take(6).map { PlaylistEntry(id = "s:${it.id}", title = it.title, artist = it.artist, track = it) })
        @Suppress("UNCHECKED_CAST")
        (PlaylistStore.lists as MutableStateFlow<List<LocalPlaylist>>).value = lists
    }

    private fun downloads() {
        saved.value = tracks.take(10).mapIndexed { i, track ->
            val file = File(compose.activity.cacheDir, track.id.replace(':', '_') + ".bin").apply {
                writeText("host fixture"); setLastModified(1_700_000_000_000L + i * 1000L)
            }
            Downloader.Item(track, file, if (i % 3 == 0) "FLAC · Sans perte" else "AAC · Haute qualité", 6_000_000L + i * 900_000L,
                hasLyrics = i % 2 == 0, hasCanvas = i % 4 == 0)
        }
    }

    @Composable
    private fun NoOverscroll(content: @Composable () -> Unit) =
        CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null, content = content)

    /** A page alone, with the large-screen context the root would give it on this window. */
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

    /** The whole app, sidebar or rail included. */
    private fun root(dark: Boolean) {
        AppSettings.themeMode.value = if (dark) 2 else 1
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
    }

    private fun openPlaylists() {
        com.ketvoice.app.ui.adaptive.LargeScreenNav.librarySection.value = 1
        AppUi.selectTab(AppUi.LIBRARY)
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** How many nodes tagged [tag] share the top of the first one: the columns of a grid. */
    private fun columns(tag: String): Int {
        val cells = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsInRoot }
        return cells.count { kotlin.math.abs(it.top - cells.first().top) < 2f }
    }

    // ---- Phone ----

    @Test fun playlistsOnAPhoneAreAListWithNewPlaylistFirstThenAGridOfTwo() {
        playlists()
        page(true) { com.ketvoice.app.ui.library.LibraryScreen() }
        compose.onNodeWithText("Playlists").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("library-title").assertTextEquals("Playlists")
        compose.onNodeWithTag("playlists-new").assertIsDisplayed()
        compose.onNodeWithTag("playlists-import").assertIsDisplayed()
        assertTrue("New playlist leads the list", bounds("playlists-new").bottom <= compose.onAllNodesWithTag("playlist-row")
            .fetchSemanticsNodes().first().boundsInRoot.top + 1f)
        compose.onAllNodesWithText("Playlist · Vous").onFirst().assertExists()
        assertEquals("one row per line on a phone", 1, columns("playlist-row"))
        image("phone/playlists-list-dark")
        // The bar's « … »: view, order, filter, join and file import.
        compose.onNodeWithTag("playlists-menu").performClick()
        compose.onNodeWithTag("playlists-sort:Title").assertExists()
        compose.onNodeWithTag("playlists-filter:Shared").assertExists()
        compose.onNodeWithText("Rejoindre avec un code").assertExists()
        compose.onNodeWithTag("playlists-view-grid").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("library-playlist-grid").assertExists()
        assertEquals("two covers per line on a phone", 2, columns("playlist-cell"))
        image("phone/playlists-grid-dark")
        // Filtering shows the shared one alone, the stored playlists unchanged.
        compose.onNodeWithTag("playlists-menu").performClick()
        compose.onNodeWithTag("playlists-filter:Shared").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("playlist-cell").assertCountEquals(1)
        assertEquals(names.size + 1, PlaylistStore.lists.value.size)
    }

    @Test fun playlistsListLight() {
        playlists()
        page(false) { com.ketvoice.app.ui.library.LibraryScreen() }
        compose.onNodeWithText("Playlists").performClick()
        compose.waitForIdle()
        image("phone/playlists-list-light")
    }

    @Test fun aPlaylistOnAPhoneIsCentredWithPlayAndShuffleAndAFooter() {
        playlists()
        AppUi.playlistId.value = "qa:pl3"
        page(true) { com.ketvoice.app.ui.library.PlaylistScreen() }
        val cover = bounds("playlist-cover")
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertEquals("the artwork is centred", width / 2f, cover.center.x, 2f)
        val play = bounds("playlist-play"); val shuffle = bounds("playlist-shuffle")
        assertEquals("Play and Shuffle side by side", play.top, shuffle.top, 1f)
        assertTrue(play.right < shuffle.left)
        compose.onNodeWithText("Vous").assertIsDisplayed()
        image("phone/playlist-dark")
        compose.onNodeWithTag("playlist-tracks").performScrollToKey("playlist-footer")
        // 10 titles of 3:42 … 5:03: « 10 titres, 43 min ».
        val count = PlaylistStore.lists.value.first { it.id == "qa:pl3" }.entries.size
        compose.onNodeWithTag("playlist-footer").assertTextContains("$count titres,", substring = true)
        image("phone/playlist-footer-dark")
    }

    @Test fun aPlaylistOnAPhoneLight() {
        playlists()
        AppUi.playlistId.value = PlaylistRules.sharedLocalId("qa-shared")
        page(false) { com.ketvoice.app.ui.library.PlaylistScreen() }
        compose.onNodeWithTag("shared-badge").assertExists()
        image("phone/playlist-shared-light")
    }

    @Test fun downloadsOnAPhoneListRowsWithTheirBadgeAndStorage() {
        downloads()
        page(true) { com.ketvoice.app.ui.sub.DownloadsScreen() }
        compose.onNodeWithTag("downloads-title").assertIsDisplayed()
        compose.onNodeWithTag("downloads-summary").assertIsDisplayed()
        image("phone/downloads-dark")
        compose.onNodeWithTag("downloads-list").performScrollToKey(saved.value[0].track.id)
        compose.onAllNodesWithTag("download-badge", useUnmergedTree = true).onFirst().assertExists()
        compose.onNodeWithTag("downloads-list").performScrollToKey(saved.value[4].track.id)
        image("phone/downloads-rows-dark")
    }

    @Test fun downloadsOnAPhoneLight() {
        downloads()
        page(false) { com.ketvoice.app.ui.sub.DownloadsScreen() }
        image("phone/downloads-light")
    }

    // ---- Tablets ----

    private fun tabletPlaylists(dark: Boolean, expected: Int, name: String) {
        playlists()
        root(dark)
        openPlaylists()
        compose.onNodeWithTag("library-title").assertTextEquals("Playlists")
        image("$name-list")
        compose.onNodeWithTag("playlists-menu").performClick()
        compose.onNodeWithTag("playlists-view-grid").performClick()
        compose.waitForIdle()
        assertEquals("covers per line", expected, columns("playlist-cell"))
        image("$name-grid")
    }

    @Test @Config(qualifiers = "w834dp-h1194dp-xlarge-port-mdpi")
    fun tabletPortraitPlaylistsHaveFourColumns() {
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        tabletPlaylists(false, 4, "portrait/playlists")
    }

    @Test @Config(qualifiers = "w1366dp-h1024dp-xlarge-land-mdpi")
    fun tabletLandscapePlaylistsHaveSixColumnsAndRowsTwoByTwo() {
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        playlists()
        root(true)
        openPlaylists()
        assertEquals("rows two by two beside the sidebar", 2, columns("playlist-row"))
        image("landscape/playlists-list")
        compose.onNodeWithTag("playlists-menu").performClick()
        compose.onNodeWithTag("playlists-view-grid").performClick()
        compose.waitForIdle()
        assertEquals("covers per line", 6, columns("playlist-cell"))
        image("landscape/playlists-grid")
    }

    private fun tabletPlaylistPage(dark: Boolean, name: String) {
        playlists()
        root(dark)
        AppUi.openPlaylist("qa:pl4")
        compose.waitForIdle()
        val art = bounds("playlist-cover"); val title = bounds("playlist-title")
        assertTrue("the artwork stands beside the title", art.right < title.left)
        compose.onNodeWithTag("playlist-play").assertIsDisplayed()
        image(name)
    }

    @Test @Config(qualifiers = "w834dp-h1194dp-xlarge-port-mdpi")
    fun tabletPortraitPlaylistPage() { org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST); tabletPlaylistPage(false, "portrait/playlist") }

    @Test @Config(qualifiers = "w1366dp-h1024dp-xlarge-land-mdpi")
    fun tabletLandscapePlaylistPage() { org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST); tabletPlaylistPage(true, "landscape/playlist") }

    @Test @Config(qualifiers = "w834dp-h1194dp-xlarge-port-mdpi")
    fun tabletPortraitDownloads() {
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        downloads(); root(false); AppUi.openDownloads(); compose.waitForIdle()
        compose.onNodeWithTag("downloads-summary").assertIsDisplayed()
        image("portrait/downloads")
    }

    @Test @Config(qualifiers = "w1366dp-h1024dp-xlarge-land-mdpi")
    fun tabletLandscapeDownloadsStandTwoByTwo() {
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        downloads(); root(true); AppUi.openDownloads(); compose.waitForIdle()
        assertEquals("rows two by two", 2, columns("saved-download"))
        image("landscape/downloads")
    }

    private fun tabletRootPages(dark: Boolean, folder: String) {
        playlists(); downloads()
        recent.value = tracks.take(12)
        root(dark)
        image("$folder/home")
        AppUi.selectTab(AppUi.LIBRARY); compose.waitForIdle()
        image("$folder/library")
        AppUi.pushOverlay(AppUi.SEARCH); compose.waitForIdle()
        image("$folder/search")
    }

    @Test @Config(qualifiers = "w834dp-h1194dp-xlarge-port-mdpi")
    fun tabletPortraitHomeLibrarySearch() { org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST); tabletRootPages(false, "portrait") }

    @Test @Config(qualifiers = "w1366dp-h1024dp-xlarge-land-mdpi")
    fun tabletLandscapeHomeLibrarySearch() { org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST); tabletRootPages(true, "landscape") }

    @Test @Config(qualifiers = "w834dp-h1194dp-xlarge-port-mdpi")
    fun tabletPortraitAlbum() {
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        val album = tracks.take(12)
        page(false) {
            com.ketvoice.app.ui.sub.AlbumPage(CatalogRequest("album", "qa:album", "Album 0"),
                CatalogPage("Album 0", "Artiste 0", "fixture:cover:2", album, "2026"), album, false, {})
        }
        val art = bounds("album-hero-visual"); val title = bounds("album-title")
        assertTrue("the artwork stands beside the title", art.right < title.left)
        image("portrait/album")
    }

    // ---- Rendering ----

    private fun pictures() {
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val index = chain.request.data.toString().substringAfterLast(':').toIntOrNull() ?: 0
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, cover(index)), chain.request,
                    coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun cover(index: Int): Bitmap {
        val palettes = listOf(
            intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF6DD5ED.toInt()),
            intArrayOf(0xFF41295A.toInt(), 0xFF2F0743.toInt(), 0xFFE96443.toInt()),
            intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt(), 0xFFF5F7A1.toInt()),
            intArrayOf(0xFFFF512F.toInt(), 0xFFDD2476.toInt(), 0xFF3A1C71.toInt()),
            intArrayOf(0xFF1D2B64.toInt(), 0xFFF8CDDA.toInt(), 0xFF8E9EAB.toInt()),
            intArrayOf(0xFF232526.toInt(), 0xFF414345.toInt(), 0xFFC9A227.toInt()),
            intArrayOf(0xFF5A3F37.toInt(), 0xFF2C7744.toInt(), 0xFFDCE35B.toInt()),
            intArrayOf(0xFF614385.toInt(), 0xFF516395.toInt(), 0xFFF0C27B.toInt()))
        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), palettes[index % palettes.size], null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null; paint.color = 0x55FFFFFF
        when (index % 3) {
            0 -> canvas.drawCircle(size * .5f, size * .5f, size * .26f, paint)
            1 -> { paint.strokeWidth = 14f; repeat(6) { canvas.drawLine(0f, it * 60f, size.toFloat(), it * 60f + 90f, paint) } }
            else -> canvas.drawRect(size * .18f, size * .18f, size * .82f, size * .82f, paint.apply { style = Paint.Style.STROKE; strokeWidth = 10f })
        }
        return bitmap
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/apple-library/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
