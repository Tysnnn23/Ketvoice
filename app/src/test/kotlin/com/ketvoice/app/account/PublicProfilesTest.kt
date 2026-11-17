package com.ketvoice.app.account

import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.FakeHttp
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The public profile's client and parser behind a fake HTTP layer, and the settings it adds to the profile. */
class PublicProfilesTest {
    private val http = FakeHttp()
    private val client = PublicProfileClient(http) { "me" }
    private fun reply(status: Int, text: String = "") = FakeHttp.reply(status, text)

    private val sample = """{"handle":"alice","display_name":"Alice","avatar_ref":"dddddddd-0000-4000-8000-0000000000aa",
      "playlists":[{"id":"pp1","name":"Summer","cover_url":"https://example.com/s.jpg","tracks":[
        {"id":"t1","title":"One","artist":"A","album":"X","durationMs":1000,"coverUrl":"https://example.com/1.jpg"},{"id":"","title":""}]}],
      "listening":{"title":"Now playing","artist":"Band","cover_url":"https://example.com/n.jpg","playing":true}}"""

    @Test fun aProfileReadsBackWithItsPlaylistsAndItsCurrentTitle() {
        val profile = PublicProfileParser.parse(sample)!!
        assertEquals("alice", profile.handle); assertEquals("Alice", profile.displayName)
        assertEquals("dddddddd-0000-4000-8000-0000000000aa", profile.avatarRef)
        assertEquals(1, profile.playlists.size)
        assertEquals("Summer", profile.playlists[0].name)
        assertEquals(listOf("t1"), profile.playlists[0].tracks.map { it.id })
        assertEquals(PublicListening("Now playing", "Band", "https://example.com/n.jpg", true), profile.listening)
    }

    @Test fun noProfileIsNoProfile() {
        assertNull(PublicProfileParser.parse("null"))
        assertNull(PublicProfileParser.parse(""))
        assertNull(PublicProfileParser.parse("{broken"))
    }

    @Test fun aProfileWithoutListeningOrPhotoHasNeither() {
        val profile = PublicProfileParser.parse("""{"handle":"bob","display_name":"Bob","avatar_ref":null,"playlists":[],"listening":null}""")!!
        assertNull(profile.avatarRef); assertNull(profile.listening); assertTrue(profile.playlists.isEmpty())
    }

    @Test fun aPhotoReferenceThatIsNotARandomNameIsIgnoredAndOnlyHttpsCoversAreKept() {
        val profile = PublicProfileParser.parse("""{"handle":"bob","display_name":"B","avatar_ref":"../x/y","playlists":
          [{"id":"p","name":"N","cover_url":"http://insecure/c.jpg","tracks":[]}],"listening":{"title":"T","artist":"","cover_url":"http://x/y.jpg","playing":false}}""")!!
        assertNull(profile.avatarRef); assertNull(profile.playlists[0].coverUrl); assertNull(profile.listening!!.coverUrl)
    }

    @Test fun theLookupAsksForALowercaseHandleWithoutAt() = runBlocking {
        http.handler = { reply(200, sample) }
        assertEquals("alice", client.fetch(" @Alice ")!!.handle)
        val call = http.calls.single()
        assertTrue(call.path.endsWith("/rpc/ketvoice_public_profile"))
        assertEquals("alice", JSONObject(call.body).getString("handle"))
        http.handler = { reply(200, "null") }
        assertNull(client.fetch("nobody"))
    }

    @Test fun publishingSendsAnUpsertOfTheTitlesAnotherAccountCanHear() = runBlocking {
        val tracks = (1..250).map { Track("t$it", "Title $it", "A", "", streamUrl = "https://stream.example/$it") } + Track("local:9", "My file", "Me", "")
        val playlist = LocalPlaylist("pl-1", "Summer", tracks.map { PlaylistEntry(title = it.title, artist = it.artist, track = it) }, coverUrl = "https://example.com/c.jpg")
        client.publish(playlist)
        val call = http.calls.single()
        assertEquals("POST", call.method)
        assertTrue(call.path.contains("ketvoice_public_playlists?on_conflict=owner_id,local_id"))
        assertTrue("merge-duplicates" in call.headers.getValue("Prefer"))
        val body = JSONObject(call.body)
        assertEquals("me", body.getString("owner_id")); assertEquals("pl-1", body.getString("local_id")); assertEquals("Summer", body.getString("name"))
        val sent = body.getJSONArray("tracks")
        assertEquals(200, sent.length())
        assertFalse(call.body!!.contains("stream.example") || call.body!!.contains("local:9"))
        assertEquals("t1", sent.getJSONObject(0).getString("id"))
    }

    @Test fun unpublishingDeletesTheSnapshotAndTheListIsRead() = runBlocking {
        client.unpublish("pl-1")
        assertEquals("DELETE", http.calls.single().method)
        assertTrue(http.calls.single().path.endsWith("ketvoice_public_playlists?local_id=eq.pl-1"))
        http.handler = { reply(200, """[{"local_id":"a"},{"local_id":"b"}]""") }
        assertEquals(setOf("a", "b"), client.published())
    }

    @Test fun aRepublishIsDueOnlyWhenTheTitlesOrTheNameChange() {
        val a = LocalPlaylist("p", "Name", listOf(PlaylistEntry(title = "x", artist = "y", track = Track("1", "x", "y", ""))))
        assertEquals(PublicProfiles.fingerprint(a), PublicProfiles.fingerprint(a.copy(pinned = true)))
        assertNotEquals(PublicProfiles.fingerprint(a), PublicProfiles.fingerprint(a.copy(name = "Other")))
        assertNotEquals(PublicProfiles.fingerprint(a), PublicProfiles.fingerprint(a.copy(entries = emptyList())))
    }

    @Test fun theProfileIsPrivateAndSilentByDefaultAndReadsItsNewFields() {
        val none = AccountProfile()
        assertFalse(none.isPublic); assertFalse(none.showListening)
        val read = Profiles.parse(JSONObject("""{"display_name":"Alice","handle":"alice","avatar_url":null,"is_public":true,"show_listening":true,"avatar_ref":"dddddddd-0000-4000-8000-0000000000aa"}"""))
        assertTrue(read.isPublic && read.showListening); assertEquals("dddddddd-0000-4000-8000-0000000000aa", read.avatarRef)
        val old = Profiles.parse(JSONObject("""{"display_name":"Bob","handle":null,"avatar_url":null}"""))
        assertFalse(old.isPublic); assertFalse(old.showListening); assertEquals("", old.avatarRef)
    }

    @Test fun theCacheNameOfAPhotoKeepsOnlySafeCharacters() {
        assertEquals("ketvoice-public-avatars-dddddddd-0000-4000-8000-0000000000aa.jpg",
            RemoteAvatars.cacheName(RemoteAvatars.PUBLIC, "dddddddd-0000-4000-8000-0000000000aa.jpg"))
        assertEquals("ketvoice-avatars-uid-avatar-1700000000000.jpg", RemoteAvatars.cacheName(RemoteAvatars.PRIVATE, "uid/avatar-1700000000000.jpg"))
        assertFalse(RemoteAvatars.cacheName("b", "../../x").contains("/"))
        assertEquals(JSONArray("[]").length(), 0)
    }
}
