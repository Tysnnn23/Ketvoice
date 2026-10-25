@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ketvoice.app.data.*
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.DownloadCover
import com.ketvoice.app.ui.library.LibraryScreen
import com.ketvoice.app.ui.player.*
import com.ketvoice.app.ui.sub.CapsuleDialog
import com.ketvoice.app.ui.settings.PublisherScreen
import com.ketvoice.app.player.SonicMeter
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import com.ketvoice.app.BuildConfig

class SelectedPolishTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Suppress("UNCHECKED_CAST")
    private fun <T> update(flow: kotlinx.coroutines.flow.StateFlow<T>, value: T) { (flow as MutableStateFlow<T>).value = value }
    private val qa = Track("qa:owned", "Owned instrumental with a deliberately very long title for readability testing", "QA artist", "QA album", albumId = "MPREQA")
    private var oldLanguage = ""
    private var oldReduced = false

    @Before fun prepare() {
        compose.runOnUiThread {
            compose.activity.requestedOrientation = if (BuildConfig.IS_QUEST)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        oldLanguage = AppSettings.language.value
        oldReduced = AppSettings.reduceAnimations.value
        AppSettings.language.value = "Français"
        AppSettings.reduceAnimations.value = false
        update(ListeningHistory.tracks, emptyList())
        update(ListeningHistory.knownTracks, emptyList())
        update(Downloader.items, emptyList())
        update(Downloader.busy, emptySet())
        update(Downloader.progress, emptyMap())
        AppUi.overlay.value = AppUi.NONE
        AppUi.editImportedSong.value = false
        AppSettings.sonicCanvas.value = false
        AppSettings.animatedArtwork.value = true
        update(PublisherSession.allowed, false)
        update(OfflineCapsule.state, CapsuleState())
    }
    @After fun restore() {
        AppSettings.language.value = oldLanguage
        AppSettings.reduceAnimations.value = oldReduced
        update(ListeningHistory.tracks, emptyList())
        update(ListeningHistory.knownTracks, emptyList())
        update(Downloader.items, emptyList())
        update(Downloader.busy, emptySet())
        update(Downloader.progress, emptyMap())
        update(PublisherSession.allowed, false)
        AppUi.editImportedSong.value = false
        AppSettings.animatedArtwork.value = true
        AppSettings.sonicCanvas.value = false
        SonicMeter.observe(false)
    }
    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir("qa-polish"), "$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output = "/sdcard/Download/ketvoice-polish-qa"
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("mkdir -p $output")
        shell("cp ${file.absolutePath} $output/${if (BuildConfig.IS_QUEST) "quest" else "mobile"}-$name.png")
    }

    @Test fun emptyLibraryExplainsHowToStartAndOpensSearch() {
        compose.setContent { KetvoiceTheme(useDark = true) { LibraryScreen() } }
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Rechercher des titres"))
        compose.onNodeWithText("Rechercher des titres").assertIsDisplayed()
        screenshot("empty-library")
        compose.onNodeWithText("Rechercher des titres").performClick()
        compose.runOnIdle { assertEquals(AppUi.SEARCH, AppUi.overlay.value) }
    }

    @Test fun albumsCanBeSortedWithoutLosingTheCards() {
        val tracks = listOf(qa.copy(id="qa:z",album="Zèbre album",albumId="MPREZ"),
            qa.copy(id="qa:e",album="Échos album",albumId="MPREE"),
            qa.copy(id="qa:a",album="Alpha album",albumId="MPREA"))
        update(ListeningHistory.tracks, tracks)
        update(ListeningHistory.knownTracks, tracks)
        compose.setContent { KetvoiceTheme(useDark = true) { LibraryScreen() } }
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Trier"))
        compose.onNodeWithText("Trier").performClick()
        compose.onNodeWithText("Titre A–Z").performClick()
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("library-albums"))
        compose.onNodeWithTag("library-albums").performScrollToIndex(0)
        compose.onNodeWithText("Alpha album").assertIsDisplayed()
        screenshot("sorted-library")
        compose.onNodeWithTag("library-albums").performScrollToIndex(2)
        compose.onNodeWithText("Zèbre album").assertIsDisplayed()
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("library-albums"))
        compose.onNodeWithTag("library-albums").performScrollToIndex(0)
        compose.onNodeWithText("Alpha album").assertIsDisplayed()
    }

    @Test fun downloadOverlayReflectsProgressThenConfirmsARealSavedItem() {
        update(Downloader.busy, setOf(qa.id))
        update(Downloader.progress, mapOf(qa.id to 37))
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadCover(qa, null, 96.dp) } }
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo(.37f, 0f..1f))).assertExists()
        screenshot("download-progress")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            update(Downloader.items, listOf(Downloader.Item(qa, File(context.cacheDir,"qa.wav"), "WAV", 100)))
            update(Downloader.busy, emptySet())
            update(Downloader.progress, emptyMap())
        }

        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithContentDescription("Téléchargé").assertExists()
        screenshot("download-complete")
        compose.mainClock.autoAdvance = true
    }

    @Test fun downloadedItemsDoNotFakeANewCompletion() {
        update(Downloader.items, listOf(Downloader.Item(qa, File(context.cacheDir,"qa.wav"), "WAV", 100)))
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadCover(qa, null, 96.dp) } }
        compose.onNodeWithContentDescription("Téléchargé").assertDoesNotExist()
    }

    @Test fun localArtworkReallySuppliesThePlayerAccent() {
        val cover = File(context.cacheDir, "qa-blue-artwork.png")
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1234CC.toInt()) }
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var sampled: Color? = null
        compose.setContent {
            val ambience = rememberCoverAmbience(cover.toURI().toString())
            SideEffect { sampled = ambience.accent }
            KetvoiceTheme(useDark = true) {
                ArtworkCanvas(qa.copy(coverUrl = cover.toURI().toString()), null, false, Modifier.size(240.dp), depth = true)
            }
        }
        compose.waitUntil(10000) { sampled != null }
        compose.runOnIdle {
            assertTrue(AlbumColors.contrast(sampled!!.toArgb(), 0xFF303038.toInt()) >= 4.5)
            assertTrue(sampled!!.blue > sampled!!.red)
        }
        screenshot("artwork-depth")
        cover.delete()
    }

    @Test fun artworkDepthDoesNotConsumeTheParentSwipe() {
        var dragged = 0f
        compose.setContent {
            KetvoiceTheme(useDark = true) {
                ArtworkCanvas(qa, null, false, Modifier.size(240.dp).testTag("qa-depth").pointerInput(Unit) {
                    detectHorizontalDragGestures { change, amount -> change.consume(); dragged += amount }
                }, depth = true)
            }
        }
        compose.onNodeWithTag("qa-depth").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(dragged < 0f) }
    }

    @Test fun importedAudioEditingIsOfferedOnlyForImportedSongs() {
        update(PublisherSession.allowed, true)
        val imported = qa.copy(id = "kv:upload:11111111-1111-4111-8111-111111111111", addon = "upload")
        var selected by mutableStateOf(imported)
        compose.setContent { KetvoiceTheme(useDark = true) { TrackMenuSheet(selected) {} } }
        compose.onNodeWithText("Modifier l'audio ou la pochette").assertExists()
        compose.runOnIdle { selected = qa.copy(id = "yt:abcdefghijk") }
        compose.onNodeWithText("Modifier l'audio ou la pochette").assertDoesNotExist()
    }

    @Test fun editingScreenRequiresVerifiedPublisherBeforeFilesCanBeSubmitted() {
        AppUi.editImportedSong.value = true
        val imported = qa.copy(id = "kv:upload:11111111-1111-4111-8111-111111111111", addon = "upload")
        compose.setContent { KetvoiceTheme(useDark = true) { PublisherScreen(imported) {} } }
        compose.onNodeWithText("Vérification du compte de publication").assertExists()
        compose.onNodeWithText("Enregistrer les modifications").assertDoesNotExist()
    }

    @Test fun capsuleRequiresAnExplicitPreviewAndPreparation() {
        update(ListeningHistory.tracks, listOf(qa.copy(durationMs = 180000)))
        compose.setContent { KetvoiceTheme(useDark = true) { CapsuleDialog {} } }
        compose.onNodeWithText("Voir la sélection").performScrollTo().performClick()
        compose.onNodeWithText("1 titres sélectionnés").assertExists()
        compose.runOnIdle { assertFalse(OfflineCapsule.state.value.busy); assertTrue(Downloader.busy.value.isEmpty()) }
        compose.onNodeWithText("Préparer la capsule").assertExists()
    }

    @Test fun disabledVideoCanvasDoesNotTryToPlayAnUnavailableVideo() {
        AppSettings.animatedArtwork.value = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            ArtworkCanvas(qa, "/nonexistent/qa-video.mp4", false, Modifier.size(180.dp))
        } }
        compose.runOnIdle { assertFalse(panoramicCanvasActive("/nonexistent/qa-video.mp4")) }
    }

    @Test fun pcmTapKeepsAudioUnchangedAndFeedsRealEnergy() {
        val processor = androidx.media3.exoplayer.audio.TeeAudioProcessor(SonicMeter)
        processor.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(48000, 1, androidx.media3.common.C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = java.nio.ByteBuffer.allocateDirect(9600).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(4800) { i -> input.putShort((kotlin.math.sin(2 * Math.PI * 80 * i / 48000) * 12000).toInt().toShort()) }
        input.flip()
        val expected = ByteArray(input.remaining()); input.duplicate().get(expected)
        SonicMeter.observe(true)
        processor.queueInput(input)
        val output = processor.output
        val actual = ByteArray(output.remaining()); output.get(actual)
        assertArrayEquals(expected, actual)
        assertTrue(SonicMeter.levels.value.low > 0f)
        SonicMeter.observe(false)
        assertEquals(com.ketvoice.app.player.SonicLevels(), SonicMeter.levels.value)
        processor.reset()
    }

    @Test fun questQueueKeepsControlsAndLongTitlesWithReducedMotion() {
        AppSettings.reduceAnimations.value = true
        val state = PlayerUiState(queue = listOf(qa), index = 0)
        compose.setContent { KetvoiceTheme(useDark = true) {
            QuestLandscapePlayer(state, qa, null, null, CoverAmbience(), PlayerPage.QUEUE,
                {}, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("Suivant").assertExists()
        compose.onNodeWithText("File d'attente").assertExists()
        compose.onNodeWithText("Ajouter des titres").assertExists()
        compose.onAllNodesWithText(qa.title).assertCountEquals(2)
        screenshot("quest-queue-long-title")
    }
}
