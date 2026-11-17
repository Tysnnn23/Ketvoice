package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Monthly Replay (6.1): what each month counts, what it keeps once over, and the recap built from it. */
class ListeningMonthsTest {
    private fun t(id: String, artist: String, album: String = "Album $artist") =
        Track(id, "Title $id", artist, album, durationMs = 180_000, streamUrl = "https://stream.example/$id", coverUrl = "https://img.example/$id.jpg")

    private val nov = LocalDate.of(2026, 11, 17)
    private val oct = LocalDate.of(2026, 10, 31)

    @Test fun playsTimeAndHoursGoToTheirMonth() {
        var months = emptyMap<String, MonthStat>()
        months = ListeningMonths.addTime(months, nov, hour = 22, deltaMs = 60_000)
        months = ListeningMonths.addTime(months, nov, hour = 22, deltaMs = 30_000)
        months = ListeningMonths.addTime(months, nov, hour = 8, deltaMs = 10_000)
        months = ListeningMonths.addTime(months, oct, hour = 23, deltaMs = 5_000)
        months = ListeningMonths.addPlay(months, nov, t("a", "Nova"), newArtist = "Nova")
        months = ListeningMonths.addPlay(months, nov, t("a", "Nova"), newArtist = null)
        months = ListeningMonths.addPlay(months, oct, t("b", "Orion"))
        val n = months.getValue("2026-11")
        assertEquals(100_000L, n.ms)
        assertEquals(90_000L, n.hours[22]); assertEquals(10_000L, n.hours[8]); assertEquals(24, n.hours.size)
        assertEquals(mapOf("a" to 2), n.plays)
        assertEquals(listOf("Nova"), n.newArtists)
        assertNull("no stream address is kept", n.tracks.getValue("a").streamUrl)
        assertEquals(mapOf("b" to 1), months.getValue("2026-10").plays)
        assertEquals(listOf("2026-11", "2026-10"), ListeningMonths.ordered(months))
        assertEquals(1, ListeningMonths.addMix(months, nov).getValue("2026-11").mixes)
        assertEquals("nothing heard, nothing counted", months, ListeningMonths.addTime(months, nov, 3, 0))
    }

    @Test fun aMonthStaysSmall() {
        var months = emptyMap<String, MonthStat>()
        (1..ListeningMonths.MAX_TITLES + 50).forEach { i ->
            repeat(if (i <= 10) 3 else 1) { months = ListeningMonths.addPlay(months, nov, t("t$i", "Artist ${i % 40}"), newArtist = "Artist $i") }
        }
        val stat = months.getValue("2026-11")
        assertTrue(stat.plays.size <= ListeningMonths.MAX_TITLES)
        assertTrue(stat.tracks.size <= ListeningMonths.KEEP_TRACKS)
        assertTrue("the most played titles keep their details", (1..10).all { "t$it" in stat.tracks })
        assertEquals(ListeningMonths.MAX_NEW_ARTISTS, stat.newArtists.size)
        val closed = ListeningMonths.close(stat)
        assertEquals(ListeningMonths.KEEP_CLOSED, closed.plays.size)
        assertTrue(closed.tracks.keys.all { it in closed.plays })
        assertEquals(stat.ms, closed.ms); assertEquals(stat.hours, closed.hours); assertEquals(stat.newArtists, closed.newArtists)
    }

    @Test fun aMonthSurvivesStorage() {
        var months = ListeningMonths.addTime(emptyMap(), nov, 21, 42_000)
        months = ListeningMonths.addPlay(months, nov, t("um:1", "Nova").copy(addon = "um", artistId = "12", isrc = "FR123"), "Nova")
        months = ListeningMonths.addMix(months, nov)
        val stat = months.getValue("2026-11")
        assertEquals(stat, ListeningMonths.decode(ListeningMonths.encode(stat)))
        assertNull(ListeningMonths.decode("{broken"))
        assertTrue(ListeningMonths.isKey("2026-11")); assertFalse(ListeningMonths.isKey("2026")); assertFalse(ListeningMonths.isKey("tracks"))
        assertEquals(linkedSetOf("nova", "orion"), ListeningMonths.decodeSeen(ListeningMonths.encodeSeen(listOf("nova", "orion"))))
    }

