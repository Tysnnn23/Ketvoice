package com.ketvoice.desktop.account

import android.content.Context
import android.content.FileSharedPreferences
import androidx.compose.runtime.snapshots.Snapshot
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.AuthSession
import com.ketvoice.app.account.LoopbackRedirect
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.CloudDoc
import com.ketvoice.app.sync.CloudPort
import com.ketvoice.app.sync.LibrarySync
import com.ketvoice.app.sync.RealCloud
import com.ketvoice.app.sync.SyncRound
import com.ketvoice.app.sync.SyncTracks
import com.ketvoice.desktop.library.DesktopLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Settings kept across restarts, the account's library sync over a fake table, and Discord's loopback redirect. */
class AccountDesktopTest {
    private fun fresh() = Context(Files.createTempDirectory("ketvoice-account").toFile())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun tearDown() {
        LibrarySync.cloud = RealCloud
        AccountSession.tokens.set(null)
        SettingsStore.resetForTest()
        AppSettings.themeMode.value = 0; AppSettings.automix.value = false
        AppSettings.equalizerGains.value = List(10) { 0 }; AppSettings.localMusicFolder.value = null
        scope.cancel()
    }

    @Test fun everySettingComesBackAfterARestart() {
        val ctx = fresh()
        SettingsStore.load(ctx)
        AppSettings.themeMode.value = 2
        AppSettings.automix.value = true
        AppSettings.glassIntensity.value = .55f
        AppSettings.equalizerGains.value = listOf(3, 0, -2, 0, 0, 0, 0, 1, 0, 4)
        AppSettings.wifiQuality.value = "Élevé"
        AppSettings.localMusicFolder.value = "C:\\Musique"
        AppSettings.cacheBytes.value = 2_000_000_000L
        SettingsStore.save()
        FileSharedPreferences.flush()
        val text = File(ctx.dataDir, "shared_prefs/ketvoice_settings.json").readText()
        // The Android keys: the Ketmix switch is `automixOn` (the retired `automix` key is never written).
        assertTrue("\"automixOn\"" in text && "\"automix\"" !in text, text)
        assertTrue("\"systemReduceMotion\"" !in text)

        // A restart: defaults first, then the file.
        AppSettings.themeMode.value = 0; AppSettings.automix.value = false; AppSettings.glassIntensity.value = .2f
        AppSettings.equalizerGains.value = List(10) { 0 }; AppSettings.wifiQuality.value = "Sans perte"
        AppSettings.localMusicFolder.value = null; AppSettings.cacheBytes.value = 10_000_000_000L
        SettingsStore.load(ctx)
        assertEquals(2, AppSettings.themeMode.value)
        assertEquals(true, AppSettings.automix.value)
        assertEquals(.55f, AppSettings.glassIntensity.value)
        assertEquals(listOf(3, 0, -2, 0, 0, 0, 0, 1, 0, 4), AppSettings.equalizerGains.value)
        assertEquals("Élevé", AppSettings.wifiQuality.value)
        assertEquals("C:\\Musique", AppSettings.localMusicFolder.value)
        assertEquals(2_000_000_000L, AppSettings.cacheBytes.value)
    }

    @Test fun aChangeIsSavedByItselfAMomentLater() = runBlocking {
        val ctx = fresh()
        SettingsStore.load(ctx)
        val job = SettingsStore.watch(scope)
        delay(100)
        AppSettings.themeMode.value = 1
        Snapshot.sendApplyNotifications()
        val prefs = ctx.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        repeat(40) { if (prefs.getInt("themeMode", -1) == 1) return@repeat; delay(50) }
        assertEquals(1, prefs.getInt("themeMode", -1))
        job.cancel()
    }

    @Test fun preferencesAnnounceOnlyRealChanges() {
        val prefs = fresh().getSharedPreferences("p", Context.MODE_PRIVATE)
        val seen = ArrayList<String?>()
        prefs.registerOnSharedPreferenceChangeListener { _, key -> seen += key }
        prefs.edit().putInt("a", 1).putString("b", "x").commit()
        prefs.edit().putInt("a", 1).putString("b", "x").commit()   // same values: nothing to announce
        prefs.edit().putInt("a", 2).remove("missing").commit()
        assertEquals(listOf<String?>("a", "b", "a"), seen)
    }

