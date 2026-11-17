package com.ketvoice.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

/**
 * What was listened to in one calendar month (6.1): the time really heard, plays per title, the
 * hour of the day it was heard at, the artists heard for the first time, Ketmix's mixes, and a
 * small copy of the most played titles so the month's playlist still plays once the listening
 * history has moved on. Stored per month in the `listening-history` preferences as `month:2026-11`.
 */
internal data class MonthStat(
    val ms: Long = 0L,
    val plays: Map<String, Int> = emptyMap(),
    /** Listening time per hour of the day (24 entries, local time). */
    val hours: List<Long> = List(24) { 0L },
    /** Artists heard for the first time this month, in the order they were met. */
    val newArtists: List<String> = emptyList(),
    /** The most played titles, without their stream addresses. */
    val tracks: Map<String, Track> = emptyMap(),
    /** Transitions Ketmix mixed this month. */
    val mixes: Int = 0,
)

internal object ListeningMonths {
    /** Titles counted in the month under way; a closed month keeps its [KEEP_CLOSED] most played. */
    const val MAX_TITLES = 300
    const val KEEP_CLOSED = 60
    /** Titles of the month whose details are kept (its playlist). */
    const val KEEP_TRACKS = 40
    const val MAX_NEW_ARTISTS = 60
    const val PREFIX = "month:"

    fun key(date: LocalDate): String = YearMonth.from(date).toString()
    fun isKey(key: String): Boolean = runCatching { YearMonth.parse(key) }.isSuccess

    fun addTime(months: Map<String, MonthStat>, date: LocalDate, hour: Int, deltaMs: Long): Map<String, MonthStat> {
        if (deltaMs <= 0L) return months
        val k = key(date)
        val stat = months[k] ?: MonthStat()
        val hours = stat.hours.toMutableList().also { if (it.size != 24) { it.clear(); repeat(24) { _ -> it += 0L } } }
        val h = hour.mod(24)
        hours[h] = hours[h] + deltaMs
        return months + (k to stat.copy(ms = stat.ms + deltaMs, hours = hours))
    }

    /**
     * A play of [track] in the month of [date]; [newArtist] is the artist's name when it was never
     * heard before. The titles kept in detail follow the plays.
     */
    fun addPlay(months: Map<String, MonthStat>, date: LocalDate, track: Track, newArtist: String? = null): Map<String, MonthStat> {
        val k = key(date)
        val stat = months[k] ?: MonthStat()
        val plays = stat.plays + (track.id to (stat.plays[track.id] ?: 0) + 1)
        val bounded = if (plays.size > MAX_TITLES) top(plays, MAX_TITLES) else plays
        val kept = keepTracks(stat.tracks + (track.id to compact(track)), bounded)
        val artists = if (newArtist.isNullOrBlank() || stat.newArtists.any { it.equals(newArtist, true) } ||
            stat.newArtists.size >= MAX_NEW_ARTISTS) stat.newArtists else stat.newArtists + newArtist
        return months + (k to stat.copy(plays = bounded, tracks = kept, newArtists = artists))
    }

    fun addMix(months: Map<String, MonthStat>, date: LocalDate): Map<String, MonthStat> {
        val k = key(date)
        val stat = months[k] ?: MonthStat()
        return months + (k to stat.copy(mixes = stat.mixes + 1))
    }

    /** A month that is over keeps only what its Replay shows. */
    fun close(stat: MonthStat): MonthStat {
        if (stat.plays.size <= KEEP_CLOSED && stat.tracks.size <= KEEP_TRACKS) return stat
        val plays = top(stat.plays, KEEP_CLOSED)
        return stat.copy(plays = plays, tracks = keepTracks(stat.tracks, plays))
    }

