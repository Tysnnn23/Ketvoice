package com.ketvoice.app.data

import android.content.Context
import org.json.JSONObject

/**
 * What the Home page learns: which shelves the listener opens. Each opening adds one
 * point; points fade by a tenth per day, so a habit counts and an old one is forgotten.
 */
object HomeLearning {
    private const val DAILY_FADE = .9
    private var prefs: android.content.SharedPreferences? = null
    private val state = androidx.compose.runtime.mutableStateOf<Map<String, Double>>(emptyMap())

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("home-learning", Context.MODE_PRIVATE)
        state.value = decode(prefs?.getString("scores", null), today())
    }

    /** Current scores, already faded to today. */
    val scores: Map<String, Double> get() = state.value

    fun record(key: String) {
        val next = opened(state.value, key)
        state.value = next
        prefs?.edit()?.putString("scores", encode(next, today()))?.apply()
    }

    internal fun today(): Long = System.currentTimeMillis() / 86_400_000L

    internal fun opened(scores: Map<String, Double>, key: String): Map<String, Double> =
        scores + (key to ((scores[key] ?: 0.0) + 1.0).coerceAtMost(50.0))

    internal fun fade(score: Double, days: Long): Double = score * Math.pow(DAILY_FADE, days.coerceAtLeast(0).toDouble())

    internal fun encode(scores: Map<String, Double>, day: Long): String =
        JSONObject().put("day", day).put("scores", JSONObject(scores)).toString()

    internal fun decode(text: String?, today: Long): Map<String, Double> = runCatching {
        val root = JSONObject(text ?: return emptyMap())
        val days = today - root.optLong("day", today)
        val rows = root.optJSONObject("scores") ?: return emptyMap()
        rows.keys().asSequence().associateWith { fade(rows.optDouble(it, 0.0).coerceIn(0.0, 50.0), days) }
            .filterValues { it >= .05 }
    }.getOrDefault(emptyMap())
}

/** The latest albums and singles of the artists the listener plays most, kept a few hours. */
object HomeReleases {
    private const val TTL_MS = 6 * 3_600_000L
    private var cache: Pair<Long, List<CatalogAlbum>>? = null
    private var cacheKey: List<String> = emptyList()

    suspend fun load(known: List<Track>, counts: Map<String, Int>, favorites: Set<String>,
                     nowMs: Long = System.currentTimeMillis()): List<CatalogAlbum> {
        val artists = PersonalMixes.topArtists(known, counts, favorites).filter { it.plays >= 2 }.take(6)
        val key = artists.map { it.name.lowercase() }
        cache?.takeIf { nowMs - it.first in 0..TTL_MS && key == cacheKey }?.let { return it.second }
        val found = artists.flatMap { artist ->
            runCatching { CatalogRepo.artistReleases(artist.name, artist.catalogId) }.getOrDefault(emptyList()).take(2)
        }
        val result = newest(found, java.time.LocalDate.now())
        cache = nowMs to result; cacheKey = key
        return result
    }

    /** Newest first, at most one year old, no album twice, ten at most. */
    internal fun newest(albums: List<CatalogAlbum>, today: java.time.LocalDate): List<CatalogAlbum> {
        val oldest = today.minusDays(366)
        return albums.filter { album ->
            album.releaseDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                ?.let { !it.isBefore(oldest) && !it.isAfter(today.plusDays(1)) } == true
        }.distinctBy { it.id }.sortedByDescending { it.releaseDate }.take(10)
    }
}
