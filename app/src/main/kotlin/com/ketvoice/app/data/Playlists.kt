package com.ketvoice.app.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PlaylistEntry(val id: String = UUID.randomUUID().toString(), val title: String,
    val artist: String, val spotifyId: String? = null, val track: Track? = null)
data class LocalPlaylist(val id: String = UUID.randomUUID().toString(), val name: String,
    val entries: List<PlaylistEntry> = emptyList(), val spotifyUrl: String? = null,
    val coverUrl: String? = null, val createdAt: Long = System.currentTimeMillis())

object PlaylistRules {
    fun name(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl)) { "Nom de playlist invalide" }
    }
    fun safe(track: Track): Track =
        // Les morceaux locaux ne vivent que par leur URI de flux (MediaStore ou
        // SAF) : le retirer rendait la lecture impossible depuis une playlist.
        if (track.id.startsWith("local:")) track
        else track.copy(streamUrl = null, downloadUrl = null)
    fun move(entries: List<PlaylistEntry>, from: Int, to: Int): List<PlaylistEntry> {
        if (from !in entries.indices || to !in entries.indices || from == to) return entries
        return entries.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun entry(track: Track) = PlaylistEntry(title = track.title, artist = track.artist, track = safe(track))
}

object PlaylistStore {
    private val _lists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val lists: StateFlow<List<LocalPlaylist>> = _lists
    private val _error = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _error
    private var file: AtomicFile? = null
    private val mutex = Mutex()
    private val gson = Gson()
    fun init(context: Context) {
        if (file != null) return
        val storage = AtomicFile(File(context.filesDir, "playlists-v1.json")); file = storage
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return
        runCatching {
            require(storage.baseFile.length() <= 16 * 1024 * 1024)
            val rows = gson.fromJson(String(storage.readFully(), Charsets.UTF_8), Array<LocalPlaylist>::class.java).toList()
            require(rows.size <= 100 && rows.all { it.id.isNotBlank() && it.entries.size <= 500 && it.name.isNotBlank() })
            _lists.value = rows
        }.onFailure { _error.value = true }
    }

    internal fun resetForTest() { _lists.value = emptyList(); _error.value = false }
    private suspend fun change(block: (List<LocalPlaylist>) -> List<LocalPlaylist>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!_error.value) { "Impossible de lire les playlists enregistrées" }
            val storage = file ?: error("Stockage des playlists indisponible")
            val next = block(_lists.value)
            require(next.size <= 100 && next.all { it.entries.size <= 500 }) { "Limite de playlists atteinte" }
            val bytes = gson.toJson(next).toByteArray(Charsets.UTF_8)
            require(bytes.size <= 16 * 1024 * 1024) { "Limite de playlists atteinte" }
            val out = storage.startWrite()
            try { out.write(bytes); storage.finishWrite(out) }
            catch (failure: Exception) { storage.failWrite(out); throw failure }
            _lists.value = next
        }
    }
    suspend fun create(name: String, entries: List<PlaylistEntry> = emptyList(), spotifyUrl: String? = null, cover: String? = null): String {
        val playlist = LocalPlaylist(name = PlaylistRules.name(name), entries = entries.map { it.copy(track = it.track?.let(PlaylistRules::safe)) },
            spotifyUrl = spotifyUrl, coverUrl = cover)
        change { listOf(playlist) + it }
        return playlist.id
    }
    suspend fun rename(id: String, value: String) { val name = PlaylistRules.name(value); change { rows -> rows.map { if (it.id == id) it.copy(name = name) else it } } }
    suspend fun delete(id: String) { change { rows -> rows.filterNot { it.id == id } } }
    suspend fun add(id: String, track: Track) { change { rows -> rows.map { if (it.id == id) it.copy(entries = it.entries + PlaylistRules.entry(track)) else it } } }
    suspend fun remove(id: String, entry: String) { change { rows -> rows.map { if (it.id == id) it.copy(entries = it.entries.filterNot { e -> e.id == entry }) else it } } }
    suspend fun move(id: String, from: Int, to: Int) { change { rows -> rows.map { if (it.id == id) it.copy(entries = PlaylistRules.move(it.entries, from, to)) else it } } }
    suspend fun setOrder(id: String, orderedIds: List<String>) {
        require(orderedIds.size <= 500)
        change { rows -> rows.map { row ->
            if (row.id != id) row else {
                val byId = row.entries.associateBy { it.id }
                val reordered = orderedIds.mapNotNull { byId[it] }
                val rest = row.entries.filter { it.id !in orderedIds.toSet() }
                row.copy(entries = reordered + rest)
            }
        } }
    }
    suspend fun resolve(id: String, entry: String, track: Track) { change { rows -> rows.map { if (it.id == id)
        it.copy(entries = it.entries.map { e -> if (e.id == entry) e.copy(track = PlaylistRules.safe(track)) else e }) else it } } }
}
