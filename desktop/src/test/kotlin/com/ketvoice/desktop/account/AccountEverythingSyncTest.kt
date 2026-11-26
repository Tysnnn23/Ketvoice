package com.ketvoice.desktop.account

import android.content.Context
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.AuthSession
import com.ketvoice.app.data.DayStat
import com.ketvoice.app.data.DeviceListening
import com.ketvoice.app.data.Heard
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.CloudDoc
import com.ketvoice.app.sync.CloudHttpError
import com.ketvoice.app.sync.CloudPort
import com.ketvoice.app.sync.ConnectionsKind
import com.ketvoice.app.sync.DeviceIdentity
import com.ketvoice.app.sync.DiscordLink
import com.ketvoice.app.sync.LibrarySync
import com.ketvoice.app.sync.ListeningKind
import com.ketvoice.app.sync.RealCloud
import com.ketvoice.app.sync.SyncMerge
import com.ketvoice.app.sync.SyncProblem
import com.ketvoice.app.sync.SyncRound
import com.ketvoice.desktop.connect.ConnectPrefs
import com.ketvoice.desktop.connect.DiscordAccount
import com.ketvoice.desktop.library.DesktopLibrary
import com.ketvoice.desktop.ui.discordElsewhere
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 9.2 « tout synchroniser » on Windows, over a fake `ketvoice_library`: recently played and the statistics come in
 * from the phone and go out from this PC without counting a play twice, Discord's choice and link travel, and a
 * server that does not know the new kinds yet still syncs the rest of the library.
 */
class AccountEverythingSyncTest {
    private fun fresh() = Context(Files.createTempDirectory("ketvoice-everything").toFile())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val user = "00000000-0000-0000-0000-00000000000b"

    @AfterTest fun tearDown() {
        LibrarySync.cloud = RealCloud
        AccountSession.tokens.set(null)
        SettingsStore.resetForTest()
        ConnectPrefs.discord.value = true
        scope.cancel()
    }

    private open class FakeCloud : CloudPort {
        val docs = HashMap<String, Pair<String, Long>>()
        override suspend fun fetch(kind: String) = synchronized(this) { docs[kind]?.let { CloudDoc(JSONObject(it.first), it.second, "", "") } }
        override suspend fun insert(kind: String, doc: JSONObject, deviceId: String) =
            synchronized(this) { if (kind in docs) false else { docs[kind] = doc.toString() to 1L; true } }
        override suspend fun update(kind: String, doc: JSONObject, revision: Long, deviceId: String) =
            synchronized(this) { if (docs[kind]?.second != revision) false else { docs[kind] = doc.toString() to revision + 1; true } }
        fun agreed(kind: String) = docs[kind]?.let { SyncMerge.decode(JSONObject(it.first)) }
    }

    private fun signIn(ctx: Context, cloud: CloudPort) {
        SettingsStore.load(ctx)
        DesktopLibrary.resetForTest()
        DesktopLibrary.init(ctx, scope, File(ctx.dataDir, "downloads"))
        LibrarySync.cloud = cloud
        AccountSession.tokens.set(AuthSession("access", "refresh", Long.MAX_VALUE / 4, user, "b@example.com"))
    }

    /** The phone's round: its own listening and its Discord link, as the shared kinds write them. */
    private suspend fun phoneWrites(cloud: FakeCloud, phoneTrack: Track) {
        val phone = SyncRound(cloud, "phone-device", System::currentTimeMillis)
        val own = DeviceListening(recent = listOf(Heard(phoneTrack, System.currentTimeMillis() + 60_000)), known = listOf(phoneTrack),
            counts = mapOf(phoneTrack.id to 7), listenedMs = 3_600_000L,
            days = mapOf(LocalDate.now().toString() to DayStat(3_600_000L, mapOf(phoneTrack.id to 7))))
        val listening = cloud.agreed("listening")
        phone.run("listening", listening, ListeningKind.values(listening?.live.orEmpty(), "phone-device",
            ListeningKind.encode("phone-device", own, LocalDate.now())))
        val connections = cloud.agreed("connections")
        phone.run("connections", connections, ConnectionsKind.values(connections?.live.orEmpty(), "phone-device",
            DiscordLink("Pixel 9", "android", true, "ketfan", false), here = false, chosen = true))
    }

