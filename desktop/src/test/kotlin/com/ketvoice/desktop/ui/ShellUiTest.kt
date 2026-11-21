package com.ketvoice.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.ketvoice.app.data.AppSettings
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ShellUiTest {
    @BeforeTest fun setUp() { Fixtures.installCovers(); AppSettings.reduceAnimations.value = true }

    @Test fun sidebarAndLinksNavigate() = runComposeUiTest {
        val player = RecordingPlayer(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true) }
        onNodeWithTag("page:home").assertExists()
        onNodeWithTag("nav:Albums").performClick()
        onNodeWithTag("page:albums").assertExists()
        onNodeWithTag("album:Marée basse").performClick()
        onNodeWithTag("page:album").assertExists()
        onNodeWithTag("track:fx-0-0").assertExists()
        onNodeWithTag("play-all").performClick()
        assertEquals("playQueue:fx-0-0", player.calls.last())
        onNodeWithTag("back").performClick()
        onNodeWithTag("page:albums").assertExists()
        onNodeWithTag("nav:Titres").performClick()
        onNodeWithTag("page:songs").assertExists()
        onNodeWithTag("nav:Téléchargements").performClick()
        onNodeWithTag("download:${Fixtures.tracks[9].id}").assertExists()
        onNodeWithTag("nav:Statistiques").performClick()
        onNodeWithTag("page:stats").assertExists()
        onNodeWithTag("playlist:pl-road").performClick()
        onNodeWithTag("page:playlist").assertExists()
    }

    @Test fun contextMenuCallsTheFacade() = runComposeUiTest {
        val player = RecordingPlayer().playing(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true, navigator = androidx.compose.runtime.remember { Navigator(Route.Songs) }) }
        val t = LibraryViews.sort(LibraryViews.songs(library), LibraryViews.SortKey.TITLE, true).first { it.albumId == "fx-album-1" }
        fun menu(item: String) {
            onNodeWithTag("row:${t.id}").performMouseInput { moveTo(center); press(MouseButton.Secondary); release(MouseButton.Secondary) }
            onNodeWithTag("context-menu").assertExists()
            onNodeWithTag("menu:$item").performClick()
        }
        menu("play-next"); assertEquals("playNext:${t.id}", player.calls.last())
        menu("enqueue"); assertEquals("enqueue:${t.id}", player.calls.last())
        menu("download"); assertTrue(library.downloads.value.any { it.track.id == t.id })
        val liked = library.favourites.value.any { it.id == t.id }
        menu("like"); assertEquals(!liked, library.favourites.value.any { it.id == t.id })
        menu("go-album"); onNodeWithTag("page:album").assertExists()
        // Double-click plays from the album's list.
        onNodeWithTag("track:fx-1-2").performMouseInput { moveTo(center); doubleClick() }
        assertEquals("playQueue:fx-1-2", player.calls.last())
    }

    @Test fun keyboardShortcuts() = runComposeUiTest {
        val player = RecordingPlayer().playing(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true) }
        waitForIdle()
        val shell = onNodeWithTag("shell")
        shell.performKeyInput { pressKey(Key.Spacebar) }
        assertEquals("toggle", player.calls.last())
        shell.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionRight) } }
        assertEquals("next", player.calls.last())
        shell.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionLeft) } }
        assertEquals("previous", player.calls.last())
        val current = player.state.value.current!!
        val before = library.favourites.value.any { it.id == current.id }
        shell.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.L) } }
        assertEquals(!before, library.favourites.value.any { it.id == current.id })
        shell.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        onNodeWithTag("search-field").assertIsFocused()
        // While typing, Space is text, not play/pause.
        val count = player.calls.size
        onNodeWithTag("search-field").performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(count, player.calls.size)
    }

    @Test fun dropATitleOnASidebarPlaylist() = runComposeUiTest {
        val player = RecordingPlayer(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true, navigator = androidx.compose.runtime.remember { Navigator(Route.Songs) }) }
        val t = LibraryViews.sort(LibraryViews.songs(library), LibraryViews.SortKey.TITLE, true)
            .first { s -> library.playlists.value.first { it.id == "pl-focus" }.tracks.none { it.id == s.id } }
        assertTrue(library.playlists.value.first { it.id == "pl-focus" }.tracks.none { it.id == t.id })
        val source = onNodeWithTag("row:${t.id}").fetchSemanticsNode().boundsInRoot
        val target = onNodeWithTag("playlist:pl-focus").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("row:${t.id}").performMouseInput {
            val start = Offset(source.width / 3, source.height / 2)
            moveTo(start); press()
            val end = target.center - source.topLeft
            for (i in 1..12) moveTo(start + (end - start) * (i / 12f))
            release()
        }
        waitForIdle()
        assertTrue(library.playlists.value.first { it.id == "pl-focus" }.tracks.any { it.id == t.id })
    }

    @Test fun queuePanelReordersAndLcdOpensThePlayer() = runComposeUiTest {
        val player = RecordingPlayer().playing(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true) }
        onNodeWithTag("toggle-queue").performClick()
        onNodeWithTag("panel:queue").assertExists()
        val q = player.state.value.queue
        val first = q[4]; val third = q[6]
        val row = onNodeWithTag("queue:${first.id}").fetchSemanticsNode().boundsInRoot
        val down = onNodeWithTag("queue:${third.id}").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("grip:${first.id}").performMouseInput {
            moveTo(center); press(); for (i in 1..10) moveBy(Offset(0f, (down.center.y - row.center.y) / 10f)); release()
        }
        waitForIdle()
        assertEquals("move:4->6", player.calls.lastOrNull(), "calls=${player.calls} row=$row down=$down")
        onNodeWithTag("lcd-artwork").performClick()
        onNodeWithTag("full-player").assertExists()
        onNodeWithTag("close-player").performClick()
        waitForIdle()
        onNodeWithTag("full-player").assertDoesNotExist()
    }

    @Test fun searchShowsLiveSections() = runComposeUiTest {
        val player = RecordingPlayer(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true, initialQuery = "kadi",
            navigator = androidx.compose.runtime.remember { Navigator(Route.Search) }) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithTag("page:search").assertExists()
        onNodeWithTag("album:Néons calmes").assertExists()
        onNodeWithTag("artist:Kadi").assertExists()
    }

    @Test fun libraryViewsSortAndGroup() {
        val lib = Fixtures.library()
        val songs = LibraryViews.songs(lib)
        assertEquals(songs.map { it.id }.distinct().size, songs.size)
        val byDuration = LibraryViews.sort(songs, LibraryViews.SortKey.DURATION, ascending = false)
        assertTrue(byDuration.zipWithNext().all { (a, b) -> a.durationMs >= b.durationMs })
        assertTrue(LibraryViews.artists(songs).none { it.name.contains(',') })
        assertTrue(AudioFormatInfo("flac", 96_000, 24, 2, 0).hiRes)
        assertTrue(!AudioFormatInfo("aac", 44_100, 16, 2, 256_000).lossless)
    }
}