    private class FakeCloud : CloudPort {
        val docs = HashMap<String, Pair<String, Long>>()
        override suspend fun fetch(kind: String) = synchronized(this) { docs[kind]?.let { CloudDoc(JSONObject(it.first), it.second, "", "") } }
        override suspend fun insert(kind: String, doc: JSONObject, deviceId: String) =
            synchronized(this) { if (kind in docs) false else { docs[kind] = doc.toString() to 1L; true } }
        override suspend fun update(kind: String, doc: JSONObject, revision: Long, deviceId: String) =
            synchronized(this) { if (docs[kind]?.second != revision) false else { docs[kind] = doc.toString() to revision + 1; true } }
    }

    @Test fun theLibraryFollowsTheAccountBothWays() = runBlocking {
        val ctx = fresh()
        SettingsStore.load(ctx)
        DesktopLibrary.resetForTest()
        DesktopLibrary.init(ctx, scope, File(ctx.dataDir, "downloads"))
        val cloud = FakeCloud()
        LibrarySync.cloud = cloud
        AccountSession.tokens.set(AuthSession("access", "refresh", Long.MAX_VALUE / 4, "00000000-0000-0000-0000-00000000000a", "a@example.com"))
        val mine = Track("yt:mine0001", "Sur ce PC", "Artiste A", "Album A", coverUrl = "https://example.com/a.jpg")
        DesktopLibrary.setFavorite(mine, true)
        AppSettings.themeMode.value = 2
        LibrarySync.signedIn(ctx, fresh = true)
        LibrarySync.syncNow()
        val up = cloud.docs.getValue("favorites").first
        assertTrue(mine.id in up && "Sur ce PC" in up, up)
        assertTrue("themeMode" in cloud.docs.getValue("settings").first)

        // The phone hearts another title: the next round brings it here, with its title.
        val phone = Track("yt:phone001", "Depuis le téléphone", "Artiste B", "Album B")
        SyncRound(cloud, "phone-device", System::currentTimeMillis)
            .run("favorites", null, mapOf(mine.id to SyncTracks.text(mine), phone.id to SyncTracks.text(phone)))
        LibrarySync.syncNow()
        repeat(60) {
            Snapshot.sendApplyNotifications()
            if (DesktopLibrary.favorites.value.any { it.id == phone.id }) return@repeat
            delay(50)
        }
        assertEquals(listOf(phone.id, mine.id), DesktopLibrary.favorites.value.map { it.id })
        assertEquals("Depuis le téléphone", DesktopLibrary.favorites.value.first().title)
        LibrarySync.signedOut(ctx)
        DesktopLibrary.resetForTest()
    }

    private fun get(url: String): Int = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).run {
        connectTimeout = 3000; readTimeout = 3000
        try { responseCode } finally { disconnect() }
    }

    @Test fun discordComesBackToTheLoopbackPage() = runBlocking {
        val redirect = LoopbackRedirect.start(AccountSession.LOOPBACK_PORTS)
        try {
            assertTrue(redirect.url.matches(Regex("http://127\\.0\\.0\\.1:4782[1-3]/callback")), redirect.url)
            assertEquals(404, get(redirect.url.replace("/callback", "/favicon.ico")))
            assertEquals(200, get(redirect.url + "?code=abcdef123456&state=x"))
            val answer = assertNotNull(redirect.await(2_000))
            assertEquals("abcdef123456", answer.code)
        } finally { redirect.close() }
    }

    @Test fun aRefusedDiscordSignInSaysSo() = runBlocking {
        val error = assertFailsWith<IllegalStateException> {
            AccountSession.signInWithDiscord(open = { url ->
                // Supabase's authorize page, sent back with an error by the listener's « Annuler ».
                val redirect = Regex("redirect_to=([^&]+)").find(url)!!.groupValues[1].let { java.net.URLDecoder.decode(it, "UTF-8") }
                Thread { get("$redirect?error=access_denied") }.start()
            }, waitMs = 5_000)
        }
        assertEquals("Connexion Discord annulée.", error.message)
        assertEquals(false, AccountSession.discordWaiting.value)
    }
}
