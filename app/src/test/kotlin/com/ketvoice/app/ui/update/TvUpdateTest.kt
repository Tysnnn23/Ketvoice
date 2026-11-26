package com.ketvoice.app.ui.update

import com.ketvoice.app.Edition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Ketvoice TV updates only from its own `tv-v<version>` releases (`Ketvoice-TV-<version>.apk` + SHA256SUMS.txt);
 * mobile, Quest and Lite keep reading the `v<version>` releases and never see a TV or Windows release.
 */
class TvUpdateTest {
    private fun asset(tag: String, name: String, digest: Boolean = true) = JSONObject().put("name", name).put("size", 123)
        .put("state", "uploaded").put("browser_download_url", UpdateFeed.DOWNLOAD_PREFIX + "$tag/$name")
        .apply { if (digest) put("digest", "sha256:" + "d".repeat(64)) }

    private fun release(tag: String, vararg names: String, sums: Boolean = true) = JSONObject().put("tag_name", tag)
        .put("name", "Ketvoice $tag").put("body", "Notes").put("assets", JSONArray().apply {
            names.forEach { put(asset(tag, it)) }
            if (sums) put(asset(tag, UpdateFeed.SUMS_ASSET, digest = false))
        })

    /** What the repository's release list looks like once TV ships: the phone line, Windows, TV and model tags. */
    private val feed = JSONArray()
        .put(release("v9.2.0", "Ketvoice-9.2.0.apk", "Ketvoice-9.2.0-Quest.apk", "Ketvoice-9.2.0-Lite.apk"))
        .put(release("windows-v2.1.0", "Ketvoice-2.1.0.msi"))
        .put(release("tv-v1.1.0", "Ketvoice-TV-1.1.0.apk"))
        .put(release("tv-v1.0.0", "Ketvoice-TV-1.0.0.apk"))
        .put(release("ketmix-beat-v1", "ketmix-beat-small0.tflite"))

    @Test fun tvAssetAndEdition() {
        assertEquals("Ketvoice-TV-1.0.0.apk", Edition.Tv.assetName("1.0.0"))
        assertEquals(Edition.Tv, Edition.of(quest = false, lite = false, tv = true))
        assertEquals(" · TV", Edition.Tv.label)
        assertEquals("tv-v", Edition.Tv.tagPrefix)
        assertEquals("v", Edition.Mobile.tagPrefix)
    }

    @Test fun tagsBelongToOneLine() {
        assertEquals("1.0.0", UpdateFeed.versionOf("tv-v1.0.0", Edition.Tv))
        assertNull(UpdateFeed.versionOf("v1.0.0", Edition.Tv))
        assertNull(UpdateFeed.versionOf("tv-vv1.0.0", Edition.Tv))
        assertNull(UpdateFeed.versionOf("TV-v1.0.0", Edition.Tv))
        assertNull(UpdateFeed.versionOf("tv-v1.0.0/../x", Edition.Tv))
        for (edition in listOf(Edition.Mobile, Edition.Quest, Edition.Lite)) {
            assertEquals("9.2.0", UpdateFeed.versionOf("v9.2.0", edition))
            assertNull(UpdateFeed.versionOf("tv-v1.0.0", edition))
            assertNull(UpdateFeed.versionOf("windows-v2.1.0", edition))
            assertNull(UpdateFeed.versionOf("ketmix-beat-v1", edition))
        }
        assertTrue(UpdateFeed.validTag("tv-v1.0.0"))
        assertTrue(UpdateFeed.validTag("v9.2.0"))
        assertFalse(UpdateFeed.validTag("windows-v2.1.0"))
    }

    @Test fun tvPicksTheNewestTvReleaseAndItsChecksumList() {
        val update = UpdateFeed.select(feed, "1.0.0", Edition.Tv)!!
        assertEquals("tv-v1.1.0", update.tag)
        assertEquals("1.1.0", update.versionLabel())
        assertEquals("Ketvoice-TV-1.1.0.apk", update.apkName())
        assertEquals(UpdateFeed.DOWNLOAD_PREFIX + "tv-v1.1.0/SHA256SUMS.txt", update.sumsUrl)
        assertEquals("d".repeat(64), update.sha256)
        assertTrue(UpdateChecker.isNewer(update.tag, "1.0.0"))
        assertFalse(UpdateChecker.isNewer("tv-v1.0.0", "1.0.0"))
        // Up to date: the phone's 9.2.0 is never an update for TV 1.1.0.
        assertNull(UpdateFeed.select(feed, "1.1.0", Edition.Tv))
    }

