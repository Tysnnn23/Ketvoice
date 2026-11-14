package com.ketvoice.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** A visual the verified owner chose for a catalogue title, album or artist. */
data class MediaOverride(
    val kind: String,
    val key: String,
    val label: String = "",
    val cover: String? = null,
    val canvas: String? = null,
    val hero: String? = null,
    val updatedAt: String = ""
) {
    val id: String get() = "$kind:$key"
}

/**
 * Owner-curated covers, Canvas and artist photos laid over the catalogue's own.
 * The catalogue is never changed: an override is looked up by a normalised key
 * (title and main artist, album and main artist, or artist name), so it follows the
 * same title from any source, and restoring the original just forgets it.
 *
 * The map is Compose snapshot state: a screen that reads a cover through this object
 * redraws as soon as an override arrives or is restored.
 */
object MediaOverrides {
    const val TRACK = "track"
    const val ALBUM = "album"
    const val ARTIST = "artist"
    private const val PREFS = "ketvoice_overrides"
    private const val REFRESH_MS = 15 * 60_000L

    private val state = mutableStateOf<Map<String, MediaOverride>>(emptyMap())
    val all: Map<String, MediaOverride> get() = state.value
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var context: Context? = null

    // ---- keys -------------------------------------------------------------------------

    /** Lower case, accents and punctuation removed, single spaces: what the server accepts. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .take(72)
            .trim()

    private val featuring = Regex("\\s*(?:,|;| / | & | x | feat\\.? | ft\\.? | featuring | with | avec )\\s*", RegexOption.IGNORE_CASE)

    /** "Daft Punk, Pharrell Williams" and "Daft Punk feat. Pharrell" both lead to "Daft Punk". */
    fun mainArtist(artist: String): String = artist.split(featuring).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    fun trackKey(title: String, artist: String): String? = pair(title, mainArtist(artist))
    fun albumKey(album: String, artist: String): String? = pair(album, mainArtist(artist))
    fun artistKey(name: String): String? = normalize(name).takeIf { it.isNotEmpty() }

    private fun pair(first: String, second: String): String? {
        val a = normalize(first); val b = normalize(second)
        return if (a.isEmpty() || b.isEmpty()) null else "$a|$b"
    }

    fun keyFor(track: Track): String? = trackKey(track.title, track.artist)
    fun albumKeyFor(track: Track): String? = track.album.takeIf { it.isNotBlank() }?.let { albumKey(it, track.artist) }

    // ---- lookups ----------------------------------------------------------------------

    fun get(kind: String, key: String?): MediaOverride? = key?.let { state.value["$kind:$it"] }
    fun track(track: Track): MediaOverride? = get(TRACK, keyFor(track))
    fun album(title: String, artist: String): MediaOverride? = get(ALBUM, albumKey(title, artist))
    fun artist(name: String): MediaOverride? = get(ARTIST, artistKey(name))

    /** The title's own cover, else its album's, else what the catalogue gave. */
    fun cover(track: Track, fallback: String?): String? {
        if (state.value.isEmpty()) return fallback
        return track(track)?.cover ?: get(ALBUM, albumKeyFor(track))?.cover ?: fallback
    }

    /** A Canvas chosen for the title, else for its album. */
    fun canvas(track: Track): String? {
        if (state.value.isEmpty()) return null
        return track(track)?.canvas ?: get(ALBUM, albumKeyFor(track))?.canvas
    }

    fun albumCover(title: String, artist: String, fallback: String?): String? =
        if (state.value.isEmpty()) fallback else album(title, artist)?.cover ?: fallback

    /** Round portrait used in lists. */
    fun artistPhoto(name: String, fallback: String?): String? =
        if (state.value.isEmpty()) fallback else artist(name)?.let { it.cover ?: it.hero } ?: fallback

    /** Wide photo at the top of the artist page. */
    fun artistHero(name: String): String? = artist(name)?.let { it.hero ?: it.cover }

    // ---- loading ----------------------------------------------------------------------

