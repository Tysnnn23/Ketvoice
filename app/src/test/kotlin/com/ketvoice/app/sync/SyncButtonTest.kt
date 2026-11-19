package com.ketvoice.app.sync

import android.os.Looper
import android.os.NetworkOnMainThreadException
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.AuthSession
import com.ketvoice.app.account.SessionRejected
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.UnknownHostException

/**
 * 7.2 (owner: « le bouton Sync ne fonctionne pas : ça met offline alors que j'ai du Wi-Fi »). The button ran the
 * round in the screen's main-thread scope; Android refuses network calls there, and every failure read as
 * « offline ». The fake cloud below refuses the main thread the way Android does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncButtonTest {
    private class AndroidLikeCloud(private val failure: Exception? = null) : CloudPort {
        var calls = 0
        private fun check() {
            calls++
            if (Looper.myLooper() == Looper.getMainLooper()) throw NetworkOnMainThreadException()
            failure?.let { throw it }
        }
        override suspend fun fetch(kind: String): CloudDoc? { check(); return null }
        override suspend fun insert(kind: String, doc: JSONObject, deviceId: String): Boolean { check(); return true }
        override suspend fun update(kind: String, doc: JSONObject, revision: Long, deviceId: String): Boolean { check(); return true }
    }

    private val context = RuntimeEnvironment.getApplication()

    private fun signedInWith(cloud: CloudPort) {
        LibrarySync.cloud = cloud
        AccountSession.tokens.set(AuthSession("access", "refresh", System.currentTimeMillis() + 3_600_000L, "user-1", "a@b.cd"))
        LibrarySync.signedIn(context, fresh = false)
    }

    /** Presses « Synchroniser » the way the screen does: from the main thread. */
    private fun pressSync(): SyncProblem {
        val result = CoroutineScope(Dispatchers.Main.immediate).async { LibrarySync.syncNow() }
        val until = System.currentTimeMillis() + 20_000
        while (!result.isCompleted && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("the round finished", result.isCompleted)
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        return result.getCompleted()
    }

    @After fun signOut() {
        LibrarySync.signedOut(context)
        AccountSession.tokens.set(null)
        LibrarySync.cloud = RealCloud
    }

    @Test fun theButtonSyncsOffTheMainThreadOnAWorkingNetwork() {
        val cloud = AndroidLikeCloud()
        signedInWith(cloud)
        assertEquals(SyncProblem.NONE, pressSync())
        assertTrue(cloud.calls > 0)
        val status = LibrarySync.status.value
        assertFalse(status.offline)
        assertEquals(SyncProblem.NONE, status.problem)
        assertTrue(status.lastSuccess > 0)
    }

    @Test fun eachFailureShowsItsRealReason() {
        signedInWith(AndroidLikeCloud(UnknownHostException("no network")))
        assertEquals(SyncProblem.OFFLINE, pressSync())
        assertTrue(LibrarySync.status.value.offline)
        LibrarySync.cloud = AndroidLikeCloud(CloudHttpError(503, "down"))
        assertEquals(SyncProblem.SERVER, pressSync())
        assertFalse(LibrarySync.status.value.offline)
        LibrarySync.cloud = AndroidLikeCloud(CloudHttpError(401, "expired"))
        assertEquals(SyncProblem.SIGNED_OUT, pressSync())
        LibrarySync.cloud = AndroidLikeCloud()
        assertEquals("a retry after the problem works", SyncProblem.NONE, pressSync())
        assertEquals(SyncProblem.NONE, LibrarySync.status.value.problem)
    }

    @Test fun onlyAnUnreachableServerIsOffline() {
        assertEquals(SyncProblem.OFFLINE, SyncProblems.of(java.net.ConnectException()))
        assertEquals(SyncProblem.OFFLINE, SyncProblems.of(java.net.SocketTimeoutException()))
        assertEquals(SyncProblem.OFFLINE, SyncProblems.of(java.io.IOException("wrapped", UnknownHostException())))
        assertEquals(SyncProblem.SERVER, SyncProblems.of(NetworkOnMainThreadException()))
        assertEquals(SyncProblem.SERVER, SyncProblems.of(org.json.JSONException("captive portal page")))
        assertEquals(SyncProblem.SERVER, SyncProblems.of(CloudHttpError(500, "")))
        assertEquals(SyncProblem.SIGNED_OUT, SyncProblems.of(CloudHttpError(403, "")))
        assertEquals(SyncProblem.SIGNED_OUT, SyncProblems.of(SessionRejected("Connexion requise")))
    }

    @Test fun theChangeCheckFiresOnlyWhenAnotherDeviceWrote() {
        val a = ChangeToken.of(JSONArray("""[{"kind":"playlists","revision":3},{"kind":"favorites","revision":7}]"""))
        val same = ChangeToken.of(JSONArray("""[{"kind":"favorites","revision":7},{"kind":"playlists","revision":3}]"""))
        val bumped = ChangeToken.of(JSONArray("""[{"kind":"favorites","revision":8},{"kind":"playlists","revision":3}]"""))
        assertEquals(a, same)
        assertFalse("first look", ChangeToken.changed(null, a))
        assertFalse(ChangeToken.changed(a, same))
        assertTrue(ChangeToken.changed(a, bumped))
    }
}
