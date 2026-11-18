package com.ketvoice.app.sync

import com.ketvoice.app.account.SupabaseHttp
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The friends feed's client, merging, reactions and offline cache, behind a fake HTTP layer (no network, no session). */
class FriendsFeedTest {
    private val http = FakeHttp()
    private val client = FriendsClient(http)
    private fun reply(status: Int, text: String = "") = FakeHttp.reply(status, text)

    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private fun at(minute: Int) = "2026-11-18T10:%02d:00.123456+00:00".format(minute)

    private fun line(n: Int, minute: Int, kind: String = "published", reactions: String = "{}", mine: String? = null) = JSONObject()
        .put("id", id(n)).put("kind", kind).put("at", at(minute)).put("handle", "alice").put("display_name", "Alice")
        .put("avatar_ref", "dddddddd-0000-4000-8000-00000000000d")
        .put("playlist", if (kind == "listening") JSONObject.NULL else JSONObject().put("id", "pl-$n").put("name", "List $n").put("cover_url", "https://example.com/$n.jpg"))
        .put("track", if (kind == "listening") JSONObject().put("id", "t$n").put("title", "Song $n").put("artist", "Artist") else JSONObject.NULL)
        .put("titles", if (kind == "added") 3 else 1).put("reactions", JSONObject(reactions)).put("mine", mine ?: JSONObject.NULL)

    private fun page(vararg lines: JSONObject) = JSONArray(lines.toList()).toString()

    @Test fun aPageIsReadWithTheCursorAndLimitAndOnlyHandlesComeBack() = runBlocking {
        http.handler = { reply(200, page(line(1, 30, "listening", """{"heart":2,"fire":0}""", "heart"), line(2, 20, "added"),
            JSONObject().put("id", "not-a-uuid").put("kind", "published"),
            line(3, 10).put("handle", "Not A Handle"), line(4, 5).put("kind", "unknown"), line(5, 4, "listening").put("track", JSONObject.NULL))) }
        val items = client.feed(at(40), 30)
        val call = http.calls.single()
        assertEquals("POST", call.method); assertEquals("/rest/v1/rpc/ketvoice_friends_feed", call.path)
        assertEquals(at(40), JSONObject(call.body).getString("before")); assertEquals(30, JSONObject(call.body).getInt("limit"))
        assertEquals(listOf(id(1), id(2)), items.map { it.id })
        val listening = items[0]
        assertEquals(FeedKind.Listening, listening.kind); assertEquals("Song 1", listening.track?.title); assertNull(listening.playlist)
        assertEquals(mapOf("heart" to 2), listening.reactions); assertEquals("heart", listening.mine)
        assertEquals(FeedKind.Added, items[1].kind); assertEquals(3, items[1].titles); assertEquals("List 2", items[1].playlist?.name)
        assertEquals("https://example.com/2.jpg", items[1].coverUrl)
        assertTrue(items[0].atMs > items[1].atMs)
    }

    @Test fun theFirstPageSendsNoCursor() = runBlocking {
        client.feed(null, 10)
        val body = JSONObject(http.calls.single().body)
        assertTrue(body.isNull("before")); assertEquals(10, body.getInt("limit"))
    }

    @Test fun pagingFollowsTheServersOwnTimeTextAndStopsAtAShortPage() = runBlocking {
        http.handler = { call ->
            val before = JSONObject(call.body).opt("before")
            if (before == JSONObject.NULL) reply(200, page(line(1, 50), line(2, 40)))
            else reply(200, page(line(2, 40), line(3, 30)))
        }
        val model = FeedModel(client, null)
        model.refresh(limit = 2)
        assertEquals(listOf(id(1), id(2)), model.state.value.items.map { it.id })
        assertFalse(model.state.value.endReached)
        model.loadMore(limit = 2)
        assertEquals(at(40), JSONObject(http.calls.last().body).getString("before"))
        assertEquals("each line once, newest first", listOf(id(1), id(2), id(3)), model.state.value.items.map { it.id })
        assertFalse(model.state.value.endReached)
        http.handler = { reply(200, "[]") }
        model.loadMore(limit = 2)
        assertTrue(model.state.value.endReached)
        val calls = http.calls.size
        model.loadMore(limit = 2)
        assertEquals("no call once the end is reached", calls, http.calls.size)
    }

