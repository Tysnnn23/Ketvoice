package com.ketvoice.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Separate from the audio LRU: never cleared by the song-cache action. */
object LyricsDiskCache {
    private var dir: File? = null
    fun init(context: Context) { dir = File(context.filesDir, "lyrics-v2").apply { mkdirs() } }
    private fun file(key: String): File? = dir?.let {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        File(it, "$hash.json")
    }
    @Synchronized
    fun read(key: String): Pair<String, List<MusicApi.LyricLine>>? = runCatching {
        val f = file(key)?.takeIf { it.exists() } ?: return null
        val o = JSONObject(f.readText())
        val a = o.getJSONArray("lines")
        o.getString("source") to (0 until a.length()).map { i ->
            val l = a.getJSONObject(i)
            val w = l.getJSONArray("words")
            val translations = l.optJSONObject("translations")
            val native = translations?.keys()?.asSequence()?.associateWith { translations.getString(it) }.orEmpty()
            MusicApi.LyricLine(l.getLong("at"), l.getString("text"), (0 until w.length()).map { j ->
                val word = w.getJSONObject(j)
                MusicApi.LyricWord(word.getLong("at"), word.getString("text"), word.optLong("end", -1))
            }, l.optBoolean("syllable"), native)
        }
    }.getOrNull()
    @Synchronized
    fun write(key: String, result: Pair<String, List<MusicApi.LyricLine>>) {
        if (result.second.isEmpty()) return
        runCatching {
            val f = file(key) ?: return
            val o = JSONObject().put("source", result.first).put("lines", JSONArray().apply {
                result.second.forEach { line -> put(JSONObject().put("at", line.timeMs).put("text", line.text)
                    .put("syllable", line.syllableTimed).put("translations", JSONObject(line.translations)).put("words", JSONArray().apply {
                        line.words.forEach { w -> put(JSONObject().put("at", w.startMs).put("text", w.text).put("end", w.endMs)) }
                    })) }
            })
            val tmp = File(f.path + ".part")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }
}
