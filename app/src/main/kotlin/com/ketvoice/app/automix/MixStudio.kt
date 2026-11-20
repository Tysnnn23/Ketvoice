package com.ketvoice.app.automix

import android.content.Context
import org.json.JSONObject
import java.util.Base64
import kotlin.math.roundToLong

/*
 * Ketmix Studio (8.0, owner: « Le bouton de keyframe ne s'affiche pas et donc impossible de tester le volume », pick
 * « Ketmix Studio »): what the full-screen editor keeps beside the pair choices of [MixPointStore] — the undo/redo
 * stack, the keyframe magnet, the listener's own presets and the share code of a mix. Pure Kotlin, host-tested.
 */

/**
 * Undo/redo of the editor: the states before each change. Changes closer together than [coalesceMs] are one step,
 * so a slider dragged through twenty values is undone at once.
 */
internal class EditHistory<T>(private val limit: Int = 60, private val coalesceMs: Long = 600L,
                              private val clock: () -> Long = System::currentTimeMillis) {
    private val undos = ArrayDeque<T>()
    private val redos = ArrayDeque<T>()
    private var lastAt = Long.MIN_VALUE / 2
    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** [before] is about to be replaced. */
    fun record(before: T) {
        val now = clock()
        if (undos.isEmpty() || now - lastAt > coalesceMs) {
            undos.addLast(before)
            if (undos.size > limit) undos.removeFirst()
        }
        lastAt = now
        redos.clear()
    }

    /** The state to go back to from [current], or null. */
    fun undo(current: T): T? {
        val previous = undos.removeLastOrNull() ?: return null
        redos.addLast(current); lastAt = Long.MIN_VALUE / 2
        return previous
    }

    fun redo(current: T): T? {
        val next = redos.removeLastOrNull() ?: return null
        undos.addLast(current); lastAt = Long.MIN_VALUE / 2
        return next
    }

    fun clear() { undos.clear(); redos.clear(); lastAt = Long.MIN_VALUE / 2 }
}

/** Keyframes on the beat grid (the magnet of the Studio). */
internal object StudioSnap {
    /**
     * A keyframe [relMs] after the start of a blend that starts at [outMs] of the outgoing title: on that title's
     * phrase, bar or beat ([GridSnap.snap]) when it stays inside the blend, else where it is.
     */
    fun key(analysis: SegmentAnalysis?, outMs: Long, relMs: Long, overlapMs: Long, toleranceMs: Long): Long {
        val end = overlapMs.coerceAtLeast(0L)
        val at = relMs.coerceIn(0L, end)
        val snapped = GridSnap.snap(analysis, outMs + at, toleranceMs)
        val rel = snapped.ms - outMs
        return if (snapped.kind != GridSnap.Kind.None && rel in 0L..end) rel else at
    }
}

/**
 * A transition the listener saved under a name (« Mes préréglages »): its style, length and details, and its
 * keyframes as fractions of the blend ([keys] `atMs` in thousandths), so it fits pairs of any tempo.
 */
internal data class MixPreset(val id: String, val name: String, val style: MixStyle?, val overlapMs: Long, val shape: MixShape,
                              val keys: List<LevelKey>) {
    /** The keyframes for a blend of [overlapMs]. */
    fun keysFor(overlapMs: Long): List<LevelKey> = keys.map { it.copy(atMs = (it.atMs * overlapMs.coerceAtLeast(0L) / 1000.0).roundToLong()) }

    companion object {
        const val NAME_MAX = 40
        fun cleanName(name: String): String = name.filter { it >= ' ' }.trim().take(NAME_MAX)

        /** [choice] heard over a blend of [blendMs] (the plan's), saved as [name]. */
        fun of(id: String, name: String, choice: PairChoice, blendMs: Long): MixPreset = MixPreset(id, cleanName(name), choice.style,
            choice.overlapMs, choice.shape,
            choice.keys.map { it.copy(atMs = if (blendMs <= 0) 0 else (it.atMs * 1000.0 / blendMs).roundToLong().coerceIn(0L, 1000L)) })
    }
}

/**
 * The listener's presets, in their own preferences ([PREFS]); each value starts with its format version, so a
 * later format can be added beside this one. The Studio's magnet and its first-use hint are kept there too.
 */
internal object MixPresetStore {
    const val PREFS = "ketmix-presets-v1"
    const val LIMIT = 50
    private const val VERSION = "1"
    private const val MAGNET = "magnet"
    private const val HINT = "hint-keys"

