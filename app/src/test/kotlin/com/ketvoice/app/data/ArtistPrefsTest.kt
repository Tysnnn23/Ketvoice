package com.ketvoice.app.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ArtistPrefsTest {
    @After fun clear() = ArtistPrefs.replaceForTest()

    @Test fun aBlockedArtistIsLeftOutWhereverItIsCredited() {
        val blocked = setOf(MediaOverrides.normalize("Ninho"))
        assertFalse(ArtistPrefs.allows("Ninho", blocked))
        assertFalse("a feature counts", ArtistPrefs.allows("Maes, Ninho", blocked))
        assertFalse(ArtistPrefs.allows("Werenoi feat. Ninho", blocked))
        assertFalse(ArtistPrefs.allows("Werenoi (avec Ninho)", blocked))
        assertFalse(ArtistPrefs.allows("Gazo & Ninho", blocked))
        assertTrue(ArtistPrefs.allows("Werenoi", blocked))
        assertTrue("a longer name that holds it is someone else", ArtistPrefs.allows("Ninhooo", blocked))
        // A name with "and" in it is blocked as a whole, and does not block its halves.
        val duo = setOf(MediaOverrides.normalize("Simon and Garfunkel"))
        assertFalse(ArtistPrefs.allows("Simon and Garfunkel", duo))
        assertTrue(ArtistPrefs.allows("Paul Simon", duo))
        assertTrue("nothing blocked, everything allowed", ArtistPrefs.allows("Anyone", emptySet()))
        // A blocked name that holds a separator is found again when featured.
        val tyler = setOf(MediaOverrides.normalize("Tyler, The Creator"))
        assertFalse(ArtistPrefs.allows("Kali Uchis, Tyler, The Creator", tyler))
        assertFalse(ArtistPrefs.allows("Tyler, The Creator", tyler))
        assertTrue(ArtistPrefs.allows("Kali Uchis, The Creators", tyler))
    }

    @Test fun blockingAndFavouritesExcludeEachOtherAndAreKept() {
        val maes = ArtistPrefs.Entry("Maes", "UCmaes", "https://example.org/maes.jpg")
        ArtistPrefs.setFavourite(maes, true)
        assertTrue(ArtistPrefs.isFavourite("MAES")); assertFalse(ArtistPrefs.isBlocked("Maes"))
        ArtistPrefs.setBlocked(maes, true)
        assertTrue(ArtistPrefs.isBlocked("Maes")); assertFalse("blocking removes it from the favourites", ArtistPrefs.isFavourite("Maes"))
        assertFalse(ArtistPrefs.allows(Track("t", "SURVIE", "Maes, Ninho", "")))
        ArtistPrefs.setFavourite(maes, true)
        assertFalse("a favourite is unblocked", ArtistPrefs.isBlocked("Maes"))
        val kept = ArtistPrefs.decode(ArtistPrefs.encode(listOf(maes)))
        assertEquals(maes, kept[maes.key])
        assertTrue(ArtistPrefs.decode("not json").isEmpty())
    }
}
