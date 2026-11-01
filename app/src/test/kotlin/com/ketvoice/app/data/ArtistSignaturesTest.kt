package com.ketvoice.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtistSignaturesTest {
    private fun response(artist: String = "QA artist", url: String = "https://upload.wikimedia.org/wikipedia/commons/a/a0/QA.svg",
                         license: String = "PD-signature", status: String = "known", format: String = "svg") = JSONObject()
        .put("artist", JSONObject().put("name", artist))
        .put("signatures", org.json.JSONArray().put(JSONObject()
            .put("artist", JSONObject().put("name", artist))
            .put("asset", JSONObject().put("url", url).put("format", format))
            .put("source", JSONObject().put("url", "https://commons.wikimedia.org/wiki/File:QA.svg"))
            .put("license", JSONObject().put("name", license).put("status", status))))

    @Test fun acceptsTheObservedV1SchemaAndRetainsAttribution() {
        val value = ArtistSignatures.parse(response(), " QA ARTIST ")!!
        assertEquals("svg", value.format)
        assertEquals("PD-signature", value.license)
        assertTrue(value.source.startsWith("https://commons.wikimedia.org/"))
    }
    @Test fun neverSubstitutesAHomonymsSignature() {
        assertNull(ArtistSignatures.parse(response("Other artist"), "QA artist"))
    }
    @Test fun rejectsUnknownOrRestrictedLicenses() {
        assertNull(ArtistSignatures.parse(response(status = "unknown"), "QA artist"))
        assertNull(ArtistSignatures.parse(response(license = "All rights reserved"), "QA artist"))
    }
    @Test fun rejectsInsecureOrUnexpectedImageHostsAndFormats() {
        listOf("http://upload.wikimedia.org/a.svg", "https://evil.example/a.svg",
            "https://upload.wikimedia.org.evil.example/a.svg", "https://user@upload.wikimedia.org/a.svg").forEach {
            assertNull(it, ArtistSignatures.parse(response(url = it), "QA artist"))
        }
        assertNull(ArtistSignatures.parse(response(format = "html"), "QA artist"))
    }
    @Test fun missingArtistsAndEmptyResultsFallBackToTheNormalName() {
        assertNull(ArtistSignatures.parse(JSONObject("{\"error\":{\"code\":\"ARTIST_NOT_FOUND\"}}"), "QA artist"))
        assertNull(ArtistSignatures.parse(response().put("signatures", org.json.JSONArray()), "QA artist"))
    }
}