    @Test fun tvNeedsItsOwnApkAndTheChecksumList() {
        assertNull(UpdateFeed.select(JSONArray().put(release("tv-v1.1.0", "Ketvoice-TV-1.1.0.apk", sums = false)), "1.0.0", Edition.Tv))
        assertNull(UpdateFeed.select(JSONArray().put(release("tv-v1.1.0", "Ketvoice-1.1.0.apk")), "1.0.0", Edition.Tv))
        // A phone release that happened to carry a TV-named file is still not a TV release.
        assertNull(UpdateFeed.select(JSONArray().put(release("v1.1.0", "Ketvoice-TV-1.1.0.apk")), "1.0.0", Edition.Tv))
        // A checksum list served from another release is refused.
        val foreign = release("tv-v1.1.0", "Ketvoice-TV-1.1.0.apk", sums = false)
        foreign.getJSONArray("assets").put(asset("v9.2.0", UpdateFeed.SUMS_ASSET, digest = false))
        assertNull(UpdateFeed.select(JSONArray().put(foreign), "1.0.0", Edition.Tv))
    }

    @Test fun tvSkipsDraftsAndPrereleasesOnAStableBuild() {
        val withDrafts = JSONArray().put(release("tv-v1.3.0", "Ketvoice-TV-1.3.0.apk").put("draft", true))
            .put(release("tv-v1.2.0-beta1", "Ketvoice-TV-1.2.0-beta1.apk").put("prerelease", true))
            .put(release("tv-v1.1.0", "Ketvoice-TV-1.1.0.apk"))
        assertEquals("tv-v1.1.0", UpdateFeed.select(withDrafts, "1.0.0", Edition.Tv)?.tag)
    }

    @Test fun phoneEditionsIgnoreTvAndWindowsReleases() {
        assertEquals("v9.2.0", UpdateFeed.select(feed, "9.1.1", Edition.Mobile)?.tag)
        assertTrue(UpdateFeed.select(feed, "9.1.1", Edition.Lite)!!.apkUrl.endsWith("/Ketvoice-9.2.0-Lite.apk"))
        assertNull(UpdateFeed.select(feed, "9.1.1", Edition.Mobile)!!.sumsUrl)
        // Even a TV or Windows release that carried a phone-named APK with a higher number is never offered.
        val decoys = JSONArray().put(release("tv-v10.0.0", "Ketvoice-10.0.0.apk", "Ketvoice-10.0.0-Lite.apk", "Ketvoice-10.0.0-Quest.apk"))
            .put(release("windows-v10.0.0", "Ketvoice-10.0.0.apk"))
        for (edition in listOf(Edition.Mobile, Edition.Quest, Edition.Lite)) assertNull(UpdateFeed.select(decoys, "9.1.1", edition))
    }

    @Test fun checksumListIsReadStrictly() {
        val sums = "${"a".repeat(64)}  Ketvoice-TV-1.1.0.apk\n${"B".repeat(64)} *RELEASE-NOTES.md\nnot a line\n"
        assertEquals("a".repeat(64), UpdateFeed.sumFor(sums, "Ketvoice-TV-1.1.0.apk"))
        assertEquals("b".repeat(64), UpdateFeed.sumFor(sums, "RELEASE-NOTES.md"))
        assertNull(UpdateFeed.sumFor(sums, "Ketvoice-TV-1.1"))
        assertEquals("a".repeat(64), UpdateInstaller.listedSha256(sums, "Ketvoice-TV-1.1.0.apk", null))
        assertEquals("a".repeat(64), UpdateInstaller.listedSha256(sums, "Ketvoice-TV-1.1.0.apk", "A".repeat(64)))
        assertThrows(IOException::class.java) { UpdateInstaller.listedSha256(sums, "Ketvoice-TV-1.2.0.apk", null) }
        assertThrows(IOException::class.java) { UpdateInstaller.listedSha256(sums, "Ketvoice-TV-1.1.0.apk", "c".repeat(64)) }
    }
}
