package com.ketvoice.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LayoutSettings
import com.ketvoice.app.data.PersonalBackup
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** « Disposition » on Windows: the phone's layout keys read for the window, the window as it was by default. */
@OptIn(ExperimentalTestApi::class)
class DeskLayoutTest {
    @BeforeTest fun setUp() { LayoutSettings.resetAll(); Fixtures.installCovers(); AppSettings.reduceAnimations.value = true }
    @AfterTest fun tearDown() { LayoutSettings.resetAll(); TrackMenuSlots.lyricsEditor = null }

    @Test fun theDefaultsAreTheWindowAsItWas() {
        assertEquals(listOf("home", "explore", "header", "recent", "artists", "albums", "songs", "downloads", "stats"), DeskLayout.sidebarRows(""))
        assertEquals(listOf("forYou", "recent", "catalog"), DeskLayout.homeSections(""))
        assertEquals(listOf("lyrics", "queue", "account", "output"), DeskLayout.barButtons(""))
        assertEquals(listOf("playNext", "enqueue", "playlist", "mixPoint", "lyricsEditor", "-", "download", "goAlbum", "goArtist", "-", "favorite"),
            DeskLayout.menuPlan(DeskLayout.menuItems("")))
        assertEquals(Route.Home, DeskLayout.startRoute(""))
        assertTrue(DeskLayout.libraryGrid("albums") && DeskLayout.libraryGrid("artists"))
        assertEquals(listOf("1", "1", "1", "Recent"), listOf("albums", "artists", "titles", "playlists").map(DeskLayout::librarySort))
        assertTrue(DeskLayout.isDefault())
    }

    @Test fun theSidebarFollowsThePhonesTabs() {
        // The phone's bar with Downloads and Favourites as tabs, Explore hidden, Library first.
        val text = LayoutSettings.tabList.encode(listOf("library", "downloads", "home", "favorites", "explore", "search").map {
            com.ketvoice.app.data.LayoutList.Entry(it, it != "explore" && it != "search")
        })
        assertEquals(listOf("header", "recent", "artists", "albums", "songs", "stats", "downloads", "home", "favorites"), DeskLayout.sidebarRows(text))
        // Home hidden: the window opens on the first entry; the library hidden: Statistics stays reachable.
        val noHome = LayoutSettings.tabList.encode(listOf("explore", "library", "home", "search", "downloads", "favorites").map {
            com.ketvoice.app.data.LayoutList.Entry(it, it == "explore" || it == "library")
        })
        assertEquals(Route.Explore, DeskLayout.startRoute(noHome))
        val noLibrary = LayoutSettings.tabList.encode(LayoutSettings.tabList.defaults.map { it.copy(shown = it.id == "home" || it.id == "downloads") })
        assertEquals(listOf("home", "downloads", "stats"), DeskLayout.sidebarRows(noLibrary))
    }

    @Test fun everyWindowChoiceIsValidOnThePhoneAndBack() {
        var bar = ""
        bar = DeskLayout.move(DeskLayout.bar, bar, DeskLayout.barLabels, 3, 0)          // output first
        bar = DeskLayout.toggle(DeskLayout.bar, bar, "like")
        assertEquals(listOf("output", "lyrics", "queue", "account", "like"), DeskLayout.barButtons(bar))
        assertTrue(LayoutSettings.playerList.valid(bar))
        assertEquals(bar, PersonalBackup.setting(LayoutSettings.PLAYER, bar))
        // The phone's ids the window has no button for keep their slots (and stay hidden for the phone).
        assertEquals(listOf("ketmix", "sleep", "share"), DeskLayout.bar.parse(bar).takeLast(3).map { it.id })
        // A phone arrangement reads as it is.
        val phone = LayoutSettings.playerList.encode(listOf("queue", "lyrics", "sleep", "like", "output", "account", "ketmix", "share", "playlist", "download")
            .map { com.ketvoice.app.data.LayoutList.Entry(it, it in setOf("queue", "lyrics", "sleep", "like")) })
        assertTrue(DeskLayout.bar.valid(phone))
        assertEquals(listOf("queue", "lyrics", "like"), DeskLayout.barButtons(phone))
        // The queue never leaves the bar, and five buttons is the most.
        assertFalse(DeskLayout.canToggle(DeskLayout.bar, "", "queue"))
        val five = listOf("like", "playlist", "download").fold("") { t, id -> DeskLayout.toggle(DeskLayout.bar, t, id) }
        assertEquals(5, DeskLayout.bar.shown(five).size)
        assertFalse(DeskLayout.canToggle(DeskLayout.bar, five, "download"))
    }

    @Test fun theWindowsDefaultBarIsNeverThePhonesDefault() {
        // The phone empties its own default arrangement; the window's moves keep the phone-only ids last, so a window
        // arrangement never reads as the phone's « default » and comes back empty.
        val phoneDefault = LayoutSettings.playerList.defaults
        val labels = DeskLayout.barLabels
        for (from in labels.keys.indices) for (to in labels.keys.indices) {
            val text = DeskLayout.move(DeskLayout.bar, "", labels, from, to)
            assertNotEquals(phoneDefault, DeskLayout.bar.parse(text).takeIf { text.isNotEmpty() })
        }
    }

