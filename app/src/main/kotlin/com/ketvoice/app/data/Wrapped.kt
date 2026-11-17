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

    data class Artist(val name: String, val plays: Int, val cover: String?, val catalogId: String? = null)
    data class Album(val title: String, val artist: String, val plays: Int, val cover: String?)
    /** How the most played titles move, read from Ketmix's tempo analysis when it has measured enough of them. */
    data class Mood(val bpm: Int, val label: String, val measured: Int)
    data class Recap(
        /** "2026", or null when the recap covers all the listening counted so far. */
        val year: String?,
        val minutes: Long,
        val plays: Int,
        val titles: Int,
        val artists: List<Artist>,
        val tracks: List<Pair<Track, Int>>,
        /** "2026-11" for a month's Replay. */
        val month: String? = null,
        val albums: List<Album> = emptyList(),
        /** Listening time per hour of the day (24 entries) when it was counted; empty before 6.1. */
        val clock: List<Long> = emptyList(),
        val newArtists: List<String> = emptyList(),
        val ketmixMixes: Int = 0,
        val mood: Mood? = null,
        /** Artists heard, all of them (only the first five are listed). */
        val artistCount: Int = 0,
    ) {
        val topArtist: Artist? get() = artists.firstOrNull()
        val topTrack: Track? get() = tracks.firstOrNull()?.first
        /** The hour most listened to, when the clock was counted. */
        val peakHour: Int? get() = clock.takeIf { it.size == 24 && it.any { ms -> ms > 0L } }?.let { c -> c.indices.maxBy { c[it] } }
    }

    /** Moments of the day, as the clock page names them. */
    fun dayPart(hour: Int): String = when (hour.mod(24)) {
        in 5..11 -> "le matin"
        in 12..17 -> "l'après-midi"
        in 18..22 -> "le soir"
        else -> "la nuit"
    }

    /** The share of the listening time of each of the four parts of the day, morning first. */
    fun dayParts(clock: List<Long>): List<Float> {
        if (clock.size != 24) return listOf(0f, 0f, 0f, 0f)
        val parts = LongArray(4)
        clock.forEachIndexed { h, ms -> parts[when (h) { in 5..11 -> 0; in 12..17 -> 1; in 18..22 -> 2; else -> 3 }] += ms }
        val total = parts.sum()
        return parts.map { if (total <= 0L) 0f else it.toFloat() / total }
    }

    /** A mood from the tempo of the most played titles; null until at least [MOOD_MIN] of them were measured. */
    const val MOOD_MIN = 3
    fun mood(tracks: List<Pair<Track, Int>>, bpmOf: (Track) -> Float?): Mood? {
        var weight = 0; var sum = 0.0; var measured = 0
        tracks.forEach { (track, plays) ->
            val bpm = bpmOf(track)?.takeIf { it in 50f..220f } ?: return@forEach
            // Half and double tempo read the same way: fold them into 70..140.
            var b = bpm.toDouble(); while (b < 70) b *= 2; while (b > 160) b /= 2
            sum += b * plays; weight += plays; measured++
        }
        if (measured < MOOD_MIN || weight == 0) return null
        val bpm = (sum / weight).toInt()
        val label = when {
            bpm < 92 -> "Posé"
            bpm < 112 -> "Groove"
            bpm < 128 -> "Entraînant"
            else -> "Énergique"
        }
        return Mood(bpm, label, measured)
    }

    /** Albums ranked by the plays of their titles; titles without an album are left out. */
    internal fun albums(ranked: List<Pair<Track, Int>>, artistOf: (Track) -> String, coverOf: (Track) -> String?): List<Album> =
        ranked.filter { it.first.album.isNotBlank() }
            .groupBy { MediaOverrides.normalize(it.first.album) + "|" + MediaOverrides.normalize(artistOf(it.first)) }
            .map { (_, rows) -> Album(rows.first().first.album, artistOf(rows.first().first), rows.sumOf { it.second },
                rows.firstNotNullOfOrNull { coverOf(it.first) }) }
            .sortedWith(compareByDescending<Album> { it.plays }.thenBy { it.title })

    private fun catalogId(track: Track): String? =
        track.artistId?.takeIf { track.addon == "um" && it.isNotBlank() && it.all(Char::isDigit) }

    private fun rank(plays: Map<String, Int>, byId: Map<String, Track>): List<Pair<Track, Int>> =
        plays.entries.mapNotNull { (id, n) -> byId[id]?.let { it to n } }
            .sortedWith(compareByDescending<Pair<Track, Int>> { it.second }.thenBy { it.first.id })

    private fun artists(ranked: List<Pair<Track, Int>>, artistOf: (Track) -> String, coverOf: (Track) -> String?): List<Artist> =
        ranked.groupBy { artistOf(it.first) }
            .map { (name, rows) -> Artist(name, rows.sumOf { it.second }, rows.first().first.let(coverOf),
                rows.firstNotNullOfOrNull { catalogId(it.first) }) }
            .filter { it.name.isNotBlank() }
            .sortedWith(compareByDescending<Artist> { it.plays }.thenBy { it.name })

    /**
     * The Replay of month [key] ("2026-11"): its time, plays, titles, artists, albums, hours and
     * discoveries. Titles are named from [known] and from the copy the month kept of its top titles.
     */
    fun month(key: String, months: Map<String, MonthStat>, known: List<Track>,
              artistOf: (Track) -> String = { MediaOverrides.mainArtist(it.artist) },
              coverOf: (Track) -> String? = { it.coverUrl }, bpmOf: (Track) -> Float? = { null }): Recap {
        val stat = months[key] ?: MonthStat()
        val byId = stat.tracks + known.associateBy { it.id }
        val ranked = rank(stat.plays, byId)
        val artists = artists(ranked, artistOf, coverOf)
        return Recap(key.take(4), stat.ms / 60_000, stat.plays.values.sum(), stat.plays.size, artists.take(5),
            ranked.take(KEEP_TOP), month = key, albums = albums(ranked, artistOf, coverOf).take(5), clock = stat.hours,
            newArtists = stat.newArtists, ketmixMixes = stat.mixes, mood = mood(ranked.take(20), bpmOf), artistCount = artists.size)
    }

    /** Titles a month's Replay keeps in its playlist. */
    const val KEEP_TOP = 25

    /** The year's recap with what the months of that year counted: the hours, the discoveries, the mixes, the mood. */
    fun withMonths(recap: Recap, months: Map<String, MonthStat>, artistOf: (Track) -> String = { MediaOverrides.mainArtist(it.artist) },
                   coverOf: (Track) -> String? = { it.coverUrl }, bpmOf: (Track) -> Float? = { null }): Recap {
        val year = recap.year
        val ofYear = months.filterKeys { year != null && it.startsWith("$year-") }.values
        val clock = if (ofYear.isEmpty()) emptyList() else List(24) { h -> ofYear.sumOf { it.hours.getOrElse(h) { 0L } } }
        return recap.copy(albums = albums(recap.tracks, artistOf, coverOf).take(5), clock = clock,
            newArtists = ofYear.flatMap { it.newArtists }.distinctBy { it.lowercase() },
            ketmixMixes = ofYear.sumOf { it.mixes }, mood = mood(recap.tracks, bpmOf))
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
        val ranked = rank(plays, byId)
        val artists = artists(ranked, artistOf, coverOf)
        return Recap(if (yearly) year else null, (if (yearly) stat!!.ms else allTimeMs) / 60_000,
            plays.values.sum(), plays.size, artists.take(5), ranked.take(5), artistCount = artists.size)
    }
}
