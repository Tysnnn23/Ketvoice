package com.ketvoice.app.ui

import com.ketvoice.app.data.CatalogRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiNavigationTest {
    @After fun reset() {
        AppUi.selectTab(AppUi.HOME)
        AppUi.playlistId.value = null
        AppUi.catalogRequest.value = null
        AppUi.artistRequest.value = null
        AppUi.searchSeed.value = ""
    }

    @Test fun returnsThroughNestedRoutesWithTheirArguments() {
        AppUi.selectTab(AppUi.LIBRARY)
        AppUi.openPlaylist("first")
        AppUi.pushOverlay(AppUi.SEARCH)
        AppUi.searchSeed.value = "titre"
        AppUi.openArtist("UC123", "Artiste")
        AppUi.openCatalog(CatalogRequest("album", "album-1", "Album"))
        AppUi.closeOverlay()
        assertEquals(AppUi.ARTIST, AppUi.overlay.intValue)
        assertEquals("UC123", AppUi.artistRequest.value?.id)
        AppUi.closeOverlay()
        assertEquals(AppUi.SEARCH, AppUi.overlay.intValue)
        assertEquals("titre", AppUi.searchSeed.value)
        AppUi.closeOverlay()
        assertEquals(AppUi.PLAYLIST, AppUi.overlay.intValue)
        assertEquals("first", AppUi.playlistId.value)
        AppUi.closeOverlay()
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        assertEquals(AppUi.LIBRARY, AppUi.tab.intValue)
    }

    @Test fun sameKindOfRouteCanReturnToPreviousArtistAndAlbum() {
        AppUi.openArtist("UC1", "A")
        AppUi.openArtist("UC2", "B")
        AppUi.closeOverlay()
        assertEquals("UC1", AppUi.artistRequest.value?.id)
        AppUi.openCatalog(CatalogRequest("album", "a", "A"))
        AppUi.openCatalog(CatalogRequest("album", "b", "B"))
        AppUi.closeOverlay()
        assertEquals("a", AppUi.catalogRequest.value?.id)
    }

    @Test fun newTabDiscardsOldHistory() {
        AppUi.openArtist("UC1", "A")
        AppUi.selectTab(AppUi.EXPLORER)
        AppUi.openSearch("mot")
        AppUi.closeOverlay()
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        assertEquals(AppUi.EXPLORER, AppUi.tab.intValue)
    }

    @Test fun returningFromSettingsNestedScreensRestoresSettings() {
        AppUi.pushOverlay(AppUi.SETTINGS)
        AppUi.pushOverlay(AppUi.DOWNLOADS)
        AppUi.closeOverlay()
        assertEquals(AppUi.SETTINGS, AppUi.overlay.intValue)
        AppUi.closeOverlay()
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
    }

    @Test fun openingPlaylistClosesThePlayerAndKeepsQueueState() {
        AppUi.showPlayer.value = true
        AppUi.openPlaylist("p1")
        assertFalse(AppUi.showPlayer.value)
        assertEquals(AppUi.PLAYLIST, AppUi.overlay.intValue)
        AppUi.closeOverlay()
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        assertTrue(AppUi.showPlayer.value.not())
    }
}