    @Test fun theMonthsRecapRanksTitlesArtistsAndAlbums() {
        val a1 = t("a1", "Nova", "Skyline"); val a2 = t("a2", "Nova", "Skyline"); val b = t("b", "Orion", "Night")
        val gone = t("gone", "Lyra", "Far")
        var months = emptyMap<String, MonthStat>()
        repeat(4) { months = ListeningMonths.addPlay(months, nov, a1) }
        repeat(2) { months = ListeningMonths.addPlay(months, nov, a2) }
        repeat(5) { months = ListeningMonths.addPlay(months, nov, b, if (it == 0) "Orion" else null) }
        months = ListeningMonths.addPlay(months, nov, gone)
        months = ListeningMonths.addTime(months, nov, 20, 30 * 60_000L)
        months = ListeningMonths.addTime(months, nov, 9, 10 * 60_000L)
        months = ListeningMonths.addMix(ListeningMonths.addMix(months, nov), nov)
        // "gone" is no longer in the listening history: the month's own copy still names it.
        val recap = Wrapped.month("2026-11", months, known = listOf(a1, a2, b))
        assertEquals("2026-11", recap.month)
        assertEquals(40L, recap.minutes)
        assertEquals(12, recap.plays)
        assertEquals(4, recap.titles)
        assertEquals(listOf("b", "a1", "a2", "gone"), recap.tracks.map { it.first.id })
        assertEquals(listOf("Nova" to 6, "Orion" to 5, "Lyra" to 1), recap.artists.map { it.name to it.plays })
        assertEquals(3, recap.artistCount)
        assertEquals(listOf("Skyline" to 6, "Night" to 5, "Far" to 1), recap.albums.map { it.title to it.plays })
        assertEquals(20, recap.peakHour)
        assertEquals(listOf("Orion"), recap.newArtists)
        assertEquals(2, recap.ketmixMixes)
        assertNull("no tempo measured, no mood", recap.mood)
        val empty = Wrapped.month("2026-09", months, emptyList())
        assertEquals(0, empty.plays); assertNull(empty.peakHour); assertTrue(empty.tracks.isEmpty())
    }

    @Test fun theYearGathersItsMonthsAndYearlyDataStaysAsItWas() {
        var months = ListeningMonths.addTime(emptyMap(), nov, 22, 60_000)
        months = ListeningMonths.addTime(months, oct, 22, 60_000)
        months = ListeningMonths.addTime(months, oct, 7, 30_000)
        months = ListeningMonths.addPlay(months, nov, t("a", "Nova"), "Nova")
        months = ListeningMonths.addPlay(months, oct, t("b", "Orion"), "Orion")
        months = ListeningMonths.addMix(months, oct)
        months = ListeningMonths.addPlay(months, LocalDate.of(2025, 12, 1), t("c", "Old"), "Old")
        var years = emptyMap<String, DayStat>()
        repeat(30) { years = Wrapped.addPlay(years, "2026", if (it % 2 == 0) "a" else "b") }
        val base = Wrapped.recap("2026", years, emptyMap(), 0L, listOf(t("a", "Nova"), t("b", "Orion")))
        val year = Wrapped.withMonths(base, months)
        assertEquals(base.tracks, year.tracks)
        assertEquals(120_000L, year.clock[22]); assertEquals(30_000L, year.clock[7])
        assertEquals(setOf("Nova", "Orion"), year.newArtists.toSet())
        assertEquals(1, year.ketmixMixes)
        assertEquals(22, year.peakHour)
    }

    @Test fun aMoodNeedsEnoughMeasuredTitles() {
        val tracks = (1..4).map { t("m$it", "A$it") to 1 }
        assertNull(Wrapped.mood(tracks.take(2)) { 120f })
        assertEquals("Entraînant", Wrapped.mood(tracks) { 120f }!!.label)
        // Half tempo reads as the tempo it moves at.
        assertEquals(120, Wrapped.mood(tracks) { 60f }!!.bpm)
        assertEquals("Posé", Wrapped.mood(tracks) { 80f }!!.label)
        assertEquals("Énergique", Wrapped.mood(tracks) { 140f }!!.label)
        assertNull(Wrapped.mood(tracks) { null })
    }

    @Test fun dayPartsShareTheListening() {
        val clock = List(24) { h -> if (h == 21) 300L else if (h == 8) 100L else 0L }
        assertEquals(listOf(.25f, 0f, .75f, 0f), Wrapped.dayParts(clock))
        assertEquals("le soir", Wrapped.dayPart(21)); assertEquals("la nuit", Wrapped.dayPart(2))
    }
}
