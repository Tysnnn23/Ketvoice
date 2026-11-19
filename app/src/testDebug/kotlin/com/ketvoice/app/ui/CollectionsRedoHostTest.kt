package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.KetvoiceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** 7.2 host renders: Home, Favoris, Téléchargements (mosaic), Library, « Titres recommandés » and the Lossless mark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionsRedoHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved get() = Downloader.items as MutableStateFlow<List<Downloader.Item>>
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    private val counts get() = ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>
    private val colours = listOf(0xFFB8473A, 0xFF2F6F8F, 0xFFD9A441, 0xFF4F7A4A, 0xFF6B4C9A, 0xFF1F1F24).map { it.toInt() }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        clear()
        // The picture is drawn on a software canvas: hardware bitmaps inside elevated layers would not show there.
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).allowHardware(false).crossfade(false).build())
    }

    @After fun clear() {
        coil.Coil.reset()
        saved.value = emptyList(); known.value = emptyList(); recent.value = emptyList(); counts.value = emptyMap()
        AppSettings.favoriteIds.value = emptySet()
        PlaylistStore.resetForTest()
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME); AppUi.catalogRequest.value = null
    }

    /** A plain square picture on disk, so Coil draws a real cover without the network. */
    private fun cover(index: Int): String {
        val file = File(compose.activity.cacheDir, "cover-$index.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colours[index % colours.size]) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return file.toURI().toString()
    }

    private fun track(id: String, title: String, artist: String, album: String = "Album de $artist", art: Int? = null) =
        Track("fixture:$id", title, artist, album, durationMs = 214_000, coverUrl = art?.let(::cover))

    /** A saved file; [rate] writes a FLAC STREAMINFO header with that sample rate. */
    private fun item(track: Track, format: String, age: Long, rate: Int? = null): Downloader.Item {
        val file = File(compose.activity.cacheDir, track.id.replace(':', '_') + ".bin").apply {
            if (rate == null) writeText("host fixture") else writeBytes(flacHead(rate))
            setLastModified(1_700_000_000_000L + age)
        }
        return Downloader.Item(track, file, format, 30_000_000)
    }

    private fun flacHead(rate: Int) = ByteArray(42).also {
        "fLaC".toByteArray().copyInto(it); it[7] = 34
        it[18] = (rate shr 12).toByte(); it[19] = (rate shr 4).toByte(); it[20] = ((rate and 0xF) shl 4).toByte()
    }

    private fun image(name: String) {
        // Coil decodes the covers off the main thread: give it a moment before the picture is taken.
        repeat(4) { Thread.sleep(150); compose.waitForIdle() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/collections-72/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    private val dark = androidx.compose.runtime.mutableStateOf(true)
    private var shown = false

    /** The content is set once per test; a second call only switches the look (light or dark). */
    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        this.dark.value = dark
        if (!shown) compose.setContent { KetvoiceTheme(useDark = this.dark.value) {
            CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null, content = content)
        } }
        shown = true
        compose.waitForIdle()
    }

    /** Six downloads: the 2nd shares the 1st's album, the 4th the 3rd's picture, so the mosaic skips them. */
    private fun sixDownloads(): List<Downloader.Item> {
        val list = listOf(
            track("d1", "Nuit blanche", "Ayla", "Lumen", art = 0), track("d2", "Aube", "Ayla", "Lumen", art = 0),
            track("d3", "Kilomètres", "Noé", "Route", art = 1), track("d4", "Retour", "Mira", "Route bis", art = 1),
            track("d5", "Sable", "Iris", "Désert", art = 2), track("d6", "Marée", "Oslo", "Côtes", art = 3))
        return list.mapIndexed { i, t -> item(t, if (i == 0) "FLAC · Sans perte" else "AAC · 256 kbps", age = 10_000L - i * 1000L, rate = if (i == 0) 96_000 else null) }
    }

    @Test fun downloadsLeadWithAMosaicOfFourDistinctCovers() {
        saved.value = sixDownloads()
        assertEquals(listOf(cover(0), cover(1), cover(2), cover(3)), com.ketvoice.app.ui.library.downloadMosaic(saved.value))
        show(true) { com.ketvoice.app.ui.sub.DownloadsScreen() }
        compose.onNodeWithTag("downloads-title").assertIsDisplayed()
        compose.onNodeWithTag("downloads-mosaic", useUnmergedTree = true).assertIsDisplayed()
        image("downloads-four-dark")
        compose.onNodeWithTag("downloads-list").performScrollToKey(saved.value[3].track.id)
        image("downloads-rows-dark")
    }

    @Test fun downloadsWithTwoDistinctCoversShowTwoTiles() {
        saved.value = listOf(item(track("t1", "Un", "Ayla", "Lumen", art = 4), "MP3 · 320 kbps", 3000),
            item(track("t2", "Deux", "Ayla", "Lumen", art = 4), "MP3 · 320 kbps", 2000),
            item(track("t3", "Trois", "Noé", "Route", art = 5), "MP3 · 320 kbps", 1000))
        assertEquals(2, com.ketvoice.app.ui.library.downloadMosaic(saved.value).size)
        show(false) { com.ketvoice.app.ui.sub.DownloadsScreen() }
        image("downloads-two-light")
    }

    @Test fun favoritesHaveTheHeartCoverCountPlayAndSorts() {
        val tracks = (1..7).map { track("f$it", "Favori $it", if (it % 2 == 0) "Ayla" else "Noé", art = it % 4) }
        known.value = tracks
        AppSettings.favoriteIds.value = tracks.map { it.id }.toCollection(LinkedHashSet())
        show(true) { com.ketvoice.app.ui.sub.CatalogScreen(CatalogRequest("favorites", "local", "Favoris")) }
        compose.onNodeWithTag("liked-cover").assertIsDisplayed()
        compose.onNodeWithTag("liked-title").assertTextEquals("Favoris")
        compose.onNodeWithText("7 titres · 24 min").assertIsDisplayed()
        compose.onNodeWithTag("liked-play").assertIsDisplayed()
        compose.onNodeWithTag("liked-shuffle").assertIsDisplayed()
        image("liked-dark")
        compose.onNodeWithText("Titre A–Z").performScrollTo().performClick()
        compose.onNodeWithTag("liked-list").performScrollToNode(hasText("Favori 7"))
        image("liked-sorted-dark")
        show(false) { com.ketvoice.app.ui.sub.CatalogScreen(CatalogRequest("favorites", "local", "Favoris")) }
        image("liked-light")
    }

    @Test fun theLibraryListsFavoritesDownloadsThenThePlaylists() {
        saved.value = sixDownloads()
        val tracks = saved.value.map { it.track }
        AppSettings.favoriteIds.value = setOf(tracks[0].id, tracks[2].id)
        kotlinx.coroutines.runBlocking { PlaylistStore.init(compose.activity) }
        @Suppress("UNCHECKED_CAST")
        (PlaylistStore.lists as MutableStateFlow<List<LocalPlaylist>>).value = listOf("Matin", "Route", "Soirée").mapIndexed { i, name ->
            LocalPlaylist(id = "qa:p$i", name = name, createdAt = 1_000L * (3 - i), pinned = i == 0,
                entries = tracks.drop(i).take(3).map { PlaylistEntry(id = "e$i:${it.id}", title = it.title, artist = it.artist, track = it) })
        }
        show(true) { com.ketvoice.app.ui.library.LibraryScreen() }
        val liked = compose.onNodeWithTag("library-liked").fetchSemanticsNode().boundsInRoot
        val downloads = compose.onNodeWithTag("library-downloads").fetchSemanticsNode().boundsInRoot
        assertTrue("Favoris first, Téléchargements second", liked.bottom <= downloads.top + 1f)
        compose.onNodeWithText("2 titres").assertIsDisplayed()
        image("library-dark")
        compose.onNodeWithTag("library-list").performScrollToNode(hasText("Soirée"))
        assertTrue(compose.onAllNodesWithTag("playlist-row").fetchSemanticsNodes().size == 3)
        image("library-playlists-dark")
        show(false) { com.ketvoice.app.ui.library.LibraryScreen() }
        image("library-light")
    }

    @Test fun homeLeadsWithLargeCardsThenShelvesAndTheLibrary() {
        val tracks = (1..6).map { track("h$it", "Titre $it", "Artiste ${it % 3}", "Album ${it % 3}", art = it % 4) }
        known.value = tracks; recent.value = tracks.take(5)
        counts.value = tracks.associate { it.id to 4 }
        AppSettings.favoriteIds.value = setOf(tracks[0].id, tracks[1].id)
        saved.value = sixDownloads()
        show(true) { com.ketvoice.app.ui.home.HomeScreen() }
        compose.onNodeWithTag("home-greeting").assertIsDisplayed()
        image("home-dark")
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-library"))
        compose.onNodeWithTag("home-library-liked", useUnmergedTree = true).assertExists()
        image("home-dark-library")
        show(false) { com.ketvoice.app.ui.home.HomeScreen() }
        image("home-light")
    }

    @Test fun anAlbumEndsWithRecommendedTitles() {
        val album = (1..4).map { track("a$it", "Piste $it", "Ayla", "Lumen", art = 0) }
        val group = com.ketvoice.app.ui.library.localAlbums(album).single()
        com.ketvoice.app.ui.sub.MoreLikeThis.prime("local:" + group.key,
            (1..5).map { track("r$it", "Voisin $it", if (it < 3) "Ayla" else "Noé", "Autre $it", art = it % 4) })
        show(true) { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background)) {
            com.ketvoice.app.ui.library.AlbumPage(group, {}) } }
        compose.onNode(hasScrollAction()).performScrollToKey("album-more-like-this")
        compose.onNodeWithTag("more-like-this").assertExists()
        assertEquals(5, compose.onAllNodesWithTag("more-like-this-row").fetchSemanticsNodes().size)
        image("album-more-like-this-dark")
    }

    @Test fun theLosslessMarkFollowsTheFileHeader() {
        val hiRes = track("q1", "Haute résolution", "Ayla", art = 1)
        val cd = track("q2", "Qualité CD", "Noé", art = 2)
        saved.value = listOf(item(hiRes, "FLAC · Sans perte", 2000, rate = 96_000), item(cd, "FLAC · Sans perte", 1000, rate = 44_100))
        show(true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF2B2F3A)).padding(top = 40.dp)) {
                com.ketvoice.app.ui.player.PlayerTitle(hiRes, false, hiRes.coverUrl, {}, Modifier.padding(horizontal = 24.dp))
                com.ketvoice.app.ui.common.TrackRow(hiRes, hiRes.coverUrl, false, false, {})
                com.ketvoice.app.ui.common.TrackRow(cd, cd.coverUrl, false, false, {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("lossless-badge", useUnmergedTree = true).fetchSemanticsNodes().size == 3 }
        assertEquals(2, compose.onAllNodesWithContentDescription("Hi-Res Lossless", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription("Lossless", useUnmergedTree = true).fetchSemanticsNodes().size)
        image("lossless-badges")
    }
}
