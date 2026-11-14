package com.ketvoice.app.ui

import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MixKind
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.home.ForYouKind
import com.ketvoice.app.ui.home.HomeBlock
import com.ketvoice.app.ui.home.forYouKind
import com.ketvoice.app.ui.home.homeBlockOrder
import com.ketvoice.app.ui.home.isFreshRelease
import com.ketvoice.app.ui.home.learningKey
import com.ketvoice.app.ui.home.mixForHour
import com.ketvoice.app.ui.library.coverGridColumns
import com.ketvoice.app.ui.player.LyricsCascade
import com.ketvoice.app.ui.player.pausedArtworkScale
import com.ketvoice.app.ui.player.scrubThicknessDp
import com.ketvoice.app.ui.studio.StudioRoute
import com.ketvoice.app.ui.studio.studioAlbums
import com.ketvoice.app.ui.studio.studioStart
import com.ketvoice.app.util.MomentCard
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StudioHomeUiLogicTest {
    private val own = Track("kv:upload:11111111-1111-4111-8111-111111111111", "Mine", "Me", "Album")
    private val other = Track("um:42", "Theirs", "Them", "Album")

    @Test fun theStudioOpensWhereItWasAskedFor() {
        assertEquals(StudioRoute.Home, studioStart(null, edit = false))
        assertEquals(StudioRoute.Song(own), studioStart(own, edit = true))
        assertEquals("Lyrics for a title of the catalogue", StudioRoute.Lyrics(other), studioStart(other, edit = true))
        assertEquals(StudioRoute.Lyrics(own), studioStart(own, edit = false))
    }

    @Test fun publicationsAreGroupedByAlbumLargestFirst() {
        fun song(id: Int, artist: String, album: String) = PublicationApi.EditableSong(
            Track("kv:upload:$id", "T$id", artist, album, coverUrl = if (id == 3) "https://c/3.jpg" else null), "r$id")
        val albums = studioAlbums(listOf(song(1, "A", "One"), song(2, "A", "Two"), song(3, "A", "Two"), song(4, "B", "One"), song(5, "A", "")))
        assertEquals(listOf("Two", "One", "One"), albums.map { it.album })
        assertEquals(2, albums.first().songs.size)
        assertEquals("https://c/3.jpg", albums.first().cover)
        assertTrue("A title without an album stays out of albums", albums.none { it.album.isBlank() })
    }

    @Test fun forYouPrefersAPausedTitleThenAFreshReleaseThenAMix() {
        assertEquals(ForYouKind.Resume, forYouKind(resumable = true, freshRelease = true, hasMix = true))
        assertEquals(ForYouKind.NewRelease, forYouKind(false, true, true))
        assertEquals(ForYouKind.Mix, forYouKind(false, false, true))
        assertEquals(ForYouKind.None, forYouKind(false, false, false))
        val today = LocalDate.of(2026, 11, 14)
        assertTrue(isFreshRelease("2026-11-01", today))
        assertFalse(isFreshRelease("2026-10-01", today))
        assertFalse(isFreshRelease("2026-12-01", today))
        assertFalse(isFreshRelease(null, today))
    }

    @Test fun theMixOfTheHourFollowsTheDay() {
        val cards = listOf(MixCard("artist:A", MixKind.Artist, "A", emptyList()), MixCard("repeat", MixKind.OnRepeat, null, emptyList()))
        assertEquals("repeat" to "Votre mix du soir", mixForHour(20, cards)!!.let { it.first.id to it.second })
        assertEquals("artist:A", mixForHour(14, cards)!!.first.id)
        assertEquals("Falls back to the first mix", "artist:A", mixForHour(8, cards)!!.first.id)
        assertEquals("Pour la nuit", mixForHour(-1, cards)!!.second)
        assertNull(mixForHour(9, emptyList()))
    }

    @Test fun shelvesTheListenerOpensMoveUpButTheFirstShelfStays() {
        val plain = homeBlockOrder(5, 3, hasAlbums = true, hasReleases = true)
        assertEquals(HomeBlock.Shelf0, plain.first())
        val learned = homeBlockOrder(5, 3, true, true, mapOf(HomeBlock.Releases.learningKey to 3.0, HomeBlock.Albums.learningKey to 1.0))
        assertEquals(listOf(HomeBlock.Shelf0, HomeBlock.Releases, HomeBlock.Albums), learned.take(3))
        assertEquals(plain.toSet(), learned.toSet())
        assertEquals("Without scores nothing changes from before", homeBlockOrder(9, 3, true), homeBlockOrder(9, 3, true, false, emptyMap()))
    }

    @Test fun aMomentHoldsOneOrTwoNeighbouringLines() {
        assertEquals(listOf(4), MomentCard.toggle(emptyList(), 4))
        assertEquals(listOf(4, 5), MomentCard.toggle(listOf(4), 5))
        assertEquals("A distant line starts a new selection", listOf(9), MomentCard.toggle(listOf(4), 9))
        assertEquals("Never three lines", listOf(6), MomentCard.toggle(listOf(4, 5), 6))
        assertEquals(listOf(5), MomentCard.toggle(listOf(4, 5), 4))
        assertEquals(emptyList<Int>(), MomentCard.toggle(listOf(4), 4))
        assertEquals("First\nSecond", MomentCard.text(listOf(" First ", "", "Second")))
        assertTrue(MomentCard.text(listOf("x".repeat(400))).length <= 180)
        assertEquals(0xFFFFFFFF.toInt(), MomentCard.inkOn(0xFF101820.toInt()))
        assertEquals(0xFF111111.toInt(), MomentCard.inkOn(0xFFF2E6C9.toInt()))
    }

    @Test fun motionValuesStayWithinTheirRange() {
        assertEquals(5f, scrubThicknessDp(0f), 0f)
        assertEquals(12f, scrubThicknessDp(1f), 1e-5f)
        assertEquals(5f, scrubThicknessDp(Float.NaN), 0f)
        assertEquals(1f, pausedArtworkScale(0f), 0f)
        assertEquals(.88f, pausedArtworkScale(1f), 1e-5f)
        assertEquals(0f, LyricsCascade.waveStartDp(0), 0f)
        assertEquals(50f, LyricsCascade.waveStartDp(12), 0f)
        assertEquals(228L, LyricsCascade.waveDelayMs(40))
        assertEquals(2, coverGridColumns(390f))
        assertEquals(4, coverGridColumns(800f))
        assertEquals(6, coverGridColumns(3000f))
    }
}
