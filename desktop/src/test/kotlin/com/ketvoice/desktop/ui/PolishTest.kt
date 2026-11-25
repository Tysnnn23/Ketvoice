package com.ketvoice.desktop.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.smartlib.ImportMatch
import com.ketvoice.app.smartlib.ImportPreview
import com.ketvoice.app.smartlib.ImportService
import com.ketvoice.app.smartlib.ImportSong
import java.time.DayOfWeek
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Phase 3: shortcuts, menus of whole albums, the language switch, dates, import, offline states, the colour fields. */
@OptIn(ExperimentalTestApi::class)
class PolishTest {
    private val french = AppLanguage.FRENCH.label
    @BeforeTest fun setUp() { com.ketvoice.app.data.AppText.init(android.content.Context.app); Fixtures.installCovers(); AppSettings.reduceAnimations.value = true; AppSettings.language.value = french }
    @AfterTest fun tearDown() { AppSettings.language.value = french; MiniPlayer.open.value = false; SearchScope.library.value = false }

    @Test fun arrowsSeekAndSetTheVolumeSpaceStaysInTextFields() = runComposeUiTest {
        val player = RecordingPlayer().playing(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true) }
        waitForIdle()
        val shell = onNodeWithTag("shell")
        val start = player.state.value.positionMs
        shell.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(start + Shortcuts.SEEK_MS, player.state.value.positionMs)
        shell.performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(start, player.state.value.positionMs)
        val v = player.volume.value
        shell.performKeyInput { pressKey(Key.DirectionUp) }
        assertEquals(v + Shortcuts.VOLUME_STEP, player.volume.value, .001f)
        shell.performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(v, player.volume.value, .001f)
        shell.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.M) } }
        assertTrue(MiniPlayer.open.value)
        shell.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Comma) } }
        onNodeWithTag("page:settings").assertExists()
        // A field outside the search box (Settings' account form) keeps Space and the arrows too.
        val before = player.calls.size
        TextEntry.set("test-field", true)
        shell.performKeyInput { pressKey(Key.Spacebar); pressKey(Key.DirectionRight) }
        TextEntry.set("test-field", false)
        assertEquals(before, player.calls.size)
    }

    @Test fun albumMenuPlaysNextInOrderAndAddsToAPlaylist() = runComposeUiTest {
        val player = RecordingPlayer().playing(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true, navigator = remember { Navigator(Route.Artist(Fixtures.artists[0])) }) }
        val ref = Fixtures.refs.first { it.artist == Fixtures.artists[0].name && it.type == "Album" }
        val i = Fixtures.refs.indexOf(ref)
        // Windows 2.0: the artist's photo is taller, the albums start below the fold.
        onNodeWithTag("page:artist").performScrollToNode(hasTestTag("album:${ref.title}"))
        onNodeWithTag("album:${ref.title}").performMouseInput { moveTo(center); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        onNodeWithTag("menu:play-next").performClick()
        waitUntil(timeoutMillis = 3_000) { player.calls.count { it.startsWith("playNext:") } == Fixtures.albumTracks(i).size }
        val s = player.state.value
        assertEquals(Fixtures.albumTracks(i).map { it.id }, s.queue.drop(s.index + 1).take(Fixtures.albumTracks(i).size).map { it.id })
        // The album page's ⋯ adds the whole album to a playlist.
        onNodeWithTag("album:${ref.title}").performClick()
        onNodeWithTag("collection-more").performClick()
        onNodeWithTag("menu:add-to-playlist").performMouseInput { moveTo(center) }
        val target = library.playlists.value.first()
        val size = target.tracks.size
        onAllNodesWithTag("menu:playlist")[0].performClick()
        waitUntil(timeoutMillis = 3_000) { library.playlists.value.first { it.id == target.id }.tracks.size > size }
    }

    @Test fun languageChangesWithoutARestart() = runComposeUiTest {
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true, navigator = remember { Navigator(Route.Settings) }) }
        fun shows(text: String) = assertTrue(onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(), text)
        shows("Réglages")
        onNodeWithTag("choice:language").performClick()
        onNodeWithTag("menu:language:2").performClick() // English (after « Langue du système » and Français)
        assertEquals(AppLanguage.ENGLISH.label, AppSettings.language.value)
        assertTrue(AppSettings.languageChosen.value)
        shows("Settings")
        onNodeWithTag("choice:language").performClick()
        onNodeWithTag("menu:language:9").performClick() // Deutsch
        shows("Einstellungen")
    }

    @Test fun dayAndMonthNamesFollowTheAppLanguage() {
        AppSettings.language.value = french
        assertEquals("lun.", LocalDates.weekday(DayOfWeek.MONDAY))
        assertEquals("janv.", LocalDates.month(1))
        AppSettings.language.value = AppLanguage.JAPANESE.label
        assertEquals("月", LocalDates.weekday(DayOfWeek.MONDAY))
        AppSettings.language.value = AppLanguage.ENGLISH.label
        assertEquals("Mon", LocalDates.weekday(DayOfWeek.MONDAY))
        AppSettings.language.value = AppLanguage.GERMAN.label
        assertNotEquals("Mon", LocalDates.weekday(DayOfWeek.MONDAY))
    }

    @Test fun importsAPlaylistFromALink() = runComposeUiTest {
        val library = Fixtures.library()
        val nav = Navigator()
        val songs = Fixtures.tracks.take(3).map { ImportSong(it.id, it.title, it.artist, it.durationMs) } + ImportSong("x", "Introuvable", "Personne", 1000)
        val importer = object : PlaylistImporter {
            override suspend fun preview(link: String) = ImportPreview(ImportService.Deezer, "Soirée importée", link, null, songs, complete = true)
            override suspend fun resolve(preview: ImportPreview, progress: (Int, Int) -> Unit) =
                preview.songs.map { s -> ImportMatch(s, Fixtures.tracks.firstOrNull { it.id == s.sourceId }, if (s.sourceId == "x") 0f else 1f) }
        }
        setContent {
            KetvoiceShell(RecordingPlayer(), library, FixtureCatalog, dark = true, navigator = nav)
            androidx.compose.runtime.CompositionLocalProvider(LocalLibrary provides library, LocalNavigator provides nav) {
                ImportPlaylistDialog(onDismiss = {}, importer = importer)
            }
        }
        onNodeWithTag("import-link").performTextInput("https://www.deezer.com/playlist/123")
        onNodeWithTag("import-go").performClick()
        onNodeWithText("Soirée importée").assertExists()
        onNodeWithTag("import-go").performClick()
        onNodeWithText("3 / 4 titres retrouvés").assertExists()
        onNodeWithTag("import-go").performClick()
        waitUntil(timeoutMillis = 3_000) { library.playlists.value.any { it.name == "Soirée importée" } }
        val created = library.playlists.value.first { it.name == "Soirée importée" }
        assertEquals(3, created.tracks.size)
        assertEquals(Route.Playlist(created.id), nav.current)
    }

    @Test fun offlinePagesSayWhatStillWorks() = runComposeUiTest {
        val offline = object : CatalogSource by FixtureCatalog {
            override suspend fun home(): HomeData = error("no network")
            override suspend fun categories(): List<Category> = emptyList()
            override suspend fun online() = false
        }
        val nav = Navigator()
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), offline, dark = true, navigator = nav) }
        // Windows 2.0: Home's hero (the last title heard) comes first, the offline state below it.
        onNodeWithTag("page:home").performScrollToNode(hasTestTag("state:offline"))
        onNodeWithTag("state:offline").assertExists()
        onNodeWithTag("state-action:1").performClick()
        onNodeWithTag("page:downloads").assertExists()
        nav.root(Route.Explore)
        onNodeWithTag("state:offline").assertExists()
    }

    @Test fun librarySearchMatchesEveryWord() {
        val library = Fixtures.library()
        val t = LibraryViews.songs(library).first()
        val found = LibraryViews.search(library, "${t.title.split(' ').first()} ${t.artist.split(' ').first()}".lowercase())
        assertTrue(found.any { it.id == t.id })
        assertTrue(LibraryViews.search(library, "zzzz qqqq").isEmpty())
    }

    @Test fun colourFieldsAreBlurredAndFeathered() {
        val img = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply { color = java.awt.Color.RED; fillRect(0, 0, 32, 64); color = java.awt.Color.BLUE; fillRect(32, 0, 32, 64); dispose() }
        }
        val fields = CoverFlow.fields("test:cover", img.toComposeImageBitmap())
        val px = IntArray(CoverFlow.SIDE * CoverFlow.SIDE)
        fields.soft.readPixels(px)
        assertEquals(0, px[0] ushr 24, "the feathered field's corner is transparent")
        val mid = IntArray(CoverFlow.SIDE * CoverFlow.SIDE).also { fields.base.readPixels(it) }[CoverFlow.SIDE * 24 + 24]
        assertTrue((mid shr 16 and 255) in 30..225 && (mid and 255) in 30..225, "red and blue are blended at the seam")
    }
}