    @Test fun storedTextOfTheBarAndMenuIsKeptAsItIs() {
        val p = android.content.Context.app.getSharedPreferences("desk-layout-test", android.content.Context.MODE_PRIVATE)
        val phoneOrder = "lyrics,output,account,queue,-like,-ketmix,-sleep,-share,-playlist,-download"
        p.edit().putString(LayoutSettings.PLAYER, phoneOrder).putString(LayoutSettings.MENU, "favorite,playNext,enqueue,download,playlist,radio,share,mixPoint,lyricsEditor,about")
            .putString(LayoutSettings.TABS, "nonsense,,").commit()
        DeskLayout.load(p)
        assertEquals(phoneOrder, LayoutSettings.player.value)
        assertEquals(listOf("lyrics", "output", "account", "queue"), DeskLayout.barButtons())
        assertEquals(listOf("favorite", "playNext", "enqueue", "-", "download", "goAlbum", "goArtist", "-", "playlist", "mixPoint", "lyricsEditor"),
            DeskLayout.menuPlan(DeskLayout.menuItems()))
        assertEquals("", LayoutSettings.tabs.value)
        p.edit().clear().commit()
    }

    @Test fun menuSeparatorsStayTidy() {
        assertEquals(listOf("playNext", "-", "goAlbum", "goArtist"), DeskLayout.menuPlan(listOf("playNext")))
        assertEquals(listOf("download", "goAlbum", "goArtist", "-", "favorite"), DeskLayout.menuPlan(listOf("download", "favorite")))
        assertEquals(listOf("a", "-", "b"), DeskLayout.tidy(listOf("-", "a", "-", "-", "b", "-")))
    }

    @Test fun librarySortsAreThePhonesValues() {
        DeskLayout.setLibrarySort("albums", "0")
        DeskLayout.setLibrarySort("titles", "1")
        assertEquals("albums=0", LayoutSettings.librarySorts.value)
        assertEquals("albums=0", PersonalBackup.setting(LayoutSettings.LIBRARY_SORTS, "albums=0"))
        assertEquals("0", DeskLayout.librarySort("albums"))
        DeskLayout.setLibrarySort("artists", "9")
        assertEquals("1", DeskLayout.librarySort("artists"))
        assertEquals(null, LibraryViews.initialKey("0"))
        assertEquals(LibraryViews.SortKey.ARTIST, LibraryViews.initialKey("2"))
        val p = listOf(UserPlaylist("b", "Bravo", List(1) { Fixtures.tracks[0] }), UserPlaylist("a", "alpha", Fixtures.tracks.take(3)))
        assertEquals(p, DeskLayout.sortedPlaylists(p))
        DeskLayout.setLibrarySort("playlists", "Title")
        assertEquals(listOf("a", "b"), DeskLayout.sortedPlaylists(p).map { it.id })
        DeskLayout.reset()
        assertTrue(DeskLayout.isDefault())
    }

    @Test fun theShellDrawsTheChosenLayout() = runComposeUiTest {
        LayoutSettings.tabs.value = LayoutSettings.tabList.encode(LayoutSettings.tabList.defaults.map {
            when (it.id) { "explore" -> it.copy(shown = false); "favorites" -> it.copy(shown = true); else -> it }
        })
        LayoutSettings.home.value = LayoutSettings.homeList.encode(LayoutSettings.homeList.defaults.map { if (it.id == "forYou") it.copy(shown = false) else it })
        LayoutSettings.player.value = DeskLayout.toggle(DeskLayout.bar, "", "like")
        LayoutSettings.libraryViews.value = "albums=list"
        val player = RecordingPlayer().playing(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true) }
        onNodeWithTag("nav:Explorer").assertDoesNotExist()
        onNodeWithTag("nav:Favoris").performClick()
        onNodeWithTag("page:favorites").assertExists()
        onNodeWithTag("bar-like").assertExists()
        onNodeWithTag("nav:Albums").performClick()
        onNodeWithTag("page:albums").assertExists()
        onNodeWithTag("album-line:Marée basse").assertExists()
        onNodeWithTag("nav:Accueil").performClick()
        onNodeWithTag("page:home").assertExists()
    }

    @Test fun theMenuFollowsTheChosenOrder() {
        // « J'aime » first, « Ajouter à la file d'attente » hidden; « Aller à l'artiste » stays.
        LayoutSettings.menu.value = DeskLayout.toggle(DeskLayout.menu, DeskLayout.move(DeskLayout.menu, "", DeskLayout.menuLabels, 6, 0), "enqueue")
        val menu = TrackActions(RecordingPlayer(), Fixtures.library(), Navigator()).menu(Fixtures.tracks[0])
        assertEquals("like", menu.first().tag)
        assertTrue(menu.none { it.tag == "enqueue" })
        assertTrue(menu.any { it.tag == "go-artist" })
        // The lyrics editor's slot: its item appears where the order puts it once the editor provides it.
        TrackMenuSlots.lyricsEditor = { MenuItem("Éditer les paroles…", null, "lyrics-editor") }
        val withEditor = TrackActions(RecordingPlayer(), Fixtures.library(), Navigator()).menu(Fixtures.tracks[0]).map { it.tag }
        assertTrue(withEditor.indexOf("lyrics-editor") in 0 until withEditor.indexOf("download"))
    }
}
