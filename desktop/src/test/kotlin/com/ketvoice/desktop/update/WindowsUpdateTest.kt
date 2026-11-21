package com.ketvoice.desktop.update

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.desktop.audio.AudioFixtures
import com.ketvoice.desktop.library.OpenFiles
import com.ketvoice.desktop.platform.About
import com.ketvoice.desktop.ui.UpdateDialog
import com.ketvoice.desktop.ui.UpdateRow
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The in-app updater against a fake GitHub feed: tag filter, no downgrade, SHA-256 check, rate limit, the dialog. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class WindowsUpdateTest {
    private val dl = ReleaseFeed.DOWNLOAD_PREFIX
    private val msiBytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun release(tag: String, msi: String? = null, sums: Boolean = true, draft: Boolean = false, pre: Boolean = false,
                        url: String? = null, body: String = "## Ketvoice for Windows\n\n- **Faster** start\n- `msiexec` upgrade") = JSONObject()
        .put("tag_name", tag).put("name", "Ketvoice for Windows ${tag.substringAfter("windows-v")}").put("draft", draft).put("prerelease", pre)
        .put("body", body).put("html_url", "https://github.com/Tysnnn23/Ketvoice/releases/tag/$tag")
        .put("assets", JSONArray().apply {
            msi?.let { put(JSONObject().put("name", it).put("size", msiBytes.size).put("browser_download_url", url ?: "$dl$tag/$it")) }
            if (sums) put(JSONObject().put("name", "SHA256SUMS.txt").put("browser_download_url", "$dl$tag/SHA256SUMS.txt"))
        })

    private val feed = JSONArray()
        .put(release("v7.6.0", "Ketvoice-7.6.0.msi"))                                   // Android tag
        .put(release("ketmix-beat-v1", "Ketvoice-1.0.0.msi"))                          // model tag
        .put(release("windows-v1.4.0", "Ketvoice-1.4.0.msi", draft = true))
        .put(release("windows-v1.3.0", "Ketvoice-1.3.0.msi", pre = true))
        .put(release("windows-v1.2.0", "Ketvoice-1.2.0.msi", sums = false))            // no SHA256SUMS.txt
        .put(release("windows-v1.1.5", "Ketvoice-1.1.5.msi", url = "https://evil.example/Ketvoice-1.1.5.msi"))
        .put(release("windows-v1.1.0", "Ketvoice-1.1.0.msi"))
        .put(release("windows-v1.0.0", "Ketvoice-1.0.0.msi"))
        .put(release("windows-v0.9.0", "Ketvoice-0.9.0.msi"))
        .toString()

    private class FakeGitHub(val files: MutableMap<String, ByteArray>) : UpdateTransport {
        var feedReads = 0
        var failFeed = false
        override fun text(url: String, limit: Int): String {
            if (url == ReleaseFeed.API) { feedReads++; if (failFeed) throw IOException("HTTP 403") }
            return String(files[url] ?: throw IOException("HTTP 404"), Charsets.UTF_8)
        }
        override fun download(url: String, to: File, onProgress: (Long) -> Unit) {
            val bytes = files[url] ?: throw IOException("HTTP 404")
            to.writeBytes(bytes); onProgress(bytes.size.toLong())
        }
    }

    private fun github(msi: ByteArray = msiBytes, sums: String = "${sha(msiBytes)}  Ketvoice-1.1.0.msi\n${"0".repeat(64)}  RELEASE-NOTES.md\n") =
        FakeGitHub(mutableMapOf(ReleaseFeed.API to feed.toByteArray(), "${dl}windows-v1.1.0/Ketvoice-1.1.0.msi" to msi,
            "${dl}windows-v1.1.0/SHA256SUMS.txt" to sums.toByteArray()))

    private fun dir(name: String) = File("build/update-test/$name").apply { deleteRecursively(); mkdirs() }

    @Test fun onlyWindowsTagsWithBothAssetsAndNeverADowngrade() {
        val newest = assertNotNull(ReleaseFeed.newest(feed, SemVer(1, 0, 0)))
        assertEquals("windows-v1.1.0", newest.tag)
        assertEquals("Ketvoice-1.1.0.msi", newest.msiName)
        assertEquals(SemVer(1, 1, 0), newest.version)
        assertNull(ReleaseFeed.newest(feed, SemVer(1, 1, 0)), "the installed version is not offered again")
        assertNull(ReleaseFeed.newest(feed, SemVer(2, 0, 0)), "an older release is never offered")
        // Android and model tags, drafts, prereleases, missing sums and foreign download hosts are all left out.
        assertEquals(listOf("windows-v1.1.0", "windows-v1.0.0", "windows-v0.9.0"), ReleaseFeed.releases(feed).map { it.tag })
        assertTrue(SemVer(1, 10, 0) > SemVer(1, 9, 9))
        assertNull(SemVer.parse("1.0.0-beta"))
    }

    @Test fun sumsFileFormats() {
        val h = "ab".repeat(32)
        assertEquals(h, ReleaseFeed.sumFor("$h  Ketvoice-1.1.0.msi\n", "Ketvoice-1.1.0.msi"))
        assertEquals(h, ReleaseFeed.sumFor("${h.uppercase()} *Ketvoice-1.1.0.msi\r\n", "Ketvoice-1.1.0.msi"))
        assertNull(ReleaseFeed.sumFor("$h  Ketvoice-1.1.0.msi.sig\n", "Ketvoice-1.1.0.msi"))
    }

    @Test fun installVerifiesTheHashThenLaunchesMsiexec(): Unit = runBlocking {
        val launched = ArrayList<File>()
        val updater = WindowsUpdater(github(), dir("ok"), SemVer(1, 0, 0), launcher = { launched += it })
        val release = assertIs<UpdateState.Available>(updater.check()).release
        assertTrue(updater.install(release))
        assertEquals(1, launched.size)
        assertEquals("Ketvoice-1.1.0.msi", launched[0].name)
        assertEquals(sha(msiBytes), ReleaseFeed.sha256(launched[0]))
        assertIs<UpdateState.Installing>(updater.state.value)
    }

    @Test fun aTamperedOrUnlistedInstallerIsRefused(): Unit = runBlocking {
        val launched = ArrayList<File>()
        val bad = msiBytes.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val tampered = WindowsUpdater(github(msi = bad), dir("bad"), SemVer(1, 0, 0), launcher = { launched += it })
        val release = assertIs<UpdateState.Available>(tampered.check()).release
        assertTrue(!tampered.install(release))
        val failed = assertIs<UpdateState.Failed>(tampered.state.value)
        assertTrue("SHA-256" in failed.message, failed.message)
        assertTrue(File("build/update-test/bad").listFiles().orEmpty().none { it.name.endsWith(".msi") || it.name.endsWith(".part") })

        val unlisted = WindowsUpdater(github(sums = "${sha(msiBytes)}  Ketvoice-1.0.9.msi\n"), dir("unlisted"), SemVer(1, 0, 0), launcher = { launched += it })
        assertTrue(!unlisted.install(release))
        assertIs<UpdateState.Failed>(unlisted.state.value)

        val digest = WindowsUpdater(github(), dir("digest"), SemVer(1, 0, 0), launcher = { launched += it })
        assertTrue(!digest.install(release.copy(msiDigest = "0".repeat(64))))
        assertTrue(launched.isEmpty())
        assertTrue(!WindowsUpdater(github(), dir("old"), SemVer(1, 1, 0), launcher = { launched += it }).install(release), "no downgrade on install")
    }

    @Test fun checksAtMostOncePerSixHoursAndKeepsTheLastFeed(): Unit = runBlocking {
        var now = 1_000_000L
        val gh = github()
        val d = dir("rate")
        val updater = WindowsUpdater(gh, d, SemVer(1, 0, 0), launcher = {}, clock = { now })
        assertIs<UpdateState.Available>(updater.check())
        now += WindowsUpdater.SIX_HOURS - 1
        assertIs<UpdateState.Available>(updater.check())
        assertEquals(1, gh.feedReads)
        // A restart within the 6 h shows what the last check found, without asking GitHub.
        assertIs<UpdateState.Available>(WindowsUpdater(gh, d, SemVer(1, 0, 0), launcher = {}, clock = { now }).check())
        assertEquals(1, gh.feedReads)
        updater.check(force = true)
        assertEquals(2, gh.feedReads)
        now += WindowsUpdater.SIX_HOURS
        updater.check()
        assertEquals(3, gh.feedReads)
        // A failure is said plainly.
        gh.failFeed = true
        val failed = assertIs<UpdateState.Failed>(updater.check(force = true))
        assertTrue("403" in failed.message, failed.message)
        // « Plus tard » is remembered per release.
        updater.dismiss(ReleaseFeed.newest(feed, SemVer(1, 0, 0))!!)
        assertEquals("windows-v1.1.0", WindowsUpdater(gh, d, SemVer(1, 0, 0), launcher = {}).dismissedTag)
    }

    @Test fun releaseNotesMarkdown() {
        val blocks = ReleaseNotes.blocks("# Ketvoice 1.1.0\n\nFirst line\nsecond line.\n\n- **Bold** item with [link](https://x)\n  continued\n  - nested\n1. one\n---\n<!-- hidden -->")
        assertEquals(ReleaseNotes.Heading("Ketvoice 1.1.0", 1), blocks[0])
        assertEquals(ReleaseNotes.Paragraph("First line second line."), blocks[1])
        assertEquals(ReleaseNotes.Bullet("**Bold** item with link continued", 0), blocks[2])
        assertEquals(ReleaseNotes.Bullet("nested", 1), blocks[3])
        assertEquals(ReleaseNotes.Bullet("one", 0), blocks[4])
        assertEquals(5, blocks.size)
        val (plain, bold, code) = ReleaseNotes.spans("**Bold** and `code`")
        assertEquals("Bold and code", plain)
        assertEquals(listOf(0..3), bold)
        assertEquals(listOf(9..12), code)
    }

    @Test fun dialogShowsNotesAndInstalls() = runComposeUiTest {
        AppText.init(android.content.Context.app)
        val launched = ArrayList<File>()
        val updater = WindowsUpdater(github(), dir("ui"), SemVer(1, 0, 0), launcher = { launched += it })
        val release = runBlocking { assertIs<UpdateState.Available>(updater.check()).release }
        var closed = false
        setContent { KetDesktopTheme(false) { UpdateDialog(release, updater) { closed = true } } }
        onNodeWithTag("update-dialog").assertIsDisplayed()
        onNodeWithText("Faster", substring = true).assertIsDisplayed()
        onNodeWithTag("update-install").performClick()
        waitUntil(timeoutMillis = 5_000) { launched.isNotEmpty() }
        assertEquals("Ketvoice-1.1.0.msi", launched.single().name)
        assertTrue(!closed)
    }

    @Test fun settingsRowChecksAndShowsAFailurePlainly() = runComposeUiTest {
        AppText.init(android.content.Context.app)
        val gh = github().apply { failFeed = true }
        val updater = WindowsUpdater(gh, dir("row"), SemVer(1, 0, 0), launcher = {})
        setContent { KetDesktopTheme(true) { UpdateRow(updater) {} } }
        onNodeWithTag("update-row").performClick()
        waitUntil(timeoutMillis = 5_000) { updater.state.value is UpdateState.Failed }
        onNodeWithTag("update-row-error").assertIsDisplayed()
    }

    @Test fun rendersTheDialog(): Unit = runBlocking {
        AppText.init(android.content.Context.app)
        AppSettings.reduceAnimations.value = false
        val out = File("build/desktop-ui-preview").apply { mkdirs() }
        val notes = "## What's new\n\nKetvoice for Windows 1.1.0 brings in-app updates.\n\n- **Updates**: checked every 6 hours, verified with SHA-256\n" +
            "- Start-menu entry and `Open with` for audio files\n- Licences listed in Settings › About"
        val release = ReleaseFeed.newest(feed, SemVer(1, 0, 0))!!.copy(notes = notes)
        for (dark in listOf(true, false)) for (failing in listOf(false, true)) {
            val updater = WindowsUpdater(github(msi = if (failing) ByteArray(10) else msiBytes), dir("render"), SemVer(1, 0, 0), launcher = {})
            if (failing) updater.install(release)
            shoot(out, "p4-update-${if (failing) "failed-" else ""}${if (dark) "dark" else "light"}") {
                KetDesktopTheme(dark) { UpdateDialog(release, updater) {} }
            }
        }
    }

    private fun shoot(out: File, name: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(900, 640, Density(1f), content = content)
        try {
            var t = 0L
            repeat(20) { scene.render(t); t += 50_000_000L }
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    @Test fun openedAudioFilesBecomeTitles() {
        val wav = AudioFixtures.wav("open-files", 44_100, AudioFixtures.pcm(44_100, 500))
        val text = File(AudioFixtures.dir, "notes.txt").apply { writeText("not audio") }
        val tracks = OpenFiles.tracks(listOf(wav.absolutePath, text.absolutePath, wav.toURI().toString(), "C:\\missing.mp3"), null)
        assertEquals(2, tracks.size)
        assertTrue(tracks.all { it.streamUrl == wav.toURI().toString() && it.id.startsWith("local:file:") })
    }

    @Test fun aboutListsEveryShippedLicence() {
        AppText.init(android.content.Context.app)
        for (c in About.components) assertNotNull(About.licence(c), "missing /licenses/${c.file}")
        for (f in About.texts) assertNotNull(About.resource(f), "missing /licenses/$f")
        val text = About.text()
        assertTrue("LGPL 3.0+" in text && "GNU LESSER GENERAL PUBLIC LICENSE" in text && "Version 3, 29 June 2007" in text)
        assertEquals(1, Regex("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION").findAll(text).count(), "the Apache text once")
        for (name in listOf("FFmpeg", "ONNX Runtime", "JavaCPP", "ICU4J", "JNA", "OkHttp", "Coil", "Compose Multiplatform")) assertTrue("• $name " in text, name)
    }
}
