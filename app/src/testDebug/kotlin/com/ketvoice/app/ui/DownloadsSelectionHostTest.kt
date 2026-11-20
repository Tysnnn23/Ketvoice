package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.ContextMenuHost
import com.ketvoice.app.ui.common.ContextMenus
import com.ketvoice.app.ui.sub.DownloadsScreen
import com.ketvoice.app.ui.sub.UpgradeChecks
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** 7.3 Downloads: long-press selection, the selection's menu, « meilleure qualité » and the live progress. Host renders, not a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsSelectionHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val active get() = Downloader.activeTracks as MutableStateFlow<Map<String, Track>>
    private val tasks get() = Downloader.tasks as MutableStateFlow<Map<String, DownloadTask>>
    private val defaultCheck = UpgradeChecks.check

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        clear()
    }

    @After fun clear() {
        coil.Coil.reset()
        saved.value = emptyList(); active.value = emptyMap(); tasks.value = emptyMap()
        ContextMenus.request.value = null
        UpgradeChecks.check = defaultCheck
        AppUi.closeOverlays(); AppUi.addTracksToPlaylist.value = null
    }

    private fun track(id: String, title: String, artist: String) = Track("fixture:$id", title, artist, "Album de $artist", durationMs = 200_000)

    private fun file(track: Track, format: String, age: Long) = Downloader.Item(track,
        File(compose.activity.cacheDir, track.id.replace(':', '_') + ".bin").apply { writeText("host fixture"); setLastModified(1_700_000_000_000L + age) },
        format, 8_388_608)

    private fun library() = listOf(
        file(track("1", "Bravo", "Zed"), "FLAC · Sans perte", 1000),
        file(track("2", "Alpha", "Abel"), "MP3 · 128 kbps", 3000),
        file(track("3", "Charlie", "Abel"), "MP3 · 320 kbps", 2000))

    private fun show(dark: Boolean = false) = compose.setContent {
        KetvoiceTheme(useDark = dark) { Box(Modifier.fillMaxSize()) { DownloadsScreen(); ContextMenuHost() } }
    }

    private fun image(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = if (dialog) org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
                else compose.activity.window.decorView
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

    private fun row(title: String) = compose.onNode(hasTestTag("saved-download") and hasAnyDescendant(hasText(title)))
    private fun hold(title: String) {
        compose.onNodeWithTag("downloads-list").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    @Test fun theBarHasNoSelectButtonAndALongPressSelects() {
        saved.value = library()
        show(dark = true)
        compose.onNodeWithText("Sélectionner").assertDoesNotExist()
        compose.onNodeWithTag("downloads-selection-count").assertDoesNotExist()
        image("dl73-top-dark")
        hold("Alpha")
        compose.onNodeWithTag("download-select").assertExists()
        // « Sélectionner » comes first in the title's usual glass menu.
        val select = compose.onNodeWithTag("download-select").fetchSemanticsNode().boundsInRoot.top
        val next = compose.onNodeWithText("Lire ensuite").fetchSemanticsNode().boundsInRoot.top
        assertTrue(select < next)
        compose.onNodeWithTag("download-select").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1 sélectionné").assertExists()
        // Taps now tick titles instead of playing them.
        compose.onNodeWithText("Bravo").performClick()
        compose.onNodeWithText("2 sélectionnés").assertExists()
        compose.onNodeWithTag("downloads-select-all").performClick()
        compose.onNodeWithText("3 sélectionnés").assertExists()
        compose.onNodeWithText("Charlie").performClick()
        image("dl73-selecting-dark")
        compose.onNodeWithTag("downloads-select-cancel").performClick()
        compose.onNodeWithTag("downloads-selection-count").assertDoesNotExist()
    }

    @Test fun aLongPressWhileSelectingOpensOneMenuForTheSelection() {
        saved.value = library()
        show()
        hold("Alpha"); compose.onNodeWithTag("download-select").performClick()
        compose.onNodeWithText("Charlie").performClick()
        hold("Charlie")
        compose.onNodeWithTag("context-menu-preview").assert(hasAnyDescendant(hasText("2 titres sélectionnés")))
        for (tag in listOf("selection-play", "selection-playlist", "selection-delete", "selection-upgrade")) compose.onNodeWithTag(tag).assertExists()
        compose.onNodeWithText("Lire ensuite").assertDoesNotExist()
        val order = listOf("selection-play", "selection-playlist", "selection-delete", "selection-upgrade")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("Écouter, playlist, Supprimer, meilleure qualité", order.sorted(), order)
        image("dl73-selection-menu-light")
        compose.onNodeWithTag("selection-playlist").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Alpha", "Charlie"), AppUi.addTracksToPlaylist.value?.map { it.title })
        // Deleting the selection goes through the usual confirmation; Annuler keeps every file.
        hold("Bravo"); compose.onNodeWithTag("download-select").performClick()
        hold("Bravo"); compose.onNodeWithTag("selection-delete").performClick()
        compose.onNodeWithTag("downloads-delete-alert").assertExists()
        compose.onNode(hasText("Annuler") and hasAnyAncestor(hasTestTag("downloads-delete-alert"))).performClick()
        assertEquals(3, saved.value.size)
        assertTrue(saved.value.all { it.file.exists() })
    }

    @Test fun betterQualitySaysHowManyCanBeImprovedAndLeavesTheOthers() {
        saved.value = library()
        UpgradeChecks.check = { item ->
            val now = DownloadUpgrade.ofSaved(item.format, null)
            val offer = MusicApi.StreamInfo("https://example.invalid/x.flac", "flac", "FLAC")
            Downloader.UpgradeOffer(item, now, offer.takeIf { DownloadUpgrade.better(now, DownloadUpgrade.ofStream(it)) })
        }
        show()
        compose.onNodeWithTag("downloads-select-all").assertDoesNotExist()
        hold("Alpha"); compose.onNodeWithTag("download-select").performClick()
        compose.onNodeWithText("Bravo").performClick()
        hold("Bravo"); compose.onNodeWithTag("selection-upgrade").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1 titre sur 2 peut être amélioré").assertExists()
        compose.onAllNodesWithTag("upgrade-line").assertCountEquals(1)
        compose.onNodeWithText("Alpha · 128 kb/s → FLAC").assertExists()
        image("dl73-upgrade-light", dialog = true)
        compose.onNode(hasText("Annuler") and hasAnyAncestor(hasTestTag("downloads-upgrade-alert"))).performClick()
        // Nothing better for a lossless file: the alert says so and offers nothing to download.
        compose.onNodeWithTag("downloads-select-cancel").performClick()
        hold("Bravo"); compose.onNodeWithTag("download-select").performClick()
        hold("Bravo"); compose.onNodeWithTag("selection-upgrade").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Aucune meilleure qualité disponible pour ce titre : il reste tel quel.").assertExists()
        compose.onNodeWithTag("upgrade-confirm").assertDoesNotExist()
    }

    /** 7.5: « Améliorer la qualité » in a title's own menu checks that title alone and starts at once, no selection. */
    @Test fun oneTitleIsImprovedFromItsOwnMenu() {
        saved.value = library()
        val started = mutableListOf<String>()
        val defaultStart = UpgradeChecks.start
        UpgradeChecks.start = { offers -> started += offers.map { it.item.track.title } }
        UpgradeChecks.check = { item ->
            val now = DownloadUpgrade.ofSaved(item.format, null)
            val offer = MusicApi.StreamInfo("https://example.invalid/x.flac", "flac", "FLAC")
            Downloader.UpgradeOffer(item, now, offer.takeIf { DownloadUpgrade.better(now, DownloadUpgrade.ofStream(it)) })
        }
        try {
            show()
            hold("Alpha"); compose.onNodeWithTag("menu-upgrade").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Alpha · 128 kb/s → FLAC").assertExists()
            compose.onNodeWithTag("upgrade-started").assertExists()
            compose.onNodeWithTag("upgrade-confirm").assertDoesNotExist()
            assertEquals(listOf("Alpha"), started)
            image("dl75-upgrade-one-light", dialog = true)
            compose.onNodeWithTag("upgrade-ok").performClick()
            compose.waitForIdle()
            // A lossless file: nothing better, nothing started.
            hold("Bravo"); compose.onNodeWithTag("menu-upgrade").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Aucune meilleure qualité disponible pour ce titre : il reste tel quel.").assertExists()
            assertEquals(listOf("Alpha"), started)
        } finally { UpgradeChecks.start = defaultStart; UpgradeChecks.single.value = null }
    }

    @Test @Config(qualifiers = "w400dp-h1600dp-mdpi")
    fun aDownloadingTitleFillsItsCoverLikeTheAppStore() {
        val moving = track("m", "En route", "Zed")
        saved.value = library()
        active.value = mapOf(moving.id to moving)
        tasks.value = mapOf(moving.id to DownloadTask(DownloadPhase.Transferring, 62_000, 100_000),
            "fixture:2" to DownloadTask(DownloadPhase.Transferring, 30_000, 100_000))
        show(dark = true)
        image("dl73-progress-dark")
        compose.onAllNodesWithTag("download-fill", useUnmergedTree = true).assertCountEquals(2)
        compose.runOnIdle { tasks.value = mapOf(moving.id to DownloadTask(DownloadPhase.Complete, 100_000, 100_000)) }
        compose.onAllNodesWithTag("download-fill", useUnmergedTree = true).assertCountEquals(0)
    }
}