    @Test fun listeningAndDiscordFollowTheAccountBothWaysWithoutCountingTwice() = runBlocking {
        val ctx = fresh()
        val cloud = FakeCloud()
        signIn(ctx, cloud)
        ConnectPrefs.init(ctx)
        DiscordAccount.install(scope)
        val heard = Track("yt:pcplay01", "Sur le PC", "Artiste PC", "Album", coverUrl = "https://example.com/pc.jpg")
        ListeningHistory.record(heard)
        LibrarySync.signedIn(ctx, fresh = true)
        assertEquals(SyncProblem.NONE, LibrarySync.syncNow())
        val id = DeviceIdentity.id(ctx)
        val up = cloud.docs.getValue("listening").first
        assertTrue("dev:$id:counts" in up && heard.id in up, up)
        assertTrue("discord:$id" in cloud.docs.getValue("connections").first)

        val phoneTrack = Track("yt:phone002", "Depuis le téléphone", "Artiste B", "Album B", coverUrl = "https://example.com/p.jpg")
        phoneWrites(cloud, phoneTrack)
        assertEquals(SyncProblem.NONE, LibrarySync.syncNow())
        assertEquals(7, ListeningHistory.playCounts.value[phoneTrack.id])
        assertTrue((ListeningHistory.playCounts.value[heard.id] ?: 0) >= 1)
        assertEquals(phoneTrack.id, ListeningHistory.tracks.value.first().id)
        assertTrue(ListeningHistory.listenedMs.value >= 3_600_000L)
        assertTrue(ListeningHistory.knownTracks.value.any { it.id == phoneTrack.id })
        assertEquals(7, ListeningHistory.days.value.getValue(LocalDate.now().toString()).plays[phoneTrack.id])
        // Discord: the phone's link shows here, and its choice (« off ») is this PC's « Statut Discord » now.
        assertEquals("ketfan", ConnectionsKind.others.value.single().user)
        assertTrue(discordElsewhere(ConnectionsKind.others.value)!!.endsWith("Pixel 9 · ketfan"))
        assertFalse(ConnectPrefs.discord.value)

        // Rounds again: the phone's plays are not added to this PC's own counters, nor counted twice.
        assertEquals(SyncProblem.NONE, LibrarySync.syncNow())
        assertEquals(7, ListeningHistory.playCounts.value[phoneTrack.id])
        val pcCounts = JSONObject(cloud.agreed("listening")!!.live.getValue("dev:$id:counts"))
        assertFalse(pcCounts.has(phoneTrack.id))

        // Signing out: the other devices' listening leaves with the account; this PC's own stays.
        LibrarySync.signedOut(ctx)
        assertEquals(null, ListeningHistory.playCounts.value[phoneTrack.id])
        assertTrue(ConnectionsKind.others.value.isEmpty())
        assertTrue((ListeningHistory.playCounts.value[heard.id] ?: 0) >= 1)
        DesktopLibrary.resetForTest()
    }

    @Test fun aServerThatDoesNotKnowTheNewKindsYetStillSyncsTheRest() = runBlocking {
        val ctx = fresh()
        val cloud = object : FakeCloud() {
            override suspend fun insert(kind: String, doc: JSONObject, deviceId: String): Boolean {
                if (kind == ListeningKind.name || kind == ConnectionsKind.name) throw CloudHttpError(400, "violates check constraint")
                return super.insert(kind, doc, deviceId)
            }
        }
        signIn(ctx, cloud)
        DesktopLibrary.setFavorite(Track("yt:fav00001", "Favori", "Artiste", "Album"), true)
        LibrarySync.signedIn(ctx, fresh = true)
        assertEquals(SyncProblem.NONE, LibrarySync.syncNow())
        assertTrue("yt:fav00001" in cloud.docs.getValue("favorites").first)
        assertFalse(ListeningKind.name in cloud.docs)
        // The next round does not ask for the refused kinds again.
        assertEquals(SyncProblem.NONE, LibrarySync.syncNow())
        LibrarySync.signedOut(ctx)
        DesktopLibrary.resetForTest()
    }
}
