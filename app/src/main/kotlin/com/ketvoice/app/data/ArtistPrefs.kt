package com.ketvoice.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Artists the listener blocked or keeps as favourites, chosen from their circle in the search.
 * A blocked artist is left out of what Ketvoice proposes — Home, the titles found by a search,
 * the titles added once the queue runs out, the personal mixes — wherever they are credited,
 * features included. What the listener chose to keep (playlists, downloads, the queue) is not touched.
 */
object ArtistPrefs {
    data class Entry(val name: String, val id: String = "", val photo: String? = null) {
        val key: String get() = MediaOverrides.normalize(name)
    }

    private var prefs: android.content.SharedPreferences? = null
    private val blockedState = mutableStateOf<Map<String, Entry>>(emptyMap())
    private val favouriteState = mutableStateOf<Map<String, Entry>>(emptyMap())

    val blocked: Map<String, Entry> get() = blockedState.value
    val favourites: List<Entry> get() = favouriteState.value.values.toList()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("artist-prefs", Context.MODE_PRIVATE).also {
            blockedState.value = decode(it.getString("blocked", null))
            favouriteState.value = decode(it.getString("favourites", null))
        }
    }

    fun isBlocked(name: String): Boolean = MediaOverrides.normalize(name) in blockedState.value
    fun isFavourite(name: String): Boolean = MediaOverrides.normalize(name) in favouriteState.value

    /** Blocking an artist takes it out of the favourites; making it a favourite unblocks it. */
    fun setBlocked(entry: Entry, on: Boolean) {
        blockedState.value = if (on) blockedState.value + (entry.key to entry) else blockedState.value - entry.key
        if (on) favouriteState.value = favouriteState.value - entry.key
        save()
    }

    fun setFavourite(entry: Entry, on: Boolean) {
        favouriteState.value = if (on) favouriteState.value + (entry.key to entry) else favouriteState.value - entry.key
        if (on) blockedState.value = blockedState.value - entry.key
        save()
    }

    /** True when no blocked artist is credited on [artist] (a title's artist line, features included). */
    fun allows(artist: String): Boolean = allows(artist, blockedState.value.keys)
    fun allows(track: Track): Boolean = allows(track.artist)

    internal fun allows(artist: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return true
        val parts = credited(artist)
        if (parts.any { it in blocked }) return false
        // A name that holds a separator ("Tyler, The Creator") is found again as consecutive parts of the line.
        val split = parts.drop(1)
        for (from in split.indices) for (until in from + 2..minOf(split.size, from + 3)) {
            if (split.subList(from, until).joinToString(" ") in blocked) return false
        }
        return true
    }

    /**
     * The whole artist line and every artist credited on it: "Maes, Ninho", "Werenoi feat. Damso",
     * "A & B", "A (avec B)". A name that holds "and" or "et" ("Simon and Garfunkel") stays whole.
     */
    internal fun credited(artist: String): List<String> =
        (listOf(artist) + artist.replace(Regex("[()\\[\\]]"), ",")
            .split(Regex("\\s*(?:,|&|/|;|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bavec\\b|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE)))
            .map { MediaOverrides.normalize(it) }.filter { it.isNotEmpty() }.distinct()

    private fun save() {
        prefs?.edit(commit = false) { putString("blocked", encode(blockedState.value.values)); putString("favourites", encode(favouriteState.value.values)) }
    }

    internal fun encode(entries: Collection<Entry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("name", it.name).put("id", it.id).put("photo", it.photo ?: "")) }
    }.toString()

    internal fun decode(text: String?): Map<String, Entry> = runCatching {
        val array = JSONArray(text ?: return emptyMap())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(name, o.optString("id"), o.optString("photo").takeIf { it.isNotBlank() })
        }.associateBy { it.key }
    }.getOrDefault(emptyMap())

    /** The account's sync: the merged blocked and favourite artists replace this device's. */
    internal fun replaceFromSync(blocked: List<Entry>, favourites: List<Entry>) {
        blockedState.value = blocked.associateBy { it.key }
        favouriteState.value = favourites.associateBy { it.key } - blockedState.value.keys
        save()
    }

    /** For tests and previews. */
    internal fun replaceForTest(blocked: List<Entry> = emptyList(), favourites: List<Entry> = emptyList()) {
        blockedState.value = blocked.associateBy { it.key }; favouriteState.value = favourites.associateBy { it.key }
    }
}
