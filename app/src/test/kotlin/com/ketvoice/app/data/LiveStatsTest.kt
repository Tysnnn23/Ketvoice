package com.ketvoice.app.data

import com.ketvoice.app.data.LiveStats.Period
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** « Replay en direct »: periods, deltas, ties, hours and streak worked out from fixture histories. */
class LiveStatsTest {
    private val today = LocalDate.of(2026, 11, 20) // a Friday
    private val min = 60_000L
    private fun t(id: String, artist: String, d: Long = 180_000L) = Track(id, "Titre $id", artist, "Album", durationMs = d)
    private val tracks = listOf(t("a", "Nova"), t("b", "Orion"), t("c", "Nova, Lyra"), t("d", "Lyra"), t("e", "Vega"))

    /** Builds days and months the way [ListeningHistory] does. */
    private class History {
        var days = emptyMap<String, DayStat>()
        var months = emptyMap<String, MonthStat>()
        fun listen(date: LocalDate, minutes: Long, hour: Int = 20) {
            days = ListeningWeek.addTime(days, date, minutes * 60_000L, hour)
            months = ListeningMonths.addTime(months, date, hour, minutes * 60_000L)
        }
        fun play(date: LocalDate, track: Track, n: Int = 1, newArtist: Boolean = false) = repeat(n) { i ->
            days = ListeningWeek.addPlay(days, date, track.id, newArtist && i == 0)
            months = ListeningMonths.addPlay(months, date, track, if (newArtist && i == 0) track.artist else null)
        }
    }

    private fun input(h: History, years: Map<String, DayStat> = emptyMap(), total: Long = 0L, counts: Map<String, Int> = emptyMap()) =
        LiveStats.Input(today, ListeningWeek.trim(h.days, today), h.months, years, total, counts, tracks)

    @Test fun weekCountsMondayToTodayAgainstTheSameDaysOfLastWeek() {
        val h = History()
        h.listen(today, 30); h.listen(today.minusDays(4), 20) // Friday and Monday of this week
        h.listen(today.minusDays(7), 25) // last Friday: compared
        h.listen(today.minusDays(5), 100) // last Sunday: not in this week, nor in its equivalent days
        h.play(today, tracks[0], 3); h.play(today.minusDays(4), tracks[1], 2)
        val r = LiveStats.compute(Period.Week, input(h))
        assertEquals(50L, r.minutes)
        assertEquals(25 * min, r.previousMs)
        assertEquals(100, r.deltaPercent)
        assertEquals(7, r.bars.size)
        assertEquals("2026-11-16", r.bars.first().key)
        assertEquals(listOf(20L, 0L, 0L, 0L, 30L, 0L, 0L), r.bars.map { it.ms / min })
        assertTrue(r.bars[4].now)
        assertEquals(5, r.plays)
        assertEquals(2, r.titles)
        assertEquals(50 * min, r.hours[20])
        assertEquals(20, r.peakHour)
    }

    @Test fun aDropShowsAsANegativeDeltaAndNothingBeforeMeansNoDelta() {
        val h = History()
        h.listen(today, 10); h.listen(today.minusDays(7), 40)
        assertEquals(-75, LiveStats.compute(Period.Week, input(h)).deltaPercent)
        val fresh = History().apply { listen(today, 10) }
        val r = LiveStats.compute(Period.Week, input(fresh))
        assertNull(r.previousMs); assertNull(r.deltaPercent)
    }

    @Test fun tiesGoToTheIdThenTheNameAndArtistsAddUpTheirTitles() {
        val h = History()
        h.play(today, tracks[1], 4); h.play(today, tracks[0], 4); h.play(today, tracks[2], 2); h.play(today, tracks[3], 6)
        val r = LiveStats.compute(Period.Month, input(h))
        assertEquals(listOf("d", "a", "b", "c"), r.tracks.map { it.track.id })
        assertEquals(4 * 180_000L, r.tracks[1].ms)
        // Nova: a (4) + c (2) = 6, Lyra: d (6): equal, so by name.
        assertEquals(listOf("Lyra" to 6, "Nova" to 6, "Orion" to 4), r.artists.map { it.name to it.plays })
    }

    @Test fun monthBarsAreWeeksFromTheFirstAndTheDeltaStopsAtTheSameDay() {
        val h = History()
        h.listen(today, 30); h.listen(today.withDayOfMonth(2), 15); h.listen(today.withDayOfMonth(9), 5)
        val october = today.minusMonths(1)
        h.listen(october.withDayOfMonth(3), 60); h.listen(october.withDayOfMonth(28), 200) // after the 20th: left out
        val r = LiveStats.compute(Period.Month, input(h))
        assertEquals(50L, r.minutes)
        assertEquals(5, r.bars.size) // 30 days: 1–7, 8–14, 15–21, 22–28, 29–30
        assertEquals(listOf(15L, 5L, 30L, 0L, 0L), r.bars.map { it.ms / min })
        assertTrue(r.bars[2].now)
        assertEquals(60 * min, r.previousMs)
        assertEquals(-17, r.deltaPercent)
    }

