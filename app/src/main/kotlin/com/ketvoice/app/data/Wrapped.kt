package com.ketvoice.app.data

import org.json.JSONObject

/**
 * Ketvoice Wrapped (5.9.0): the recap of a listening year, computed on the device from the plays
 * and the time Ketvoice really counted. Years are recorded from 5.9.0 on; until a year holds
 * enough plays, the recap covers everything counted since the first launch instead, and says so.
 */
internal object Wrapped {
    /** Plays a year needs before the recap is that year's rather than all time's. */
    const val ENOUGH_PLAYS = 25
    private const val MAX_TITLES = 600

    data class Artist(val name: String, val plays: Int, val cover: String?)
    data class Recap(
        /** "2026", or null when the recap covers all the listening counted so far. */
        val year: String?,
        val minutes: Long,
        val plays: Int,
        val titles: Int,
        val artists: List<Artist>,
        val tracks: List<Pair<Track, Int>>,
    ) {
        val topArtist: Artist? get() = artists.firstOrNull()
        val topTrack: Track? get() = tracks.firstOrNull()?.first
    }

    fun addTime(years: Map<String, DayStat>, year: String, deltaMs: Long): Map<String, DayStat> {
        if (deltaMs <= 0L) return years
        val stat = years[year] ?: DayStat()
        return years + (year to stat.copy(ms = stat.ms + deltaMs))
    }

    fun addPlay(years: Map<String, DayStat>, year: String, trackId: String): Map<String, DayStat> {
        val stat = years[year] ?: DayStat()
        val plays = stat.plays + (trackId to (stat.plays[trackId] ?: 0) + 1)
        val bounded = if (plays.size > MAX_TITLES) plays.entries.sortedByDescending { it.value }
            .take(MAX_TITLES).associate { it.key to it.value } else plays
        return years + (year to stat.copy(plays = bounded))
    }

    fun decode(text: String?): Map<String, DayStat> = runCatching {
        val root = JSONObject(text ?: "{}")
        root.keys().asSequence().mapNotNull { k ->
            if (k.length != 4 || k.toIntOrNull() == null) return@mapNotNull null
            val row = root.optJSONObject(k) ?: return@mapNotNull null
            val playsJson = row.optJSONObject("plays") ?: JSONObject()
            val plays = playsJson.keys().asSequence().associateWith { playsJson.optInt(it).coerceAtLeast(0) }.filterValues { it > 0 }
            k to DayStat(row.optLong("ms").coerceAtLeast(0L), plays)
        }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * The recap of [year] from [years]; when that year has fewer than [ENOUGH_PLAYS] plays, the
     * all-time [counts] and [allTimeMs] are used instead. Titles are named from [known].
     */
    fun recap(year: String, years: Map<String, DayStat>, counts: Map<String, Int>, allTimeMs: Long,
              known: List<Track>, artistOf: (Track) -> String = { MediaOverrides.mainArtist(it.artist) },
              coverOf: (Track) -> String? = { it.coverUrl }): Recap {
        val stat = years[year]
        val yearly = stat != null && stat.plays.values.sum() >= ENOUGH_PLAYS
        val plays = if (yearly) stat!!.plays else counts
        val byId = known.associateBy { it.id }
        val ranked = plays.entries.mapNotNull { (id, n) -> byId[id]?.let { it to n } }
            .sortedWith(compareByDescending<Pair<Track, Int>> { it.second }.thenBy { it.first.id })
        val artists = ranked.groupBy { artistOf(it.first) }
            .map { (name, rows) -> Artist(name, rows.sumOf { it.second }, rows.first().first.let(coverOf)) }
            .filter { it.name.isNotBlank() }
            .sortedWith(compareByDescending<Artist> { it.plays }.thenBy { it.name })
        return Recap(if (yearly) year else null, (if (yearly) stat!!.ms else allTimeMs) / 60_000,
            plays.values.sum(), plays.size, artists.take(5), ranked.take(5))
    }
}