    private fun top(plays: Map<String, Int>, n: Int): Map<String, Int> =
        plays.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n).associate { it.key to it.value }

    private fun keepTracks(tracks: Map<String, Track>, plays: Map<String, Int>): Map<String, Track> {
        if (tracks.size <= KEEP_TRACKS) return tracks.filterKeys { it in plays }
        val wanted = top(plays.filterKeys { it in tracks }, KEEP_TRACKS).keys
        return tracks.filterKeys { it in wanted }
    }

    /** Only what names and plays a title again: no stream address, no session token. */
    fun compact(track: Track): Track = Track(track.id, track.title, track.artist, track.album, durationMs = track.durationMs,
        explicit = track.explicit, coverUrl = track.coverUrl, addon = track.addon, addonId = track.addonId, videoId = track.videoId,
        isrc = track.isrc, albumId = track.albumId, artistId = track.artistId)

    /** Months with something in them, newest first. */
    fun ordered(months: Map<String, MonthStat>): List<String> =
        months.filter { (_, s) -> s.ms > 0L || s.plays.isNotEmpty() }.keys.filter(::isKey).sortedDescending()

    fun encode(stat: MonthStat): String = JSONObject().apply {
        put("ms", stat.ms)
        put("plays", JSONObject(stat.plays))
        put("hours", JSONArray(stat.hours))
        put("new", JSONArray(stat.newArtists))
        put("mixes", stat.mixes)
        put("tracks", JSONArray().apply { stat.tracks.values.forEach { put(trackJson(it)) } })
    }.toString()

    fun decode(text: String?): MonthStat? = runCatching {
        val o = JSONObject(text ?: return null)
        val playsJson = o.optJSONObject("plays") ?: JSONObject()
        val plays = playsJson.keys().asSequence().associateWith { playsJson.optInt(it).coerceAtLeast(0) }.filterValues { it > 0 }
        val hoursJson = o.optJSONArray("hours")
        val hours = List(24) { i -> hoursJson?.optLong(i)?.coerceAtLeast(0L) ?: 0L }
        val newJson = o.optJSONArray("new") ?: JSONArray()
        val names = (0 until newJson.length()).mapNotNull { newJson.optString(it).takeIf(String::isNotBlank) }.take(MAX_NEW_ARTISTS)
        val tracksJson = o.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until tracksJson.length()).mapNotNull { track(tracksJson.optJSONObject(it)) }.associateBy { it.id }
        MonthStat(o.optLong("ms").coerceAtLeast(0L), plays, hours, names, tracks, o.optInt("mixes").coerceAtLeast(0))
    }.getOrNull()

    internal fun trackJson(t: Track) = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
        .put("d", t.durationMs).put("x", t.explicit).apply {
            t.coverUrl?.let { put("cover", it) }; t.addon?.let { put("addon", it) }; t.addonId?.let { put("addonId", it) }
            t.videoId?.let { put("videoId", it) }; t.isrc?.let { put("isrc", it) }; t.albumId?.let { put("albumId", it) }
            t.artistId?.let { put("artistId", it) }
        }

    internal fun track(o: JSONObject?): Track? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        fun opt(name: String) = o.optString(name).takeIf { o.has(name) && it.isNotBlank() }
        return Track(id, o.optString("title"), o.optString("artist"), o.optString("album"), durationMs = o.optLong("d"),
            explicit = o.optBoolean("x"), coverUrl = opt("cover"), addon = opt("addon"), addonId = opt("addonId"),
            videoId = opt("videoId"), isrc = opt("isrc"), albumId = opt("albumId"), artistId = opt("artistId"))
    }

    /** Names (normalised) of the artists ever heard, so a first listen can be told apart. */
    fun encodeSeen(seen: Collection<String>): String = JSONArray(seen.toList()).toString()
    fun decodeSeen(text: String?): LinkedHashSet<String>? = runCatching {
        val a = JSONArray(text ?: return null)
        (0 until a.length()).mapNotNullTo(LinkedHashSet()) { a.optString(it).takeIf(String::isNotBlank) }
    }.getOrNull()
}
