package com.ketvoice.app.tv

import com.ketvoice.app.data.CatalogAlbum
import com.ketvoice.app.data.CatalogChart
import com.ketvoice.app.data.HomeShelf
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MixKind
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows of Home and Library, from the phone's own data (pure, no Android). */
class TvRowsTest {
    private fun t(i: Int, artist: String = "Artiste $i", album: String = "Album $i") =
        Track("id$i", "Titre $i", artist, album, coverUrl = "https://img/$i.jpg")

    @Test fun homeRowsFollowThePhonesHomeAndLeaveOutEmptyOnes() {
        val recent = (1..30).map { t(it) } + t(1) // a title heard twice is shown once
        val rows = TvRows.home(TvRows.HomeInput(
            recent = recent,
            favorites = listOf(t(2)),
            playlists = listOf(LocalPlaylist("p1", "Soirée", listOf(PlaylistEntry(title = "x", artist = "y", track = t(3)))),
                LocalPlaylist("p2", "Vide")),
            mixes = listOf(MixCard("artist:Nova", MixKind.Artist, "Nova", listOf("https://img/m.jpg"))),
            releases = listOf(CatalogAlbum("42", "Nouvel album", "Nova", "https://img/a.jpg")),
            feed = listOf(HomeShelf("Pour vous", "", listOf(t(5)))),
            chart = CatalogChart(listOf(t(7)), emptyList(), emptyList()),
        ))
        assertEquals(listOf("recent", "library", "mixes", "releases", "feed:0:Pour vous", "chart"), rows.map { it.key })
        val recentRow = rows.first()
        assertEquals("Écoutés récemment", recentRow.title)
        assertEquals("rows are bounded", TvRows.ROW_MAX, recentRow.items.size)
        assertEquals(recentRow.items.size, recentRow.items.map { it.key }.toSet().size)
        // A title plays its row from itself.
        val third = recentRow.items[2]
        assertEquals(TvItemKind.Track, third.kind)
        assertEquals(2, third.index)
        assertEquals("id3", third.queue[third.index].id)
        // Favourites and the playlist that has titles; the empty playlist is left out.
        val library = rows[1].items
        assertEquals(listOf("favorites", "history", "pl:p1"), library.map { it.key })
        assertTrue(library.first().appText)
        assertEquals(TvRoute.Collection("playlist", "p1", "Soirée", "https://img/3.jpg"), library.last().route)
        assertEquals(TvRoute.Collection("album", "42", "Nouvel album", "https://img/a.jpg"), rows[3].items.single().route)
        assertEquals("Mix Nova", rows[2].items.single().title)
    }

    @Test fun aFreshTvWithoutHistoryStillGetsTheCatalogue() {
        val rows = TvRows.home(TvRows.HomeInput(chart = CatalogChart(listOf(t(1), t(2)),
            listOf(CatalogAlbum("9", "A", "B", null)), emptyList())))
        assertEquals(listOf("chart", "chart-albums"), rows.map { it.key })
        assertTrue(TvRows.home(TvRows.HomeInput()).isEmpty())
    }

    @Test fun libraryGroupsTheListenersOwnAlbumsAndArtists() {
        val a = t(1, "Nova", "Été")
        val b = Track("id2", "Autre", "Nova", "Été", coverUrl = null)
        val c = t(3, "Kael", "Nuit")
        val albums = TvRows.localAlbums(listOf(a, b, c, a), { it.coverUrl })
        assertEquals(listOf("Été", "Nuit"), albums.map { it.title })
        assertEquals(TvRoute.Collection("localAlbum", "été|nova", "Été", "https://img/1.jpg"), albums.first().route)
        val rows = TvRows.library(listOf(a), listOf(c), listOf(a, b), listOf(a, b, c), emptyList(),
            PersonalMixes.topArtists(listOf(a, b, c), mapOf("id1" to 3, "id3" to 1), emptySet()), emptyMap(), { it.coverUrl })
        assertEquals(listOf("collections", "albums", "artists", "favorite-tracks", "download-tracks"), rows.map { it.key })
        val artists = rows.first { it.key == "artists" }.items
        assertEquals("Nova", artists.first().title)
        assertEquals(TvItemKind.Artist, artists.first().kind)
    }

    @Test fun countsReadAsTheirFrenchSource() {
        assertEquals("1 titre", TvRows.count(1))
        assertEquals("12 titres", TvRows.count(12))
    }

    @Test fun tinyBlurSoftensButKeepsTheColours() {
        val w = 6; val h = 1
        val pixels = intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        TinyBlur.blur(pixels, w, h, 1)
        val reds = pixels.map { (it shr 16) and 0xff }
        assertEquals(0, reds[0])
        assertEquals(255, reds[5])
        assertTrue("the edge is softened", reds[2] in 1..254 && reds[3] in 1..254)
        assertTrue(pixels.all { it ushr 24 == 0xff })
        // A flat colour stays the same.
        val flat = IntArray(9) { 0xFF336699.toInt() }
        TinyBlur.blur(flat, 3, 3, 2)
        assertTrue(flat.all { it == 0xFF336699.toInt() })
    }

    @Test fun picturesAreDecodedAtTheirShownSize() {
        // A 148 dp card on a 1080p TV (xhdpi, 2.0): 296 px, rounded up to 8.
        assertEquals(296, TvImages.decodePx(148f, 2f))
        // The same card on a 4K interface (xxxhdpi, 4.0).
        assertEquals(592, TvImages.decodePx(148f, 4f))
        assertEquals(8, TvImages.decodePx(.1f, 1f))
        assertFalse(TvImages.decodePx(147f, 2f) % 8 != 0)
    }

    @Test fun keyboardTypesAndErases() {
        assertEquals("ab", TvKeys.type("a", "b"))
        assertEquals("a", TvKeys.backspace("ab"))
        assertEquals("", TvKeys.backspace(""))
        assertEquals("a ", TvKeys.space("a"))
        assertEquals("a ", TvKeys.space("a "))
        assertEquals("", TvKeys.space(""))
        assertEquals(TvKeys.MAX, TvKeys.type("x".repeat(TvKeys.MAX), "y").length)
        assertEquals(40, TvKeys.letters.size)
        assertEquals(0, TvKeys.letters.size % TvKeys.COLUMNS)
    }

    @Test fun timesReadLikeAPlayer() {
        assertEquals("3:05", TvTime.format(185_000))
        assertEquals("1:02:10", TvTime.format(3_730_000))
        assertEquals("0:00", TvTime.format(-5))
    }
}
