package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.ui.chrome.TabDestination
import com.ketvoice.app.ui.chrome.TabLayout
import com.ketvoice.app.ui.common.SwipeAction
import com.ketvoice.app.ui.common.TitleMenuLayout
import com.ketvoice.app.ui.home.HomeSection
import com.ketvoice.app.ui.home.HomeSections
import com.ketvoice.app.ui.library.LibraryLayout
import com.ketvoice.app.ui.library.PlaylistSort
import com.ketvoice.app.ui.player.MiniAction
import com.ketvoice.app.ui.player.PlayerAction
import com.ketvoice.app.ui.player.PlayerArtworkSize
import com.ketvoice.app.ui.player.PlayerLayout
import com.ketvoice.app.ui.AppUi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 9.0 « Disposition »: the stored layout choices, their limits, their defaults (the 8.4 layout) and their round trips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutSettingsTest {
    @Before @After fun reset() { LayoutSettings.resetAll(); AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME) }

    @Test fun theDefaultsAreTheLayoutOf84() {
        assertTrue(LayoutSettings.isDefault())
        assertEquals(listOf(TabDestination.Home, TabDestination.Explore, TabDestination.Library), TabLayout.current())
        assertEquals(HomeSection.entries.toList(), HomeSections.shown(""))
        assertEquals(listOf(PlayerAction.Lyrics, PlayerAction.Output, PlayerAction.Account, PlayerAction.Queue), PlayerLayout.bar(""))
        assertEquals(PlayerLayout.DEFAULT_BAR, PlayerLayout.bar(LayoutSettings.player.value))
        assertEquals(listOf(MiniAction.Next), PlayerLayout.mini(""))
        assertEquals(listOf("playNext", "enqueue", "favorite", "download", "playlist", "radio", "share", "mixPoint", "lyricsEditor", "about"),
            TitleMenuLayout.shown(""))
        assertEquals(SwipeAction.PlayNext, SwipeAction.toEnd())
        assertEquals(SwipeAction.Enqueue, SwipeAction.toStart())
        assertFalse(LibraryLayout.grid("playlists")); assertTrue(LibraryLayout.grid("albums")); assertTrue(LibraryLayout.grid("artists"))
        listOf("albums", "artists", "titles").forEach { assertEquals(it, 0, LibraryLayout.sort(it)) }
        assertEquals(PlaylistSort.Recent, LibraryLayout.playlistSort())
        assertEquals(1f, PlayerLayout.artworkScale(LayoutSettings.artwork.intValue))
        assertTrue(LayoutSettings.canvas.value)
        // The defaults are stored as nothing: a later default reaches listeners who changed nothing.
        assertEquals("", LayoutSettings.tabList.encode(LayoutSettings.tabList.defaults))
    }

    @Test fun tabsAreTwoToFiveWithHomeOrLibrary() {
        val list = LayoutSettings.tabList
        assertTrue(list.valid("library,search,-home,-explore,-downloads,-favorites"))
        assertEquals(listOf(TabDestination.Library, TabDestination.Search), TabLayout.parse("library,search,-home,-explore,-downloads,-favorites"))
        // Only one tab, six tabs, or neither Home nor Library: refused, read as the default.
        assertFalse(list.valid("home,-explore,-library,-search,-downloads,-favorites"))
        assertFalse(list.valid("home,explore,library,search,downloads,favorites"))
        assertFalse(list.valid("explore,search,-home,-library"))
        assertEquals(TabLayout.DEFAULT, TabLayout.parse("explore,search,-home,-library"))
        assertEquals(TabLayout.DEFAULT, TabLayout.parse("home"))
        // Unknown ids (a newer version) are dropped; ids left out take their default place and visibility.
        assertEquals(listOf(TabDestination.Favorites, TabDestination.Home, TabDestination.Explore, TabDestination.Library),
            TabLayout.parse("favorites,radio,home"))
        assertFalse(list.valid("favorites,radio,home"))
        assertFalse(list.valid("home,home,explore"))
        assertFalse(list.valid("home,ex plore"))
        // The toggles stay within the limits.
        val defaults = list.defaults
        val homeIndex = defaults.indexOfFirst { it.id == "home" }
        assertTrue(list.canToggle(defaults, homeIndex))
        assertFalse("never fewer than two tabs", list.canToggle(list.toggle(defaults, homeIndex), defaults.indexOfFirst { it.id == "explore" }))
        val five = listOf("search", "downloads").fold(defaults) { acc, id -> list.toggle(acc, acc.indexOfFirst { it.id == id }) }
        assertFalse("never more than five", list.canToggle(five, five.indexOfFirst { it.id == "favorites" }))
        val moved = list.move(defaults, defaults.indexOfFirst { it.id == "library" }, 0)
        assertEquals("library,home,explore,-search,-downloads,-favorites", list.encode(moved))
    }

    @Test fun theLitTabFollowsThePageAndTheStartTabTheBar() {
        val tabs = listOf(TabDestination.Library, TabDestination.Search, TabDestination.Favorites, TabDestination.Home)
        assertEquals(0, TabLayout.selected(tabs, AppUi.LIBRARY, AppUi.NONE, null))
        assertEquals(1, TabLayout.selected(tabs, AppUi.HOME, AppUi.SEARCH, null))
        assertEquals(2, TabLayout.selected(tabs, AppUi.LIBRARY, AppUi.CATALOG, "favorites"))
        assertEquals("an album over Home lights Home", 3, TabLayout.selected(tabs, AppUi.HOME, AppUi.CATALOG, "album"))
        assertEquals(-1, TabLayout.selected(tabs, AppUi.EXPLORER, AppUi.NONE, null))
        assertEquals(AppUi.HOME, TabLayout.startTab(tabs))
        assertEquals(AppUi.LIBRARY, TabLayout.startTab(listOf(TabDestination.Search, TabDestination.Library)))
        // The default bar: what 8.4 lit.
        assertEquals(1, TabLayout.selected(TabLayout.DEFAULT, AppUi.EXPLORER, AppUi.SEARCH, null))
        // Opening a page tab keeps the root tab under it.
        AppUi.selectTab(AppUi.LIBRARY)
        TabLayout.open(TabDestination.Downloads)
        assertEquals(AppUi.DOWNLOADS, AppUi.overlay.intValue)
        assertEquals(AppUi.LIBRARY, AppUi.tab.intValue)
        TabLayout.open(TabDestination.Home)
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        assertEquals(AppUi.HOME, AppUi.tab.intValue)
    }

    @Test fun homeSectionsMoveAndHide() {
        val list = LayoutSettings.homeList
        var entries = list.defaults
        entries = list.move(entries, entries.indexOfFirst { it.id == "replay" }, 0)
        entries = list.toggle(entries, entries.indexOfFirst { it.id == "moods" })
        val text = list.encode(entries)
        assertTrue(list.valid(text))
        val shown = HomeSections.shown(text)
        assertEquals(HomeSection.Replay, shown.first())
        assertFalse(HomeSection.Moods in shown)
        assertEquals(HomeSection.entries.size - 1, shown.size)
        // Every section may be hidden: the greeting stays.
        assertTrue(list.valid(HomeSection.entries.joinToString(",") { "-" + it.id }))
        assertEquals(emptyList<HomeSection>(), HomeSections.shown(HomeSection.entries.joinToString(",") { "-" + it.id }))
    }

    @Test fun playerAndMiniPlayerButtonsStayWithinTheirLimits() {
        val bar = LayoutSettings.playerList
        assertFalse("the queue is always there", bar.valid("lyrics,like,-queue"))
        assertFalse("five at most", bar.valid("lyrics,output,account,queue,like,ketmix"))
        assertTrue(bar.valid("like,queue,-lyrics,-output,-account"))
        val actions = PlayerLayout.bar("like,sleep,share,queue,-lyrics,-output,-account")
        assertEquals(listOf(PlayerAction.Like, PlayerAction.Sleep, PlayerAction.Share, PlayerAction.Queue), actions)
        val row = PlayerLayout.row(actions)
        assertEquals(PlayerAction.Like, row.start); assertEquals(PlayerAction.Queue, row.end)
        assertEquals(listOf(PlayerAction.Sleep, PlayerAction.Share), row.middle)
        assertEquals(140f, PlayerLayout.capsuleWidthDp(PlayerLayout.row(PlayerLayout.DEFAULT_BAR).middle.size))
        assertEquals(PlayerLayout.Row(PlayerAction.Like, emptyList(), PlayerAction.Queue), PlayerLayout.row(listOf(PlayerAction.Like, PlayerAction.Queue)))
        val mini = LayoutSettings.miniList
        assertTrue("none at all is allowed", mini.valid("-next"))
        assertEquals(emptyList<MiniAction>(), PlayerLayout.mini("-next"))
        assertFalse("two at most", mini.valid("next,previous,like"))
        val two = PlayerLayout.mini("like,previous,-next")
        assertEquals(listOf(MiniAction.Previous), PlayerLayout.miniBefore(two))
        assertEquals(listOf(MiniAction.Like), PlayerLayout.miniAfter(two))
    }

    @Test fun theArtworkKeepsTheShapeOf83AndOnlyScalesWhenAsked() {
        listOf(1f, .5625f, 1.78f, null).forEach { aspect ->
            assertEquals(PlayerArtworkSize.heightDp(390f, aspect, 500f), PlayerArtworkSize.heightDp(390f, aspect, 500f, 1f), 0f)
        }
        assertEquals(390f * .85f, PlayerArtworkSize.heightDp(390f, 1f, 500f, PlayerLayout.artworkScale(1)), .01f)
        assertEquals(390f * 1.15f, PlayerArtworkSize.heightDp(390f, 1f, 500f, PlayerLayout.artworkScale(2)), .01f)
        // Never past the controls, never below a fraction of the shortest artwork.
        assertEquals(500f, PlayerArtworkSize.heightDp(390f, .5625f, 500f, 1.15f), 0f)
        assertEquals(500f * .85f, PlayerArtworkSize.heightDp(390f, .5625f, 500f, .85f), .01f)
        assertEquals(PlayerArtworkSize.MIN_DP * 1.15f, PlayerArtworkSize.heightDp(390f, 3f, 500f, 1.15f), .01f)
    }

    @Test fun libraryViewsAndOrdersAreKeptPerSection() {
        LayoutSettings.setLibraryView("albums", "list")
        LayoutSettings.setLibraryView("playlists", "grid")
        LayoutSettings.setLibrarySort("titles", "2")
        LayoutSettings.setLibrarySort("playlists", "Size")
        LayoutSettings.setLibrarySort("artists", "5")
        LayoutSettings.setLibraryView("titles", "grid")
        assertFalse(LibraryLayout.grid("albums")); assertTrue(LibraryLayout.grid("playlists")); assertTrue(LibraryLayout.grid("artists"))
        assertEquals(2, LibraryLayout.sort("titles")); assertEquals(0, LibraryLayout.sort("artists"))
        assertEquals(PlaylistSort.Size, LibraryLayout.playlistSort())
        assertEquals("playlists=grid,albums=list", LayoutSettings.libraryViews.value)
        // Back to a default: nothing stored for it.
        LayoutSettings.setLibraryView("albums", "grid")
        assertEquals("playlists=grid", LayoutSettings.libraryViews.value)
        assertEquals("Nombre de titres", LibraryLayout.sortLabel("playlists"))
    }

    @Test fun backupsAndSyncCarryOnlyCheckedLayoutValues() {
        val backup = PersonalBackup
        assertEquals("library,search,-home,-explore,-downloads,-favorites",
            backup.setting(LayoutSettings.TABS, "library,search,-home,-explore,-downloads,-favorites"))
        assertNull(backup.setting(LayoutSettings.TABS, "explore,-home,-library"))
        assertEquals("a partial text keeps the default for what it leaves out", "search", backup.setting(LayoutSettings.TABS, "search"))
        assertNull(backup.setting(LayoutSettings.PLAYER, "lyrics,-queue"))
        assertEquals("", backup.setting(LayoutSettings.HOME, ""))
        assertEquals(2, backup.setting(LayoutSettings.ARTWORK, 2))
        assertNull(backup.setting(LayoutSettings.ARTWORK, 3))
        assertEquals(false, backup.setting(LayoutSettings.CANVAS, false))
        assertEquals("favorite", backup.setting(LayoutSettings.SWIPE_END, "favorite"))
        assertNull(backup.setting(LayoutSettings.SWIPE_START, "delete everything"))
        assertEquals("albums=list", backup.setting(LayoutSettings.LIBRARY_VIEWS, "albums=list"))
        assertNull(backup.setting(LayoutSettings.LIBRARY_SORTS, "albums=9"))
        // The account's settings sync knows every layout key (the desktop build shares that file, so they are spelled out).
        assertEquals(LayoutSettings.keys, com.ketvoice.app.sync.SettingsKind.keys.filter { it.startsWith("layout") }.toSet())
    }

    @Test fun settingsKeepTheLayoutAcrossARestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore.load(context)
        assertTrue("nothing stored is the 8.4 layout", LayoutSettings.isDefault())
        LayoutSettings.tabs.value = "library,home,favorites,-explore,-search,-downloads"
        LayoutSettings.home.value = "recent,-featured"
        LayoutSettings.player.value = "like,queue,-lyrics,-output,-account"
        LayoutSettings.mini.value = "previous,next"
        LayoutSettings.artwork.intValue = 1
        LayoutSettings.canvas.value = false
        LayoutSettings.setLibraryView("albums", "list")
        LayoutSettings.setLibrarySort("titles", "1")
        LayoutSettings.menu.value = "share,playNext,enqueue,favorite,download,playlist,-radio,mixPoint,about"
        LayoutSettings.swipeEnd.value = "favorite"
        LayoutSettings.swipeStart.value = "none"
        SettingsStore.save()
        LayoutSettings.resetAll()
        SettingsStore.load(context)
        assertEquals(listOf(TabDestination.Library, TabDestination.Home, TabDestination.Favorites), TabLayout.current())
        assertEquals(HomeSection.Recent, HomeSections.shown(LayoutSettings.home.value).first())
        assertFalse(HomeSection.Featured in HomeSections.shown(LayoutSettings.home.value))
        assertEquals(listOf(PlayerAction.Like, PlayerAction.Queue), PlayerLayout.bar(LayoutSettings.player.value))
        assertEquals(listOf(MiniAction.Previous, MiniAction.Next), PlayerLayout.mini(LayoutSettings.mini.value))
        assertEquals(1, LayoutSettings.artwork.intValue)
        assertFalse(LayoutSettings.canvas.value)
        assertFalse(LibraryLayout.grid("albums")); assertEquals(1, LibraryLayout.sort("titles"))
        assertEquals("share", TitleMenuLayout.shown(LayoutSettings.menu.value).first())
        assertFalse("radio" in TitleMenuLayout.shown(LayoutSettings.menu.value))
        assertEquals(SwipeAction.Favorite, SwipeAction.toEnd()); assertEquals(SwipeAction.None, SwipeAction.toStart())
        // A broken value written by hand reads as the default, never as a crash.
        context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).edit()
            .putString(LayoutSettings.TABS, "explore").putInt(LayoutSettings.SWIPE_END, 4).putString(LayoutSettings.ARTWORK, "x").commit()
        SettingsStore.load(context)
        assertEquals(TabLayout.DEFAULT, TabLayout.current())
        assertEquals(SwipeAction.PlayNext, SwipeAction.toEnd())
        assertEquals(0, LayoutSettings.artwork.intValue)
        LayoutSettings.resetAll(); SettingsStore.save()
        assertTrue(LayoutSettings.isDefault())
    }
}
