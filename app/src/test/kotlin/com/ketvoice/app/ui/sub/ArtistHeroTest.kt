package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.Album
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test

/** 7.4: the immersive artist hero. */
class ArtistHeroTest {
    @Test fun anUprightPhoneOpensOnThePhotoOverMoreThanHalfTheScreen() {
        val h = ArtistLayout.heroHeight(412f, 915f, wide = false)
        assertEquals(915f * ArtistLayout.PHONE_SHARE, h, 1f)
        assertTrue(h / 915f in .55f..0.60f)
        // A small phone keeps a sensible minimum, a very tall one a cap.
        assertEquals(340f, ArtistLayout.heroHeight(320f, 480f, wide = false), .5f)
        assertTrue(ArtistLayout.heroHeight(412f, 2000f, wide = false) <= 640f)
    }

    @Test fun landscapeWindowsKeepACappedBanner() {
        // Quest and a phone on its side are not "wide" but are landscape: a banner, not a portrait.
        val quest = ArtistLayout.heroHeight(1280f, 720f, wide = false)
        assertTrue(quest <= 720f * .56f + .5f)
        val tablet = ArtistLayout.heroHeight(1194f, 834f, wide = true)
        assertTrue(tablet <= 560f && tablet <= 834f * .56f + .5f)
    }

    @Test fun thePictureFollowsTheScrollMoreSlowlyAndTightens() {
        val (rest, restScale) = ArtistHero.parallax(0f, 1000f, reduced = false)
        assertEquals(0f, rest, 0f); assertEquals(1f, restScale, 0f)
        val (shift, scale) = ArtistHero.parallax(400f, 1000f, reduced = false)
        assertEquals(400f * ArtistHero.PARALLAX, shift, .01f)
        assertTrue(scale > 1f && scale < 1f + ArtistHero.TIGHTEN)
        // Past the hero it stays where it got to; pulling down changes nothing.
        assertEquals(ArtistHero.parallax(1000f, 1000f, false), ArtistHero.parallax(5000f, 1000f, false))
        assertEquals(0f to 1f, ArtistHero.parallax(-200f, 1000f, false))
    }

    @Test fun reducedMotionHasNoParallax() {
        assertEquals(0f to 1f, ArtistHero.parallax(400f, 1000f, reduced = true))
    }

    @Test fun theNameFoldsAwayBeforeTheBarTitleIsIn() {
        assertEquals(1f, ArtistHero.nameAlpha(0f), 0f)
        assertEquals(0f, ArtistHero.nameAlpha(.75f), 0f)
        assertTrue(ArtistHero.nameAlpha(.3f) in .5f..0.6f)
    }

    @Test fun withoutAPhotoTheBestKnownCoverStandsIn() {
        val artist = MusicArtist("a", "Nova", null)
        val tracks = listOf(Track("t0", "A", "Nova", "", coverUrl = ""), Track("t1", "B", "Nova", "", coverUrl = "cover:1"))
        assertEquals("cover:1", ArtistHero.fallbackCover(ArtistPage(artist, "", tracks, emptyList(), emptyList(), "", 1f)))
        val albums = listOf(Album("al", "X", "Nova", coverUrl = "cover:album"))
        assertEquals("cover:album", ArtistHero.fallbackCover(ArtistPage(artist, "", emptyList(), albums, emptyList(), "", 1f)))
        assertNull(ArtistHero.fallbackCover(ArtistPage(artist, "", emptyList(), emptyList(), emptyList(), "", 1f)))
    }
}
