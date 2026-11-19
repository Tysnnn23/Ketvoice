package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.smartlib.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.ui.library.PlaylistImportSheet
import com.ketvoice.app.ui.library.PlaylistImportState
import com.ketvoice.app.ui.smartlib.*
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

/** The smart library's screens drawn by host Skia (Robolectric), not on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartLibraryHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>

    @Before fun setup() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        PlaylistStore.resetForTest()
        PlaylistStore.init(compose.activity)
        SmartLibrary.resetKeptForTests()
        saved.value = emptyList(); known.value = emptyList()
    }

    @After fun reset() {
        PlaylistStore.resetForTest()
        saved.value = emptyList(); known.value = emptyList()
        SmartLibrary.setMixesForTests(emptyList())
        AppSettings.reduceAnimations.value = false
        AppSettings.liquidGlass.value = true
        AppUi.showSpotifyImport.value = false
        AppUi.closeOverlays()
        AppUi.selectTab(AppUi.HOME)
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            // Sheets and alerts are windows of their own: every window is drawn, in order, where its gravity puts it
            // (the host reports no screen position for a dialog), over the dimming it asks for.
            for (view in windows()) {
                val params = view.layoutParams as? android.view.WindowManager.LayoutParams
                if (view !== root && params != null && params.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                    canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                val centred = params?.gravity == android.view.Gravity.CENTER
                val x = if (centred) (root.width - view.width) / 2f else 0f
                val y = if (centred) (root.height - view.height) / 2f else 0f
                canvas.save(); canvas.translate(x, y); view.draw(canvas); canvas.restore()
            }
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/smartlib-$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = ReflectionHelpers.getField<java.util.ArrayList<android.view.View>>(global, "mViews")
        return views.toList().filter { it.isShown && it.width > 0 }.ifEmpty { listOf(compose.activity.window.decorView) }
    }

    private fun track(id: String, title: String, artist: String, ms: Long = 200_000) = Track(id, title, artist, "Album", durationMs = ms)

    @Test fun understoodChipsCanBeRemoved() {
        val query = mutableStateOf<NaturalQuery?>(NaturalQuery.parse("rap français calme des années 2010"))
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().pageBackground(LocalTheme.current).padding(top = 40.dp)) {
                com.ketvoice.app.ui.common.CompactSearchField("rap français calme des années 2010", {}, {}, Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                UnderstoodChips(query.value, { removed -> query.value = query.value?.without(setOf(removed.key)) })
            }
        } }
        compose.onNodeWithTag("chip-g:Rap").assertIsDisplayed()
        compose.onNodeWithText("2010–2019").assertIsDisplayed()
        image("chips")
        compose.onNodeWithTag("chip-m:Calm").performClick()
        compose.onNodeWithTag("chip-m:Calm").assertDoesNotExist()
        assertEquals(listOf("Rap", "Français", "2010–2019"), query.value!!.filters.map { it.label })
    }

    @Test fun ambiancesShowInTheLibraryWithGeneratedCovers() {
        val pool = (1..12).map { track("t$it", "Titre $it", "Artiste ${it % 4}") }
        SmartLibrary.setMixesForTests(Mood.entries.map { MoodMix(it, pool.shuffled(java.util.Random(it.ordinal.toLong()))) })
        known.value = pool
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.library.LibraryScreen() } }
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("open-duplicates"))
        compose.onNodeWithTag("ambiance-Calm").assertIsDisplayed()
        image("ambiances")
        compose.onNodeWithTag("ambiance-Calm").performClick()
        assertEquals(AppUi.MOOD, AppUi.overlay.intValue)
        assertEquals(Mood.Calm, SmartLibrary.openMood.value)
    }

    @Test fun aMoodPageCanBeSavedAsAPlaylist() {
        val pool = (1..8).map { track("t$it", "Titre $it", "Artiste ${it % 3}") }
        SmartLibrary.setMixesForTests(listOf(MoodMix(Mood.Melancholy, pool)))
        SmartLibrary.openMood.value = Mood.Melancholy
        compose.setContent { KetvoiceTheme(useDark = true) { MoodScreen() } }
        compose.onNodeWithTag("mood-cover-Melancholy").assertIsDisplayed()
        image("mood-page")
        compose.onNodeWithTag("mood-save").performClick()
        compose.waitUntil(5000) { PlaylistStore.lists.value.isNotEmpty() }
        assertEquals("Mélancolie", PlaylistStore.lists.value.single().name)
        assertEquals(8, PlaylistStore.lists.value.single().entries.size)
    }

    private fun item(track: Track, format: String, bytes: Long): Downloader.Item {
        val file = File(compose.activity.filesDir, "${track.id.replace(':', '_')}.m4a").apply { writeBytes(ByteArray(16)) }
        return Downloader.Item(track, file, format, bytes)
    }

    @Test fun duplicatesAreReviewedAndDownloadsAreNeverDeletedWithoutConfirmation() {
        val a = track("yt:1", "Bené", "Werenoi", 201_000)
        val b = track("um:2", "Bené (Clip officiel)", "Werenoi", 202_000).copy(addon = "um")
        val live = track("um:3", "Nuit blanche (Live)", "Lunis", 215_000)
        val studio = track("um:4", "Nuit blanche", "Lunis", 214_000)
        saved.value = listOf(item(a, "Opus", 4_200_000), item(b, "FLAC", 31_000_000))
        runBlocking { PlaylistStore.create("Soirée", listOf(PlaylistRules.entry(a), PlaylistRules.entry(b), PlaylistRules.entry(live), PlaylistRules.entry(studio))) }
        compose.setContent { KetvoiceTheme(useDark = true) { DuplicatesScreen() } }
        compose.onNodeWithText("Autres versions").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Bené", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        image("duplicates")
        compose.onAllNodesWithTag("duplicate-merge")[0].performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("duplicates-delete").fetchSemanticsNodes().isNotEmpty() }
        image("duplicates-confirm")
        compose.onNodeWithText("Garder les fichiers").performClick()
        compose.waitForIdle()
        assertTrue("Files kept on purpose: the group is not offered again", SmartLibrary.kept.value.isNotEmpty())
        assertEquals("Both downloads are still there", 2, saved.value.size)
        assertTrue(saved.value.all { it.file.exists() })
        val entries = PlaylistStore.lists.value.single().entries
        assertEquals("The playlist holds Bené once, pointing at the kept FLAC", 1, entries.count { it.track?.title?.startsWith("Bené") == true })
        assertEquals("um:2", entries.first { it.track?.title?.startsWith("Bené") == true }.track?.id)
    }

    @Test fun importShowsProgressThenUnmatchedTitles() {
        // Glass draws its backdrop layer without blur on the host; the flat sheet reads clearly.
        AppSettings.liquidGlass.value = false
        val songs = (1..40).map { ImportSong("0AAAAAAAAAAAAAAAAAAA${it.toString().padStart(2, '0')}", "Titre $it", "Artiste $it", 180_000) }
        val preview = ImportPreview(ImportService.Deezer, "Rap FR chill 2010s", "https://www.deezer.com/playlist/1", null, songs, true)
        val state = PlaylistImportState().apply {
            link = "https://www.deezer.com/fr/playlist/11223344556"; this.preview = preview; busy = true; done = 17
        }
        compose.setContent { KetvoiceTheme(useDark = true) { PlaylistImportSheet({}, state) } }
        compose.onNodeWithTag("playlist-import-progress").assertExists()
        compose.onNodeWithText("17 / 40 titres analysés").assertExists()
        image("import-progress")
        compose.runOnIdle {
            state.busy = false
            state.matches = songs.mapIndexed { i, song -> ImportMatch(song, if (i % 7 == 3) null else Track("um:$i", song.title, song.artist, ""), 1f) }
        }
        compose.onNodeWithTag("playlist-import-found").assertExists()
        compose.onNodeWithText("34 / 40 titres retrouvés").assertExists()
        compose.onNodeWithTag("playlist-import-missing").performScrollTo()
        image("import-result")
        compose.onNodeWithText("Créer avec les titres affichés").assertIsEnabled()
    }

    @Test fun importListsTheFiveSourcesAndSaysWhenAListIsPartial() {
        AppSettings.liquidGlass.value = false
        val songs = (1..50).map { ImportSong("td:$it", "Titre $it", "Artiste $it", 200_000) }
        val preview = ImportPreview(ImportService.Tidal, "New Arrivals", "https://tidal.com/playlist/1", null, songs, false, publishedCount = 100)
        val state = PlaylistImportState().apply { link = "https://tidal.com/browse/playlist/1b418bb8-90a7-4f87-901d-707993838346"; this.preview = preview }
        compose.setContent { KetvoiceTheme(useDark = true) { PlaylistImportSheet({}, state) } }
        for (name in listOf("Spotify", "Deezer", "Apple Music", "Tidal", "SoundCloud")) compose.onNodeWithText(name).assertExists()
        compose.onNodeWithTag("playlist-import-partial").assertExists()
        compose.onNodeWithText("Ce service ne montre publiquement que 50 titres sur 100. Seuls ceux-ci seront importés.").assertExists()
        compose.runOnIdle { state.matches = songs.map { ImportMatch(it, Track("um:${it.sourceId}", it.title, it.artist, ""), 1f) } }
        compose.onNodeWithText("Créer avec les titres affichés").assertIsNotEnabled()
        image("import-partial")
    }
}
