package com.ketvoice.app.social

import com.ketvoice.app.data.Track
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profile visibility (the server's rule, same table as supabase/tests/social_rls.sql) and the friends state machine. */
class SocialRulesTest {
    @Test fun visibilityMatchesTheServersTable() {
        val cases = listOf(
            Triple(Relation.Me, Reach.Me, false) to true, Triple(Relation.Me, Reach.Public, true) to true,
            Triple(Relation.Friend, Reach.Me, true) to false, Triple(Relation.Friend, Reach.Friends, false) to true,
            Triple(Relation.Friend, Reach.Public, false) to true, Triple(Relation.Stranger, Reach.Me, true) to false,
            Triple(Relation.Stranger, Reach.Friends, true) to false, Triple(Relation.Stranger, Reach.Public, false) to false,
            Triple(Relation.Stranger, Reach.Public, true) to true)
        cases.forEach { (c, expected) -> assertEquals("$c", expected, ProfileRules.canSee(c.first, c.second, c.third)) }
    }

    @Test fun everyReachAndRelationIsCovered() {
        for (relation in Relation.entries) for (reach in Reach.entries) for (pub in listOf(true, false)) {
            val seen = ProfileRules.canSee(relation, reach, pub)
            if (reach == Reach.Me) assertEquals(relation == Relation.Me, seen)
            if (relation == Relation.Me) assertTrue(seen)
            // Widening a module never hides it from anyone who saw it.
            if (seen && reach != Reach.Public) assertTrue(ProfileRules.canSee(relation, Reach.Public, pub))
        }
        assertFalse(ProfileRules.identityVisible(Relation.Stranger, false))
        assertTrue(ProfileRules.identityVisible(Relation.Friend, false) && ProfileRules.identityVisible(Relation.Stranger, true))
        assertEquals(Reach.Friends, ProfileRules.effective(Reach.Public, profilePublic = false))
    }

    @Test fun unknownWireValuesAreReadAsPrivate() {
        assertEquals(Reach.Me, Reach.of("everyone"))
        assertEquals(Reach.Me, Reach.of(null))
        assertEquals(Relation.Stranger, Relation.of("admin"))
        assertEquals(FriendState.None, FriendState.of("???"))
    }

    @Test fun bioAndAccentFollowTheServersChecks() {
        assertEquals("Nuits blanches et synthés", ProfileRules.cleanBio("  Nuits\nblanches\u0000 et\tsynthés \u202E"))
        assertEquals(160, ProfileRules.cleanBio("a".repeat(500)).length)
        assertEquals("#FF2D55", ProfileRules.accent("ff2d55"))
        assertEquals("#FF2D55", ProfileRules.accent(" #Ff2D55 "))
        assertNull(ProfileRules.accent("red"))
        assertNull(ProfileRules.accent("#FF2D5"))
        assertEquals(0xFFFF2D55L, ProfileRules.accentArgb("#FF2D55"))
    }

    @Test fun friendsStateMachine() {
        assertEquals(listOf(FriendAction.Ask), FriendStates.actions(FriendState.None))
        assertEquals(listOf(FriendAction.Accept, FriendAction.Decline), FriendStates.actions(FriendState.Incoming))
        assertTrue(FriendStates.actions(FriendState.Self).isEmpty())
        assertEquals(FriendState.Outgoing, FriendStates.after(FriendState.None, FriendAction.Ask, "requested"))
        assertEquals(FriendState.Friends, FriendStates.after(FriendState.None, FriendAction.Ask, "accepted"))
        assertEquals(FriendState.None, FriendStates.after(FriendState.None, FriendAction.Ask, "unavailable"))
        assertEquals(FriendState.None, FriendStates.after(FriendState.None, FriendAction.Ask, "needs_handle"))
        assertEquals(FriendState.Friends, FriendStates.after(FriendState.Incoming, FriendAction.Accept, "accepted"))
        assertEquals(FriendState.None, FriendStates.after(FriendState.Incoming, FriendAction.Decline, "declined"))
        assertEquals(FriendState.None, FriendStates.after(FriendState.Outgoing, FriendAction.Cancel, "removed"))
        assertEquals(FriendState.None, FriendStates.after(FriendState.Friends, FriendAction.Remove, "not_found"))
        // A server error leaves the state as it was.
        assertEquals(FriendState.Friends, FriendStates.after(FriendState.Friends, FriendAction.Remove, "error"))
        listOf("requested", "pending", "accepted", "friends", "unavailable", "self", "needs_handle", "limit").forEach {
            assertTrue(it, FriendStates.message(it) != null)
        }
    }

    @Test fun aFriendIsAddedByHandleOrLink() {
        assertEquals("luna.k", FriendStates.handleFrom("@Luna.K"))
        assertEquals("luna.k", FriendStates.handleFrom(" luna.k "))
        assertEquals("luna.k", FriendStates.handleFrom("ketvoice://u/luna.k"))
        assertEquals("luna.k", FriendStates.handleFrom("https://ketvoice-web.vercel.app/u/@Luna.K?x=1"))
        assertNull(FriendStates.handleFrom("Luna K"))
        assertNull(FriendStates.handleFrom("lu"))
        assertNull(FriendStates.handleFrom("luna@example.com"))
    }

    @Test fun friendListsKeepOnlyWellFormedLines() {
        val answer = JSONObject().put("friends", JSONArray().put(JSONObject().put("ref", "c4ef6b1b-e2f5-4705-85bf-9804aa63e070")
                .put("handle", "alice").put("display_name", "Alice\u0000").put("accent", "#FF2D55").put("has_avatar", true))
                .put(JSONObject().put("ref", "x").put("handle", "bob")))
            .put("incoming", JSONArray().put(JSONObject().put("ref", "350697da-d20d-4a71-82cc-2c0b16d9dc40").put("handle", "carol")
                .put("display_name", "").put("accent", JSONObject.NULL)))
        val lists = FriendStates.lists(answer)
        assertEquals(listOf("alice"), lists.friends.map { it.handle })
        assertEquals("Alice", lists.friends[0].name)
        assertEquals("carol", lists.incoming[0].name)
        assertNull(lists.incoming[0].accent)
        assertTrue(lists.outgoing.isEmpty())
    }

    @Test fun profileViewReadsOnlyWhatIsThere() {
        val o = JSONObject("""{"handle":"alice","relation":"friend","friend":"friends","ref":"457f4543-5206-46b9-8a61-1db0e7faf11a",
            "private":false,"display_name":"Alice","bio":"Synthés","accent":"#FF2D55","has_avatar":true,"has_banner":false,
            "modules":{"top":{"reach":"friends","data":{"items":[{"name":"Néon","cover":"https://evil.example/x.jpg"},{"name":""}]}},
            "now":{"reach":"public","data":{}}},
            "listening":{"id":"yt:abcdefghijk","title":"Ocean Drive","artist":"Néon","coverUrl":"https://i.ytimg.com/a.jpg","playing":true}}""")
        val v = ProfileCodec.view(o)!!
        assertEquals(Relation.Friend, v.relation)
        assertEquals(FriendState.Friends, v.friend)
        assertTrue(v.shows(ProfileModule.Top) && v.shows(ProfileModule.Now) && !v.shows(ProfileModule.Pinned))
        assertEquals(listOf(TopArtist("Néon")), v.top)
        assertEquals("Ocean Drive", v.listening?.title)
        assertEquals("https://i.ytimg.com/a.jpg", v.listening?.coverUrl)
        assertTrue(v.listeningNow)
        assertNull(ProfileCodec.view(JSONObject().put("handle", "A B")))
        assertNull(ProfileCodec.view(null))
        val hidden = ProfileCodec.view(JSONObject().put("handle", "carol").put("private", true).put("relation", "stranger"))!!
        assertTrue(hidden.hidden && hidden.reaches.isEmpty() && hidden.displayName.isEmpty())
    }

    @Test fun modulesRoundTrip() {
        val top = listOf(TopArtist("Néon", "https://i.ytimg.com/a.jpg", "UCabcdefghijklmnop"), TopArtist("Marée"))
        assertEquals(top, ProfileCodec.top(ProfileCodec.topData(top)))
        val pinned = listOf(PinnedList("Été", null, 12, "457f4543-5206-46b9-8a61-1db0e7faf11a"), PinnedList("Nuit", null, 3, "nope"))
        assertEquals(listOf(pinned[0], pinned[1].copy(link = null)), ProfileCodec.pinned(ProfileCodec.pinnedData(pinned)))
        assertEquals(ProfileRules.TOP_MAX, ProfileCodec.topData(List(9) { TopArtist("A$it") }).getJSONArray("items").length())
    }

    @Test fun friendsNowAndWhatIsShared() {
        val rows = JSONArray().put(JSONObject().put("handle", "alice").put("display_name", "Alice").put("playing", true)
            .put("room", "ABC234-ABCDEFGH").put("track", JSONObject().put("id", "yt:abcdefghijk").put("title", "Ocean Drive")))
            .put(JSONObject().put("handle", "bob").put("room", "nope").put("track", JSONObject().put("id", "local:file:x").put("title", "Mine")))
        val now = FriendsNow.parse(rows)
        assertEquals("ABC234", now[0].room?.code)
        assertEquals("ABCDEFGH", now[0].room?.fingerprint)
        assertNull(now[1].room)
        assertNull("a local file is never shown", now[1].track)
        assertNull(FriendsNow.trackJson(Track("local:file:C:/x.mp3", "Mine", "Me", "")))
        val shared = FriendsNow.trackJson(Track("yt:abcdefghijk", "Ocean Drive", "Néon", "", streamUrl = "https://x/s", coverUrl = "https://evil.example/c.jpg"))!!
        assertFalse(shared.has("streamUrl") || shared.has("coverUrl"))
        assertEquals("ABC234-ABCDEFGH", FriendsNow.roomWire("ABC234", "ABCDEFGH"))
        assertNull(FriendsNow.roomWire("ABC234", null))
        val a = FriendsNow.Snapshot("t1", true, null, 0)
        assertFalse(FriendsNow.due(a, a.copy(at = 30_000), 30_000))
        assertTrue(FriendsNow.due(a, a.copy(playing = false, at = 1), 1))
        assertTrue(FriendsNow.due(a, a.copy(at = 60_000), 60_000))
    }
}
