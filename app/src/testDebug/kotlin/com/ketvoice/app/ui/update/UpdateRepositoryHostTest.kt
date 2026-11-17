package com.ketvoice.app.ui.update

import com.ketvoice.app.Edition
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateRepositoryHostTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("ketvoice_updates", Context.MODE_PRIVATE)
    private val feed = """[{"tag_name":"v3.10.1","assets":[{"name":"Ketvoice-3.10.1.apk","size":123,"browser_download_url":"https://github.com/Tysnnn23/Ketvoice/releases/download/v3.10.1/Ketvoice-3.10.1.apk"}]}]"""
    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun cachedCandidateSurvivesRelaunchInsideTheCheckInterval() = runBlocking {
        var calls = 0
        val repo = UpdateRepository(context, "3.10.0", Edition.Mobile, { 1_000_000L }) { calls++; feed }
        assertEquals("v3.10.1", repo.check()?.tag)
        val relaunched = UpdateRepository(context, "3.10.0", Edition.Mobile, { 1_000_001L }) { calls++; throw IOException() }
        assertEquals("v3.10.1", relaunched.check()?.tag)
        assertEquals(1, calls)
    }

    @Test fun liteBuildOffersOnlyTheLiteApkAndRemembersItAcrossRelaunches() = runBlocking {
        val both = """[{"tag_name":"v3.10.1","assets":[
            {"name":"Ketvoice-3.10.1.apk","size":123,"browser_download_url":"https://github.com/Tysnnn23/Ketvoice/releases/download/v3.10.1/Ketvoice-3.10.1.apk"},
            {"name":"Ketvoice-3.10.1-Lite.apk","size":77,"browser_download_url":"https://github.com/Tysnnn23/Ketvoice/releases/download/v3.10.1/Ketvoice-3.10.1-Lite.apk"}]}]"""
        val lite = UpdateRepository(context, "3.10.0", Edition.Lite, { 1_000_000L }) { both }
        val offered = lite.check()!!
        assertTrue(offered.apkUrl.endsWith("Ketvoice-3.10.1-Lite.apk"))
        assertEquals(77L, offered.sizeBytes)
        assertTrue(UpdateRepository(context, "3.10.0", Edition.Lite, { 1_000_001L }) { throw IOException() }
            .check()!!.apkUrl.endsWith("-Lite.apk"))
        // A mobile build reading the same cached feed keeps its own APK.
        assertFalse(UpdateRepository(context, "3.10.0", Edition.Mobile, { 1_000_001L }) { throw IOException() }
            .cached()!!.apkUrl.endsWith("-Lite.apk"))
        prefs.edit().clear().commit()
        assertNull(UpdateRepository(context, "3.10.0", Edition.Lite, { 1_000_000L }) { feed }.check())
    }

    @Test fun forceCheckBypassesSkippedAndDismissedButAutomaticChecksRespectThem() = runBlocking {
        val repo = UpdateRepository(context, "3.10.0", Edition.Mobile, { 1_000_000L }) { feed }
        prefs.edit().putString("skipped_tag", "v3.10.1").putString("dismissed_tag", "v3.10.1").commit()
        assertNull(repo.check())
        assertEquals("v3.10.1", repo.check(true)?.tag)
    }

    @Test fun checkTimestampFromAnOlderInstalledVersionCannotSuppressTheFirstCheck() = runBlocking {
        prefs.edit().putLong("last_check", 999_999L).putString("checked_version", "3.9.0").commit()
        var calls = 0
        val repo = UpdateRepository(context, "3.10.0", Edition.Mobile, { 1_000_000L }) { calls++; feed }
        assertEquals("v3.10.1", repo.check()?.tag)
        assertEquals(1, calls)
    }

    @Test fun networkAndMalformedResponsesDoNotThrottleRetries() {
        val network = UpdateRepository(context, "3.10.0", Edition.Mobile, { 1_000_000L }) { throw IOException("offline") }
        assertThrows(IOException::class.java) { runBlocking { network.check() } }
        assertEquals(0L, prefs.getLong("last_check", 0L))
        val malformed = UpdateRepository(context, "3.10.0", Edition.Mobile, { 1_000_000L }) { "not JSON" }
        assertThrows(Exception::class.java) { runBlocking { malformed.check() } }
        assertEquals(0L, prefs.getLong("last_check", 0L))
    }

    @Test fun foregroundCheckRunsAgainWhenDueOrAfterAClockRollback() = runBlocking {
        var time = 1_000_000L
        var calls = 0
        val repo = UpdateRepository(context, "3.10.0", Edition.Mobile, { time }) { calls++; feed }
        repo.check(); time += UpdateChecker.CHECK_INTERVAL_MS; repo.check()
        time = 10L; repo.check()
        assertEquals(3, calls)
    }
}
