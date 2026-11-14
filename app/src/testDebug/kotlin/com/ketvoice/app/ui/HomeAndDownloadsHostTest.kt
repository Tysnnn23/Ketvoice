package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.home.HomeMixes
import com.ketvoice.app.ui.home.HomeShortcuts
import com.ketvoice.app.ui.sub.CatalogScreen
import com.ketvoice.app.ui.sub.DownloadsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Host fixtures in software Skia: layout, semantics and state, not a physical device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAndDownloadsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val active get() = Downloader.activeTracks as MutableStateFlow<Map<String, Track>>
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    private val counts get() = ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        clear()
    }

    @After fun clear() {
        coil.Coil.reset()
        saved.value = emptyList(); active.value = emptyMap()
        known.value = emptyList(); recent.value = emptyList(); counts.value = emptyMap()
        AppSettings.favoriteIds.value = emptySet()
        PlaylistStore.resetForTest()
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
        AppUi.catalogRequest.value = null
    }

    private fun track(id: String, title: String, artist: String) =
        Track("fixture:$id", title, artist, "Album de $artist", durationMs = 200_000)

    private fun file(track: Track, format: String, bytes: Long, age: Long): Downloader.Item {
        val file = File(compose.activity.cacheDir, track.id.replace(':', '_') + ".bin").apply {
            writeText("host fixture"); setLastModified(1_700_000_000_000L + age)
        }
        return Downloader.Item(track, file, format, bytes)
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
                parentFile?.mkdirs()
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    private fun library() = listOf(
        file(track("1", "Bravo", "Zed"), "FLAC · Sans perte", 31_457_280, age = 1000),
        file(track("2", "Alpha", "Abel"), "MP3 · Haute qualité", 8_388_608, age = 3000),
        file(track("3", "Charlie", "Abel"), "DASH · FLAC", 41_943_040, age = 2000))

    @Test @Config(qualifiers = "w400dp-h1500dp-mdpi")
    fun downloadsShowASummaryAndSortTheSameFiles() {
        saved.value = library()
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadsScreen() } }
        compose.onNodeWithTag("downloads-summary").assertIsDisplayed()
        compose.onNodeWithText("3 titres", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("downloads-mosaic").assertIsDisplayed()
        compose.onNodeWithText("Sans perte 2").assertIsDisplayed()
        compose.onNodeWithText("Compressé 1").assertIsDisplayed()
        fun order() = listOf("Alpha", "Bravo", "Charlie").sortedBy {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("Most recent file first by default", listOf("Alpha", "Charlie", "Bravo"), order())
        compose.onNode(hasText("Titre") and hasAnyAncestor(hasTestTag("downloads-sort"))).performScrollTo().performClick()
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), order())
        compose.onNode(hasText("Taille") and hasAnyAncestor(hasTestTag("downloads-sort"))).performScrollTo().performClick()
        assertEquals(listOf("Charlie", "Bravo", "Alpha"), order())
        assertEquals("Sorting never changes the stored library", 3, saved.value.size)
        image("downloads-4-11-dark")
    }

    @Test fun artistsViewGroupsOpensAndReturns() {
        saved.value = library()
        compose.setContent { KetvoiceTheme(useDark = false) { DownloadsScreen() } }
        compose.onNode(hasText("Artistes") and hasAnyAncestor(hasTestTag("downloads-view"))).performScrollTo().performClick()
        compose.onAllNodesWithTag("saved-download").assertCountEquals(0)
        compose.onNodeWithText("Abel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Zed").performScrollTo().assertIsDisplayed()
        image("downloads-4-11-artists-light")
        compose.onNodeWithText("Abel").performClick()
        compose.onAllNodesWithTag("saved-download").assertCountEquals(2)
        compose.onNodeWithText("Bravo").assertDoesNotExist()
        compose.onNode(hasText("Artistes") and hasClickAction() and !hasAnyAncestor(hasTestTag("downloads-view")))
            .performScrollTo().performClick()
        compose.onNodeWithText("Zed").performScrollTo().assertIsDisplayed()
    }

    @Test fun transferFiltersOnlyAppearWhileSomethingIsTransferring() {
        saved.value = library()
        compose.setContent { KetvoiceTheme(useDark = false) { DownloadsScreen() } }
        // "Tout" is the first transfer-state chip; the others may sit beyond the row's edge.
        compose.onNodeWithText("Tout").assertDoesNotExist()
        val moving = track("t", "En transfert", "Zed")
        compose.runOnIdle { active.value = mapOf(moving.id to moving) }
        compose.onNodeWithText("Tout").assertExists()
    }

    private fun history(plays: Int) {
        val tracks = (1..6).map { track("a$it", "Alpha $it", "Alpha") } + (1..5).map { track("b$it", "Beta $it", "Beta") } +
            (1..10).map { track("s$it", "Solo $it", "Solo $it") }
        known.value = tracks
        recent.value = tracks.takeLast(4)
        counts.value = tracks.associate { it.id to if (it.artist == "Alpha") plays else if (it.artist == "Beta") 3 else 1 }
    }

    @Test fun mixesWaitForEnoughListeningThenOpenAMixPage() {
        known.value = listOf(track("only", "Seul", "Alpha")); counts.value = mapOf("fixture:only" to 2)
        compose.setContent { KetvoiceTheme(useDark = true) { androidx.compose.foundation.layout.Column { HomeShortcuts(); HomeMixes() } } }
        compose.onNodeWithTag("home-mixes").assertDoesNotExist()
        compose.runOnIdle { history(plays = 5) }
        compose.onNodeWithTag("home-mixes").assertIsDisplayed()
        compose.onNodeWithText("Vos mix").assertIsDisplayed()
        compose.onNodeWithText("Mix Alpha").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("home-mix").fetchSemanticsNodes().size in 1..PersonalMixes.MAX_CARDS)
        compose.onNodeWithTag("home-shortcuts").assertIsDisplayed()
        image("home-mixes-4-11-dark")
        compose.onNodeWithText("Mix Alpha").performClick()
        compose.runOnIdle {
            assertEquals(AppUi.CATALOG, AppUi.overlay.intValue)
            assertEquals(CatalogRequest("mix", "artist:Alpha", "Mix Alpha"), AppUi.catalogRequest.value)
        }
    }

    @Test fun aLocalMixListsItsTracksAndCanBeSavedAsAPlaylist() {
        history(plays = 5)
        kotlinx.coroutines.runBlocking { PlaylistStore.init(compose.activity) }
        compose.setContent { KetvoiceTheme(useDark = false) { CatalogScreen(CatalogRequest("mix", "repeat", "En boucle")) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Alpha 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Solo 1").assertDoesNotExist()
        compose.onNodeWithTag("mix-save").performScrollTo().performClick()
        compose.waitUntil(5000) { PlaylistStore.lists.value.isNotEmpty() }
        val playlist = PlaylistStore.lists.value.single()
        assertEquals("En boucle", playlist.name)
        assertEquals(11, playlist.entries.size)
        assertEquals((1..6).map { "Alpha $it" } + (1..5).map { "Beta $it" }, playlist.entries.map { it.title })
    }
}
