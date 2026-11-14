package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.sub.AlbumPage
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

/** Native software fixtures, not screenshots or playback from physical hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryRedesignHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val active get() = Downloader.activeTracks as MutableStateFlow<Map<String, Track>>
    private val busy get() = Downloader.busy as MutableStateFlow<Set<String>>
    private val tasks get() = Downloader.tasks as MutableStateFlow<Map<String, DownloadTask>>

    @Before fun setup() {
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        AppSettings.albumHeroCanvas.value = false
        saved.value = emptyList(); active.value = emptyMap(); busy.value = emptySet(); tasks.value = emptyMap()
    }

    @After fun reset() {
        coil.Coil.reset()
        saved.value = emptyList(); active.value = emptyMap(); busy.value = emptySet(); tasks.value = emptyMap()
        AppSettings.reduceAnimations.value = false
        AppSettings.albumHeroCanvas.value = true
        AppUi.closeOverlays()
    }

    private fun track(id: String, title: String = id) = Track("fixture:$id", title, "Artiste $id", "Album $id", durationMs = 180_000)
    private fun file(track: Track): Downloader.Item {
        val file = File(compose.activity.cacheDir, track.id.replace(':', '_') + ".flac").apply { writeText("host fixture") }
        return Downloader.Item(track, file, "FLAC", 12_582_912)
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

    @Test @Config(qualifiers = "w400dp-h1500dp-mdpi")
    fun transfersAndSavedFilesHaveSeparateRowsAndOneStatusEach() {
        val paused = track("pause", "Un transfert en pause")
        saved.value = listOf(file(track("saved", "Une piste hors ligne")))
        active.value = mapOf(paused.id to paused, saved.value.single().track.id to saved.value.single().track)
        busy.value = setOf(paused.id)
        tasks.value = mapOf(paused.id to DownloadTask(DownloadPhase.Paused, 40_000, 100_000))
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadsScreen() } }
        compose.onNodeWithTag("transfer-download").assertExists()
        compose.onAllNodes(hasText("En pause") and hasAnyAncestor(hasTestTag("transfer-download"))).assertCountEquals(1)
        compose.onNodeWithText("Une piste hors ligne").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("saved-download").assertCountEquals(1)
        image("downloads-redesign-dark")
    }

    @Test fun deletionNeedsConfirmationAndCancelKeepsTheActualFile() {
        val item = file(track("keep", "À conserver"))
        saved.value = listOf(item)
        compose.setContent { KetvoiceTheme(useDark = false) { DownloadsScreen() } }
        compose.onNodeWithTag("download-delete").performScrollTo().performClick()
        compose.onNodeWithText("Supprimer").assertIsDisplayed()
        assertTrue("Opening the confirmation must not delete the file", item.file.exists())
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText("Supprimer").assertDoesNotExist()
        assertTrue("Cancel must keep the offline file, not re-download it", item.file.exists())
        assertEquals(listOf(item), saved.value)
    }

    @Test fun failedTransfersCanBeRemovedWithoutStartingANewDownload() {
        val failed = track("failed", "Transfert échoué")
        active.value = mapOf(failed.id to failed)
        tasks.value = mapOf(failed.id to DownloadTask(DownloadPhase.Failed))
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadsScreen() } }
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performScrollTo().performClick()
        compose.onNodeWithText("Transfert échoué").assertDoesNotExist()
        assertTrue(active.value.isEmpty())
    }

    @Test fun localSearchFiltersAndClearsWithoutChangingOfflineFiles() {
        saved.value = listOf(file(track("a", "Première piste")), file(track("b", "Deuxième piste")))
        compose.setContent { KetvoiceTheme(useDark = false) { DownloadsScreen() } }
        compose.onNode(hasSetTextAction()).performTextInput("Deuxième")
        compose.onNodeWithText("Deuxième piste").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Première piste").assertDoesNotExist()
        assertEquals(2, saved.value.size)
        image("downloads-search-light")
        compose.onNodeWithContentDescription("Effacer").performScrollTo().performClick()
        compose.onAllNodesWithTag("saved-download").assertCountEquals(2)
    }

    @Test fun emptyLibraryDoesNotOfferDeadPlaybackButtons() {
        compose.setContent { KetvoiceTheme(useDark = false) { DownloadsScreen() } }
        compose.onNodeWithText("Aucun téléchargement").assertIsDisplayed()
        compose.onNodeWithText("Lecture").assertDoesNotExist()
        compose.onNodeWithText("Aléatoire").assertDoesNotExist()
        image("downloads-empty-light")
    }

    @Test fun restoredPauseHasAResumeActionEvenWithoutABusySlot() {
        val paused = track("restored")
        active.value = mapOf(paused.id to paused)
        tasks.value = mapOf(paused.id to DownloadTask(DownloadPhase.Paused))
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadsScreen() } }
        compose.onNodeWithText("Reprendre").assertIsDisplayed()
        compose.onNodeWithText("Annuler").assertIsDisplayed()
    }

    @Test fun libraryFiltersPreserveTracksAndOpenWorkingShortcuts() {
        val recent = ListeningHistory.tracks as MutableStateFlow<List<Track>>
        val known = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
        val priorRecent = recent.value
        val priorKnown = known.value
        recent.value = listOf(track("library", "Piste personnelle"))
        known.value = emptyList()
        try {
            compose.setContent { KetvoiceTheme(useDark = false) { com.ketvoice.app.ui.library.LibraryScreen() } }
            compose.onNodeWithContentDescription("Compte").assertIsDisplayed()
            compose.onNodeWithContentDescription("Créer une playlist").performClick()
            assertTrue(AppUi.showCreatePlaylist.value)
            compose.runOnIdle { AppUi.showCreatePlaylist.value = false }
            image("library-optical-light")
            compose.onNode(hasText("Titres") and hasClickAction()).performClick().assertIsSelected()
            compose.onNodeWithText("Piste personnelle").performScrollTo().assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextInput("introuvable")
            compose.onNodeWithText("Piste personnelle").assertDoesNotExist()
            assertEquals(1, recent.value.size)
        } finally {
            recent.value = priorRecent
            known.value = priorKnown
        }
    }

    @Test fun collectionFiltersExposeSelectionAndKeepReadableTargets() {
        var chosen by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { KetvoiceTheme(useDark = true) {
            androidx.compose.foundation.layout.Row {
                com.ketvoice.app.ui.common.CollectionFilter("Albums", chosen, { chosen = true })
            }
        } }
        compose.onNodeWithText("Albums").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Albums").performClick().assertIsSelected()
    }

    @Test fun decodedArtworkFixtureFloatsAsACentredSquare() {
        val cover = File("../ketvoice-banner.png")
        assertTrue("Use the repository's canonical image as a local software fixture", cover.isFile)
        // Supply a decoded drawable: Windows paths are not Android file URIs.
        // This verifies image layout, not the live provider or Android disk pipeline.
        val drawable = BitmapDrawable(compose.activity.resources, BitmapFactory.decodeFile(cover.absolutePath))
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                coil.request.SuccessResult(drawable, chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build())
        val track = track("image", "Piste de démonstration")
        compose.setContent { KetvoiceTheme(useDark = false) {
            AlbumPage(CatalogRequest("album", "fixture:image", "Ketvoice — aperçu local"),
                CatalogPage("Ketvoice — aperçu local", "Artiste", "fixture:artwork", listOf(track), "2026"), listOf(track), false, {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-artwork-loaded").fetchSemanticsNodes().isNotEmpty() }
        val art = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
        assertEquals("The artwork floats centred", 400f / 2f, (art.left + art.right) / 2f, 1.5f)
        assertEquals("The artwork is a square", art.width, art.height, 1f)
        assertTrue("The artwork is large without filling the width", art.width in 160f..400f * .8f)
        image("album-redesign-local-artwork-light")
    }

    @Test @Config(qualifiers = "w900dp-h480dp-mdpi")
    fun landscapeAlbumKeepsItsIdentityBesideArtworkAndItsTracksScrollable() {
        val tracks = List(30) { track("$it", "Piste $it") }
        compose.setContent { KetvoiceTheme(useDark = true) {
            AlbumPage(CatalogRequest("album", "fixture:wide", "Album paysage"),
                CatalogPage("Album paysage", "Artiste", null, tracks, "2026"), tracks, false, {})
        } }
        compose.onNodeWithText("Lecture").assertIsDisplayed()
        val art = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithText("Lecture").fetchSemanticsNode().boundsInRoot
        assertTrue("Landscape actions belong beside artwork", play.left >= art.right)
        image("album-redesign-landscape-dark")
        compose.onNodeWithTag("album-list").performScrollToIndex(10)
        compose.onNodeWithText("Piste 9").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun narrowAlbumKeepsLongMetadataAndControlsSeparateAtLargeFontScale() {
        val track = track("narrow", "Piste lisible")
        compose.setContent { KetvoiceTheme(useDark = false) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                AlbumPage(CatalogRequest("album", "fixture:narrow", "Un album au titre particulièrement long"),
                    CatalogPage("Un album au titre particulièrement long", "Un artiste au nom très long", null, listOf(track), "2026"),
                    listOf(track), false, {})
            }
        } }
        compose.onNodeWithText("Lecture").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aléatoire").assertIsDisplayed()
        val play = compose.onNodeWithText("Lecture").fetchSemanticsNode().boundsInRoot
        val shuffle = compose.onNodeWithText("Aléatoire").fetchSemanticsNode().boundsInRoot
        assertTrue(play.right < shuffle.left || play.bottom < shuffle.top)
        image("album-redesign-narrow-large-font-light")
    }
}
