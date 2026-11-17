package com.ketvoice.app.sync

import com.ketvoice.app.account.RestHttp
import com.ketvoice.app.account.RestRejected
import com.ketvoice.app.account.SupabaseHttp
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.Track
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** What the shared playlists' client sends and understands, behind a fake HTTP layer (no network, no session). */
internal class FakeHttp : RestHttp {
    data class Call(val method: String, val path: String, val body: String?, val headers: Map<String, String>)
    val calls = ArrayList<Call>()
    var handler: (Call) -> SupabaseHttp.Response = { reply(200, "[]") }

    override suspend fun call(method: String, path: String, body: String?, headers: Map<String, String>): SupabaseHttp.Response =
        Call(method, path, body, headers).also { calls += it }.let(handler)

    companion object {
        fun reply(status: Int, text: String = "") = SupabaseHttp.Response(status, text.toByteArray(), 0L)
    }
}

class SharedClientTest {
    private val http = FakeHttp()
    private val client = SharedClient(http) { "me" }
    private fun track(id: String) = Track(id = id, title = "Title $id", artist = "Artist", album = "Album", durationMs = 180_000,
        coverUrl = "https://example.com/$id.jpg", albumId = "alb", isrc = "ISRC$id", streamUrl = "https://stream.example/$id", downloadUrl = "https://dl.example/$id")
    private fun reply(status: Int, text: String = "") = FakeHttp.reply(status, text)

    @Test fun listsAndRowsAreReadWithTheFieldsTheAppNeeds() = runBlocking {
        http.handler = { call ->
            if ("ketvoice_shared_playlists" in call.path) reply(200, """[{"id":"p1","name":"Road trip","owner_id":"me"},{"id":"p2","name":"Gym","owner_id":"you"}]""")
            else reply(200, """[
              {"id":"r2","track_id":"t2","title":"Two","artist":"B","album":"","cover_url":null,"duration_ms":1000,"extra":{},"added_by":"you","added_at":"2026-11-18T10:00:00+00:00","pos":2},
              {"id":"r1","track_id":"t1","title":"One","artist":"A","album":"X","cover_url":"https://example.com/c.jpg","duration_ms":181000,"extra":{"albumId":"alb","isrc":"ISRC1"},"added_by":null,"added_at":"2026-11-18T09:00:00+00:00","pos":1.5},
              {"id":"bad","track_id":"","title":"","pos":3}]""")
        }
        val heads = client.lists()
        assertEquals(listOf(SharedHead("p1", "Road trip", "me"), SharedHead("p2", "Gym", "you")), heads)
        val rows = client.rows("p1")
        assertEquals(listOf("r1", "r2"), rows.map { it.id })
        assertEquals("One", rows[0].track.title); assertEquals("https://example.com/c.jpg", rows[0].track.coverUrl)
        assertEquals("alb", rows[0].track.albumId); assertEquals(181_000L, rows[0].track.durationMs)
        assertNull(rows[0].addedBy)
        assertTrue(http.calls.last().path.contains("playlist_id=eq.p1") && http.calls.last().path.contains("order=pos.asc"))
    }

    @Test fun anAddedTitleCarriesItsFieldsButNeverAStreamAddress() = runBlocking {
        client.send(SharedOp.Add("p1", "row-1", track("t1"), 2.5))
        val call = http.calls.single()
        assertEquals("POST", call.method)
        assertTrue(call.path.contains("ketvoice_shared_tracks") && call.path.contains("on_conflict=playlist_id,track_id"))
        assertTrue("ignore-duplicates" in call.headers.getValue("Prefer"))
        val row = JSONArray(call.body).getJSONObject(0)
        assertEquals("p1", row.getString("playlist_id")); assertEquals("t1", row.getString("track_id")); assertEquals("me", row.getString("added_by"))
        assertEquals("Title t1", row.getString("title")); assertEquals(2.5, row.getDouble("pos"), 0.0)
        assertEquals("https://example.com/t1.jpg", row.getString("cover_url"))
        assertEquals("alb", row.getJSONObject("extra").getString("albumId"))
        assertFalse(call.body!!.contains("stream.example") || call.body!!.contains("dl.example"))
        assertFalse(row.has("added_at"))
    }

