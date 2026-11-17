package com.ketvoice.app.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * One synced value of the library (a favourite, a playlist, a setting…): its JSON in canonical form,
 * when it last changed and whether it was removed. A removed item stays as a tombstone so a device
 * that still holds it learns of the removal instead of bringing it back.
 */
internal data class SyncItem(val value: String?, val updatedAt: Long, val deleted: Boolean = false) {
    companion object {
        fun tombstone(at: Long) = SyncItem(null, at, true)
    }
}

/** A whole kind of the library (all favourites, all playlists…), one document per account and kind in the cloud. */
internal data class SyncDoc(val items: Map<String, SyncItem> = emptyMap()) {
    /** The values that are not removed, by key. */
    val live: Map<String, String> get() = items.filterValues { !it.deleted && it.value != null }.mapValues { it.value.value!! }
}

internal object SyncMerge {
    const val MAX_KEY = 256
    /** Tombstones older than this are forgotten: every device signed in since has seen them. */
    const val TOMBSTONE_KEEP_MS = 90L * 24 * 3600 * 1000

    /** Union of both sides; per key the newest wins; on a tie the cloud's copy wins so every device lands on the same value. */
    fun merge(local: SyncDoc, cloud: SyncDoc): SyncDoc {
        val keys = local.items.keys + cloud.items.keys
        return SyncDoc(keys.associateWith { key -> pick(local.items[key], cloud.items[key]) })
    }

    private fun pick(local: SyncItem?, cloud: SyncItem?): SyncItem = when {
        local == null -> cloud!!
        cloud == null -> local
        local.updatedAt > cloud.updatedAt -> local
        else -> cloud
    }

    /**
     * What changed on this device since [previous]: a new or changed value is stamped [now], a value that
     * disappeared becomes a tombstone at [now]. Unchanged items keep their time. [initial] is the first
     * sync of this device with the account: its values are stamped 0, so the account's copy wins over them
     * while what only this device had is still added.
     */
    fun stamp(previous: SyncDoc, current: Map<String, String>, now: Long, initial: Boolean = false): SyncDoc {
        val at = if (initial) 0L else now
        val out = LinkedHashMap<String, SyncItem>()
        previous.items.forEach { (key, item) ->
            val value = current[key]
            out[key] = when {
                value == null -> if (item.deleted) item else SyncItem.tombstone(at)
                item.deleted || item.value != value -> SyncItem(value, at)
                else -> item
            }
        }
        current.forEach { (key, value) -> if (key !in out) out[key] = SyncItem(value, at) }
        return SyncDoc(out)
    }

    fun prune(doc: SyncDoc, now: Long): SyncDoc =
        SyncDoc(doc.items.filterValues { !it.deleted || now - it.updatedAt < TOMBSTONE_KEEP_MS })

    fun encode(doc: SyncDoc): JSONObject {
        val items = JSONObject()
        doc.items.toSortedMap().forEach { (key, item) ->
            val row = JSONObject().put("t", item.updatedAt)
            if (item.deleted) row.put("d", true) else row.put("v", parseValue(item.value!!))
            items.put(key, row)
        }
        return JSONObject().put("v", 1).put("items", items)
    }

    fun decode(raw: JSONObject?): SyncDoc {
        val items = raw?.optJSONObject("items") ?: return SyncDoc()
        val out = LinkedHashMap<String, SyncItem>()
        items.keys().forEach { key -> decodeItem(key, items.optJSONObject(key))?.let { out[key] = it } }
        return SyncDoc(out)
    }

    private fun decodeItem(key: String, row: JSONObject?): SyncItem? {
        if (row == null || key.isEmpty() || key.length > MAX_KEY) return null
        val at = row.optLong("t", -1)
        if (at < 0) return null
        if (row.optBoolean("d")) return SyncItem.tombstone(at)
        if (!row.has("v") || row.isNull("v")) return null
        return SyncItem(Canonical.text(row.get("v")), at)
    }

    private fun parseValue(text: String): Any = when {
        text.startsWith("{") -> JSONObject(text)
        text.startsWith("[") -> JSONArray(text)
        else -> JSONArray("[$text]").get(0)
    }
}

/**
 * JSON written the same way whatever produced it: object keys sorted, at every depth. The cloud
 * (Postgres jsonb) gives keys back in its own order; without this every device would see a change
 * in a value that did not change.
 */
internal object Canonical {
    fun text(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is JSONObject -> writeObject(out, value)
            is JSONArray -> writeArray(out, value)
            is Map<*, *> -> writeObject(out, JSONObject(value))
            is Collection<*> -> writeArray(out, JSONArray(value))
            is String -> out.append(JSONObject.quote(value))
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Number -> out.append(JSONObject.numberToString(value))
            else -> out.append(JSONObject.quote(value.toString()))
        }
    }

    private fun writeObject(out: StringBuilder, value: JSONObject) {
        out.append('{')
        value.keys().asSequence().sorted().forEachIndexed { index, key ->
            if (index > 0) out.append(',')
            out.append(JSONObject.quote(key)).append(':')
            write(out, value.opt(key))
        }
        out.append('}')
    }

    private fun writeArray(out: StringBuilder, value: JSONArray) {
        out.append('[')
        for (index in 0 until value.length()) {
            if (index > 0) out.append(',')
            write(out, value.opt(index))
        }
        out.append(']')
    }
}
