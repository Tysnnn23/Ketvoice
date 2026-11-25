package com.ketvoice.desktop.social

import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.social.FriendAction
import com.ketvoice.app.social.ProfileModule
import com.ketvoice.app.social.Reach
import com.ketvoice.desktop.platform.DesktopPlatform
import com.ketvoice.desktop.ui.AlbumRef
import com.ketvoice.desktop.ui.DeskLayout
import com.ketvoice.desktop.ui.Fixtures
import com.ketvoice.desktop.ui.Navigator
import com.ketvoice.desktop.ui.RecordingPlayer
import com.ketvoice.desktop.ui.TrackActions
import com.ketvoice.desktop.ui.TrackMenuSlots
import com.ketvoice.desktop.ui.UserPlaylist
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A server in memory: the listener's rows and what each call asked, without any network. */
class FakeSocial(var uid: String? = "aaaaaaaa-0000-4000-8000-000000000001") : SocialBackend {
    val calls = ArrayList<String>()
    var profile = JSONObject().put("display_name", "Alice").put("handle", "alice").put("bio", "").put("is_public", false).put("share_listening", false)
    val modules = LinkedHashMap<String, JSONObject>()
    val objects = LinkedHashMap<String, ByteArray>()
    var answers = HashMap<String, String>()
    var patchStatus = 204

    override fun userId(): String? = uid
    override suspend fun rpc(name: String, body: JSONObject): String? { calls += "rpc $name $body"; return answers[name] }
    override suspend fun openRpc(name: String, body: JSONObject): String? { calls += "open $name $body"; return answers[name] }
    override suspend fun myProfile(): JSONObject? = profile.takeIf { uid != null }
    override suspend fun patchProfile(fields: JSONObject): Int {
        calls += "patch $fields"
        if (patchStatus in 200..299) fields.keys().forEach { profile.put(it, fields.get(it)) }
        return patchStatus
    }
    override suspend fun myModules(): JSONArray = JSONArray().apply { modules.forEach { (m, row) -> put(JSONObject(row.toString()).put("module", m)) } }
    override suspend fun putModule(module: String, reach: String, data: JSONObject): Boolean {
        calls += "module $module $reach"; modules[module] = JSONObject().put("reach", reach).put("data", data); return true
    }
    override suspend fun upload(path: String, jpeg: ByteArray): Boolean { calls += "upload $path"; objects[path] = jpeg; return true }
    override suspend fun removeObjects(paths: List<String>) { calls += "remove $paths"; paths.forEach(objects::remove) }
    override suspend fun picture(handle: String, kind: String): ByteArray? = null
    override suspend fun saveLink(localId: String, name: String, cover: String?, tracks: JSONArray): String? {
        calls += "link $localId $name ${tracks.length()}"; return "457f4543-5206-46b9-8a61-1db0e7faf11a"
    }
}

/** Profile, friends, « Partager mon écoute », share links and ketvoice:// on Windows, over [FakeSocial]. */
class SocialDesktopTest {
    private val server = FakeSocial()
    private val copied = ArrayList<String>()
    private val song = Track("yt:abcdefghijk", "Ocean Drive", "Néon", "Nuits", 200_000, coverUrl = "https://i.ytimg.com/vi/a/hq.jpg")
    private val local = Track("local:file:C:/Music/x.mp3", "Mine", "Me", "", 100_000)

    @BeforeTest fun setUp() {
        Social.backend = server; Social.clear()
        ShareLinks.copy = { copied += it }
        ShareLinks.register()
    }

    @AfterTest fun tearDown() {
        Social.backend = RestSocial; Social.clear(); ShareLinks.notice.value = null
        TrackMenuSlots.share = null; TrackMenuSlots.shareAlbum = null; TrackMenuSlots.sharePlaylist = null
        DesktopPlatform.pendingLinks.value = emptyList()
    }

    @Test fun theProfileIsReadAndSavedWithTheServersRules() = runBlocking {
        val me = Social.refreshMe()!!
        assertEquals("alice", me.handle)
        assertEquals(Reach.Me, me.reach(ProfileModule.Top), "a module never set is private")
        assertEquals("Un profil public a besoin d'un nom d'utilisateur.", Social.saveIdentity("Alice", "", "", null, isPublic = true))
        assertEquals("Nom invalide (1 à 40 caractères).", Social.saveIdentity(" ", "alice", "", null, false))
        assertNotNull(Social.saveIdentity("Alice", "a b", "", null, false))
        server.patchStatus = 409
        assertEquals("Ce nom d'utilisateur est déjà pris.", Social.saveIdentity("Alice", "bob", "", null, false))
        server.patchStatus = 204
        assertNull(Social.saveIdentity("Alice L.", "@Alice", "Nuits\nblanches", "ff2d55", true))
        val sent = JSONObject(server.calls.last { it.startsWith("patch") }.removePrefix("patch "))
        assertEquals("alice", sent.getString("handle"))
        assertEquals("Nuits blanches", sent.getString("bio"))
        assertEquals("#FF2D55", sent.getString("accent"))
        assertTrue(Social.me.value!!.isPublic)
    }