    @Test fun longTextIsCutToWhatTheServerAccepts() = runBlocking {
        client.send(SharedOp.Add("p1", "r", track("t").copy(title = "x".repeat(500), artist = "y".repeat(500), durationMs = Long.MAX_VALUE), 1.0))
        val row = JSONArray(http.calls.single().body).getJSONObject(0)
        assertEquals(300, row.getString("title").length); assertEquals(300, row.getString("artist").length)
        assertEquals(86_400_000L, row.getLong("duration_ms"))
    }

    @Test fun severalAddsGoInOneCall() = runBlocking {
        client.addAll(listOf(SharedOp.Add("p1", "a", track("a"), 1.0), SharedOp.Add("p1", "b", track("b"), 2.0)))
        assertEquals(1, http.calls.size)
        assertEquals(2, JSONArray(http.calls[0].body).length())
        client.addAll(emptyList()); assertEquals(1, http.calls.size)
    }

    @Test fun eachWriteHasItsRequest() = runBlocking {
        client.send(SharedOp.Create("p1", "Road trip"))
        client.send(SharedOp.Remove("p1", "r1"))
        client.send(SharedOp.Move("p1", "r1", 0.5))
        client.send(SharedOp.Rename("p1", "Gym"))
        client.send(SharedOp.Leave("p1"))
        client.send(SharedOp.Delete("p1"))
        val (create, remove, move, rename, leave, delete) = http.calls
        assertEquals("POST", create.method); assertEquals("me", JSONObject(create.body).getString("owner_id")); assertEquals("p1", JSONObject(create.body).getString("id"))
        assertEquals("DELETE", remove.method); assertTrue(remove.path.endsWith("ketvoice_shared_tracks?id=eq.r1&playlist_id=eq.p1"))
        assertEquals("PATCH", move.method); assertEquals(0.5, JSONObject(move.body).getDouble("pos"), 0.0)
        assertEquals("PATCH", rename.method); assertEquals("Gym", JSONObject(rename.body).getString("name"))
        assertTrue(leave.path.contains("ketvoice_shared_members?playlist_id=eq.p1&user_id=eq.me"))
        assertTrue(delete.path.endsWith("ketvoice_shared_playlists?id=eq.p1"))
    }

    private operator fun <T> List<T>.component6() = this[5]

    @Test fun aPlaylistAlreadyCreatedIsNotAnError() = runBlocking {
        http.handler = { reply(409, """{"code":"23505"}""") }
        client.send(SharedOp.Create("p1", "Road trip"))
    }

    @Test fun aRefusalIsTerminalAndANetworkOrServerFailureIsRetried() = runBlocking {
        http.handler = { reply(403, """{"code":"42501","message":"denied"}""") }
        val refused = runCatching { client.send(SharedOp.Remove("p1", "r")) }.exceptionOrNull()
        assertTrue(refused is RestRejected); assertEquals("42501", (refused as RestRejected).code)
        http.handler = { reply(503) }
        assertTrue(runCatching { client.send(SharedOp.Remove("p1", "r")) }.exceptionOrNull() is IOException)
        http.handler = { reply(429) }
        assertTrue(runCatching { client.send(SharedOp.Remove("p1", "r")) }.exceptionOrNull() is IOException)
        http.handler = { throw IOException("offline") }
        assertTrue(runCatching { client.send(SharedOp.Remove("p1", "r")) }.exceptionOrNull() is IOException)
    }

