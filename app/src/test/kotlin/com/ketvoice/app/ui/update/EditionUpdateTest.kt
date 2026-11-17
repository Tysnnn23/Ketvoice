package com.ketvoice.app.ui.update

import com.ketvoice.app.Edition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Mobile, Quest and Lite each update to their own APK of a release (Ketvoice Lite, 6.1). */
class EditionUpdateTest {
    private fun asset(tag: String, edition: Edition): JSONObject {
        val name = edition.assetName(tag.removePrefix("v"))
        return JSONObject().put("name", name).put("size", 123).put("state", "uploaded")
            .put("digest", "sha256:" + "b".repeat(64)).put("browser_download_url", UpdateFeed.DOWNLOAD_PREFIX + "$tag/$name")
    }

    private fun release(tag: String, vararg editions: Edition) = JSONObject().put("tag_name", tag).put("body", "Notes")
        .put("assets", JSONArray().apply { editions.forEach { put(asset(tag, it)) } })

    @Test fun assetNamesFollowThePublishedFiles() {
        assertEquals("Ketvoice-6.1.0.apk", Edition.Mobile.assetName("6.1.0"))
        assertEquals("Ketvoice-6.1.0-Quest.apk", Edition.Quest.assetName("6.1.0"))
        assertEquals("Ketvoice-6.1.0-Lite.apk", Edition.Lite.assetName("6.1.0"))
    }

    @Test fun buildFlagsMapToOneEdition() {
        assertEquals(Edition.Mobile, Edition.of(quest = false, lite = false))
        assertEquals(Edition.Quest, Edition.of(quest = true, lite = false))
        assertEquals(Edition.Lite, Edition.of(quest = false, lite = true))
    }

    @Test fun everyEditionPicksItsOwnAssetFromTheSameRelease() {
        val feed = JSONArray().put(release("v6.1.0", Edition.Quest, Edition.Lite, Edition.Mobile))
        assertTrue(UpdateFeed.select(feed, "6.0.0", Edition.Mobile)!!.apkUrl.endsWith("/Ketvoice-6.1.0.apk"))
        assertTrue(UpdateFeed.select(feed, "6.0.0", Edition.Quest)!!.apkUrl.endsWith("/Ketvoice-6.1.0-Quest.apk"))
        val lite = UpdateFeed.select(feed, "6.0.0", Edition.Lite)!!
        assertTrue(lite.apkUrl.endsWith("/Ketvoice-6.1.0-Lite.apk"))
        assertEquals("b".repeat(64), lite.sha256)
    }

    @Test fun liteNeverFallsBackToTheMobileApk() {
        // A release published before Ketvoice Lite existed only has the mobile and Quest APKs.
        val older = JSONArray().put(release("v6.0.1", Edition.Mobile, Edition.Quest))
        assertNull(UpdateFeed.select(older, "6.0.0", Edition.Lite))
        assertNotNull(UpdateFeed.select(older, "6.0.0", Edition.Mobile))
        // The newest release carrying a Lite APK is offered even when a newer one lacks it.
        val mixed = JSONArray().put(release("v6.2.0", Edition.Mobile)).put(release("v6.1.0", Edition.Mobile, Edition.Lite))
        assertEquals("v6.1.0", UpdateFeed.select(mixed, "6.0.0", Edition.Lite)?.tag)
        assertEquals("v6.2.0", UpdateFeed.select(mixed, "6.0.0", Edition.Mobile)?.tag)
    }

    @Test fun mobileAndQuestNeverPickTheLiteApk() {
        val liteOnly = JSONArray().put(release("v6.1.0", Edition.Lite))
        assertNull(UpdateFeed.select(liteOnly, "6.0.0", Edition.Mobile))
        assertNull(UpdateFeed.select(liteOnly, "6.0.0", Edition.Quest))
        // The boolean form used before Lite keeps its meaning.
        assertNull(UpdateFeed.select(liteOnly, "6.0.0", false))
        assertNull(UpdateFeed.select(liteOnly, "6.0.0", true))
    }
}
