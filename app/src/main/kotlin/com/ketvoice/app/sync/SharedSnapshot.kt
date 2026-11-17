package com.ketvoice.app.sync

import org.json.JSONArray
import org.json.JSONObject

/** The shared playlists as kept on the device between runs (the lists last read and the writes still to send). */
internal object SharedSnapshot {
    private const val VERSION = 1

    fun encode(state: SharedState): String = JSONObject().put("v", VERSION).put("user", state.user)
        .put("lists", JSONArray(state.lists.values.map(::list)))
        .put("ops", JSONArray(state.ops.map(::op))).toString()

    fun decode(text: String): SharedState? = runCatching {
        val json = JSONObject(text)
        require(json.getInt("v") == VERSION)
        val lists = json.getJSONArray("lists").let { rows -> (0 until rows.length()).mapNotNull { list(rows.getJSONObject(it)) } }
        val ops = json.getJSONArray("ops").let { rows -> (0 until rows.length()).mapNotNull { op(rows.getJSONObject(it)) } }
        SharedState(json.getString("user"), lists.associateBy { it.id }, ops)
    }.getOrNull()

    private fun list(list: SharedList): JSONObject = JSONObject().put("id", list.id).put("name", list.name).put("owner", list.owner)
        .put("rows", JSONArray(list.rows.map { row ->
            JSONObject().put("id", row.id).put("track", SyncTracks.json(row.track)).put("by", row.addedBy ?: JSONObject.NULL)
                .put("at", row.addedAt).put("pos", row.pos)
        }))

    private fun list(json: JSONObject): SharedList? = runCatching {
        val rows = json.getJSONArray("rows")
        SharedList(json.getString("id"), json.getString("name"), json.getString("owner"), (0 until rows.length()).mapNotNull {
            val row = rows.getJSONObject(it)
            val track = SyncTracks.parse(row.getJSONObject("track").toString()) ?: return@mapNotNull null
            SharedRow(row.getString("id"), track, row.optString("by").takeIf { !row.isNull("by") && it.isNotBlank() },
                row.optString("at"), row.getDouble("pos"))
        })
    }.getOrNull()

    private fun op(op: SharedOp): JSONObject = JSONObject().put("list", op.list).also {
        when (op) {
            is SharedOp.Create -> it.put("t", "create").put("name", op.name)
            is SharedOp.Add -> it.put("t", "add").put("row", op.row).put("track", SyncTracks.json(op.track)).put("pos", op.pos)
            is SharedOp.Remove -> it.put("t", "remove").put("row", op.row)
            is SharedOp.Move -> it.put("t", "move").put("row", op.row).put("pos", op.pos)
            is SharedOp.Rename -> it.put("t", "rename").put("name", op.name)
            is SharedOp.Delete -> it.put("t", "delete")
            is SharedOp.Leave -> it.put("t", "leave")
        }
    }

    private fun op(json: JSONObject): SharedOp? = runCatching {
        val list = json.getString("list")
        when (json.getString("t")) {
            "create" -> SharedOp.Create(list, json.getString("name"))
            "add" -> SharedOp.Add(list, json.getString("row"), SyncTracks.parse(json.getJSONObject("track").toString())!!, json.getDouble("pos"))
            "remove" -> SharedOp.Remove(list, json.getString("row"))
            "move" -> SharedOp.Move(list, json.getString("row"), json.getDouble("pos"))
            "rename" -> SharedOp.Rename(list, json.getString("name"))
            "delete" -> SharedOp.Delete(list)
            "leave" -> SharedOp.Leave(list)
            else -> null
        }
    }.getOrNull()
}
