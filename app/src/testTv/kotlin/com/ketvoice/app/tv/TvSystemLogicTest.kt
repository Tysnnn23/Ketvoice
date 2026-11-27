package com.ketvoice.app.tv

import com.ketvoice.app.data.LiveStats
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MonthStat
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ketvoice TV 1.1.0's system features, their pure rules: art frame, screensaver, home-screen channel, links, Replay. */
class TvSystemLogicTest {
    private fun t(id: String, album: String = "Album $id", artist: String = "Artiste", cover: String? = "https://img.example/$id.jpg",
                  duration: Long = 200_000L) = Track(id, "Titre $id", artist, album, durationMs = duration, coverUrl = cover)

    // ------------------------------------------------------------------ Art frame

    @Test fun artFrameShowsTheAlbumsPlayedAndLikedMostOncePerPicture() {
        val a1 = t("a1", "Lumières"); val a2 = t("a2", "Lumières")
        val b = t("b", "Minuit"); val c = t("c", "Océan")
        val noCover = t("d", "Sans pochette", cover = null)
        val sameImage = t("e", "Autre", cover = a1.coverUrl)
        val counts = mapOf("a1" to 2, "a2" to 2, "b" to 3, "c" to 1, "d" to 50, "e" to 1)
        val frames = TvArtFramePlan.albums(listOf(a1, a2, b, c, noCover, sameImage), counts, favoriteIds = setOf("c"), cover = { it.coverUrl })
        // « Lumières » (4 plays) before « Océan » (1 play + a favourite = 4, tie broken by key) before « Minuit » (3).
        assertEquals(listOf("Lumières", "Océan", "Minuit"), frames.map { it.title })
        // Two titles of one album are one picture that plays both; the cover-less album and the repeated picture are left out.
        assertEquals(listOf("a1", "a2"), frames.first().tracks.map { it.id })
        assertTrue(frames.none { it.title == "Sans pochette" || it.title == "Autre" })
        assertEquals(TvArtFramePlan.MAX, TvArtFramePlan.albums((1..60).map { t("x$it") }, emptyMap(), emptySet(), { it.coverUrl }).size)
    }

    @Test fun artistsPhotosComeEveryThreeCoversAndStepsWrapAround() {
        val albums = (1..7).map { TvArtFrameItem("al:$it", "https://img/$it", "A$it", "") }
        val artists = (1..3).map { TvArtFrameItem("ar:$it", "https://photo/$it", "P$it", "", artist = true) }
        val mixed = TvArtFramePlan.withArtists(albums, artists)
        assertEquals(listOf("A1", "A2", "A3", "P1", "A4", "A5", "A6", "P2", "A7"), mixed.map { it.title })
        assertEquals(albums, TvArtFramePlan.withArtists(albums, emptyList()))
        assertEquals(0, TvArtFramePlan.next(2, 3))
        assertEquals(2, TvArtFramePlan.previous(0, 3))
        assertEquals(0, TvArtFramePlan.next(0, 0))
        assertEquals(20_000L, TvArtFramePlan.HOLD_MS)
        assertEquals(0, TvArtFramePlan.fadeMs(TvTier.Full, reduced = true))
        assertTrue(TvArtFramePlan.fadeMs(TvTier.Light, false) < TvArtFramePlan.fadeMs(TvTier.Balanced, false))
        // 70 % of a 540 dp screen: a 378 dp picture, decoded at 760 px on a 1080p TV.
        assertEquals(378f, TvArtFramePlan.pictureDp(540f), 1f)
        val music = TvArtFramePlan.music(listOf(TvArtFrameItem("k", "u", "", "", tracks = listOf(t("1"), t("2"))),
            TvArtFrameItem("l", "v", "", "", tracks = listOf(t("2"), t("3")))))
        assertEquals(listOf("1", "2", "3"), music.map { it.id })
    }

    // ------------------------------------------------------------------ Screensaver

    @Test fun theScreensaverShowsWhatPlaysElseTheGallery() {
        val track = t("p")
        assertEquals(TvDreamScene.Playing(track), TvDreamPlan.scene(track, playing = true))
        assertEquals(TvDreamScene.Gallery, TvDreamPlan.scene(track, playing = false))
        assertEquals(TvDreamScene.Gallery, TvDreamPlan.scene(null, playing = true))
    }

