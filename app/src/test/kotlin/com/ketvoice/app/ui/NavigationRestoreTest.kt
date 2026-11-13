package com.ketvoice.app.ui

import com.ketvoice.app.data.CatalogRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NavigationRestoreTest {
    @Before fun fresh() { reset() }
    @After fun reset() {
        AppUi.selectTab(AppUi.HOME)
        AppUi.playlistId.value = null
        AppUi.catalogRequest.value = null
        AppUi.artistRequest.value = null
        AppUi.searchSeed.value = ""
        AppUi.resetNavigationRestoreForTests()
    }

    @Test fun aSavedStackComesBackWithItsArgumentsAndItsBackOrder() {
        AppUi.selectTab(AppUi.LIBRARY)
        AppUi.openPlaylist("first")
        AppUi.openArtist("UC123", "Artiste")
        AppUi.openCatalog(CatalogRequest("album", "album-1", "Album"))
        val saved = AppUi.saveNavigation()

        // Process death: the singleton starts again from its defaults.
        AppUi.selectTab(AppUi.HOME)
        AppUi.catalogRequest.value = null
        AppUi.artistRequest.value = null
        AppUi.playlistId.value = null
        AppUi.restoreNavigationOnce(saved)

        assertEquals(AppUi.LIBRARY, AppUi.tab.intValue)
        assertEquals(AppUi.CATALOG, AppUi.overlay.intValue)
        assertEquals(CatalogRequest("album", "album-1", "Album"), AppUi.catalogRequest.value)
        AppUi.closeOverlay()
        assertEquals(AppUi.ARTIST, AppUi.overlay.intValue)
        assertEquals("Artiste", AppUi.artistRequest.value?.name)
        AppUi.closeOverlay()
        assertEquals(AppUi.PLAYLIST, AppUi.overlay.intValue)
        assertEquals("first", AppUi.playlistId.value)
        AppUi.closeOverlay()
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        assertEquals(AppUi.LIBRARY, AppUi.tab.intValue)
    }

    @Test fun restoreHappensOnceAndNeverOverALiveState() {
        AppUi.selectTab(AppUi.EXPLORER)
        AppUi.openSettings()
        val saved = AppUi.saveNavigation()
        // A recreated activity in a living process keeps the real state.
        AppUi.selectTab(AppUi.LIBRARY)
        AppUi.restoreNavigationOnce(saved)
        assertEquals(AppUi.LIBRARY, AppUi.tab.intValue)
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        // And a second call in the same process does nothing either.
        AppUi.selectTab(AppUi.HOME)
        AppUi.restoreNavigationOnce(saved)
        assertEquals(AppUi.HOME, AppUi.tab.intValue)
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
    }

    @Test fun aColdLaunchDisablesLaterRestores() {
        AppUi.selectTab(AppUi.EXPLORER)
        val saved = AppUi.saveNavigation()
        AppUi.selectTab(AppUi.HOME)
        AppUi.markNavigationFresh()
        AppUi.restoreNavigationOnce(saved)
        assertEquals(AppUi.HOME, AppUi.tab.intValue)
    }

    @Test fun damagedOrUnsafeEntriesAreDropped() {
        assertNull(AppUi.parseNavigation(null))
        assertNull(AppUi.parseNavigation("{"))
        assertNull(AppUi.parseNavigation("""{"version":2,"tab":1,"entries":[]}"""))
        val parsed = AppUi.parseNavigation("""{"version":1,"tab":9,"entries":[
            {"overlay":${AppUi.QUEUE}}, {"overlay":${AppUi.PLAYLIST}}, {"overlay":${AppUi.CATALOG},"catalog":{"kind":"","id":"x","title":"t"}},
            {"overlay":${AppUi.ARTIST},"artist":{"id":"","name":"Nom"}}, {"overlay":999}, "junk",
            {"overlay":${AppUi.SEARCH},"searchSeed":"rock"}]}""")!!
        assertEquals(AppUi.HOME, parsed.first)
        assertEquals(listOf(AppUi.ARTIST, AppUi.SEARCH), parsed.second.map { it.overlay })
        assertEquals("rock", parsed.second.last().searchSeed)
    }

    @Test fun heldBackGestureShrinksThePageAwayFromTheSwipedEdge() {
        assertEquals(BackPageTransform(1f, 0f), predictiveBackPage(0f, false, 80f))
        val left = predictiveBackPage(1f, false, 80f)
        assertEquals(.92f, left.scale, 1e-4f)
        assertEquals(80f, left.translationX, 1e-4f)
        assertEquals(-80f, predictiveBackPage(1f, true, 80f).translationX, 1e-4f)
        val invalid = predictiveBackPage(Float.NaN, true, 80f)
        assertEquals(1f, invalid.scale, 0f)
        assertEquals(0f, invalid.translationX, 1e-6f)
    }
}