    internal fun decode(text: String): Map<String, MediaOverride> {
        val rows = runCatching { JSONObject(text).optJSONArray("overrides") }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, MediaOverride>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            decodeRow(row)?.let { result[it.id] = it }
        }
        return result
    }

    internal fun decodeRow(row: JSONObject): MediaOverride? {
        fun url(name: String) = row.optString(name).takeIf { !row.isNull(name) && it.startsWith("https://") }
        val kind = row.optString("kind").takeIf { it in setOf(TRACK, ALBUM, ARTIST) } ?: return null
        val key = row.optString("key").takeIf { it.isNotBlank() && it.length <= 160 } ?: return null
        val item = MediaOverride(kind, key, row.optString("label"), url("coverURL"), url("canvasURL"), url("heroURL"),
            row.optString("updatedAt"))
        return item.takeIf { it.cover != null || it.canvas != null || it.hero != null }
    }

    internal fun encode(items: Collection<MediaOverride>): String = JSONObject().put("overrides", JSONArray().apply {
        items.forEach { o ->
            put(JSONObject().put("kind", o.kind).put("key", o.key).put("label", o.label)
                .put("coverURL", o.cover ?: JSONObject.NULL).put("canvasURL", o.canvas ?: JSONObject.NULL)
                .put("heroURL", o.hero ?: JSONObject.NULL).put("updatedAt", o.updatedAt))
        }
    }).toString()

    fun init(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        val cached = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null)
        if (cached != null) state.value = decode(cached)
        scope.launch {
            while (true) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    suspend fun refresh() {
        val text = PublicationApi.overrides() ?: return
        val next = decode(text)
        if (next == state.value) return
        set(next)
    }

    private fun set(next: Map<String, MediaOverride>) {
        val before = state.value
        state.value = next
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("json", encode(next.values))?.apply()
        if (before.values.map { it.canvas } != next.values.map { it.canvas }) {
            com.ketvoice.app.player.Player.state.value.current?.let { scope.launch { CanvasRepo.resolve(it) } }
        }
    }

    /** What the owner just saved, shown at once without waiting for the next refresh. */
    internal fun accept(kind: String, key: String, saved: MediaOverride?) {
        val id = "$kind:$key"
        set(if (saved == null) state.value - id else state.value + (id to saved))
    }

    /** For tests and previews. */
    internal fun replaceForTest(items: Collection<MediaOverride>) {
        state.value = items.associateBy { it.id }
    }
}

/**
 * A catalogue item whose visuals the owner can change: what is shown in the Studio
 * (name, current cover) and the key the override is saved under. [sample] is a title
 * of the item, used to preview a Canvas with the real player.
 */
data class VisualTarget(
    val kind: String,
    val key: String,
    val title: String,
    val subtitle: String,
    val cover: String?,
    val sample: Track? = null
) {
    companion object {
        fun track(track: Track, cover: String? = track.coverUrl): VisualTarget? = MediaOverrides.keyFor(track)?.let {
            VisualTarget(MediaOverrides.TRACK, it, track.title, track.artist, cover, track)
        }
        fun album(title: String, artist: String, cover: String?, sample: Track? = null): VisualTarget? =
            MediaOverrides.albumKey(title, artist)?.let { VisualTarget(MediaOverrides.ALBUM, it, title, artist, cover, sample) }
        fun artist(name: String, photo: String?): VisualTarget? =
            MediaOverrides.artistKey(name)?.let { VisualTarget(MediaOverrides.ARTIST, it, name, "", photo) }
        /** An override already saved, reopened from the Studio list. */
        fun saved(item: MediaOverride): VisualTarget {
            val parts = item.label.split(" — ", limit = 2)
            return VisualTarget(item.kind, item.key, parts.first().ifBlank { item.key }, parts.getOrElse(1) { "" }, null)
        }
    }
    /** What the server keeps to name the item in the Studio list. */
    val label: String get() = if (subtitle.isBlank()) title else "$title — $subtitle"
}