    @Test fun theScreensaverWakesOncePerMinuteAndDriftsOverTheWholeScreen() {
        assertEquals(60_000L, TvDreamPlan.untilNextMinute(120_000L))
        assertEquals(15_000L, TvDreamPlan.untilNextMinute(165_000L))
        // A wake-up right on the edge still waits a second, never zero.
        assertEquals(1_000L, TvDreamPlan.untilNextMinute(179_999L))
        val path = (0L until 120L).map(TvDreamPlan::drift)
        path.forEach { (x, y) -> assertTrue(x in 0f..1f && y in 0f..1f) }
        // Every minute it moves, and over two hours it sweeps (almost) the whole room, across and down.
        path.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        assertTrue(path.maxOf { it.first } - path.minOf { it.first } > .9f)
        assertTrue(path.maxOf { it.second } - path.minOf { it.second } > .9f)
        // No quarter of the screen is left out.
        val quarters = path.map { (x, y) -> (if (x < .5f) 0 else 1) + (if (y < .5f) 0 else 2) }.toSet()
        assertEquals(setOf(0, 1, 2, 3), quarters)
        assertEquals(0, TvDreamPlan.driftMs(TvTier.Light, reduced = false))
        assertEquals(0, TvDreamPlan.driftMs(TvTier.Full, reduced = true))
    }

    @Test fun theClockFollowsTheTvsFormat() {
        assertEquals("09:05", TvDreamPlan.clock(9, 5, is24Hour = true))
        assertEquals("21:40", TvDreamPlan.clock(21, 40, is24Hour = true))
        assertEquals("9:05 PM", TvDreamPlan.clock(21, 5, is24Hour = false))
        assertEquals("12:00 AM", TvDreamPlan.clock(0, 0, is24Hour = false))
        assertEquals("12:30 PM", TvDreamPlan.clock(12, 30, is24Hour = false))
    }

    // ------------------------------------------------------------------ Links

    @Test fun homeScreenLinksOpenTheirPagesAgain() {
        val routes = listOf(TvRoute.Home, TvRoute.ArtFrame, TvRoute.Replay("2026-11"), TvRoute.Replay(null),
            TvRoute.Collection("playlist", "p 1/é&x", "Été & co", "https://img.example/a b.jpg"),
            TvRoute.Collection("mix", "onrepeat", "En boucle"), TvRoute.Artist("UC123", "Nova Lane"))
        routes.forEach { route -> assertEquals(route.toString(), TvLink.Open(route), TvLinks.parse(TvLinks.of(route))) }
        assertEquals(TvLink.Resume("qa:1"), TvLinks.parse(TvLinks.resume("qa:1")))
        assertEquals(TvLink.Resume(null), TvLinks.parse(TvLinks.resume(null)))
        assertEquals(TvLink.Resume("qa:1", 42_000L), TvLinks.parse(TvLinks.resume("qa:1", 42_000L)))
        assertEquals(TvLink.Resume("qa:1"), TvLinks.parse("ketvoice://tv/resume?track=qa%3A1&at=-5"))
        assertEquals("ketvoice://tv/resume?track=yt%3Aabc", TvLinks.resume("yt:abc"))
        // Other links of the app, other hosts and broken ones are not Ketvoice TV's.
        assertNull(TvLinks.parse("ketvoice://listen/abc"))
        assertNull(TvLinks.parse("https://tv/home"))
        assertNull(TvLinks.parse("ketvoice://tv/collection?kind=playlist"))
        assertNull(TvLinks.parse("ketvoice://tv/nowhere"))
        assertNull(TvLinks.parse("::"))
        assertNull(TvLinks.of(TvRoute.NowPlaying))
    }

    // ------------------------------------------------------------------ Channel

    @Test fun theChannelHoldsPlaylistsThenMixesThenAlbumsWithWebPicturesOnly() {
        val playlists = TvRows.libraryCards(listOf(t("f")), emptyList(), emptyList(), (1..8).map { i ->
            LocalPlaylist(id = "p$i", name = "Liste $i", entries = listOf(PlaylistEntry(title = "x", artist = "y", track = t("pt$i"))),
                coverUrl = if (i == 2) "file:///data/cover.jpg" else null)
        }, { it.coverUrl })
        val albums = TvRows.localAlbums((1..10).map { t("al$it") }, { it.coverUrl })
        val mix = TvItem("mix:onrepeat", TvItemKind.Mix, "En boucle", "Ce que vous écoutez le plus", "https://img/m",
            TvRoute.Collection("mix", "onrepeat", "En boucle"), appText = true)
        val programs = TvChannelPlan.programs(playlists, listOf(mix), albums) { "EN:$it" }
        // The favourites card is not a programme; « Liste 2 » has only a file cover the home screen could not read.
        assertTrue(programs.none { it.key == "favorites" || it.key == "pl:p2" })
        assertEquals(listOf("pl:p1", "pl:p3", "pl:p4", "pl:p5", "pl:p6"), programs.take(5).map { it.key })
        assertEquals(TvProgramKind.Mix, programs[5].kind)
        assertEquals("EN:En boucle", programs[5].title)
        assertEquals("Liste 1", programs[0].title)
        assertEquals(TvChannelPlan.ALBUMS, programs.count { it.kind == TvProgramKind.Album })
        assertTrue(programs.size <= TvChannelPlan.MAX_PROGRAMS)
        assertEquals(TvLink.Open(TvRoute.Collection("playlist", "p1", "Liste 1", "https://img.example/pt1.jpg")), TvLinks.parse(programs[0].link))
        assertTrue(TvChannelPlan.weight(0, 10) > TvChannelPlan.weight(9, 10))
    }