    @Test fun membersCarryWhatTheSheetShows() = runBlocking {
        http.handler = { reply(200, """[{"user_id":"u1","role":"owner","joined_at":"x","display_name":"Alice","handle":"alice","avatar_path":"u1/avatar-1700000000000.jpg","is_public":true},
            {"user_id":"u2","role":"editor","joined_at":"y","display_name":"Bob","handle":null,"avatar_path":null,"is_public":false}]""") }
        val members = client.members("p1")
        assertEquals(listOf("u1", "u2"), members.map { it.userId })
        assertTrue(members[0].owner && members[0].isPublic && members[0].handle == "alice" && members[0].avatarPath.endsWith(".jpg"))
        assertTrue(!members[1].owner && members[1].handle.isEmpty() && members[1].avatarPath.isEmpty())
        assertEquals("p1", JSONObject(http.calls.single().body).getString("p_playlist"))
        assertTrue(http.calls.single().path.endsWith("/rpc/ketvoice_shared_playlist_members"))
    }

    @Test fun anInvitationIsAShortCodeAndALink() = runBlocking {
        http.handler = { reply(200, """{"code":"ABCDEFGH23","expires_at":"2026-11-25T10:00:00+00:00"}""") }
        val invite = client.invite("p1")
        assertEquals("ABCDEFGH23", invite.code); assertEquals("ketvoice://join/ABCDEFGH23", invite.link)
        assertTrue(http.calls.single().path.endsWith("/rpc/ketvoice_create_shared_invite"))
    }

    @Test fun joiningSendsTheCodeCleanedAndReadsTheStatus() = runBlocking {
        http.handler = { reply(200, """{"status":"joined","playlist_id":"p9","name":"Gym"}""") }
        val result = client.join("  abcdefgh23 ")
        assertEquals(JoinResult("joined", "p9", "Gym"), result)
        assertEquals("ABCDEFGH23", JSONObject(http.calls.single().body).getString("code"))
        assertTrue(http.calls.single().path.endsWith("/rpc/ketvoice_join_shared_playlist"))
        http.handler = { reply(200, """{"status":"invalid"}""") }
        assertEquals(JoinResult("invalid", null, null), client.join("ZZZZZZZZZZ"))
        http.handler = { reply(400, """{"code":"54000","message":"Too many attempts, try again later"}""") }
        assertEquals("54000", (runCatching { client.join("ZZZZZZZZZZ") }.exceptionOrNull() as RestRejected).code)
    }

    // ---- The engine: reading, queueing offline, sending in order -------------------------------------------------

    private fun entry(id: String) = PlaylistEntry("row-$id", "Title $id", "Artist", null, track(id))
    private fun serverRow(id: String, pos: Double) = """{"id":"row-$id","track_id":"$id","title":"Title $id","artist":"Artist","album":"","cover_url":null,"duration_ms":0,"extra":{},"added_by":"you","added_at":"2026-11-18T10:00:00+00:00","pos":$pos}"""

    private fun server(vararg lists: Pair<String, List<String>>): (FakeHttp.Call) -> SupabaseHttp.Response = { call ->
        when {
            call.method != "GET" -> reply(204)
            "ketvoice_shared_playlists" in call.path -> reply(200, lists.joinToString(",", "[", "]") { """{"id":"${it.first}","name":"List ${it.first}","owner_id":"me"}""" })
            else -> {
                val id = call.path.substringAfter("playlist_id=eq.").substringBefore("&")
                reply(200, lists.first { it.first == id }.second.withIndex().joinToString(",", "[", "]") { serverRow(it.value, it.index + 1.0) })
            }
        }
    }

    private fun engine(box: SharedStateBox = SharedStateBox(SharedState("me"))) = SharedEngine(client, box) {} to box

    @Test fun readingFillsTheStateAndKeepsWhatWaitsOnTop() = runBlocking {
        http.handler = server("p1" to listOf("a", "b"))
        val (engine, box) = engine(SharedStateBox(SharedState("me", ops = listOf(SharedOp.Add("p1", "row-new", track("n"), 9.0)))))
        http.handler = { call -> if (call.method == "POST") reply(500) else server("p1" to listOf("a", "b"))(call) }
        assertTrue(engine.refresh())
        val list = box.value.lists.getValue("p1")
        assertEquals(listOf("a", "b"), list.rows.map { it.track.id })
        assertEquals(listOf("a", "b", "n"), SharedMerge.display(list, box.value.ops).rows.map { it.track.id })
        assertEquals(1, box.value.ops.size)
    }

