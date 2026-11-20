package com.ketvoice.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * « Replay en direct » (8.0): the statistics of a period worked out from what [ListeningHistory] already keeps
 * (the last 14 days, each month, each year, everything). Pure: no Android, no clock but [today], so the page
 * computes it off the main thread and the tests feed it fixture histories.
 */
internal object LiveStats {
    enum class Period { Week, Month, Year, All }

    /** One bar of the chart: a day of the week, a week of the month, a month of the year. [key] names it (ISO date, "S2", "2026-03"). */
    data class Bar(val key: String, val ms: Long, val now: Boolean)
    data class ArtistRow(val name: String, val plays: Int, val cover: String?, val catalogId: String?)
    /** [ms] is estimated from the title's length: time is counted per day, not per title. */
    data class TrackRow(val track: Track, val plays: Int, val ms: Long)
    data class GenreRow(val label: String, val plays: Int, val percent: Int)

    data class Input(
        val today: LocalDate,
        val days: Map<String, DayStat> = emptyMap(),
        val months: Map<String, MonthStat> = emptyMap(),
        val years: Map<String, DayStat> = emptyMap(),
        val totalMs: Long = 0L,
        val counts: Map<String, Int> = emptyMap(),
        val known: List<Track> = emptyList(),
    )

    data class Result(
        val period: Period,
        val ms: Long,
        /** Time of the previous equivalent period; null when there is nothing to compare with. */
        val previousMs: Long?,
        val bars: List<Bar>,
        val plays: Int,
        val titles: Int,
        val artists: List<ArtistRow>,
        val tracks: List<TrackRow>,
        val genres: List<GenreRow>,
        /** 24 entries, or empty when the period's hours are not known. */
        val hours: List<Long>,
        /** Days in a row with music, up to today (or yesterday, while today is still silent). */
        val streak: Int,
        val newArtists: Int,
        val mixes: Int,
    ) {
        val minutes: Long get() = ms / 60_000L
        val isEmpty: Boolean get() = ms <= 0L && plays == 0
        /** Rounded change from the previous period, in percent; null without a previous period. */
        val deltaPercent: Int? get() = previousMs?.takeIf { it > 0L }?.let { Math.round((ms - it) * 100.0 / it).toInt() }
        val peakHour: Int? get() = hours.takeIf { it.size == 24 && it.any { ms -> ms > 0L } }?.let { h -> h.indices.maxBy { h[it] } }
    }

    const val TOP_ARTISTS = 5
    const val TOP_TRACKS = 10
    const val TOP_GENRES = 5

    fun compute(period: Period, input: Input, artistOf: (Track) -> String = { it.artist.substringBefore(',').trim() },
                coverOf: (Track) -> String? = { it.coverUrl }, genresOf: (List<Track>) -> Map<String, Set<String>> = { emptyMap() }): Result {
        val today = input.today
        val month = YearMonth.from(today)
        val shape = when (period) {
            Period.Week -> week(input)
            Period.Month -> month(input, month)
            Period.Year -> year(input)
            Period.All -> all(input)
        }
        val byId = HashMap<String, Track>()
        input.months.values.forEach { byId.putAll(it.tracks) }
        input.known.forEach { byId[it.id] = it }
        val ranked = shape.plays.entries.mapNotNull { (id, n) -> byId[id]?.let { it to n } }
            .sortedWith(compareByDescending<Pair<Track, Int>> { it.second }.thenBy { it.first.id })
        val artists = ranked.groupBy { artistOf(it.first) }.filterKeys { it.isNotBlank() }
            .map { (name, rows) -> ArtistRow(name, rows.sumOf { it.second }, rows.firstNotNullOfOrNull { coverOf(it.first) },
                rows.firstNotNullOfOrNull { Wrapped.catalogId(it.first) }) }
            .sortedWith(compareByDescending<ArtistRow> { it.plays }.thenBy { it.name.lowercase() })
        val tracks = ranked.take(TOP_TRACKS).map { (t, n) -> TrackRow(t, n, n * t.durationMs.coerceAtLeast(0L)) }
        return Result(period, shape.ms, shape.previousMs, shape.bars, shape.plays.values.sum(), shape.plays.size,
            artists.take(TOP_ARTISTS), tracks, genres(ranked.take(60), genresOf), shape.hours, streak(input),
            shape.newArtists, shape.mixes)
    }

    private class Shape(val ms: Long, val previousMs: Long?, val bars: List<Bar>, val plays: Map<String, Int>,
                        val hours: List<Long>, val newArtists: Int, val mixes: Int)

    /** The calendar week (Monday first) up to today, against the same days of the week before. */
    private fun week(input: Input): Shape {
        val today = input.today
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val dates = generateSequence(monday) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        val stats = dates.mapNotNull { input.days[ListeningWeek.key(it)] }
        val previous = dates.map { it.minusWeeks(1) }
        val previousMs = previous.sumOf { dayMs(input, it) }.takeIf { it > 0L }
        val bars = (0 until 7).map { i -> monday.plusDays(i.toLong()).let { d -> Bar(d.toString(), if (d.isAfter(today)) 0L else dayMs(input, d), d == today) } }
        val plays = HashMap<String, Int>()
        stats.forEach { s -> s.plays.forEach { (id, n) -> plays[id] = (plays[id] ?: 0) + n } }
        return Shape(stats.sumOf { it.ms }, previousMs, bars, plays, sumHours(stats.map { it.hours }),
            stats.sumOf { it.newArtists }, stats.sumOf { it.mixes })
    }