    @Test fun channelUpdatesRemoveRewriteAddAndLeaveWhatTheListenerRemoved() {
        fun spec(k: String) = TvProgramSpec(k, TvProgramKind.Playlist, k, "", "https://img/$k", "ketvoice://tv/home")
        val existing = listOf(TvExistingProgram(1, "a", true), TvExistingProgram(2, "b", false), TvExistingProgram(3, "gone", true),
            TvExistingProgram(4, "a", true))
        val ops = TvChannelPlan.ops(existing, listOf(spec("a"), spec("b"), spec("new")))
        assertEquals(listOf(3L, 4L), ops.delete)
        assertEquals(listOf(1L), ops.update.map { it.first })
        // « b » was removed from the home screen by the listener: neither rewritten nor added again.
        assertEquals(listOf("new"), ops.insert.map { it.key })
        assertTrue(TvChannelPlan.ops(emptyList(), emptyList()).isEmpty)
        assertEquals(listOf(1L, 3L, 4L), TvChannelPlan.ops(existing.filter { it.browsable }, emptyList()).delete)
    }

    @Test fun continueListeningIsTheTitlePlayingOrTheLastOne() {
        val playing = t("now", duration = 180_000L)
        val last = t("last")
        val spec = TvChannelPlan.watchNext(playing, 61_000L, last) { it.coverUrl }!!
        assertEquals("continue:now", spec.key)
        assertEquals(61_000L, spec.positionMs)
        assertEquals(TvLink.Resume("now", 61_000L), TvLinks.parse(spec.link))
        assertEquals(180_000L, TvChannelPlan.watchNext(playing, 999_000L, last) { null }!!.positionMs)
        assertNull(TvChannelPlan.watchNext(playing, 0L, null) { "file:///x.jpg" }!!.poster)
        val fromHistory = TvChannelPlan.watchNext(null, 50_000L, last) { it.coverUrl }!!
        assertEquals("last", fromHistory.trackId)
        assertEquals(0L, fromHistory.positionMs)
        assertNull(TvChannelPlan.watchNext(null, 0L, null) { null })
    }

    @Test fun continueListeningKeepsOneEntryAndRespectsARemovedTitle() {
        val spec = TvChannelPlan.watchNext(t("x"), 0L, null) { null }
        val (delete, write) = TvChannelPlan.watchNextOps(listOf(TvExistingProgram(5, "continue:old", true),
            TvExistingProgram(6, "continue:x", true), TvExistingProgram(7, "other-app-like", true)), spec)
        assertEquals(listOf(5L), delete)
        assertEquals(6L, write!!.updateId)
        // Removed by the listener: the same title stays away; another title comes as a new entry.
        assertNull(TvChannelPlan.watchNextOps(listOf(TvExistingProgram(6, "continue:x", false)), spec).second)
        val other = TvChannelPlan.watchNext(t("y"), 0L, null) { null }
        val (gone, fresh) = TvChannelPlan.watchNextOps(listOf(TvExistingProgram(6, "continue:x", false)), other)
        assertEquals(listOf(6L), gone)
        assertNull(fresh!!.updateId)
        assertEquals(listOf(6L) to null, TvChannelPlan.watchNextOps(listOf(TvExistingProgram(6, "continue:x", true)), null))
    }

    // ------------------------------------------------------------------ Replay

    private fun recap(month: String? = "2026-11", tracks: Int = 6, clock: List<Long> = List(24) { if (it == 21) 9L else 1L }) =
        Wrapped.Recap("2026", 1_234, 300, 80, listOf(Wrapped.Artist("Nova Lane", 40, null), Wrapped.Artist("Kael", 20, null)),
            (1..tracks).map { t("r$it", artist = if (it == 3) "Nova Lane" else "Kael") to (10 - it) }, month = month, clock = clock)

