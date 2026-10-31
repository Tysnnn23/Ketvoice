package com.ketvoice.app.ui.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateFeedTest {
    private fun release(tag: String, quest: Boolean = false): JSONObject {
        val name = "Ketvoice-${tag.removePrefix("v")}${if (quest) "-Quest" else ""}.apk"
        return JSONObject().put("tag_name", tag).put("name", "Ketvoice $tag").put("body", "## Automatic updates\nFixed.")
            .put("assets", JSONArray().put(JSONObject().put("name", name).put("size", 123)
                .put("state", "uploaded").put("digest", "sha256:" + "a".repeat(64))
                .put("browser_download_url", UpdateFeed.DOWNLOAD_PREFIX + "$tag/$name")))
    }

    @Test fun newestCompatibleVersionWinsRegardlessOfApiOrdering() {
        val feed = JSONArray().put(release("v3.9.0")).put(release("v3.10.1")).put(release("v3.10.0"))
        assertEquals("v3.10.1", UpdateFeed.select(feed, "3.8.0", false)?.tag)
    }

    @Test fun questAndMobileNeverChooseEachOthersPackage() {
        val feed = JSONArray().put(release("v3.10.1", true)).put(release("v3.10.1"))
        assertTrue(UpdateFeed.select(feed, "3.10.0", true)!!.apkUrl.endsWith("-Quest.apk"))
        assertFalse(UpdateFeed.select(feed, "3.10.0", false)!!.apkUrl.endsWith("-Quest.apk"))
        assertNull(UpdateFeed.select(JSONArray().put(release("v3.10.1")), "3.10.0", true))
    }

    @Test fun stableInstallationsIgnoreDraftsAndPrereleases() {
        val feed = JSONArray().put(release("v9.0.0").put("draft", true))
            .put(release("v8.0.0-BETA").put("prerelease", true)).put(release("v3.10.1"))
        assertEquals("v3.10.1", UpdateFeed.select(feed, "3.10.0", false)?.tag)
    }

    @Test fun incompleteReleaseDoesNotHideTheNextInstallableUpdate() {
        val feed = JSONArray().put(release("v4.0.0", true)).put(release("v3.10.1"))
        assertEquals("v3.10.1", UpdateFeed.select(feed, "3.10.0", false)?.tag)
    }

    @Test fun sameOlderOrMalformedVersionsAreNeverOffered() {
        listOf("v3.10.0", "v3.9.0", "v99junk", "../../evil", "latest").forEach {
            assertNull(it, UpdateFeed.select(JSONArray().put(release(it)), "3.10.0", false))
        }
    }

    @Test fun unuploadedEmptyOrExternalAssetsAreRejected() {
        listOf("state" to "new", "size" to 0, "browser_download_url" to "http://evil.example/file.apk",
            "browser_download_url" to "https://github.com/another/repo/file.apk", "digest" to "sha256:invalid").forEach { (key, value) ->
            val candidate = release("v3.10.1")
            candidate.getJSONArray("assets").getJSONObject(0).put(key, value)
            assertNull(key, UpdateFeed.select(JSONArray().put(candidate), "3.10.0", false))
        }
    }

    @Test fun digestAndEnglishNotesSurviveSelection() {
        val result = UpdateFeed.select(JSONArray().put(release("v3.10.1")), "3.10.0", false)!!
        assertEquals("a".repeat(64), result.sha256)
        assertTrue(result.notes.contains("## Automatic updates"))
        assertEquals("Automatic updates", UpdateChecker.parseSections(result.notes.joinToString("\n")).single().first)
    }

    @Test fun partialOrOversizedDownloadsFailWithoutTheOldTwoPercentTolerance() {
        assertTrue(UpdateInstaller.payloadMatches(100, 100, "a".repeat(64), "a".repeat(64)))
        assertFalse(UpdateInstaller.payloadMatches(99, 100, "a", null))
        assertFalse(UpdateInstaller.payloadMatches(101, 100, "a", null))
        assertFalse(UpdateInstaller.payloadMatches(0, 0, "a", null))
        assertFalse(UpdateInstaller.payloadMatches(100, 100, "a".repeat(64), "b".repeat(64)))
    }

    @Test fun wrongPackageSignatureAndNonIncreasingVersionAreRejected() {
        val installed = UpdateApkIdentity("com.ketvoice.app", 54, setOf("trusted"))
        assertTrue(UpdateInstaller.identityMatches(installed.copy(versionCode = 55), installed))
        assertFalse(UpdateInstaller.identityMatches(installed, installed))
        assertFalse(UpdateInstaller.identityMatches(installed.copy(versionCode = 53), installed))
        assertFalse(UpdateInstaller.identityMatches(installed.copy(packageName = "com.other", versionCode = 55), installed))
        assertFalse(UpdateInstaller.identityMatches(installed.copy(certificates = setOf("other"), versionCode = 55), installed))
        assertFalse(UpdateInstaller.identityMatches(installed.copy(certificates = emptySet(), versionCode = 55), installed))
    }
}