    @Test fun aMonthKeptBeforeDailyTimeComparesWithTheWholeMonth() {
        val h = History()
        h.listen(today, 30)
        val r = LiveStats.compute(Period.Month, input(h).let { it.copy(months = it.months + ("2026-10" to MonthStat(ms = 120 * min))) })
        assertEquals(120 * min, r.previousMs)
        assertEquals(-75, r.deltaPercent)
    }

    @Test fun yearComparesWithLastYearUpToTheSameMonthAndCountsDiscoveriesAndMixes() {
        val h = History()
        h.listen(today, 30); h.listen(LocalDate.of(2026, 3, 4), 70, hour = 8)
        h.play(today, tracks[0], 2, newArtist = true); h.play(LocalDate.of(2026, 3, 4), tracks[4], 1, newArtist = true)
        h.months = ListeningMonths.addMix(h.months, today)
        h.listen(LocalDate.of(2025, 6, 1), 50); h.listen(LocalDate.of(2025, 12, 1), 500)
        val years = mapOf("2026" to DayStat(100 * min, mapOf("a" to 2, "e" to 1)))
        val r = LiveStats.compute(Period.Year, input(h, years))
        assertEquals(100L, r.minutes)
        assertEquals(50 * min, r.previousMs) // December 2025 is after November
        assertEquals(12, r.bars.size)
        assertEquals(70L, r.bars[2].ms / min)
        assertTrue(r.bars[10].now)
        assertEquals(2, r.newArtists)
        assertEquals(1, r.mixes)
        assertEquals(70 * min, r.hours[8])
    }

    @Test fun allTimeUsesTheTotalAndHasNoComparison() {
        val h = History()
        h.listen(today, 30)
        val r = LiveStats.compute(Period.All, input(h, total = 900 * min, counts = mapOf("e" to 9, "a" to 3)))
        assertEquals(900L, r.minutes)
        assertNull(r.deltaPercent)
        assertEquals(12, r.bars.size)
        assertTrue(r.bars.last().now)
        assertEquals("e", r.tracks.first().track.id)
    }

    @Test fun streakRunsBackFromTodayOrYesterdayAndCrossesMonths() {
        val h = History()
        (1L..3L).forEach { h.listen(today.minusDays(it), 5) }
        assertEquals(3, LiveStats.streak(input(h))) // today silent so far: the streak holds
        h.listen(today, 5)
        assertEquals(4, LiveStats.streak(input(h)))
        // Days older than the two weeks are read from the months' days.
        (4L..25L).forEach { h.listen(today.minusDays(it), 1) }
        assertEquals(26, LiveStats.streak(input(h)))
        assertEquals(0, LiveStats.streak(LiveStats.Input(today)))
    }

    @Test fun genresAreWeightedByPlaysAndAnEmptyHistoryIsEmpty() {
        val h = History()
        h.play(today, tracks[0], 6); h.play(today, tracks[1], 3); h.play(today, tracks[4], 1)
        val genres = mapOf("a" to setOf("Pop"), "b" to setOf("Rap", "Pop"))
        val r = LiveStats.compute(Period.Week, input(h), genresOf = { list -> genres.filterKeys { id -> list.any { it.id == id } } })
        assertEquals(listOf("Pop" to 100, "Rap" to 33), r.genres.map { it.label to it.percent })
        val empty = LiveStats.compute(Period.Week, LiveStats.Input(today))
        assertTrue(empty.isEmpty)
        assertTrue(empty.tracks.isEmpty() && empty.artists.isEmpty() && empty.genres.isEmpty() && empty.hours.isEmpty())
        assertEquals(0, empty.streak)
    }

    @Test fun newFieldsSurviveTheirStorage() {
        val h = History()
        h.listen(today, 12, hour = 7); h.play(today, tracks[0], 1, newArtist = true)
        h.days = ListeningWeek.addMix(h.days, today)
        val day = ListeningWeek.decode(ListeningWeek.encode(h.days))[today.toString()]!!
        assertEquals(12 * min, day.hours[7]); assertEquals(1, day.newArtists); assertEquals(1, day.mixes)
        val month = ListeningMonths.decode(ListeningMonths.encode(h.months.getValue("2026-11")))!!
        assertEquals(12 * min, month.days[19])
        // A day stored before 8.0 still reads.
        assertEquals(DayStat(5L, mapOf("a" to 1)), ListeningWeek.decode("""{"2026-11-20":{"ms":5,"plays":{"a":1}}}""")["2026-11-20"])
    }
}