    @Test fun modulesAreWrittenWithTheirReachAndPinsAreLinks() = runBlocking {
        Social.refreshMe()
        assertTrue(Social.setReach(ProfileModule.Now, Reach.Friends))
        assertEquals("friends", server.modules["now"]!!.getString("reach"))
        assertTrue(Social.pin(listOf(UserPlaylist("pl-1", "Été", listOf(song, local)))))
        assertTrue(server.calls.any { it == "link pl-1 Été 1" }, "the local file stays on this computer: ${server.calls}")
        val pinned = server.modules["pinned"]!!
        assertEquals("me", pinned.getString("reach"), "pinning does not widen who sees it")
        assertEquals("457f4543-5206-46b9-8a61-1db0e7faf11a", pinned.getJSONObject("data").getJSONArray("lists").getJSONObject(0).getString("link"))
        Social.refreshMe()
        assertEquals(Reach.Friends, Social.me.value!!.reach(ProfileModule.Now))
        assertEquals(1, Social.me.value!!.pinned.size)
    }

    @Test fun aNewPhotoReplacesTheOldOne() = runBlocking {
        Social.refreshMe()
        val image = BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB)
        val jpeg = ProfileImages.render(image, ProfileImages.Kind.Avatar)
        assertNull(Social.setPicture(ProfileImages.Kind.Avatar, jpeg))
        val first = Social.me.value!!.avatarPath!!
        assertTrue(first.matches(Regex("aaaaaaaa-0000-4000-8000-000000000001/avatar-[0-9]{10,16}\\.jpg")), first)
        Thread.sleep(2)
        assertNull(Social.setPicture(ProfileImages.Kind.Avatar, jpeg))
        assertTrue(server.calls.any { it == "remove [$first]" }, server.calls.toString())
        assertEquals(1, server.objects.size)
        assertTrue(Social.removePicture(ProfileImages.Kind.Avatar))
        assertTrue(server.objects.isEmpty())
    }

    @Test fun picturesAreFramedSmallAndJpeg() {
        val wide = BufferedImage(3000, 1000, BufferedImage.TYPE_INT_RGB)
        val avatar = ImageIO.read(ByteArrayInputStream(ProfileImages.render(wide, ProfileImages.Kind.Avatar, 2f, 1f, 0f)))
        assertEquals(512 to 512, avatar.width to avatar.height)
        val bannerBytes = ProfileImages.render(wide, ProfileImages.Kind.Banner)
        val banner = ImageIO.read(ByteArrayInputStream(bannerBytes))
        assertEquals(1500 to 500, banner.width to banner.height)
        assertTrue(bannerBytes.size <= com.ketvoice.app.account.AvatarCrop.MAX_BYTES)
        assertTrue(bannerBytes[0] == 0xFF.toByte() && bannerBytes[1] == 0xD8.toByte())
        val notAnImage = File.createTempFile("kvimage", ".png").apply { writeText("hello"); deleteOnExit() }
        assertNull(ProfileImages.read(notAnImage))
        assertEquals(com.ketvoice.app.account.CropRect(0, 0, 1500, 500), com.ketvoice.app.account.AvatarCrop.frame(3000, 1000, 3f, 2f, -1f, -1f))
    }

    @Test fun friendsGoThroughTheServersFunctions() = runBlocking {
        server.answers["ketvoice_friend_request"] = """{"status":"requested","ref":"c4ef6b1b-e2f5-4705-85bf-9804aa63e070"}"""
        server.answers["ketvoice_friend_lists"] = """{"friends":[],"incoming":[],"outgoing":[{"ref":"c4ef6b1b-e2f5-4705-85bf-9804aa63e070","handle":"bob","display_name":"Bob"}]}"""
        assertEquals("requested", Social.act(FriendAction.Ask, "bob", null))
        assertEquals("bob", Social.friends.value.outgoing.single().handle)
        server.answers["ketvoice_friend_remove"] = """{"status":"removed"}"""
        assertEquals("removed", Social.act(FriendAction.Cancel, null, "c4ef6b1b-e2f5-4705-85bf-9804aa63e070"))
        assertTrue(server.calls.any { it.startsWith("rpc ketvoice_friend_remove") && it.contains("p_ref") })
        server.answers.remove("ketvoice_friend_answer")
        assertEquals("error", Social.act(FriendAction.Accept, null, "x"), "no answer is an error, never a success")
        server.answers["ketvoice_friends_now"] = """[{"handle":"bob","display_name":"Bob","playing":true,"room":"ABC234-ABCDEFGH","track":{"id":"yt:abcdefghijk","title":"Ocean Drive"}}]"""
        Social.refreshNow()
        assertEquals("ABC234", Social.now.value.single().room?.code)
    }

    @Test fun listeningIsSharedOnlyWhenSwitchedOn() = runBlocking {
        Player.resetForTest()
        server.answers["ketvoice_presence_put"] = """{"status":"ok"}"""
        Social.refreshMe()
        Presence.force()
        Presence.step(0)
        assertTrue(server.calls.none { it.contains("presence") }, "off: nothing is written")
        assertTrue(Social.setShareListening(true))
        Presence.step(1_000)
        val put = server.calls.single { it.startsWith("rpc ketvoice_presence_put") }
        assertTrue(put.contains("\"p_track\":null") && put.contains("\"p_playing\":false"), put)
        Presence.step(2_000)
        assertEquals(1, server.calls.count { it.startsWith("rpc ketvoice_presence_put") }, "nothing changed: no second write")
        Presence.step(70_000)
        assertEquals(2, server.calls.count { it.startsWith("rpc ketvoice_presence_put") }, "once a minute while sharing")
        assertTrue(Social.setShareListening(false))
        Presence.step(200_000)
        assertEquals(2, server.calls.count { it.startsWith("rpc ketvoice_presence_put") })
        // Without a handle, sharing cannot start.
        server.profile.put("handle", JSONObject.NULL); Social.refreshMe()
        assertFalse(Social.setShareListening(true))
    }

    @Test fun shareIsInEveryMenuAndCopiesTheSmartLink() = runBlocking {
        val menu = TrackActions(RecordingPlayer(), Fixtures.library(), Navigator()).menu(song)
        val share = menu.single { it.tag == "share" }
        share.action()
        assertEquals("https://ketvoice-web.vercel.app/listen?track=yt%3Aabcdefghijk&title=Ocean%20Drive&artist=N%C3%A9on" +
            "&cover=https%3A%2F%2Fi.ytimg.com%2Fvi%2Fa%2Fhq.jpg", copied.single())
        assertEquals(ShareLinks.trackUrl(song), copied.single())
        assertEquals("Lien copié", ShareLinks.notice.value)
        assertTrue(TrackActions(RecordingPlayer(), Fixtures.library(), Navigator()).menu(local).none { it.tag == "share" })
        assertNull(ShareLinks.trackUrl(local))
        assertTrue("share" in DeskLayout.menuItems(""), "the phone's id, shown by default once the slot is there")
        assertEquals("https://ketvoice-web.vercel.app/album/MPREb_abcdefgh", ShareLinks.albumItem(AlbumRef("MPREb_abcdefgh", "Nuits", "Néon", null))?.let {
            it.action(); copied.last()
        })
        assertNull(ShareLinks.albumItem(AlbumRef(null, "Nuits", "Néon", null)))
        assertEquals("https://ketvoice-web.vercel.app/listen?playlist=457f4543-5206-46b9-8a61-1db0e7faf11a&name=%C3%89t%C3%A9",
            ShareLinks.playlistUrl(UserPlaylist("pl-1", "Été", listOf(song))))
        server.uid = null
        ShareLinks.playlistItem(UserPlaylist("pl-1", "Été", listOf(song)))!!.action()
        assertEquals("Connectez-vous à votre compte Ketvoice pour partager une playlist", ShareLinks.notice.value)
    }

    @Test fun ketvoiceLinksWaitForTheFeatureThatKnowsThem() {
        DesktopPlatform.open(listOf("ketvoice://u/luna.k", "ketvoice://auth/qr?x=1", "C:\\Music\\a.mp3"))
        assertEquals(listOf("ketvoice://u/luna.k", "ketvoice://auth/qr?x=1"), DesktopPlatform.pendingLinks.value)
        assertEquals(listOf("ketvoice://u/luna.k"), DesktopPlatform.claim(DeepLinks::mine))
        assertEquals(listOf("ketvoice://auth/qr?x=1"), DesktopPlatform.pendingLinks.value, "another feature's link stays for it")
        assertEquals("\"C:\\Program Files\\Ketvoice\\Ketvoice.exe\" \"%1\"", UrlScheme.command("C:\\Program Files\\Ketvoice\\Ketvoice.exe"))
    }
}
