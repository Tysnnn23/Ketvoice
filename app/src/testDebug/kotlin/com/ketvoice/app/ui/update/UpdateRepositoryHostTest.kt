package com.ketvoice.app.ui.update

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
        val repo = UpdateRepository(context, "3.10.0", false, { 1_000_000L }) { calls++; feed }
        assertEquals("v3.10.1", repo.check()?.tag)
        val relaunched = UpdateRepository(context, "3.10.0", false, { 1_000_001L }) { calls++; throw IOException() }
        assertEquals("v3.10.1", relaunched.check()?.tag)
        assertEquals(1, calls)
    }

    @Test fun forceCheckBypassesSkippedAndDismissedButAutomaticChecksRespectThem() = runBlocking {
        val repo = UpdateRepository(context, "3.10.0", false, { 1_000_000L }) { feed }
        prefs.edit().putString("skipped_tag", "v3.10.1").putString("dismissed_tag", "v3.10.1").commit()
        assertNull(repo.check())
        assertEquals("v3.10.1", repo.check(true)?.tag)
    }

    @Test fun checkTimestampFromAnOlderInstalledVersionCannotSuppressTheFirstCheck() = runBlocking {
        prefs.edit().putLong("last_check", 999_999L).putString("checked_version", "3.9.0").commit()
        var calls = 0
        val repo = UpdateRepository(context, "3.10.0", false, { 1_000_000L }) { calls++; feed }
        assertEquals("v3.10.1", repo.check()?.tag)
        assertEquals(1, calls)
    }

    @Test fun networkAndMalformedResponsesDoNotThrottleRetries() {
        val network = UpdateRepository(context, "3.10.0", false, { 1_000_000L }) { throw IOException("offline") }
        assertThrows(IOException::class.java) { runBlocking { network.check() } }
        assertEquals(0L, prefs.getLong("last_check", 0L))
        val malformed = UpdateRepository(context, "3.10.0", false, { 1_000_000L }) { "not JSON" }
        assertThrows(Exception::class.java) { runBlocking { malformed.check() } }
        assertEquals(0L, prefs.getLong("last_check", 0L))
    }

    @Test fun foregroundCheckRunsAgainWhenDueOrAfterAClockRollback() = runBlocking {
        var time = 1_000_000L
        var calls = 0
        val repo = UpdateRepository(context, "3.10.0", false, { time }) { calls++; feed }
        repo.check(); time += UpdateChecker.CHECK_INTERVAL_MS; repo.check()
        time = 10L; repo.check()
        assertEquals(3, calls)
    }
}
