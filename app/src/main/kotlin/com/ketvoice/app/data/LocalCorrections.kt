package com.ketvoice.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * « Musique locale pro » (7.0): corrections of the listener's tags found in the catalogue, kept in the app.
 *
 * A file is searched once ([findAll], with the search the app already uses); a match becomes a [LocalMatch.Proposal]
 * the listener accepts per album or all at once, or dismisses. Accepted changes are laid over the file's tags in
 * the app only (the `local-music-corrections` preferences); the file itself changes only through « Écrire dans le
 * fichier » ([LocalFileWriter]). A sure match is also remembered as « Aussi disponible en streaming », and lends
 * its cover to a file that has none.
 */
internal object LocalCorrections {
    private const val PREFS = "local-music-corrections"
    private const val MAX_PER_PASS = 150
    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    private val _accepted = MutableStateFlow<Map<String, Map<LocalMatch.Field, String>>>(emptyMap())
    val accepted: StateFlow<Map<String, Map<LocalMatch.Field, String>>> = _accepted
    private val _proposals = MutableStateFlow<List<LocalMatch.Proposal>>(emptyList())
    val proposals: StateFlow<List<LocalMatch.Proposal>> = _proposals
    private val _streaming = MutableStateFlow<Map<String, Track>>(emptyMap())
    /** Local ids whose recording the catalogue surely has too. */
    val streaming: StateFlow<Map<String, Track>> = _streaming
    data class Progress(val done: Int, val total: Int)
    private val _searching = MutableStateFlow<Progress?>(null)
    val searching: StateFlow<Progress?> = _searching

    @Synchronized fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        runCatching { _accepted.value = decodeAccepted(p.getString("accepted", null)) }
        runCatching { _proposals.value = gson.fromJson(p.getString("proposals", "[]"), Array<StoredProposal>::class.java).mapNotNull { it.toProposal() } }
        runCatching { _streaming.value = gson.fromJson<Map<String, Track>>(p.getString("streaming", "{}"), object : TypeToken<Map<String, Track>>() {}.type).orEmpty() }
    }

    @Synchronized internal fun resetForTest() {
        prefs = null; _accepted.value = emptyMap(); _proposals.value = emptyList(); _streaming.value = emptyMap(); _searching.value = null
    }

    private data class StoredProposal(val localId: String? = null, val catalog: Track? = null, val score: Double = 0.0, val changes: Map<String, String>? = null) {
        fun toProposal(): LocalMatch.Proposal? {
            val fields = changes.orEmpty().mapNotNull { (k, v) -> runCatching { LocalMatch.Field.valueOf(k) }.getOrNull()?.let { it to v } }.toMap()
            return if (localId == null || catalog == null || fields.isEmpty()) null else LocalMatch.Proposal(localId, catalog, score, fields)
        }
    }

    private fun decodeAccepted(text: String?): Map<String, Map<LocalMatch.Field, String>> {
        val raw: Map<String, Map<String, String>> = gson.fromJson(text ?: return emptyMap(),
            object : TypeToken<Map<String, Map<String, String>>>() {}.type) ?: return emptyMap()
        return raw.mapValues { (_, m) -> m.mapNotNull { (k, v) -> runCatching { LocalMatch.Field.valueOf(k) }.getOrNull()?.let { it to v } }.toMap() }
    }

    private fun save() {
        val p = prefs ?: return
        p.edit().putString("accepted", gson.toJson(_accepted.value.mapValues { (_, m) -> m.mapKeys { it.key.name } }))
            .putString("proposals", gson.toJson(_proposals.value.map { StoredProposal(it.localId, PlaylistRules.safe(it.catalog), it.score, it.changes.mapKeys { e -> e.key.name }) }))
            .putString("streaming", gson.toJson(_streaming.value.mapValues { PlaylistRules.safe(it.value) }))
            .putStringSet("checked", checked)
            .apply()
    }

    private val checked: MutableSet<String> get() = prefs?.getStringSet("checked", emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun isChecked(id: String) = prefs?.getStringSet("checked", emptySet())?.contains(id) == true

    // ------------------------------------------------------------------ what the library shows

    /** [tags] with the accepted corrections of [id] laid over them. */
    fun corrected(id: String, tags: LocalTags): LocalTags = _accepted.value[id]?.let { LocalMatch.apply(tags, it) } ?: tags

    /** The catalogue's cover for a file that has none, from a sure match. */
    fun borrowedCover(id: String): String? = _streaming.value[id]?.coverUrl?.takeIf { it.startsWith("https://") }

    // ------------------------------------------------------------------ the listener's answers

    @Synchronized fun accept(ids: Collection<String>): List<LocalMatch.Proposal> {
        val chosen = _proposals.value.filter { it.localId in ids }
        if (chosen.isEmpty()) return emptyList()
        _accepted.value = _accepted.value + chosen.associate { p -> p.localId to ((_accepted.value[p.localId] ?: emptyMap()) + p.changes) }
        _proposals.value = _proposals.value.filterNot { it.localId in ids }
        save()
        return chosen
    }

    @Synchronized fun dismiss(ids: Collection<String>) {
        _proposals.value = _proposals.value.filterNot { it.localId in ids }
        save()
    }

    /** Back to the file's own tags for [id]. */
    @Synchronized fun undo(id: String) { _accepted.value = _accepted.value - id; save() }

    // ------------------------------------------------------------------ the search

    /**
     * Searches the catalogue for files not searched yet (the least tagged first, at most 150 a pass) and keeps what
     * it proposes. [search] is the app's catalogue search; [pauseMs] keeps the requests gentle.
     */
    suspend fun findAll(files: List<Pair<String, LocalTags>>, search: suspend (String) -> List<Track>, pauseMs: Long = 400L): Int {
        if (_searching.value != null) return 0
        val todo = files.filter { !isChecked(it.first) }.sortedByDescending { missing(it.second) }.take(MAX_PER_PASS)
        if (todo.isEmpty()) return 0
        var found = 0
        _searching.value = Progress(0, todo.size)
        try {
            todo.forEachIndexed { i, (id, tags) ->
                if (findOne(id, tags, search)) found++
                _searching.value = Progress(i + 1, todo.size)
                if (pauseMs > 0) delay(pauseMs)
            }
        } finally { _searching.value = null; save() }
        return found
    }

    private fun missing(t: LocalTags): Int = listOf(t.artist, t.album, t.albumArtist).count { it == null }

    private suspend fun findOne(id: String, tags: LocalTags, search: suspend (String) -> List<Track>): Boolean {
        val query = LocalMatch.query(tags).takeIf { it.length >= 2 } ?: return false
        val results = runCatching { search(query) }.getOrNull() ?: return false  // a failed search is tried again later
        synchronized(this) { prefs?.edit()?.putStringSet("checked", checked + id)?.apply() }
        val (best, score) = LocalMatch.best(tags, results) ?: return false
        if (score >= LocalMatch.SURE_FROM) synchronized(this) { _streaming.value = _streaming.value + (id to best) }
        val proposal = LocalMatch.proposal(id, corrected(id, tags), best, score) ?: return false
        synchronized(this) { _proposals.value = _proposals.value.filterNot { it.localId == id } + proposal }
        return true
    }

    /** Search everything again (after the listener changed their files, or wants another try). */
    @Synchronized fun forgetSearches() { prefs?.edit()?.remove("checked")?.apply() }
}
