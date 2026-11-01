package com.ketvoice.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtistSignaturesTest {
    private val mbid = "b95ce3ff-3d05-4e87-9e01-c97b66af13d4"
    private val image = "https://assets.fanart.tv/fanart/eminem-example.png"
    private fun response(artist: String = "QA artist", url: String = image,
                         provider: String = "fanart.tv", source: String = "https://fanart.tv/artist/$mbid/") = JSONObject()
        .put("artist", artist).put("mbid", mbid)
        .put("logo", JSONObject().put("url", url).put("format", "png").put("kind", "logo")
            .put("provider", provider).put("source", source))

    @Test fun acceptsBrokeredArtistLogoWithAttributionAndPreservesItsColors() {
        val value = ArtistSignatures.parse(response(), " QA ARTIST ")!!
        assertEquals("png", value.format)
        assertEquals("fanart.tv", value.attribution)
        assertFalse(value.monochrome)
        assertEquals("https://fanart.tv/artist/$mbid/", value.source)
    }
    @Test fun rejectsADifferentArtistNameOrMalformedMusicBrainzId() {
        assertNull(ArtistSignatures.parse(response("Other artist"), "QA artist"))
        assertNull(ArtistSignatures.parse(response().put("mbid", "../other"), "QA artist"))
    }
    @Test fun rejectsUnexpectedProvidersAndAttribution() {
        assertNull(ArtistSignatures.parse(response(provider = "unknown"), "QA artist"))
        assertNull(ArtistSignatures.parse(response(source = "https://evil.example/"), "QA artist"))
    }
    @Test fun rejectsInsecureUnexpectedOrCredentialBearingImageUrls() {
        listOf(image.replace("https:", "http:"), image.replace("assets.fanart.tv", "evil.example"),
            image.replace("assets.fanart.tv", "assets.fanart.tv.evil.example"), image + "?api_key=not-allowed",
            image.replace("/fanart/", "/private/")).forEach {
            assertNull(ArtistSignatures.parse(response(url = it), "QA artist"))
        }
    }
    @Test fun missingArtworkFallsBackToTheNormalName() {
        assertNull(ArtistSignatures.parse(JSONObject("{\"error\":\"Artist artwork not found\"}"), "QA artist"))
        assertNull(ArtistSignatures.parse(response().put("logo", JSONObject.NULL), "QA artist"))
    }
}