    @Test fun whatWasDoneOfflineGoesOutInOrderLater() = runBlocking {
        val (engine, box) = engine(SharedStateBox(SharedState("me", mapOf("p1" to SharedList("p1", "List p1", "me", emptyList())))))
        box.shown = setOf("p1")
        var online = false
        http.handler = { if (!online) throw IOException("offline") else reply(204) }
        engine.onEdit(listOf(LocalPlaylist(PlaylistRules.sharedLocalId("p1"), "List p1", listOf(entry("a"), entry("b")))))
        assertEquals(2, box.value.ops.size)
        assertFalse(engine.flush())
        assertEquals(2, box.value.ops.size)
        online = true
        assertTrue(engine.flush())
        assertTrue(box.value.ops.isEmpty())
        val sent = http.calls.filter { it.method == "POST" && online }.last()
        assertEquals(2, JSONArray(sent.body).length())
        assertEquals("a", JSONArray(sent.body).getJSONObject(0).getString("track_id"))
    }

    @Test fun aWriteTheServerRefusesIsDroppedAndTheRestGoesOn() = runBlocking {
        val (engine, box) = engine(SharedStateBox(SharedState("me", ops = listOf(SharedOp.Remove("gone", "r1"), SharedOp.Rename("p1", "Gym")))))
        http.handler = { call -> if (call.path.contains("ketvoice_shared_tracks")) reply(403, """{"code":"42501"}""") else reply(204) }
        assertTrue(engine.flush())
        assertTrue(box.value.ops.isEmpty())
        assertEquals(2, http.calls.size)
    }

    @Test fun aPlaylistTheServerNoLongerListsDisappearsButANewOneStays() = runBlocking {
        val gone = SharedList("old", "Old", "you", emptyList())
        val fresh = SharedList("new", "New", "me", emptyList())
        val (engine, box) = engine(SharedStateBox(SharedState("me", mapOf("old" to gone, "new" to fresh), listOf(SharedOp.Create("new", "New"), SharedOp.Remove("old", "x")))))
        http.handler = { call -> if (call.method == "POST") reply(500) else server("p1" to listOf("a"))(call) }
        engine.refresh()
        assertEquals(setOf("p1", "new"), box.value.lists.keys)
        assertEquals(listOf<SharedOp>(SharedOp.Create("new", "New")), box.value.ops)
    }

    @Test fun aPlaylistBeingLeftIsNotBroughtBackByARead() = runBlocking {
        val (engine, box) = engine(SharedStateBox(SharedState("me", ops = listOf(SharedOp.Leave("p1")))))
        http.handler = { call -> if (call.method == "DELETE") reply(500) else server("p1" to listOf("a"))(call) }
        engine.refresh()
        assertTrue(box.value.lists.isEmpty())
        assertEquals(1, box.value.ops.size)
    }

    @Test fun sharingAPlaylistQueuesItsCreationThenItsTitlesInOrder() = runBlocking {
        val (engine, box) = engine()
        engine.share("p1", "Mine", listOf(track("a"), track("b"), track("c")))
        assertEquals(SharedOp.Create("p1", "Mine"), box.value.ops.first())
        assertEquals(listOf(1.0, 2.0, 3.0), box.value.ops.filterIsInstance<SharedOp.Add>().map { it.pos })
        assertEquals("me", box.value.lists.getValue("p1").owner)
        http.handler = { reply(204) }
        assertTrue(engine.flush())
        assertEquals(listOf("POST", "POST"), http.calls.map { it.method })
        assertTrue(http.calls[0].path.endsWith("ketvoice_shared_playlists") && http.calls[1].path.contains("ketvoice_shared_tracks"))
        assertEquals(3, JSONArray(http.calls[1].body).length())
    }
}
