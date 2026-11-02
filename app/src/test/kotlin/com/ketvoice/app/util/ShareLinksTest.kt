package com.ketvoice.app.util

import org.junit.Assert.*
import org.junit.Test

class ShareLinksTest {
    @Test fun artistLinkIsARealChannelPageWhenTheIdIsKnown() {
        val url = ShareLinks.artistUrl("UCedvOgsKFzcK3hA5taf3KoQ", "Eminem")
        assertEquals("https://music.youtube.com/channel/UCedvOgsKFzcK3hA5taf3KoQ", url)
    }

    @Test fun artistWithoutAChannelIdFallsBackToASearchPage() {
        val url = ShareLinks.artistUrl(null, "Juice WRLD")
        assertTrue(url.startsWith("https://music.youtube.com/search?q="))
        assertTrue(url.contains("Juice"))
    }

    @Test fun catalogueAlbumLinksToItsOwnPageAndImportsKeepTheKetvoicePage() {
        assertEquals("https://music.youtube.com/browse/MPREb_123",
            ShareLinks.albumPageUrl("MPREb_123", "Album", "Artiste"))
        assertTrue(ShareLinks.albumPageUrl("kv:upload:abc", "Album", "Artiste").startsWith(ShareLinks.LISTEN_PAGE))
    }

    @Test fun playlistAndCollectionLinksEncodeTheirIdentity() {
        val playlist = ShareLinks.playlistUrl("p 1/2", "Ma playlist")
        assertTrue(playlist.startsWith(ShareLinks.LISTEN_PAGE))
        assertTrue(playlist.contains("playlist=p+1%2F2") || playlist.contains("playlist=p%201%2F2"))
        assertTrue(playlist.contains("name=Ma+playlist"))
        val collection = ShareLinks.collectionUrl("favorites", "Titres que vous aimez")
        assertTrue(collection.contains("collection=favorites"))
        assertTrue(collection.contains("name=Titres+que+vous+aimez"))
    }
}
