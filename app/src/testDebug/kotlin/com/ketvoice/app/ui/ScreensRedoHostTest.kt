package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.FullPlayerScreen
import com.ketvoice.app.ui.player.PlayerPage
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
 * The screens redone for Ket 27 (2026-11-16): the full-bleed player, Home in the manner of Apple Music,
 * Library and Downloads. Drawn in software Skia on the host with painted fixture covers: layout,
 * semantics and pixels, not a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensRedoHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>

    private var lyricsModeBefore = 0

    @Before fun setup() {
        lyricsModeBefore = AppSettings.lyricsMode.value
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.animatedArtwork.value = false
        paintedCovers()
    }

    @After fun reset() {
        AppSettings.lyricsMode.value = lyricsModeBefore
        coil.Coil.reset()
        player.value = PlayerUiState()
        AppSettings.reduceAnimations.value = false
        AppSettings.animatedArtwork.value = true
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    /** Every image request answers with a painted cover whose colours follow its address. */
    private fun paintedCovers() {
        val palettes = listOf(intArrayOf(0xFFE8505B.toInt(), 0xFFF9D56E.toInt()), intArrayOf(0xFF3A7BD5.toInt(), 0xFF00D2FF.toInt()),
            intArrayOf(0xFF7F00FF.toInt(), 0xFFE100FF.toInt()), intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt()),
            intArrayOf(0xFFF7971E.toInt(), 0xFFFFD200.toInt()), intArrayOf(0xFF232526.toInt(), 0xFF8E9EAB.toInt()))
        val cache = HashMap<Int, BitmapDrawable>()
        fun cover(index: Int) = cache.getOrPut(index) {
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val (a, b) = palettes[index].let { it[0] to it[1] }
            canvas.drawRect(0f, 0f, 300f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, a, b, Shader.TileMode.CLAMP) })
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
            canvas.drawCircle(210f, 96f, 58f, ink)
            canvas.drawRect(36f, 190f, 160f, 206f, ink)
            canvas.drawRect(36f, 220f, 240f, 236f, ink)
            BitmapDrawable(compose.activity.resources, bitmap)
        }
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val key = chain.request.data.toString()
                coil.request.SuccessResult(cover(Math.floorMod(key.hashCode(), palettes.size)), chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    private fun track(id: String, title: String = "Titre $id", artist: String = "Artiste $id") =
        Track("fixture:$id", title, artist, "Album $artist", coverUrl = "fixture://cover/$id", durationMs = 200_000)

    // ---------------------------------------------------------------- the player

    private fun playing(vararg tracks: Track) {
        player.value = PlayerUiState(queue = tracks.toList(), queueEntryIds = tracks.map { it.id }, index = 0,
            durationMs = 200_000, isPlaying = true, playWhenReady = true)
    }







    @Test @Config(qualifiers = "w900dp-h420dp-mdpi")
    fun aLandscapeWindowKeepsTheArtworkBesideTheControls() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        playing(track("wide", "Paysage"))
        compose.setContent { KetvoiceTheme(useDark = true) { FullPlayerScreen(onDismiss = {}) } }
        compose.onNodeWithTag("player-glass-platter").assertDoesNotExist()
        compose.onNodeWithTag("player-hero-artwork").assertDoesNotExist()
        compose.onNodeWithText("Paysage", useUnmergedTree = true).assertExists()
    }


    // ---------------------------------------------------------------- Home

    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    private val counts get() = ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>

    private fun history() {
        val tracks = (1..6).map { track("a$it", "Alpha $it", "Alpha") } + (1..5).map { track("b$it", "Beta $it", "Beta") } +
            (1..10).map { track("s$it", "Solo $it", "Solo $it") }
        known.value = tracks
        recent.value = tracks.take(9)
        counts.value = tracks.associate { it.id to if (it.artist == "Alpha") 5 else if (it.artist == "Beta") 3 else 1 }
    }

    @Test fun homeLeadsWithLargeCardsAndHandsItsTitleToTheBar() {
        history()
        try {
            compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.home.HomeScreen() } }
            compose.onNodeWithTag("home-greeting").assertIsDisplayed()
            compose.onNodeWithTag("home-inline-title").assertDoesNotExist()
            // The owner removed the For you card and the shortcut tiles (5.9.0): they stay away.
            compose.onNodeWithTag("for-you-mix").assertDoesNotExist()
            compose.onNodeWithTag("for-you-resume").assertDoesNotExist()
            compose.onAllNodesWithTag("home-shortcut").assertCountEquals(0)
            image("redo-home-top-dark")
            // Scrolled to the mixes, the greeting has left: the bar names the page in small.
            compose.onNodeWithTag("home-list").performScrollToIndex(3)
            compose.onNodeWithTag("home-inline-title").assertExists()
            val card = compose.onAllNodesWithTag("home-mix").onFirst().fetchSemanticsNode().boundsInRoot
            assertEquals("a large card", com.ketvoice.app.ui.home.HomeLayout.featureWidth.value, card.width, 1f)
            assertTrue("taller than wide", card.height > card.width)
            assertEquals("aligned with the page's margin", 16f, card.left, 1f)
            image("redo-home-mixes-dark")
            compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-recent-carousel"))
            compose.onNodeWithTag("home-recent-carousel").assertIsDisplayed()
        } finally { known.value = emptyList(); recent.value = emptyList(); counts.value = emptyMap() }
    }

    @Test fun homeLightKeepsTheSameCards() {
        history()
        try {
            compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.home.HomeScreen() } }
            compose.onNodeWithTag("home-list").performScrollToIndex(3)
            image("redo-home-mixes-light")
        } finally { known.value = emptyList(); recent.value = emptyList(); counts.value = emptyMap() }
    }

    @Test fun theTitleHandoverAndTheHeaderRiseFollowTheScroll() {
        val home = com.ketvoice.app.ui.home.HomeLayout
        assertEquals(0f, home.collapse(0, 0, 64f), 0f)
        assertEquals(.5f, home.collapse(0, 32, 64f), .001f)
        assertEquals(1f, home.collapse(0, 400, 64f), 0f)
        assertEquals(1f, home.collapse(3, 0, 64f), 0f)
        assertEquals("at the bottom edge", 0f, home.reveal(900f, 900f, 140f), 0f)
        assertEquals(.5f, home.reveal(830f, 900f, 140f), .001f)
        assertEquals("well inside the window", 1f, home.reveal(100f, 900f, 140f), 0f)
        assertEquals(1f, home.reveal(Float.NaN, 900f, 140f), 0f)
    }

    // ---------------------------------------------------------------- Library and Downloads

    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>

    private fun file(track: Track, format: String, bytes: Long, age: Long): Downloader.Item {
        val file = File(compose.activity.cacheDir, track.id.replace(':', '_') + ".bin").apply {
            writeText("host fixture"); setLastModified(1_700_000_000_000L + age)
        }
        return Downloader.Item(track, file, format, bytes)
    }

    private fun offline() = listOf(
        file(track("d1", "Bravo", "Zed"), "FLAC · Sans perte", 31_457_280, 1000),
        file(track("d2", "Alpha", "Abel"), "MP3 · Haute qualité", 8_388_608, 3000),
        file(track("d3", "Charlie", "Abel"), "DASH · FLAC", 41_943_040, 2000),
        file(track("d4", "Delta", "Mira"), "FLAC · Sans perte", 21_457_280, 500),
        file(track("d5", "Echo", "Noor"), "AAC", 6_457_280, 400))

    @Test fun libraryAlbumsAreACoverGridSortedWithGlassPills() {
        recent.value = (1..6).map { track("l$it", "Piste $it", "Artiste ${it % 3}") }
        try {
            compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.library.LibraryScreen() } }
            compose.onNodeWithTag("library-inline-title").assertDoesNotExist()
            image("redo-library-root-dark")
            compose.onNode(hasText("Albums") and hasClickAction()).performClick()
            compose.onNodeWithTag("library-filters").assertExists()
            compose.onNodeWithTag("library-albums").assertExists()
            compose.onNode(hasText("Albums") and hasAnyAncestor(hasTestTag("library-filters"))).assertIsSelected()
            // The orders are pills; choosing one fills it, no menu opens.
            compose.onNode(hasText("Artiste A–Z") and hasAnyAncestor(hasTestTag("library-sort"))).performClick().assertIsSelected()
            compose.onNode(hasText("Récents") and hasAnyAncestor(hasTestTag("library-sort"))).assertIsNotSelected()
            image("redo-library-albums-dark")
            compose.onNode(hasText("Artistes") and hasAnyAncestor(hasTestTag("library-filters"))).performClick()
            compose.onNodeWithTag("library-artist-grid").assertExists()
            image("redo-library-artists-dark")
            compose.onNode(hasText("Titres") and hasAnyAncestor(hasTestTag("library-filters"))).performClick()
            compose.onNodeWithTag("library-list").performScrollToIndex(4)
            compose.onNodeWithTag("library-inline-title").assertExists()
            image("redo-library-titles-scrolled-dark")
        } finally { recent.value = emptyList() }
    }

    @Test fun downloadsShowAlbumsAndArtistsAsCoversAndKeepSelectionAndBadges() {
        saved.value = offline()
        try {
            compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.sub.DownloadsScreen() } }
            compose.onNodeWithTag("downloads-mosaic").assertIsDisplayed()
            image("redo-downloads-top-light")
            compose.onNode(hasText("Albums") and hasAnyAncestor(hasTestTag("downloads-view"))).performScrollTo().performClick()
            compose.onAllNodesWithTag("saved-download").assertCountEquals(0)
            val cells = compose.onAllNodesWithTag("download-cover-cell").fetchSemanticsNodes().map { it.boundsInRoot }
            assertTrue("a grid of covers (${cells.size})", cells.size >= 2)
            assertEquals("two on a line", cells[0].top, cells[1].top, 1f)
            image("redo-downloads-albums-light")
            // Selecting ticks a whole album from its cover; nothing is deleted without the alert.
            compose.onNodeWithText("Sélectionner").performClick()
            compose.onAllNodesWithTag("download-cover-cell").onFirst().performClick()
            compose.onNodeWithContentDescription("Supprimer").performScrollTo().performClick()
            compose.onNodeWithTag("downloads-delete-alert").assertExists()
            compose.onNode(hasText("Annuler") and hasAnyAncestor(hasTestTag("downloads-delete-alert"))).performClick()
            assertEquals(5, saved.value.size)
            assertTrue(saved.value.all { it.file.exists() })
            compose.onNodeWithText("Annuler").performClick()
            compose.onNode(hasText("Artistes") and hasAnyAncestor(hasTestTag("downloads-view"))).performScrollTo().performClick()
            compose.onNodeWithText("Abel").performScrollTo().assertIsDisplayed()
            image("redo-downloads-artists-light")
            compose.onNodeWithText("Abel").performClick()
            compose.onAllNodesWithTag("saved-download").assertCountEquals(2)
        } finally { saved.value = emptyList() }
    }

    @Test fun downloadsHeaderShrinksAsTheListScrolls() {
        saved.value = offline() + (6..14).map { file(track("e$it", "Titre $it", "Artiste $it"), "FLAC", 10_000_000, it.toLong()) }
        try {
            compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.sub.DownloadsScreen() } }
            val before = compose.onNodeWithTag("downloads-mosaic").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("downloads-list").performTouchInput { swipeUp(startY = bottom - 40f, endY = bottom - 240f) }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("downloads-mosaic").fetchSemanticsNode().boundsInRoot
            assertTrue("the header shrinks (${before.width} → ${after.width})", after.width < before.width - 1f)
            image("redo-downloads-scrolled-dark")
            // The swipe actions and the separated-music badge belong to the rows, which keep them.
            compose.onAllNodesWithTag("saved-download").onFirst().assertExists()
        } finally { saved.value = emptyList() }
    }

    @Test fun downloadRowsSpanTheLineOnAPhoneAndHalfOfItOnAWidePane() {
        val layout = com.ketvoice.app.ui.sub.DownloadsLayout
        assertEquals(2, layout.rowSpan(2)); assertEquals(1, layout.rowSpan(1)); assertEquals(3, layout.rowSpan(6))
        val scale = { c: Float -> com.ketvoice.app.ui.common.heroShrinkScale(c) }
        assertEquals(1f, scale(0f), 0f); assertEquals(.86f, scale(1f), .001f)
        assertEquals(1f, scale(Float.NaN), 0f)
    }
}
