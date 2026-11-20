package com.ketvoice.app.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId

/** The seasonal registry (6.2): its windows in the listener's time zone, overlaps, offers and the end of a season. */
class SeasonsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private fun at(local: String, zone: ZoneId) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private fun on(day: String) = Seasons.activeOn(LocalDate.parse(day))?.first?.id

    @After fun reset() {
        AppSettings.seasonTheme.value = ""
    }

    @Test fun eachSeasonHasItsWindowLastDayIncluded() {
        // 7.6: the Vice City edition is gone, nothing fills its weeks.
        assertNull(on("2026-11-17"))
        assertNull(on("2026-12-10"))
        assertEquals("noel", on("2026-12-11"))
        assertEquals("noel", on("2026-12-30"))
        assertEquals("saintvalentin", on("2027-02-07"))
        assertEquals("saintvalentin", on("2027-02-15"))
        assertNull(on("2027-02-16"))
        assertNull(on("2027-03-19"))
        assertEquals("printemps", on("2027-03-20"))
        assertEquals("printemps", on("2027-04-20"))
        assertNull(on("2027-04-21"))
        assertEquals("ete", on("2027-06-21"))
        assertEquals("ete", on("2027-08-31"))
        assertNull(on("2027-09-01"))
        assertEquals("halloween", on("2027-10-20"))
        assertEquals("halloween", on("2027-11-02"))
        assertNull(on("2027-11-03"))
    }

    @Test fun noelComesBackEveryYear() {
        assertNull(on("2027-11-20"))
        assertEquals("noel", on("2027-12-11"))
        assertEquals("noel", on("2031-12-24"))
    }

    @Test fun nouvelAnRunsOverTheNewYearAndWinsTheOverlapWithNoel() {
        // 31 December is in both windows: Nouvel An started later (31 Dec against 11 Dec), it wins.
        assertEquals("nouvelan", on("2026-12-31"))
        assertEquals("nouvelan", on("2027-01-01"))
        assertEquals("nouvelan", on("2027-01-02"))
        assertNull(on("2027-01-03"))
        val (first, last) = Seasons.NOUVEL_AN.window.occurrence(LocalDate.parse("2027-01-01"))!!
        assertEquals(LocalDate.parse("2026-12-31"), first)
        assertEquals(LocalDate.parse("2027-01-02"), last)
    }

    @Test fun theLaterStartWinsWhateverTheOrderOfTheRegistry() {
        val early = SeasonWindow(MonthDay.of(12, 1), MonthDay.of(12, 31))
        val late = SeasonWindow(MonthDay.of(12, 20), MonthDay.of(12, 26))
        val day = LocalDate.parse("2026-12-24")
        assertTrue(late.occurrence(day)!!.first.isAfter(early.occurrence(day)!!.first))
    }

    @Test fun theDayIsTheListenersOwn() {
        // 10 December 23:30 in Paris is already the 11th in Tokyo and still the 10th in Los Angeles.
        val moment = at("2026-12-10T23:30", paris)
        assertNull(Seasons.active(moment, paris))
        assertEquals("noel", Seasons.active(moment, tokyo)?.id)
        assertNull(Seasons.active(moment, losAngeles))
        // New Year's Eve begins at local midnight.
        val eve = at("2026-12-30T20:00", losAngeles)
        assertEquals("noel", Seasons.active(eve, losAngeles)?.id)
        assertEquals("nouvelan", Seasons.active(eve, paris)?.id)
    }

    @Test fun aSeasonIsDrawnOnlyWhileActiveAndChosen() {
        val noel = at("2026-12-20T12:00", paris)
        assertNull(Seasons.inEffect(noel, paris))
        Seasons.choose(Seasons.NOEL, true)
        assertEquals("noel", Seasons.inEffect(noel, paris)?.id)
        assertNull("not on New Year's Eve", Seasons.inEffect(at("2026-12-31T12:00", paris), paris))
        Seasons.choose(Seasons.NOEL, false)
        assertEquals("", AppSettings.seasonTheme.value)
    }

    @Test fun aSeasonIsSwitchedOffOnceOver() {
        AppSettings.seasonTheme.value = "noel"
        assertFalse("nothing changes while it lasts", Seasons.expire(at("2026-12-31T00:00", tokyo).minus(60_000), tokyo))
        assertEquals("noel", AppSettings.seasonTheme.value)
        assertTrue(Seasons.expire(at("2026-12-31T00:00", paris), paris))
        assertEquals("", AppSettings.seasonTheme.value)
        assertFalse("once is enough", Seasons.expire(at("2026-12-31T00:00", paris), paris))
    }

    @Test fun anUnknownOrRetiredValueIsNeverKeptInSeasonTheme() {
        val day = at("2026-12-20T12:00", paris)
        assertTrue(Seasons.storable("", day, paris))
        assertTrue(Seasons.storable("noel", day, paris))
        assertFalse(Seasons.storable("ete", day, paris))
        assertFalse(Seasons.storable("vicecity", at("2026-11-25T12:00", paris), paris))
        assertFalse(Seasons.storable("nimporte", day, paris))
    }

    @Test fun eachSeasonIsOfferedOncePerOccurrence() {
        val offered = mutableSetOf<String>()
        val first = at("2026-12-11T09:00", paris)
        val due = Seasons.offerDue({ it in offered }, first, paris)
        assertEquals("noel", due?.id)
        offered += Seasons.offerKey(due!!, LocalDate.parse("2026-12-11"))
        assertNull("not twice", Seasons.offerDue({ it in offered }, at("2026-12-24T09:00", paris), paris))
        assertEquals("Nouvel An is offered too", "nouvelan", Seasons.offerDue({ it in offered }, at("2026-12-31T09:00", paris), paris)?.id)
        assertEquals("and Noël again next year", "noel", Seasons.offerDue({ it in offered }, at("2027-12-12T09:00", paris), paris)?.id)
        assertNull("nothing between seasons", Seasons.offerDue({ false }, at("2027-05-10T09:00", paris), paris))
    }

    @Test fun nothingIsOfferedOutsideASeasonAndAChosenSeasonIsNotOfferedAgain() {
        assertNull(Seasons.offerDue({ false }, at("2026-11-25T09:00", paris), paris))
        AppSettings.seasonTheme.value = "halloween"
        assertNull(Seasons.offerDue({ false }, at("2027-10-25T09:00", paris), paris))
    }

    @Test fun theRegistryIdsAreUniqueAndUsableAsResourceNames() {
        val ids = Seasons.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it, it.matches(Regex("[a-z]+"))) }
    }
}