    @Test fun replaySlidesFollowWhatTheRecapKnows() {
        val full = TvReplayData(recap(), genres = listOf(LiveStats.GenreRow("Pop", 10, 60), LiveStats.GenreRow("Rap", 6, 40)))
        assertEquals(TvSlide.entries.toList(), TvReplayPlan.slides(full))
        val bare = TvReplayData(recap(tracks = 0, clock = emptyList()).copy(artists = emptyList()))
        assertEquals(listOf(TvSlide.Intro, TvSlide.Minutes, TvSlide.Summary), TvReplayPlan.slides(bare))
        assertTrue(TvReplayData(recap().copy(plays = 0, minutes = 0)).isEmpty)
        assertEquals(1, TvReplayPlan.next(0, 3)); assertEquals(2, TvReplayPlan.next(2, 3)); assertEquals(0, TvReplayPlan.previous(0))
    }

    @Test fun eachSlidePlaysATopTitleTheArtistsSlideTheTopArtists() {
        val r = recap()
        val slides = TvSlide.entries.toList()
        val clips = TvReplayPlan.clips(slides, r)
        assertEquals("r1", clips[slides.indexOf(TvSlide.Intro)]!!.id)
        assertEquals("r1", clips[slides.indexOf(TvSlide.Summary)]!!.id)
        assertEquals("r3", clips[slides.indexOf(TvSlide.Artists)]!!.id)
        assertEquals("r2", clips[slides.indexOf(TvSlide.Minutes)]!!.id)
        assertTrue(TvReplayPlan.clips(slides, recap(tracks = 0)).all { it == null })
    }

    @Test fun replayNumbersClockAndPeriod() {
        assertEquals(20L, TvReplayPlan.hours(1_234))
        val bars = TvReplayPlan.clockBars(List(24) { if (it == 21) 10L else 5L })
        assertEquals(1f, bars[21]); assertEquals(.5f, bars[3])
        assertEquals(List(24) { 0f }, TvReplayPlan.clockBars(emptyList()))
        val today = java.time.LocalDate.of(2026, 11, 27)
        assertEquals(java.time.LocalDate.of(2026, 10, 31), TvReplayPlan.dayFor("2026-10", today))
        assertEquals(today, TvReplayPlan.dayFor("2026-11", today))
        assertEquals(today, TvReplayPlan.dayFor(null, today))
    }

    @Test fun theExtrasShelfOffersReplayWrappedAndTheArtFrame() {
        val stats = mapOf("2026-11" to MonthStat(ms = 60_000, plays = mapOf("a" to 2)), "2026-10" to MonthStat(ms = 1, plays = mapOf("b" to 1)))
        val frames = listOf("https://img/2026-11", "https://frame/1", "https://frame/2")
        val shelf = TvExtras.shelf(listOf("2026-11", "2026-10"), stats, "2026", { "Novembre" }, { "https://img/$it" }, frames)!!
        assertEquals(listOf("replay:2026-11", "wrapped:2026", "art-frame"), shelf.items.map { it.key })
        // Three cards, three different pictures: Wrapped's (the same month's cover) and the frame's give way to the next ones.
        assertEquals(listOf("https://img/2026-11", "https://frame/1", "https://frame/2"), shelf.items.map { it.image })
        assertEquals(TvRoute.Replay("2026-11"), shelf.items[0].route)
        assertEquals(TvRoute.Replay(null), shelf.items[1].route)
        assertEquals(TvRoute.ArtFrame, shelf.items[2].route)
        assertNull(TvExtras.shelf(emptyList(), emptyMap(), "2026", { it }, { null }, frames.take(2)))
        // Last year's plays only: no Wrapped of this year.
        assertEquals(listOf("replay:2025-12"), TvExtras.shelf(listOf("2025-12"), mapOf("2025-12" to MonthStat(plays = mapOf("a" to 1))),
            "2026", { it }, { null }, emptyList())!!.items.map { it.key })
        val rows = listOf(TvShelf("a", "", emptyList()), TvShelf("b", "", emptyList()), TvShelf("c", "", emptyList()))
        assertEquals(listOf("a", "b", "x", "c"), TvExtras.placeIn(rows, TvShelf("x", "", emptyList())).map { it.key })
        assertEquals(listOf("x"), TvExtras.placeIn(emptyList(), TvShelf("x", "", emptyList())).map { it.key })
        assertEquals(rows, TvExtras.placeIn(rows, null))
    }

    @Test fun artFrameAndReplayAreFullScreenPagesOfTheStack() {
        assertTrue(TvRoute.ArtFrame.immersive)
        assertTrue(TvRoute.Replay(null).immersive)
        assertTrue(TvRoute.NowPlaying.immersive)
        assertFalse(TvRoute.Home.immersive)
        val nav = TvNavigator()
        nav.open(TvRoute.ArtFrame)
        assertEquals(TvRoute.ArtFrame, nav.current)
        assertTrue(nav.back())
        assertEquals(TvRoute.Home, nav.current)
        assertNotEquals(TvRoute.Replay("2026-11").key, TvRoute.Replay(null).key)
    }
}