    @Test fun aFreshFirstPageDropsWhatVanishedAndMovesAnUpdatedLineUp() {
        val parse = { json: JSONObject -> FeedParser.item(json)!! }
        val shown = listOf(parse(line(1, 50)), parse(line(2, 40, "added")), parse(line(3, 30)), parse(line(4, 10)))
        // Line 2 had titles added (it moved up), line 3 was unpublished; line 4 is older than a full page.
        val fresh = listOf(parse(line(2, 55, "added")), parse(line(1, 50)), parse(line(5, 20)))
        val merged = FeedMerge.head(shown, fresh, limit = 3)
        assertEquals(listOf(id(2), id(1), id(5), id(4)), merged.map { it.id })
        assertEquals(at(55), merged[0].at)
        assertEquals("a short page holds everything", listOf(id(2), id(1), id(5)), FeedMerge.head(shown, fresh, limit = 30).map { it.id })
        assertTrue("an empty page empties the feed", FeedMerge.head(shown, emptyList()).isEmpty())
        assertEquals(id(4), FeedMerge.more(shown, listOf(parse(line(4, 10)))).last().id)
        assertEquals(4, FeedMerge.more(shown, listOf(parse(line(4, 10)))).size)
    }

    @Test fun aReactionShowsAtOnceAndTheServersCountsWin() = runBlocking {
        http.handler = { call ->
            if (call.path.endsWith("ketvoice_friends_feed")) reply(200, page(line(1, 50, reactions = """{"heart":1}""")))
            else reply(200, """{"status":"ok","reactions":{"heart":1,"fire":4},"mine":"fire"}""")
        }
        val model = FeedModel(client, null)
        model.refresh()
        assertTrue(model.react(id(1), "fire"))
        val sent = JSONObject(http.calls.last().body)
        assertEquals("/rest/v1/rpc/ketvoice_react", http.calls.last().path)
        assertEquals(id(1), sent.getString("p_activity")); assertEquals("fire", sent.getString("p_reaction"))
        val item = model.state.value.items.single()
        assertEquals(mapOf("heart" to 1, "fire" to 4), item.reactions); assertEquals("fire", item.mine)
        // Touching one's own reaction again takes it back.
        http.handler = { reply(200, """{"status":"removed","reactions":{"heart":1},"mine":null}""") }
        model.react(id(1), "fire")
        assertTrue(JSONObject(http.calls.last().body).isNull("p_reaction"))
        assertNull(model.state.value.items.single().mine); assertEquals(mapOf("heart" to 1), model.state.value.items.single().reactions)
    }

    @Test fun aFailedReactionPutsTheOldOneBackAndAnUnavailableLineLeaves() = runBlocking {
        http.handler = { call ->
            if (call.path.endsWith("ketvoice_friends_feed")) reply(200, page(line(1, 50, reactions = """{"clap":2}""", mine = "clap"), line(2, 40)))
            else reply(503)
        }
        val model = FeedModel(client, null)
        model.refresh()
        assertFalse(model.react(id(1), "heart"))
        assertEquals("clap", model.state.value.items[0].mine); assertEquals(mapOf("clap" to 2), model.state.value.items[0].reactions)
        http.handler = { reply(200, """{"status":"unavailable"}""") }
        assertFalse(model.react(id(2), "heart"))
        assertEquals(listOf(id(1)), model.state.value.items.map { it.id })
    }

    @Test fun theOptimisticCountsMoveFromTheOldReactionToTheNewOne() {
        val item = FeedParser.item(line(1, 1, reactions = """{"heart":1,"fire":2}""", mine = "heart"))!!
        val moved = FeedReactions.apply(item, "fire")
        assertEquals(mapOf("fire" to 3), moved.reactions); assertEquals("fire", moved.mine)
        assertEquals(mapOf("fire" to 2), FeedReactions.apply(moved, null).reactions)
        assertEquals(5, FeedReactions.keys.size)
        FeedReactions.keys.forEach { assertTrue(FeedReactions.emoji(it).isNotEmpty()) }
    }

    @Test fun followUnfollowAndBlockSendAHandleAndReadTheStatus() = runBlocking {
        http.handler = { call -> reply(200, when {
            call.path.endsWith("ketvoice_follow") -> """{"status":"following"}"""
            call.path.endsWith("ketvoice_unfollow") -> """{"status":"removed"}"""
            else -> """{"status":"blocked"}"""
        }) }
        assertEquals("following", client.follow(" @Alice "))
        assertEquals("alice", JSONObject(http.calls.last().body).getString("handle"))
        assertEquals("removed", client.unfollow("alice"))
        assertEquals("blocked", client.block("@bob"))
        assertEquals(listOf("/rest/v1/rpc/ketvoice_follow", "/rest/v1/rpc/ketvoice_unfollow", "/rest/v1/rpc/ketvoice_block"), http.calls.map { it.path })
        assertTrue(http.calls.all { it.method == "POST" && JSONObject(it.body).length() == 1 })
    }

