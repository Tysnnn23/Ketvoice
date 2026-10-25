package com.ketvoice.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistHeaderImageTest {
    @Test fun prefersWideHeaderArtworkOverSquarePortrait() {
        val header = JSONObject("""{
          "thumbnail":{"thumbnails":[{"url":"https://example.com/portrait","width":1200,"height":1200}]},
          "foregroundThumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
            {"url":"https://example.com/banner-small","width":640,"height":360},
            {"url":"https://example.com/banner-large","width":1920,"height":1080}
          ]}}}
        }""")
        assertEquals("https://example.com/banner-large", YouTubeCatalog.artistHeaderImage(header))
    }

    @Test fun retainsPortraitWhenNoWideArtworkIsAvailable() {
        val header = JSONObject("""{
          "thumbnail":{"thumbnails":[{"url":"https://example.com/portrait","width":800,"height":800}]},
          "backgroundThumbnail":{"thumbnails":[{"url":"https://example.com/blurred","width":1920,"height":1080}]}
        }""")
        assertEquals("https://example.com/portrait", YouTubeCatalog.artistHeaderImage(header))
    }
}
