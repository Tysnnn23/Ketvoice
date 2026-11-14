package com.ketvoice.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StudioAndHomeDataTest {
    @Test fun canvasSourcesAreRecognised() {
        assertEquals(StudioCanvas.Source.Spotify("4uLU6hMCjMI75M1A2tKUQC"),
            StudioCanvas.classify("https://open.spotify.com/intl-fr/track/4uLU6hMCjMI75M1A2tKUQC?si=abc"))
        assertEquals(StudioCanvas.Source.Spotify("4uLU6hMCjMI75M1A2tKUQC"), StudioCanvas.classify("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(StudioCanvas.Source.Link("https://cdn.example.com/a.mp4"), StudioCanvas.classify("  https://cdn.example.com/a.mp4 "))
        assertNull("Plain HTTP is refused", StudioCanvas.classify("http://cdn.example.com/a.mp4"))
        assertNull(StudioCanvas.classify("https://user:pass@cdn.example.com/a.mp4"))
        assertNull(StudioCanvas.classify("not a link"))
    }

    @Test fun onlyAnMp4HeaderIsAccepted() {
        assertTrue(StudioCanvas.isMp4(byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())))
        assertFalse(StudioCanvas.isMp4("<html>".toByteArray()))
        assertFalse(StudioCanvas.isMp4(ByteArray(3)))
    }

    @Test fun anApiAnswerNamesItsVideo() {
        assertEquals("https://v.example/c.mp4", StudioCanvas.videoUrlIn(JSONObject("""{"data":{"canvasUrl":"https://v.example/c.mp4","url":"https://x/y"}}""")))
        assertEquals("https://v.example/a.mp4", StudioCanvas.videoUrlIn(JSONObject("""{"items":[{"name":"x"},{"video":{"src":"https://v.example/a.mp4"}}]}""")))
        assertEquals("Only an MP4 is taken from an unnamed field", "https://v.example/b.mp4?t=1",
            StudioCanvas.videoUrlIn(JSONObject("""{"a":"https://site.example/page","b":"https://v.example/b.mp4?t=1"}""")))
        assertNull(StudioCanvas.videoUrlIn(JSONObject("""{"canvas":"http://insecure.example/a.mp4"}""")))
        assertNull(StudioCanvas.videoUrlIn(JSONObject("{}")))
    }

    @Test fun canvasChecksBlockOnlyWhatTheServerRefuses() {
        val good = StudioCanvas.checks(3 * 1024 * 1024, 6_000, 720, 1280, mp4 = true)
        assertTrue(StudioCanvas.canSend(good))
        assertTrue(good.all { it.level == StudioCanvas.Level.Ok })
        assertEquals("Poids : 3.0 Mo", good[1].label)
        val wide = StudioCanvas.checks(1024, 40_000, 1920, 1080, mp4 = true)
        assertTrue("Long and landscape are warnings", StudioCanvas.canSend(wide))
        assertEquals(2, wide.count { it.level == StudioCanvas.Level.Warning })
        assertFalse(StudioCanvas.canSend(StudioCanvas.checks(StudioCanvas.MAX_BYTES + 1, 5_000, 0, 0, mp4 = true)))
        assertFalse(StudioCanvas.canSend(StudioCanvas.checks(1024, 5_000, 0, 0, mp4 = false)))
        assertFalse(StudioCanvas.canSend(StudioCanvas.checks(0, 5_000, 0, 0, mp4 = true)))
    }

    @Test fun aRefusedSessionIsToldApartFromANetworkFailure() {
        assertTrue(PublisherAccess.rejectedSession("Invalid Refresh Token: Refresh Token Not Found"))
        assertTrue(PublisherAccess.rejectedSession("Ce compte ne peut pas publier"))
        assertFalse(PublisherAccess.rejectedSession("Service de publication indisponible"))
        assertFalse(PublisherAccess.rejectedSession(null))
    }

    private fun track(id: String, artist: String) = Track(id, "Title $id", artist, "Album")

    @Test fun aWeekAddsUpTimeAndPlaysOfTheLastSevenDays() {
        val today = LocalDate.of(2026, 11, 14)
        var days = emptyMap<String, DayStat>()
        days = ListeningWeek.addTime(days, today, 60_000)
        days = ListeningWeek.addTime(days, today.minusDays(6), 30_000)
        days = ListeningWeek.addTime(days, today.minusDays(7), 999_000)
        repeat(3) { days = ListeningWeek.addPlay(days, today, "a") }
        days = ListeningWeek.addPlay(days, today.minusDays(2), "b")
        days = ListeningWeek.addPlay(days, today.minusDays(2), "c")
        val known = listOf(track("a", "Nova"), track("b", "Orion"), track("c", "Orion"))
        val week = ListeningWeek.summary(days, today, known)
        assertEquals("The eighth day is outside the week", 90_000L, week.listenedMs)
        assertEquals(5, week.plays)
        assertEquals("a", week.topTrack?.id)
        assertEquals("Nova", week.topArtist)
        assertEquals(days, ListeningWeek.decode(ListeningWeek.encode(days)))
    }

    @Test fun onlyTwoWeeksAreKept() {
        val today = LocalDate.of(2026, 11, 14)
        val old = ListeningWeek.addTime(emptyMap(), today.minusDays(20), 1_000)
        val next = ListeningWeek.addTime(old, today, 1_000)
        assertEquals(listOf("2026-11-14"), next.keys.toList())
        assertTrue(ListeningWeek.decode("{\"nonsense\":1,\"2026-11-14\":{\"ms\":-5}}").getValue("2026-11-14").ms == 0L)
        assertTrue(ListeningWeek.decode("broken").isEmpty())
    }

    @Test fun homeLearnsWhatIsOpenedAndForgetsSlowly() {
        val once = HomeLearning.opened(emptyMap(), "block:albums")
        assertEquals(1.0, once.getValue("block:albums"), 1e-9)
        assertEquals(.9, HomeLearning.fade(1.0, 1), 1e-9)
        val stored = HomeLearning.encode(mapOf("block:albums" to 2.0, "block:releases" to .05), day = 100)
        val read = HomeLearning.decode(stored, today = 102)
        assertEquals(2.0 * .81, read.getValue("block:albums"), 1e-9)
        assertFalse("A faded habit is forgotten", "block:releases" in read)
        assertTrue(HomeLearning.decode("garbage", 1).isEmpty())
        assertEquals(50.0, (1..80).fold(emptyMap<String, Double>()) { s, _ -> HomeLearning.opened(s, "k") }.getValue("k"), 1e-9)
    }

    @Test fun newReleasesAreRecentDistinctAndNewestFirst() {
        val today = LocalDate.of(2026, 11, 14)
        fun album(id: String, date: String?) = CatalogAlbum(id, "A$id", "Nova", null, date)
        val shown = HomeReleases.newest(listOf(album("1", "2026-10-01"), album("2", "2026-11-10"), album("2", "2026-11-10"),
            album("3", "2024-01-01"), album("4", null), album("5", "2027-05-01")), today)
        assertEquals(listOf("2", "1"), shown.map { it.id })
    }
}