    @Test fun theListsAreReadByRefAndManagedByRef() = runBlocking {
        val ref = id(9)
        http.handler = { call ->
            if (call.path.endsWith("ketvoice_follow_lists")) reply(200, """{"following":[{"ref":"$ref","handle":"alice","display_name":"Alice",
                "avatar_ref":"dddddddd-0000-4000-8000-00000000000d","is_public":true,"since":"x"}],
              "followers":[{"ref":"${id(8)}","handle":"carol","display_name":"Carol","avatar_ref":null,"is_public":false,"following":true},
                {"ref":"bad","handle":"x"}],"blocked":[]}""")
            else reply(200, """{"status":"done"}""")
        }
        val lists = client.lists()
        assertEquals("alice", lists.following.single().handle); assertTrue(lists.following.single().isPublic)
        assertEquals("dddddddd-0000-4000-8000-00000000000d", lists.following.single().avatarRef)
        val carol = lists.followers.single()
        assertEquals("carol", carol.handle); assertNull(carol.avatarRef); assertFalse(carol.isPublic); assertTrue(carol.followsBack)
        assertTrue(lists.blocked.isEmpty())
        assertEquals("done", client.manage(carol.ref, "block"))
        val body = JSONObject(http.calls.last().body)
        assertEquals(id(8), body.getString("p_ref")); assertEquals("block", body.getString("p_action"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.manage(ref, "delete") } }
        Unit
    }

    @Test fun theLastPageIsKeptForOfflineAndARefusalIsNotOffline() = runBlocking {
        val file = File.createTempFile("friends-feed", ".json").also { it.delete(); it.deleteOnExit() }
        http.handler = { reply(200, page(line(1, 50, "listening", """{"wow":1}""", "wow"), line(2, 40))) }
        FeedModel(client, FeedCache(file)).refresh()
        assertTrue(file.exists())
        // The next launch, offline: the cached lines are shown at once, and stay when the network fails.
        http.handler = { throw java.io.IOException("offline") }
        val offline = FeedModel(client, FeedCache(file))
        assertEquals(listOf(id(1), id(2)), offline.state.value.items.map { it.id })
        assertEquals("Song 1", offline.state.value.items[0].track?.title); assertEquals("wow", offline.state.value.items[0].mine)
        offline.refresh()
        assertEquals(FeedProblem.Offline, offline.state.value.problem); assertEquals(2, offline.state.value.items.size)
        http.handler = { reply(404, """{"code":"PGRST202"}""") }
        offline.refresh()
        assertEquals(FeedProblem.Unavailable, offline.state.value.problem)
        FeedCache(file).clear()
        assertTrue(FeedModel(client, FeedCache(file)).state.value.items.isEmpty())
    }

    @Test fun unfollowingSomeoneTakesTheirLinesAwayAtOnce() = runBlocking {
        http.handler = { reply(200, page(line(1, 50), line(2, 40).put("handle", "bob"))) }
        val model = FeedModel(client, null)
        model.refresh()
        model.forget("alice")
        assertEquals(listOf(id(2)), model.state.value.items.map { it.id })
    }

    @Test fun theActivitySettingIsTheListenersOwnRow() = runBlocking {
        http.handler = { call -> if (call.method == "GET") reply(200, """[{"share_activity":false}]""") else reply(204) }
        assertEquals(false, client.shareActivity("me"))
        assertTrue(http.calls.last().path.contains("select=share_activity") && http.calls.last().path.contains("id=eq.me"))
        client.setShareActivity("me", true)
        assertEquals("PATCH", http.calls.last().method); assertEquals(true, JSONObject(http.calls.last().body).getBoolean("share_activity"))
        http.handler = { reply(200, "[]") }
        assertNull(client.shareActivity("me"))
    }

    @Test fun theServersTimeTextIsReadWithItsMicroseconds() {
        assertEquals(java.time.Instant.parse("2026-11-18T10:00:00.123Z").toEpochMilli(), FeedParser.millis("2026-11-18T10:00:00.123456+00:00"))
        assertEquals(0L, FeedParser.millis("not a time"))
        assertTrue(SupabaseHttp.Response(200, ByteArray(0), 0).ok)
    }
}
