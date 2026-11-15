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

/**
 * How an album's hero artwork (cover or Canvas) is framed for everyone: the picture may
 * be mirrored and turned, and a rectangle of it is what the page shows.
 *
 * Lengths are in widths of the picture. [cx], [cy] place the centre of the rectangle
 * from the centre of the picture, as seen on screen once mirrored and turned; [w], [h]
 * are its size. [rotation] is in degrees, clockwise.
 */
data class Frame(val cx: Float = 0f, val cy: Float = 0f, val w: Float = 1f, val h: Float = 1f,
                 val rotation: Float = 0f, val flip: Boolean = false) {
    /** The same framing kept inside what the server accepts. */
    fun clamped(): Frame = Frame(cx.coerceIn(-2f, 2f), cy.coerceIn(-2f, 2f), w.coerceIn(MIN_SIDE, 3f), h.coerceIn(MIN_SIDE, 3f),
        (((rotation + 180f) % 360f + 360f) % 360f - 180f), flip)

    /** Width over height of what the page shows. */
    val aspect: Float get() = w / h

    companion object { const val MIN_SIDE = .05f }
}

/** A visual the verified owner chose for a catalogue title, album or artist. */
data class MediaOverride(
    val kind: String,
    val key: String,
    val label: String = "",
    val cover: String? = null,
    val canvas: String? = null,
    val hero: String? = null,
    val updatedAt: String = "",
    val frame: Frame? = null,
    /** The owner switched the Canvas off for this title or album. */
    val canvasOff: Boolean = false,
    /** How the music player alone frames this album's artwork; null = as the album page does. */
    val playerFrame: Frame? = null,
    /** The Canvas in the music player alone: true = shown, false = hidden, null = as the album page does. */
    val playerCanvas: Boolean? = null
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

    /** A Canvas chosen for the title, else for its album; none when the title's own override switched it off. */
    fun canvas(track: Track): String? {
        if (state.value.isEmpty()) return null
        val own = track(track)
        if (own?.canvas != null) return own.canvas
        if (own?.canvasOff == true) return null
        return get(ALBUM, albumKeyFor(track))?.canvas
    }

    /**
     * The owner switched this title's Canvas off, or its album's: nothing plays behind it, whatever
     * the catalogue offers. A Canvas chosen for the title itself wins over an album that is off.
     */
    fun canvasDisabled(track: Track): Boolean {
        if (state.value.isEmpty()) return false
        val own = track(track)
        if (own?.canvas != null) return false
        if (own?.canvasOff == true) return true
        val album = get(ALBUM, albumKeyFor(track))
        return album != null && album.canvas == null && album.canvasOff
    }

    /**
     * The music player's own say on the Canvas of this title, else of its album; without one
     * the player does what the album page does.
     */
    fun canvasHiddenInPlayer(track: Track): Boolean {
        if (state.value.isEmpty()) return false
        val choice = track(track)?.playerCanvas ?: get(ALBUM, albumKeyFor(track))?.playerCanvas
        return if (choice != null) !choice else canvasDisabled(track)
    }

    /** Nothing shows this title's Canvas, neither the album page nor the player: it is not even fetched. */
    fun canvasUnused(track: Track): Boolean = canvasDisabled(track) && canvasHiddenInPlayer(track)

    /** How the music player frames the artwork of this title: its own framing of the album, else the album page's. */
    fun playerFrame(track: Track): Frame? {
        if (state.value.isEmpty()) return null
        val album = get(ALBUM, albumKeyFor(track)) ?: return null
        return album.playerFrame ?: album.frame
    }

    fun albumCover(title: String, artist: String, fallback: String?): String? =
        if (state.value.isEmpty()) fallback else album(title, artist)?.cover ?: fallback

    /** Round portrait used in lists. */
    fun artistPhoto(name: String, fallback: String?): String? =
        if (state.value.isEmpty()) fallback else artist(name)?.let { it.cover ?: it.hero } ?: fallback

    /** How the owner framed this album's hero artwork, if they did. */
    fun albumFrame(title: String, artist: String): Frame? = if (state.value.isEmpty()) null else album(title, artist)?.frame

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
        // Older framings (a zoom and an offset) carry no rectangle and are left out.
        fun rectangle(f: JSONObject?): Frame? {
            f ?: return null
            val w = f.optDouble("w", Double.NaN).toFloat(); val h = f.optDouble("h", Double.NaN).toFloat()
            return if (w.isNaN() || h.isNaN() || w <= 0f || h <= 0f) null
            else Frame(f.optDouble("cx", 0.0).toFloat(), f.optDouble("cy", 0.0).toFloat(), w, h,
                f.optDouble("rotation", 0.0).toFloat(), f.optBoolean("flip", false)).clamped()
        }
        val player = row.optJSONObject("player")
        val playerCanvas = when (player?.optString("canvas")) { "on" -> true; "off" -> false; else -> null }
        val item = MediaOverride(kind, key, row.optString("label"), url("coverURL"), url("canvasURL"), url("heroURL"),
            row.optString("updatedAt"), rectangle(row.optJSONObject("frame")), row.optBoolean("canvasOff", false),
            rectangle(player?.optJSONObject("frame")), playerCanvas)
        return item.takeIf { it.cover != null || it.canvas != null || it.hero != null || it.frame != null || it.canvasOff ||
            it.playerFrame != null || it.playerCanvas != null }
    }

    internal fun encode(items: Collection<MediaOverride>): String = JSONObject().put("overrides", JSONArray().apply {
        items.forEach { o ->
            put(JSONObject().put("kind", o.kind).put("key", o.key).put("label", o.label)
                .put("coverURL", o.cover ?: JSONObject.NULL).put("canvasURL", o.canvas ?: JSONObject.NULL)
                .put("heroURL", o.hero ?: JSONObject.NULL).put("updatedAt", o.updatedAt)
                .put("canvasOff", o.canvasOff)
                .put("frame", o.frame?.let(::frameJson) ?: JSONObject.NULL)
                .put("player", if (o.playerFrame == null && o.playerCanvas == null) JSONObject.NULL else JSONObject()
                    .put("frame", o.playerFrame?.let(::frameJson) ?: JSONObject.NULL)
                    .put("canvas", o.playerCanvas?.let { if (it) "on" else "off" } ?: JSONObject.NULL)))
        }
    }).toString()

    internal fun frameJson(frame: Frame): JSONObject = frame.clamped().let {
        JSONObject().put("cx", it.cx.toDouble()).put("cy", it.cy.toDouble()).put("w", it.w.toDouble()).put("h", it.h.toDouble())
            .put("rotation", it.rotation.toDouble()).put("flip", it.flip)
    }

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
        if (before.values.map { Triple(it.canvas, it.canvasOff, it.playerCanvas) } != next.values.map { Triple(it.canvas, it.canvasOff, it.playerCanvas) }) {
            com.ketvoice.app.player.Player.state.value.current?.let { scope.launch { CanvasRepo.resolve(it) } }
        }
    }

    /** What the owner just saved, shown at once without waiting for the next refresh. */
    internal fun accept(kind: String, key: String, saved: MediaOverride?) {
        val id = "$kind:$key"
        set(if (saved == null) state.value - id else state.value + (id to saved))
    }

    /** What the owner just saved for several items: the ones the server no longer lists have no override left. */
    internal fun acceptMany(kind: String, keys: List<String>, saved: List<MediaOverride>) {
        val kept = saved.filter { it.kind == kind }.associateBy { it.id }
        set(state.value - keys.map { "$kind:$it" }.toSet() + kept)
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
    val sample: Track? = null,
    /** The titles of an album, to choose among when changing their Canvas. */
    val tracks: List<Track> = emptyList()
) {
    companion object {
        fun track(track: Track, cover: String? = track.coverUrl): VisualTarget? = MediaOverrides.keyFor(track)?.let {
            VisualTarget(MediaOverrides.TRACK, it, track.title, track.artist, cover, track)
        }
        fun album(title: String, artist: String, cover: String?, sample: Track? = null, tracks: List<Track> = emptyList()): VisualTarget? =
            MediaOverrides.albumKey(title, artist)?.let { VisualTarget(MediaOverrides.ALBUM, it, title, artist, cover, sample, tracks) }
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
