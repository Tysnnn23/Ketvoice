package com.ketvoice.app.ui

import android.graphics.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.GLASS_AGSL
import com.ketvoice.app.player.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.chrome.FloatingChrome
import com.ketvoice.app.ui.common.LiquidGlassButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.home.HomeEditorial
import com.ketvoice.app.ui.library.LibraryScreen
import com.ketvoice.app.ui.player.*
import com.ketvoice.app.ui.sub.DownloadsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** UI and pixel checks in host Skia; not a physical GPU, provider or owner session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnifiedExperienceHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    private val position get() = Player.positionMs as MutableStateFlow<Long>
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    private fun track(id: String) = Track("qa:$id", "Titre $id", "Artiste local", "Album local", durationMs = 180_000)

    @Before fun setup() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.animatedArtwork.value = false
        PlaylistStore.resetForTest()
        PlaylistStore.init(compose.activity)
        // Robolectric's Android rename cannot replace an existing file on Windows.
        // Model the AtomicFile boundary with a same-directory host atomic move.
        ReflectionHelpers.setField(PlaylistStore, "file", HostAtomicFile(File(compose.activity.filesDir, "playlists-v1.json")))
        recent.value = emptyList(); known.value = emptyList(); saved.value = emptyList()
        player.value = PlayerUiState(); position.value = 0L
    }
    private class HostAtomicFile(file: File) : android.util.AtomicFile(file) {
        private val pending = File(file.path + ".host-pending")
        override fun startWrite(): java.io.FileOutputStream {
            pending.parentFile?.mkdirs()
            return java.io.FileOutputStream(pending)
        }
        override fun finishWrite(stream: java.io.FileOutputStream?) {
            stream?.fd?.sync(); stream?.close()
            java.nio.file.Files.move(pending.toPath(), baseFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        override fun failWrite(stream: java.io.FileOutputStream?) { stream?.close(); pending.delete() }
    }
    @After fun reset() {
        PlaylistStore.resetForTest()
        recent.value = emptyList(); known.value = emptyList(); saved.value = emptyList()
        player.value = PlayerUiState(); position.value = 0L
        AppSettings.reduceAnimations.value = false
        AppSettings.animatedArtwork.value = true
        AppUi.closeOverlays()
        AppUi.selectTab(AppUi.HOME)
    }
    private fun image(name: String): IntArray {
        compose.waitForIdle()
        var pixels = IntArray(0)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
        return pixels
    }

    @Test fun heldLyricsHaveIdenticalPixelsAtAndAfterTheEndInsteadOfGrowingAGhostCopy() {
        AppSettings.reduceAnimations.value = false
        val line = MusicApi.LyricLine(0, "Des mots nets et stables", listOf(MusicApi.LyricWord(0, "Des mots nets et stables", 3000)))
        position.value = 3000L
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) { KaraokeLine(line, Modifier.fillMaxWidth()) }
        } }
        val atEnd = image("lyrics-clean-at-end")
        compose.runOnIdle { position.value = 4000L }
        val after = image("lyrics-clean-after-hold")
        assertArrayEquals("No scaled or diffused word copy may survive the hold", atEnd, after)
        compose.onAllNodesWithText(line.text).assertCountEquals(1)
    }

    @Test fun resumeCardOpensTheCurrentQueueWithoutRestartingIt() {
        val song = track("resume")
        player.value = PlayerUiState(queue = listOf(song), index = 0, isPlaying = true, playWhenReady = true)
        position.value = 42_000L
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background)) { HomeEditorial(listOf(song), resume = true) }
        } }
        image("home-resume-editorial")
        compose.onNodeWithTag("home-editorial").performClick()
        assertEquals(42_000L, position.value)
        assertEquals(song, player.value.current)
        assertTrue(AppUi.showPlayer.value)
    }

    @Test fun playlistPinPersistsFromTheLibraryWithoutChangingItsEntries() {
        val id = runBlocking { PlaylistStore.create("Ma playlist", listOf(PlaylistRules.entry(track("pin")))) }
        compose.setContent { KetvoiceTheme(useDark = false) { LibraryScreen() } }
        compose.onNodeWithTag("library-list").performScrollToNode(hasContentDescription("Épingler"))
        compose.onNodeWithContentDescription("Épingler").performClick()
        compose.waitUntil(5000) { PlaylistStore.lists.value.single().pinned }
        assertEquals(id, PlaylistStore.lists.value.single().id)
        assertEquals(1, PlaylistStore.lists.value.single().entries.size)
        val disk = org.json.JSONArray(File(compose.activity.filesDir, "playlists-v1.json").readText())
        assertTrue("The pin must survive the modeled atomic write", disk.getJSONObject(0).getBoolean("pinned"))
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Épinglés"))
        image("library-pinned-light")
    }

    @Test fun localAlbumViewDoesNotNeedACatalogueIdAndCanReturnToSongs() {
        known.value = listOf(track("one"), track("two"))
        compose.setContent { KetvoiceTheme(useDark = true) { LibraryScreen() } }
        compose.onNode(hasText("Albums") and hasClickAction()).performClick()
        compose.onNodeWithText("Album local").performScrollTo().performClick()
        compose.onNodeWithText("Titre one").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Titre two").assertExists()
        image("library-local-album-dark")
    }

    @Test fun bulkDeleteConfirmationCancelsWithoutChangingAnyOfflineFile() {
        val items = listOf("one", "two").map { id ->
            val file = File(compose.activity.cacheDir, "$id.flac").apply { writeText("host fixture") }
            Downloader.Item(track(id), file, "FLAC", 1024)
        }
        saved.value = items
        compose.setContent { KetvoiceTheme(useDark = false) { DownloadsScreen() } }
        compose.onNodeWithText("Sélectionner").performClick()
        compose.onNodeWithText("Tout sélectionner").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Supprimer").performClick()
        compose.onNode(hasText("Annuler") and hasAnyAncestor(isDialog())).performClick()
        assertTrue(items.all { it.file.exists() })
        assertEquals(items, saved.value)
        image("downloads-multiple-selection-light")
    }

    @Test fun failedFileRemovalKeepsItsMetadataInsteadOfPretendingDeletionSucceeded() {
        val directory = File(compose.activity.cacheDir, "protected.mp3").apply { mkdirs() }
        File(directory, "child").writeText("blocks directory deletion")
        val metadata = File(directory.parentFile, "protected.json").apply { writeText("metadata") }
        assertFalse(Downloader.delete(Downloader.Item(track("protected"), directory, "MP3", 1)))
        assertTrue(metadata.exists())
        assertTrue(directory.exists())
    }

    @Test fun aSharedChromeRetainsTransportAndNavigationInBothStates() {
        player.value = PlayerUiState(queue = listOf(track("chrome")), index = 0)
        var compact by mutableStateOf(false)
        compose.setContent { KetvoiceTheme(useDark = false) { FloatingChrome(compact, {}) } }
        compose.onNodeWithContentDescription("Accueil").assertIsDisplayed()
        image("unified-dock-expanded-light")
        compose.runOnIdle { compact = true }
        compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lecture").assertIsDisplayed()
        image("unified-dock-compact-light")
    }

    @Test fun pressureChangesTheMaterialWithoutChangingTheGlyphBounds() {
        AppSettings.reduceAnimations.value = false
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize()) {
                LiquidGlassButton({}, Modifier.size(160.dp, 56.dp).testTag("pressure")) { Text("Stable") }
            }
        } }
        val before = compose.onNodeWithText("Stable", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("pressure").performTouchInput { down(centerLeft + androidx.compose.ui.geometry.Offset(15f, 0f)) }
        compose.mainClock.advanceTimeBy(180)
        val during = compose.onNodeWithText("Stable", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before, during)
        compose.onNodeWithTag("pressure").performTouchInput { up() }
    }

    @Test fun phonePlayerKeepsTheTransportAnchoredAcrossItsThreePages() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return // Quest keeps its existing two-pane placement.
        player.value = PlayerUiState(queue = listOf(track("player")), index = 0, durationMs = 180_000L)
        compose.setContent { KetvoiceTheme(useDark = true) { FullPlayerScreen(onDismiss = {}) } }
        val before = compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot
        compose.onNode(hasText("File d'attente") and hasAnyAncestor(hasTestTag("player-tabs"))).performClick()
        assertEquals(before, compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot)
        compose.onNode(hasText("Paroles") and hasAnyAncestor(hasTestTag("player-tabs"))).performClick()
        assertEquals(before, compose.onNodeWithTag("player-transport").fetchSemanticsNode().boundsInRoot)
        image("player-unified-lyrics-dark")
    }

    @Test fun adaptiveGlassKeepsPrimaryLabelsReadableOverOppositeLuminanceBackdrops() {
        val examples = intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.BLACK, android.graphics.Color.rgb(20, 90, 180))
        val gallery = Bitmap.createBitmap(360, 160, Bitmap.Config.ARGB_8888)
        val target = Canvas(gallery)
        ReflectionHelpers.setField(target, "mAllowHwFeaturesInSwMode", true)
        fun luminance(pixel: Int): Double {
            fun linear(value: Int): Double {
                val c = value / 255.0
                return if (c <= .04045) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4)
            }
            return .2126 * linear(android.graphics.Color.red(pixel)) + .7152 * linear(android.graphics.Color.green(pixel)) + .0722 * linear(android.graphics.Color.blue(pixel))
        }
        for ((row, dark) in listOf(true, false).withIndex()) examples.forEachIndexed { column, sourceColor ->
            val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(sourceColor) }
            val shader = RuntimeShader(GLASS_AGSL)
            shader.setInputShader("content", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            shader.setFloatUniform("size", 120f, 80f); shader.setFloatUniform("offset", 0f, 0f)
            shader.setFloatUniform("contentSize", 120f, 80f); shader.setFloatUniform("cornerRadii", 20f, 20f, 20f, 20f)
            shader.setFloatUniform("refractionHeight", 4f); shader.setFloatUniform("refractionAmount", 6f)
            shader.setFloatUniform("dispersion", .006f); shader.setFloatUniform("depthEffect", 0f)
            shader.setFloatUniform("tint", if (dark) .16f else 1f, if (dark) .16f else 1f, if (dark) .19f else 1f, .04f)
            shader.setFloatUniform("saturation", 1.08f); shader.setFloatUniform("brightness", 1f)
            shader.setFloatUniform("edgeDark", .055f); shader.setFloatUniform("rimLight", .3f); shader.setFloatUniform("sheen", .02f)
            shader.setFloatUniform("materialDark", if (dark) 1f else 0f)
            shader.setFloatUniform("lightDir", -.7071f, -.7071f); shader.setFloatUniform("pixelScale", 1f)
            val cell = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(cell)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            canvas.drawRect(0f, 0f, 120f, 80f, Paint().apply { this.shader = shader })
            val body = luminance(cell.getPixel(60, 40))
            val contrast = if (dark) 1.05 / (body + .05) else (body + .05) / .05
            assertTrue("Primary label contrast is $contrast for dark=$dark", contrast >= 4.5)
            target.drawBitmap(cell, column * 120f, row * 80f, null)
            cell.recycle(); source.recycle()
        }
        File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/glass-adaptive-readability-grid.png").apply {
            parentFile?.mkdirs(); outputStream().use { gallery.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        gallery.recycle()
    }
}
