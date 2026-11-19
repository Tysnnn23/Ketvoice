package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.library.AlbumDownloads
import com.ketvoice.app.ui.library.localAlbums
import com.ketvoice.app.ui.player.QueuePane
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

/** 7.0: « Lecture automatique » in the queue, Downloads as Apple Music lists it, and downloaded albums. Host fixtures, not a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsAlbumsAutoplayHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        clear()
    }

    @After fun clear() {
        coil.Coil.reset()
        saved.value = emptyList()
        player.value = PlayerUiState()
        AlbumDownloads.clearForTests()
        PlaylistStore.resetForTest()
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun track(id: String, title: String, artist: String, album: String = "") =
        Track("fixture:$id", title, artist, album, durationMs = 200_000)

    private fun item(track: Track, format: String = "FLAC", bytes: Long = 30_000_000) =
        Downloader.Item(track, File(compose.activity.cacheDir, track.id.replace(':', '_') + ".bin").apply { writeText("host fixture") }, format, bytes)

    private fun library(): List<Downloader.Item> = listOf(
        item(track("n1", "Nuit blanche", "Aya Nakamura", "Nakamura")),
        item(track("n2", "Pookie", "Aya Nakamura", "Nakamura"), "MP3", 8_000_000),
        item(track("n3", "Djadja", "Aya Nakamura", "Nakamura")),
        item(track("s1", "Bande organisée", "Jul", "Bande organisée")),
        item(track("s2", "Tout va bien", "Alonzo", "Capo dei capi")),
        item(track("s3", "La kiffance", "Naps", "On est fait pour ça"), "AAC", 6_000_000))

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

    private fun autoplayQueue() = PlayerUiState(
        queue = listOf(track("c", "Bande organisée", "Jul"), track("m", "Lire ensuite", "Naps"),
            track("a1", "Tout va bien", "Alonzo"), track("a2", "Ma story", "SCH"), track("a3", "Djadja", "Aya Nakamura")),
        queueEntryIds = listOf("k0", "k1", "k2", "k3", "k4"), index = 0, autoEntryIds = setOf("k2", "k3", "k4"))

    @Test fun queueShowsLectureAutomatiqueAsItsOwnSection() {
        player.value = autoplayQueue()
        assertEquals(2, player.value.autoStart)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF14141A)).padding(18.dp)) { val state by Player.state.collectAsState(); QueuePane(state) }
        } }
        compose.onNodeWithTag("queue-autoplay").assertIsDisplayed()
        compose.onNodeWithText("Des titres du même style suivent votre file.").assertIsDisplayed()
        compose.onNodeWithTag("queue-autoplay-switch").assertIsDisplayed()
        image("queue-autoplay-dark")
        // A title the listener adds goes before the automatic ones.
        compose.runOnIdle { Player.enqueue(listOf(track("u", "Ajouté", "Ninho"))) }
        assertEquals(listOf("Bande organisée", "Lire ensuite", "Ajouté", "Tout va bien", "Ma story", "Djadja"),
            player.value.queue.map { it.title })
        assertEquals(3, player.value.autoStart)
        val before = player.value
        compose.onNodeWithTag("queue-autoplay-clear").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Bande organisée", "Lire ensuite", "Ajouté"), player.value.queue.map { it.title })
        // Undo puts the section back, still marked as automatic.
        compose.runOnIdle { assertTrue(Player.restoreAutoplay(before)) }
        assertEquals(6, player.value.queue.size)
        assertEquals(3, player.value.autoStart)
        compose.runOnIdle { Player.setAutoplay(false) }
        assertEquals(3, player.value.queue.size)
        compose.onNodeWithText("Activez-la pour enchaîner des titres du même style après votre file.").assertIsDisplayed()
        image("queue-autoplay-off-dark")
        compose.runOnIdle { Player.setAutoplay(true) }
    }

    @Test fun queueAutoplayLight() {
        player.value = autoplayQueue()
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize().background(Color(0xFF3A3A40)).padding(18.dp)) { val state by Player.state.collectAsState(); QueuePane(state, showModes = true) }
        } }
        compose.onNodeWithTag("queue-autoplay").assertExists()
        image("queue-autoplay-light")
    }

    @Test fun downloadsListsCategoriesAndOpensARealAlbumPage() {
        saved.value = library()
        compose.setContent { KetvoiceTheme(useDark = false) { DownloadsScreen() } }
        compose.onNodeWithTag("downloads-title").assertIsDisplayed()
        compose.onNodeWithTag("downloads-view").assertExists()
        listOf("Titres", "Albums", "Artistes", "Playlists").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("downloads-view"))).assertExists()
        }
        image("downloads-phone-light")
        compose.onNode(hasText("Albums") and hasAnyAncestor(hasTestTag("downloads-view"))).performScrollTo().performClick()
        compose.onAllNodesWithTag("album-downloaded", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("albums marked downloaded (${it.size})", it.size >= 2) }
        image("downloads-albums-light")
        // Nakamura is 3 of 5 titles once the catalogue's tracklist is known: shown as partly downloaded.
        val nakamura = localAlbums(library().map { it.track }).first { it.title == "Nakamura" }
        compose.runOnIdle { AlbumDownloads.remember(nakamura.key, nakamura.tracks + track("r1", "Copines", "Aya Nakamura", "Nakamura") +
            track("r2", "Sucette", "Aya Nakamura", "Nakamura")) }
        compose.onNodeWithTag("album-partly-downloaded", useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("download-cover-cell") and hasText("Nakamura")).performScrollTo().performClick()
        compose.onNodeWithTag("album-page").assertIsDisplayed()
        compose.onNodeWithText("3 sur 5 titres téléchargés").assertIsDisplayed()
        compose.onAllNodesWithTag("album-track-downloaded").assertCountEquals(3)
        compose.onAllNodesWithTag("album-track-missing").assertCountEquals(2)
        image("downloaded-album-light")
        compose.onNodeWithTag("album-delete-download").performScrollTo().performClick()
        compose.onNodeWithTag("album-delete-alert").assertExists()
        compose.onNode(hasText("Annuler") and hasAnyAncestor(hasTestTag("album-delete-alert"))).performClick()
        assertEquals(6, saved.value.size)
        assertTrue(saved.value.all { it.file.exists() })
    }

    @Test fun downloadsAndAlbumDark() {
        saved.value = library()
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadsScreen() } }
        image("downloads-phone-dark")
        compose.onNode(hasText("Albums") and hasAnyAncestor(hasTestTag("downloads-view"))).performScrollTo().performClick()
        compose.onNode(hasTestTag("download-cover-cell") and hasText("Nakamura")).performScrollTo().performClick()
        compose.onNodeWithTag("album-page").assertIsDisplayed()
        compose.onNodeWithText("Téléchargé").assertIsDisplayed()
        image("downloaded-album-dark")
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi") fun downloadsOnATablet() {
        saved.value = library()
        compose.setContent { KetvoiceTheme(useDark = true) { DownloadsScreen() } }
        compose.onNodeWithTag("downloads-view").assertExists()
        image("downloads-tablet-dark")
        compose.onNode(hasText("Albums") and hasAnyAncestor(hasTestTag("downloads-view"))).performScrollTo().performClick()
        compose.onNode(hasTestTag("download-cover-cell") and hasText("Nakamura")).performScrollTo().performClick()
        image("downloaded-album-tablet-dark")
    }
}
