package com.ketvoice.app.handoff

import com.ketvoice.app.data.Track
import org.json.JSONArray
import org.json.JSONObject

data class HandoffMessage(val sender: String, val queue: List<Track>, val index: Int,
                          val shuffle: Boolean, val repeatOne: Boolean, val autoplay: Boolean) {
    val current: Track get() = queue[index]
    fun json(): JSONObject = JSONObject().put("sender", sender).put("index", index)
        .put("shuffle", shuffle).put("repeatOne", repeatOne).put("autoplay", autoplay)
        .put("queue", JSONArray().apply { queue.forEach { put(encodeTrack(it)) } })

    companion object {
        const val MAX_BYTES = 65_536
        const val MAX_QUEUE = 50
        private val identity = Regex("(?:yt:[A-Za-z0-9_-]{11}|kv:upload:[0-9a-fA-F-]{36}|(?:kv:)?(?:um|last):[A-Za-z0-9_-]{1,100})")
        fun compatible(track: Track): Boolean = identity.matches(track.id)
        fun clean(text: String, max: Int = 200): String {
            require(text.length <= max)
            return text.filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }.trim()
        }
        private fun encodeTrack(track: Track): JSONObject {
            require(compatible(track))
            return JSONObject().put("id", track.id).put("title", clean(track.title)).put("artist", clean(track.artist))
                .put("album", clean(track.album)).put("durationMs", track.durationMs.coerceIn(0, 86_400_000))
                .put("explicit", track.explicit)
        }
        fun decode(json: JSONObject): HandoffMessage {

            require(json.keys().asSequence().all { it in setOf("sender", "index", "shuffle", "repeatOne", "autoplay", "queue") })
            val sender = clean(json.getString("sender"), 64).also { require(it.isNotBlank()) }
            val rows = json.getJSONArray("queue")
            require(rows.length() in 1..MAX_QUEUE)
            val tracks = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                require(row.keys().asSequence().all { it in setOf("id", "title", "artist", "album", "durationMs", "explicit") })
                val id = row.getString("id").also { require(identity.matches(it)) }
                val title = clean(row.getString("title")).also { require(it.isNotBlank()) }
                val artist = clean(row.getString("artist")).also { require(it.isNotBlank()) }
                val duration = row.getLong("durationMs").also { require(it in 0..86_400_000) }
                val upload = id.startsWith("kv:upload:")
                val tag = if (upload) "upload" else id.removePrefix("kv:").substringBefore(':').takeIf { it in setOf("um", "last") }
                Track(id, title, artist, clean(row.optString("album")), durationMs = duration,
                    explicit = row.optBoolean("explicit"), addon = tag, addonId = if (tag != null) id.substringAfterLast(':') else null)

            }
            val index = json.getInt("index").also { require(it in tracks.indices) }
            return HandoffMessage(sender, tracks, index, json.optBoolean("shuffle"), json.optBoolean("repeatOne"), json.optBoolean("autoplay", true))
        }
    }
}