    /** The month under way, by weeks of seven days from the 1st, against the previous month up to the same day when its days are known. */
    private fun month(input: Input, month: YearMonth): Shape {
        val stat = input.months[month.toString()] ?: MonthStat()
        val before = input.months[month.minusMonths(1).toString()]
        val dayOfMonth = input.today.dayOfMonth
        val previousMs = before?.takeIf { it.ms > 0L }?.let { b ->
            if (b.days.any { it > 0L }) b.days.take(dayOfMonth).sum() else b.ms
        }?.takeIf { it > 0L }
        val weeks = (month.lengthOfMonth() + 6) / 7
        val bars = (0 until weeks).map { w ->
            val from = w * 7 + 1
            val to = minOf(from + 6, month.lengthOfMonth())
            Bar("S${w + 1}", (from..to).filter { it <= dayOfMonth }.sumOf { dayMs(input, month.atDay(it)) }, dayOfMonth in from..to)
        }
        return Shape(stat.ms, previousMs, bars, stat.plays, stat.hours.takeIf { h -> h.any { it > 0L } }.orEmpty(),
            stat.newArtists.size, stat.mixes)
    }

    /** The year under way, by months, against the previous year up to the same month (its months when kept, else the whole year). */
    private fun year(input: Input): Shape {
        val today = input.today
        val y = today.year
        val ofYear = input.months.filterKeys { it.startsWith("$y-") }.values
        val stat = input.years[y.toString()]
        val ms = stat?.ms ?: ofYear.sumOf { it.ms }
        val lastYearMonths = (1..today.monthValue).mapNotNull { input.months[YearMonth.of(y - 1, it).toString()] }
        val previousMs = when {
            lastYearMonths.isNotEmpty() -> lastYearMonths.sumOf { it.ms }
            else -> input.years[(y - 1).toString()]?.ms
        }?.takeIf { it > 0L }
        val bars = (1..12).map { m -> YearMonth.of(y, m).let { k -> Bar(k.toString(), input.months[k.toString()]?.ms ?: 0L, m == today.monthValue) } }
        val plays = stat?.plays ?: merged(ofYear.map { it.plays })
        return Shape(ms, previousMs, bars, plays, sumHours(ofYear.map { it.hours }), ofYear.sumOf { it.newArtists.size }, ofYear.sumOf { it.mixes })
    }

    /** Everything since the first launch; the chart shows the last twelve months. */
    private fun all(input: Input): Shape {
        val now = YearMonth.from(input.today)
        val bars = (11 downTo 0).map { back -> now.minusMonths(back.toLong()).let { k -> Bar(k.toString(), input.months[k.toString()]?.ms ?: 0L, back == 0) } }
        val ms = maxOf(input.totalMs, input.years.values.sumOf { it.ms })
        val plays = input.counts.ifEmpty { merged(input.years.values.map { it.plays }) }
        return Shape(ms, null, bars, plays, sumHours(input.months.values.map { it.hours }),
            input.months.values.sumOf { it.newArtists.size }, input.months.values.sumOf { it.mixes })
    }

    /** Time heard on [date]: the last two weeks' days first, then the days of its month (8.0 onwards). */
    fun dayMs(input: Input, date: LocalDate): Long =
        input.days[ListeningWeek.key(date)]?.ms ?: input.months[YearMonth.from(date).toString()]?.days?.getOrNull(date.dayOfMonth - 1) ?: 0L

    /** Days in a row with music, ending today, or yesterday while nothing was heard yet today. */
    fun streak(input: Input): Int {
        var day = input.today
        if (!heard(input, day)) day = day.minusDays(1)
        var n = 0
        while (heard(input, day) && n < 3660) { n++; day = day.minusDays(1) }
        return n
    }

    private fun heard(input: Input, date: LocalDate): Boolean =
        dayMs(input, date) > 0L || input.days[ListeningWeek.key(date)]?.plays?.isNotEmpty() == true

    private fun merged(maps: List<Map<String, Int>>): Map<String, Int> {
        val out = HashMap<String, Int>()
        maps.forEach { m -> m.forEach { (id, n) -> out[id] = (out[id] ?: 0) + n } }
        return out
    }

    private fun sumHours(rows: List<List<Long>>): List<Long> {
        val known = rows.filter { it.size == 24 }
        if (known.none { r -> r.any { it > 0L } }) return emptyList()
        return List(24) { h -> known.sumOf { it[h] } }
    }

    /** Genres weighted by plays; the share is of the plays whose genre is known. Ties go to the name. */
    private fun genres(ranked: List<Pair<Track, Int>>, genresOf: (List<Track>) -> Map<String, Set<String>>): List<GenreRow> {
        if (ranked.isEmpty()) return emptyList()
        val of = genresOf(ranked.map { it.first })
        val plays = HashMap<String, Int>()
        var counted = 0
        ranked.forEach { (t, n) ->
            val g = of[t.id].orEmpty()
            if (g.isNotEmpty()) counted += n
            g.forEach { plays[it] = (plays[it] ?: 0) + n }
        }
        if (counted == 0) return emptyList()
        return plays.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_GENRES).map { GenreRow(it.key, it.value, Math.round(it.value * 100.0 / counted).toInt().coerceIn(0, 100)) }
    }
}
