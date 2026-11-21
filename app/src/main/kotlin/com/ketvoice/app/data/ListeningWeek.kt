package com.ketvoice.app.data

import org.json.JSONObject
import java.time.LocalDate

/** What was listened to on one day: real listening time and plays per title. */
internal data class DayStat(
    val ms: Long = 0L,
    val plays: Map<String, Int> = emptyMap(),
    /** 8.0: listening time per hour of the day (24 entries once anything was heard), for the live statistics. */
    val hours: List<Long> = emptyList(),
    /** 8.0: artists heard for the first time that day. */
    val newArtists: Int = 0,
    /** 8.0: transitions Ketmix mixed that day. */
    val mixes: Int = 0,
)

/** A week in a few figures, for the Home card. */
internal data class WeekSummary(val listenedMs: Long, val plays: Int, val topTrack: Track?, val topArtist: String?)

/**
 * Daily listening kept on the device for the last [KEEP_DAYS] days: enough for "your
 * week" without a history of dates that grows forever.
 */
internal object ListeningWeek {
    const val KEEP_DAYS = 14
    private const val MAX_TITLES_PER_DAY = 200

    fun key(day: LocalDate): String = day.toString()

    fun addTime(days: Map<String, DayStat>, day: LocalDate, deltaMs: Long, hour: Int? = null): Map<String, DayStat> {
        if (deltaMs <= 0L) return days
        val k = key(day)
        val stat = days[k] ?: DayStat()
        val hours = if (hour == null) stat.hours else (if (stat.hours.size == 24) stat.hours.toMutableList() else MutableList(24) { 0L })
            .also { it[hour.mod(24)] += deltaMs }
        return trim(days + (k to stat.copy(ms = stat.ms + deltaMs, hours = hours)), day)
    }

    fun addMix(days: Map<String, DayStat>, day: LocalDate): Map<String, DayStat> {
        val k = key(day)
        val stat = days[k] ?: DayStat()
        return trim(days + (k to stat.copy(mixes = stat.mixes + 1)), day)
    }

    fun addPlay(days: Map<String, DayStat>, day: LocalDate, trackId: String, newArtist: Boolean = false): Map<String, DayStat> {
        val k = key(day)
        val stat = days[k] ?: DayStat()
        val plays = stat.plays + (trackId to (stat.plays[trackId] ?: 0) + 1)
        val bounded = if (plays.size > MAX_TITLES_PER_DAY) plays.entries.sortedByDescending { it.value }
            .take(MAX_TITLES_PER_DAY).associate { it.key to it.value } else plays
        return trim(days + (k to stat.copy(plays = bounded, newArtists = stat.newArtists + if (newArtist) 1 else 0)), day)
    }

    /** Days older than the window are forgotten; a clock moved back never deletes the future. */
    fun trim(days: Map<String, DayStat>, today: LocalDate): Map<String, DayStat> {
        val oldest = today.minusDays(KEEP_DAYS.toLong() - 1)
        return days.filterKeys { runCatching { !LocalDate.parse(it).isBefore(oldest) }.getOrDefault(false) }
    }

    /** The last seven days up to [today]; the artist is credited with all their titles' plays. */
    fun summary(days: Map<String, DayStat>, today: LocalDate, known: List<Track>): WeekSummary {
        val first = today.minusDays(6)
        val week = days.filterKeys { k -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { !it.isBefore(first) && !it.isAfter(today) } == true }.values
        val plays = mutableMapOf<String, Int>()
        week.forEach { stat -> stat.plays.forEach { (id, n) -> plays[id] = (plays[id] ?: 0) + n } }
        val byId = known.associateBy { it.id }
        val topTrack = plays.entries.filter { it.key in byId }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.let { byId[it.key] }
        val artists = mutableMapOf<String, Int>()
        plays.forEach { (id, n) -> byId[id]?.let { t ->
            val name = PersonalMixes.primaryArtist(t)
            if (name.isNotBlank()) artists[name] = (artists[name] ?: 0) + n
        } }
        val topArtist = artists.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
        return WeekSummary(week.sumOf { it.ms }, plays.values.sum(), topTrack, topArtist)
    }

    /** Listening time of each of the last seven days, oldest first, [today] last. */
    fun daily(days: Map<String, DayStat>, today: LocalDate): LongArray =
        LongArray(7) { index -> days[key(today.minusDays((6 - index).toLong()))]?.ms?.coerceAtLeast(0L) ?: 0L }

    /** Height of a day's bar as a share of the tallest: a day without music keeps a stub, so the week reads as seven days. */
    fun barShare(ms: Long, longest: Long): Float =
        if (longest <= 0L) .1f else (.1f + .9f * (ms.coerceIn(0L, longest).toFloat() / longest)).coerceIn(.1f, 1f)

    fun encode(days: Map<String, DayStat>): String = JSONObject().apply {
        days.forEach { (k, stat) -> put(k, JSONObject().put("ms", stat.ms).put("plays", JSONObject(stat.plays)).apply {
            if (stat.hours.size == 24) put("h", org.json.JSONArray(stat.hours))
            if (stat.newArtists > 0) put("n", stat.newArtists)
            if (stat.mixes > 0) put("x", stat.mixes)
        }) }
    }.toString()

    fun decode(text: String?): Map<String, DayStat> = runCatching {
        val root = JSONObject(text ?: "{}")
        root.keys().asSequence().mapNotNull { k ->
            runCatching { LocalDate.parse(k) }.getOrNull() ?: return@mapNotNull null
            val row = root.optJSONObject(k) ?: return@mapNotNull null
            val playsJson = row.optJSONObject("plays") ?: JSONObject()
            val plays = playsJson.keys().asSequence().associateWith { playsJson.optInt(it).coerceAtLeast(0) }.filterValues { it > 0 }
            val hoursJson = row.optJSONArray("h")
            val hours = if (hoursJson?.length() == 24) List(24) { hoursJson.optLong(it).coerceAtLeast(0L) } else emptyList()
            k to DayStat(row.optLong("ms").coerceAtLeast(0L), plays, hours, row.optInt("n").coerceAtLeast(0), row.optInt("x").coerceAtLeast(0))
        }.toMap()
    }.getOrDefault(emptyMap())
}
