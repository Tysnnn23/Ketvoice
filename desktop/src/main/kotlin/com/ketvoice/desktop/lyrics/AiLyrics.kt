package com.ketvoice.desktop.lyrics

import com.ketvoice.app.data.CustomLyrics
import com.ketvoice.app.data.LyricsDraft
import com.ketvoice.app.data.LyricsExport
import com.ketvoice.app.data.MusicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Lyrics timed word by word by the AI, kept in the lyrics cache (`lyrics-v2/ai`) as enhanced LRC, one file per title,
 * with an index: which lines they were made from ([basis]: a digest of those lines' times and words, so lyrics that
 * changed are aligned again), and whether the listener went back to the original timing (« Revenir à la synchro
 * d'origine »). An attempt that found the text was not what is sung is remembered, so it is not run again.
 */
object AiLyrics {
    const val LABEL = "Synchronisé par IA"
    private var dir: File? = null
    private val index = HashMap<String, Entry>()
    private val _changes = MutableStateFlow(0)
    /** Bumped at every change, for the lyrics view and the editor. */
    val changes: StateFlow<Int> = _changes

    data class Entry(val basis: String, val file: String?, val reverted: Boolean)

    @Synchronized fun init(filesDir: File) {
        val d = File(filesDir, "lyrics-v2/ai").apply { mkdirs() }
        dir = d
        index.clear()
        runCatching {
            val o = JSONObject(File(d, "index.json").readText())
            for (id in o.keys()) o.getJSONObject(id).let { e ->
                index[id] = Entry(e.getString("basis"), e.optString("file").ifBlank { null }, e.optBoolean("reverted"))
            }
        }
    }

    /** The lines' times and words, digested: what an AI timing was made from. */
    fun basis(lines: List<MusicApi.LyricLine>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (l in lines) md.update("${l.timeMs}\u0001${l.text}\n".toByteArray(Charsets.UTF_8))
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun entry(trackId: String): Entry? = index[trackId]

    /** Whether [trackId]'s lyrics made from [basis] were already tried (found, failed, or set aside). */
    @Synchronized fun tried(trackId: String, basis: String): Boolean = index[trackId]?.basis == basis

    /** The AI timing of [trackId] made from [basis], unless the listener went back to the original. */
    fun lines(trackId: String, basis: String): List<MusicApi.LyricLine>? {
        val e = entry(trackId)?.takeIf { it.basis == basis && !it.reverted } ?: return null
        val f = File(dir ?: return null, e.file ?: return null).takeIf { it.isFile } ?: return null
        return runCatching { CustomLyrics.parse(f.readText()).second }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Keeps the AI timing of [trackId] (enhanced LRC). */
    fun save(trackId: String, basis: String, lines: List<MusicApi.LyricLine>, title: String = "", artist: String = "") {
        val d = dir ?: return
        val name = MessageDigest.getInstance("SHA-1").digest(trackId.toByteArray()).take(10).joinToString("") { "%02x".format(it) } + ".lrc"
        val text = LyricsExport.enhanced(LyricsDraft.fromLines(lines), title, artist)
        val tmp = File(d, "$name.part")
        tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), File(d, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        put(trackId, Entry(basis, name, reverted = false))
    }

    /** The lyrics of [trackId] made from [basis] could not be timed: not tried again. */
    fun failed(trackId: String, basis: String) = put(trackId, Entry(basis, null, reverted = false))

    /** « Revenir à la synchro d'origine » ([back] false), or the AI timing again. */
    fun revert(trackId: String, back: Boolean = true) {
        val e = entry(trackId) ?: return
        put(trackId, e.copy(reverted = back))
    }

    @Synchronized private fun put(trackId: String, e: Entry) {
        index[trackId] = e
        val d = dir ?: return
        val o = JSONObject()
        index.forEach { (id, x) -> o.put(id, JSONObject().put("basis", x.basis).put("file", x.file ?: "").put("reverted", x.reverted)) }
        runCatching {
            val tmp = File(d, "index.json.part"); tmp.writeText(o.toString())
            java.nio.file.Files.move(tmp.toPath(), File(d, "index.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        _changes.value++
    }

    /** Tests. */
    @Synchronized internal fun resetForTest() { index.clear(); dir = null }
}
