package com.ketvoice.app.data

/** A title heard lately and when (epoch ms; 0 when the history predates the time being kept). */
internal data class Heard(val track: Track, val at: Long)

/**
 * One device's own listening (9.2, « tout synchroniser »): what it heard itself, never what other devices
 * sent. Each device publishes only this under its own id in the account's `listening` document, so the
 * figures every device shows are its own plus the others', summed, and nothing is counted twice.
 */
internal data class DeviceListening(
    val recent: List<Heard> = emptyList(),
    val known: List<Track> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val listenedMs: Long = 0L,
    val days: Map<String, DayStat> = emptyMap(),
    val years: Map<String, DayStat> = emptyMap(),
    val months: Map<String, MonthStat> = emptyMap(),
)

/** Pure rules that put several devices' listening together. */
internal object ListeningShare {
    const val RECENT_MAX = 80
    const val KNOWN_MAX = 2_000

    /** [own] with every device of [others] added: sums for times and plays, the latest first for recents. */
    fun combine(own: DeviceListening, others: List<DeviceListening>): DeviceListening {
        if (others.isEmpty()) return own
        val all = listOf(own) + others
        return DeviceListening(
            recent = recent(all.flatMap { it.recent }),
            known = all.flatMap { it.known }.distinctBy { it.id }.take(KNOWN_MAX),
            counts = sumCounts(all.map { it.counts }),
            listenedMs = all.sumOf { it.listenedMs.coerceAtLeast(0L) },
            days = sumMaps(all.map { it.days }, ::sumDay),
            years = sumMaps(all.map { it.years }, ::sumDay),
            months = sumMaps(all.map { it.months }, ::sumMonth),
        )
    }

    /** The latest hearing of each title first; the same song under another id shows once, as on one device. */
    fun recent(heard: List<Heard>): List<Heard> {
        val out = ArrayList<Heard>()
        heard.withIndex().sortedWith(compareByDescending<IndexedValue<Heard>> { it.value.at }.thenBy { it.index }).forEach { (_, h) ->
            val same = out.any { it.track.id == h.track.id ||
                (it.track.title.equals(h.track.title, true) && it.track.artist.equals(h.track.artist, true)) }
            if (!same && out.size < RECENT_MAX) out += h
        }
        return out
    }

    fun sumCounts(maps: List<Map<String, Int>>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        maps.forEach { m -> m.forEach { (id, n) -> if (n > 0) out[id] = (out[id] ?: 0) + n } }
        return out
    }

    private fun <T> sumMaps(maps: List<Map<String, T>>, add: (T, T) -> T): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        maps.forEach { m -> m.forEach { (k, v) -> out[k] = out[k]?.let { add(it, v) } ?: v } }
        return out
    }

    private fun sumLists(a: List<Long>, b: List<Long>): List<Long> = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> List(maxOf(a.size, b.size)) { a.getOrElse(it) { 0L } + b.getOrElse(it) { 0L } }
    }

    fun sumDay(a: DayStat, b: DayStat): DayStat = DayStat(a.ms + b.ms, sumCounts(listOf(a.plays, b.plays)),
        sumLists(a.hours, b.hours), a.newArtists + b.newArtists, a.mixes + b.mixes)

    fun sumMonth(a: MonthStat, b: MonthStat): MonthStat = MonthStat(a.ms + b.ms, sumCounts(listOf(a.plays, b.plays)),
        sumLists(a.hours, b.hours), (a.newArtists + b.newArtists).distinctBy { it.lowercase() }.take(ListeningMonths.MAX_NEW_ARTISTS),
        b.tracks + a.tracks, a.mixes + b.mixes, sumLists(a.days, b.days))

    /** A title as another device may show it: no stream address, no file of this device, a cover only on https. */
    fun shareable(track: Track): Track? = ListeningMonths.compact(track)
        .copy(coverUrl = track.coverUrl?.takeIf { it.startsWith("https://") && it.length <= 1000 })
        .takeIf { it.id.isNotBlank() && it.id.length <= 256 && !it.id.startsWith("local:") && it.title.length <= 300 }
}
