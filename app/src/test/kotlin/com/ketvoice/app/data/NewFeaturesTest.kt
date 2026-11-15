package com.ketvoice.app.data

import com.ketvoice.app.player.RadioMix
import org.junit.Assert.*
import org.junit.Test

class NewFeaturesTest {
    private fun t(id: String, title: String, artist: String) = Track(id, title, artist, "")

    @Test fun aRadioMixesArtistsAndNeverRepeats() {
        val similar = (1..12).map { t("s$it", "Song $it", "Other $it") }
        val own = (1..6).map { t("o$it", "Own $it", "Werenoi") }
        val queue = listOf(t("seed", "Own 1", "Werenoi"))
        val next = RadioMix.next(similar, own, queue, { it.artist != "Other 3" }, seed = 7)
        assertEquals(RadioMix.BATCH, next.size)
        assertTrue("never what is already queued", next.none { it.title == "Own 1" })
        assertTrue("never a blocked artist", next.none { it.artist == "Other 3" })
        assertEquals(next.size, next.map { it.id }.toSet().size)
        assertTrue("mostly other artists", next.count { it.artist != "Werenoi" } > next.count { it.artist == "Werenoi" })
        assertTrue(RadioMix.next(emptyList(), emptyList(), queue, { true }, 1).isEmpty())
    }

    @Test fun onlyNewFavouritesAreDownloaded() {
        assertEquals(listOf("b"), FavoritesOffline.added(setOf("a"), setOf("a", "b")))
        assertTrue(FavoritesOffline.added(setOf("a", "b"), setOf("a")).isEmpty())
    }

    @Test fun titlesAreFoundByTheirLyrics() {
        val a = LyricsSearch.Indexed(Track("1", "Pyramide", "Werenoi", ""), LyricsSearch.normalize("J'ai bâti ma pyramide, pierre par pierre"))
        val b = LyricsSearch.Indexed(Track("2", "Autre", "Maes", ""), LyricsSearch.normalize("Pierre et la pyramide du soir"))
        assertEquals("j ai bati ma pyramide pierre par pierre", a.text)
        val words = listOf("pierre", "pyramide")
        val ranked = LyricsSearch.rank(listOf(b, a), LyricsSearch.normalize("Pierre par pierre"), words)
        assertEquals("the words in a row first", "1", ranked.first().track.id)
        assertEquals("then all of the words", listOf("1", "2"), LyricsSearch.rank(listOf(b, a), "pyramide pierre", listOf("pyramide", "pierre")).map { it.track.id }.sorted())
        assertTrue(LyricsSearch.looksLikeLyrics("pierre par pierre")); assertFalse(LyricsSearch.looksLikeLyrics("Werenoi"))
        val found = LyricsSearch.parse("""{"response":{"sections":[{"hits":[{"result":{"title":"Pyramide","primary_artist":{"name":"Werenoi"},"song_art_image_thumbnail_url":"https://x.example/a.jpg"}},{"result":{"title":"Pyramide","primary_artist":{"name":"Werenoi"}}}]}]}}""")
        assertEquals(1, found.size); assertEquals("Pyramide Werenoi", found.first().query)
        assertTrue(LyricsSearch.parse("not json").isEmpty())
    }
}
