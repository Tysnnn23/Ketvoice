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
        AppUi.searchSeed.value = "titre"
        AppUi.navigate(AppUi.SEARCH)
        AppUi.openArtist("UC123", "Artiste")
        AppUi.openCatalog(CatalogRequest("album", "album-1", "Album"))
        AppUi.back()
        assertEquals(AppUi.ARTIST, AppUi.overlay.intValue)
        assertEquals("UC123", AppUi.artistRequest.value?.id)
        AppUi.back()
        assertEquals(AppUi.SEARCH, AppUi.overlay.intValue)
        assertEquals("titre", AppUi.searchSeed.value)
        AppUi.back()
        assertEquals(AppUi.PLAYLIST, AppUi.overlay.intValue)
        assertEquals("first", AppUi.playlistId.value)
        AppUi.back()
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        assertEquals(AppUi.LIBRARY, AppUi.tab.intValue)
    }

    @Test fun sameKindOfRouteCanReturnToPreviousArtistAndAlbum() {
        AppUi.openArtist("UC1", "A")
        AppUi.openArtist("UC2", "B")
        AppUi.back()
        assertEquals("UC1", AppUi.artistRequest.value?.id)
        AppUi.openCatalog(CatalogRequest("album", "a", "A"))
        AppUi.openCatalog(CatalogRequest("album", "b", "B"))
        AppUi.back()
        assertEquals("a", AppUi.catalogRequest.value?.id)
    }

    @Test fun backRestoresPlayerAndNewTabDiscardsOldHistory() {
        AppUi.showPlayer.value = true
        AppUi.showLyrics.value = true
        AppUi.openArtist("UC1", "A")
        assertFalse(AppUi.showPlayer.value)
        AppUi.back()
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        assertTrue(AppUi.showPlayer.value)
        assertTrue(AppUi.showLyrics.value)
        AppUi.selectTab(AppUi.EXPLORER)
        AppUi.navigate(AppUi.SEARCH)
        AppUi.back()
        assertEquals(AppUi.EXPLORER, AppUi.tab.intValue)
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
    }

    @Test fun returningFromSettingsNestedDownloadsRestoresSettings() {
        AppUi.navigate(AppUi.SETTINGS)
        AppUi.navigate(AppUi.DOWNLOADS)
        AppUi.back()
        assertEquals(AppUi.SETTINGS, AppUi.overlay.intValue)
        AppUi.back()
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
    }
}