    @Volatile private var backend: PointsBackend? = null
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0)
    val revision: kotlinx.coroutines.flow.StateFlow<Int> = _revision

    fun init(context: Context) {
        if (backend != null) return
        synchronized(this) {
            if (backend != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            backend = object : PointsBackend {
                override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
                override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
            }
        }
    }

    /** For tests on the host. */
    internal fun use(target: PointsBackend?) { backend = target; _revision.value++ }

    internal fun encode(p: MixPreset): String =
        "$VERSION\t${MixPreset.cleanName(p.name)}\t" + MixPointStore.encodePair(PairChoice(p.style, p.overlapMs, p.shape, p.keys))

    /** Null for a value this version cannot read. */
    internal fun decode(id: String, text: String): MixPreset? {
        val parts = text.split('\t')
        if (parts.size != 3 || parts[0] != VERSION || parts[1].isBlank()) return null
        // An empty choice (Ketmix's own) is a valid preset too: « Auto » with nothing changed.
        val choice = if (parts[2] == "-1,-1") PairChoice() else MixPointStore.decodePair(parts[2]) ?: return null
        if (choice.keys.any { it.atMs > 1000 }) return null
        return MixPreset(id, parts[1], choice.style, choice.overlapMs, choice.shape, choice.keys)
    }

    fun all(): List<MixPreset> = backend?.all().orEmpty().filterKeys { it.startsWith("u:") }
        .mapNotNull { (k, v) -> decode(k, v) }.sortedBy { it.id.removePrefix("u:").toLongOrNull() ?: 0L }

    /** Saves [preset]; a new one (blank id) gets the next id. Null when the list is full. */
    @Synchronized fun save(preset: MixPreset): MixPreset? {
        val target = backend ?: return null
        val present = all()
        val id = preset.id.ifBlank {
            if (present.size >= LIMIT) return null
            "u:" + ((present.maxOfOrNull { it.id.removePrefix("u:").toLongOrNull() ?: 0L } ?: 0L) + 1)
        }
        val kept = preset.copy(id = id, name = MixPreset.cleanName(preset.name).ifBlank { return null })
        target.put(id, encode(kept)); _revision.value++
        return kept
    }

    @Synchronized fun rename(id: String, name: String) { all().firstOrNull { it.id == id }?.let { save(it.copy(name = name)) } }
    @Synchronized fun delete(id: String) { backend?.put(id, null); _revision.value++ }

    var magnet: Boolean
        get() = backend?.all()?.get(MAGNET) != "0"
        set(on) { backend?.put(MAGNET, if (on) null else "0") }
    /** Whether the « + Point » hint was dismissed. */
    var hintSeen: Boolean
        get() = backend?.all()?.get(HINT) == "1"
        set(seen) { backend?.put(HINT, if (seen) "1" else null) }
}

/**
 * « Partager ce mix »: one pair's transition as a short text code another Ketvoice user pastes into « Importer un
 * mix ». The website has no page that receives a mix, so the code is plain text. It holds the catalogue ids, titles
 * and artists of both titles and the choice, nothing about the listener.
 */
internal object MixShare {
    const val PREFIX = "KM1."
    private val token = Regex("KM1\\.[A-Za-z0-9_-]{8,4000}")
    private const val TEXT_MAX = 200
    private const val POSITION_MAX = 3_600_000L

    data class End(val id: String, val title: String, val artist: String)
    data class Shared(val from: End, val to: End, val exitMs: Long, val entryMs: Long, val choice: PairChoice)

    fun encode(s: Shared): String {
        fun end(e: End) = JSONObject().put("id", e.id.take(TEXT_MAX)).put("t", e.title.take(TEXT_MAX)).put("a", e.artist.take(TEXT_MAX))
        val json = JSONObject().put("v", 1).put("from", end(s.from)).put("to", end(s.to))
            .put("exit", s.exitMs.coerceIn(-1L, POSITION_MAX)).put("entry", s.entryMs.coerceIn(-1L, POSITION_MAX))
            .put("mix", MixPointStore.encodePair(s.choice))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** The first code found in [text] (a whole shared message may be pasted), or null. */
    fun find(text: String): Shared? {
        val code = token.find(text)?.value ?: return null
        return runCatching {
            val json = JSONObject(String(Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)), Charsets.UTF_8))
            if (json.optInt("v") != 1) return null
            fun end(o: JSONObject?): End? {
                o ?: return null
                val id = o.optString("id"); if (id.isBlank() || id.length > TEXT_MAX) return null
                return End(id, o.optString("t").take(TEXT_MAX), o.optString("a").take(TEXT_MAX))
            }
            val from = end(json.optJSONObject("from")) ?: return null
            val to = end(json.optJSONObject("to")) ?: return null
            val exit = json.optLong("exit", -1L); val entry = json.optLong("entry", -1L)
            if (exit !in -1L..POSITION_MAX || entry !in -1L..POSITION_MAX) return null
            val mix = json.optString("mix")
            val choice = if (mix == "-1,-1") PairChoice() else MixPointStore.decodePair(mix) ?: return null
            Shared(from, to, exit, entry, choice)
        }.getOrNull()
    }
}
