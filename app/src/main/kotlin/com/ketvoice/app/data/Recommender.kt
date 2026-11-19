package com.ketvoice.app.data

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Titles the listener left early (7.2): a manual « next » in the first 30 seconds and the first third of a
 * title. Kept on the device only (never synced, never sent), at most [MAX] titles, forgotten after 60 days.
 */
object TasteSignals {
    data class Skip(val count: Int, val at: Long, val artist: String)

    private const val MAX = 400
    private const val KEEP_MS = 60L * 24 * 3600 * 1000
    private const val EARLY_MS = 30_000L
    @Volatile private var skips: Map<String, Skip> = emptyMap()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("ketvoice-taste", Context.MODE_PRIVATE)
        skips = decode(prefs?.getString("skips", null), System.currentTimeMillis())
    }

    fun skips(): Map<String, Skip> = skips

    /** An early skip of [track] at [positionMs]; anything later is a title heard, not refused. */
    fun skipped(track: Track?, positionMs: Long, now: Long = System.currentTimeMillis()) {
        if (track == null || !isEarly(positionMs, track.durationMs)) return
        synchronized(this) {
            skips = withSkip(skips, track, now)
            prefs?.edit()?.putString("skips", encode(skips))?.apply()
        }
    }

    internal fun isEarly(positionMs: Long, durationMs: Long): Boolean =
        positionMs in 0 until EARLY_MS && (durationMs <= 0 || positionMs < durationMs / 3)

    internal fun withSkip(previous: Map<String, Skip>, track: Track, now: Long): Map<String, Skip> {
        val old = previous[track.id]
        val next = LinkedHashMap(previous.filterKeys { it != track.id })
        next[track.id] = Skip((old?.count ?: 0) + 1, now, SearchRank.mainArtist(track.artist))
        return if (next.size <= MAX) next else next.entries.sortedByDescending { it.value.at }.take(MAX).associate { it.key to it.value }
    }

    internal fun encode(map: Map<String, Skip>): String = JSONObject().apply {
        map.forEach { (id, s) -> put(id, JSONObject().put("n", s.count).put("at", s.at).put("a", s.artist)) }
    }.toString()

    internal fun decode(text: String?, now: Long): Map<String, Skip> = runCatching {
        val json = JSONObject(text ?: return emptyMap())
        json.keys().asSequence().mapNotNull { id ->
            val o = json.optJSONObject(id) ?: return@mapNotNull null
            Skip(o.optInt("n"), o.optLong("at"), o.optString("a")).takeIf { it.count > 0 && now - it.at < KEEP_MS }?.let { id to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    internal fun useForTest(map: Map<String, Skip>) { skips = map }
}

/**
 * « Pour toi » (7.2, owner: « recommandations personnalisées »): one simple, explainable score per title,
 * from what the listener did. Each reason is kept with its points ([Scored.why]) so a row can say why a
 * title is there and a test can check each rule:
 *  - artist affinity: plays of the artist, a liked title counting as three, early skips counting against;
 *  - the title itself: liked, or played often (familiar), or refused (each early skip costs);
 *  - recency: a title heard among the last ones waits, so the row does not repeat the last hour;
 *  - blocked artists never appear; diversity: each further title of the same artist counts for less.
 */
object Recommender {
    data class Signals(
        val plays: Map<String, Int> = emptyMap(),
        val liked: Set<String> = emptySet(),
        val skips: Map<String, TasteSignals.Skip> = emptyMap(),
        /** Most recent first: the ids of the last titles heard. */
        val recent: List<String> = emptyList(),
        /** Normalised names ([MediaOverrides.normalize]) of the blocked artists. */
        val blocked: Set<String> = emptySet(),
        /** Titles the listener knows, to learn which artists they like. */
        val known: List<Track> = emptyList(),
    )

    data class Scored(val track: Track, val score: Double, val why: List<Pair<String, Double>>)

    private fun artistOf(track: Track) = SearchRank.mainArtist(track.artist)

    /** Artist → affinity between -1 and 1 (log of plays, liked titles count three, early skips two against). */
    fun affinity(signals: Signals): Map<String, Double> {
        val raw = HashMap<String, Double>()
        val seen = HashSet<String>()
        (signals.known).forEach { t ->
            if (!seen.add(t.id)) return@forEach
            val a = artistOf(t).ifEmpty { return@forEach }
            val v = (signals.plays[t.id] ?: 0) + if (t.id in signals.liked) 3 else 0
            raw.merge(a, v.toDouble(), Double::plus)
        }
        signals.skips.values.forEach { s -> if (s.artist.isNotEmpty()) raw.merge(s.artist, -2.0 * s.count, Double::plus) }
        val top = raw.values.maxOrNull()?.takeIf { it > 0 } ?: return raw.mapValues { (_, v) -> if (v < 0) -1.0 else 0.0 }
        return raw.mapValues { (_, v) ->
            if (v >= 0) ln(1 + v) / ln(1 + top) else -min(1.0, ln(1 - v) / ln(1 + top.coerceAtLeast(2.0)))
        }
    }

    fun score(track: Track, signals: Signals, affinity: Map<String, Double> = affinity(signals)): Scored {
        val why = ArrayList<Pair<String, Double>>()
        affinity[artistOf(track)]?.takeIf { it != 0.0 }?.let { why += "artist" to it * 40 }
        if (track.id in signals.liked) why += "liked" to 12.0
        (signals.plays[track.id] ?: 0).takeIf { it > 0 }?.let { why += "familiar" to min(8.0, 2.5 * ln(1.0 + it)) }
        signals.skips[track.id]?.let { why += "skipped" to -min(45.0, 15.0 * it.count) }
        val at = signals.recent.indexOf(track.id)
        if (at in 0 until 10) why += "heard-now" to -30.0 else if (at in 10 until 40) why += "heard-recently" to -10.0
        return Scored(track, why.sumOf { it.second }, why)
    }

    /**
     * The best [limit] titles among [candidates]: blocked artists and duplicates left out, then picked one by
     * one, each further title of an artist losing a third of its score per title already picked of them,
     * and at most [perArtist] per artist.
     */
    fun rank(candidates: List<Track>, signals: Signals, limit: Int = 25, perArtist: Int = 3): List<Scored> {
        val aff = affinity(signals)
        val seen = HashSet<String>()
        val pool = candidates.filter { t ->
            ArtistPrefs.allows(t.artist, signals.blocked) &&
                seen.add(t.id) && seen.add("r:" + SearchRank.fold(t.title) + "|" + artistOf(t))
        }.map { score(it, signals, aff) }.toMutableList()
        val picked = ArrayList<Scored>()
        val perArtistCount = HashMap<String, Int>()
        while (picked.size < limit && pool.isNotEmpty()) {
            var bestIndex = -1
            var bestValue = Double.NEGATIVE_INFINITY
            pool.forEachIndexed { i, s ->
                val n = perArtistCount[artistOf(s.track)] ?: 0
                if (n >= perArtist) return@forEachIndexed
                val v = s.score - n * max(10.0, kotlin.math.abs(s.score) / 3)
                if (v > bestValue) { bestValue = v; bestIndex = i }
            }
            if (bestIndex < 0) break
            val s = pool.removeAt(bestIndex)
            perArtistCount.merge(artistOf(s.track), 1, Int::plus)
            picked += s
        }
        return picked
    }

    /** What the app knows of the listener now. */
    fun liveSignals(): Signals = Signals(
        plays = ListeningHistory.playCounts.value,
        liked = AppSettings.favoriteIds.value,
        skips = TasteSignals.skips(),
        recent = ListeningHistory.tracks.value.map { it.id },
        blocked = ArtistPrefs.blocked.keys,
        known = ListeningHistory.knownTracks.value,
    )

    /**
     * For the UI: [candidates] (a radio, the catalogue's titles, the week's discoveries) in the listener's
     * order; with no candidates, the titles they know (Home « Pour toi »). Explainable through [score].
     */
    fun forYou(candidates: List<Track> = emptyList(), limit: Int = 25): List<Track> {
        val signals = liveSignals()
        return rank(candidates.ifEmpty { signals.known }, signals, limit).map { it.track }
    }
}
