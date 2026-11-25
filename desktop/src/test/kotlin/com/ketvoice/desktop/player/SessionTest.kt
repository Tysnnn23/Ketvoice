package com.ketvoice.desktop.player

import com.ketvoice.app.data.Track
import com.ketvoice.desktop.ui.LastPage
import com.ketvoice.desktop.ui.Route
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Near-instant start: what is saved of the last session, and the smart cache's warming list. */
class SessionTest {
    private fun t(i: Int) = Track(id = "yt:$i", title = "Titre $i", artist = "Artiste", album = "Album", durationMs = 180_000,
        coverUrl = "https://img.example.com/$i.jpg", streamUrl = "https://stream.example.com/$i?sig=secret", downloadUrl = "https://x/$i")

    @Test fun theQueueComesBackWithoutStreamAddresses() {
        val saved = Session.Saved((0 until 20).map(::t), 7, 61_000, "playlist:p1")
        val text = Session.encode(saved)
        assertTrue("sig=secret" !in text && "https://x/" !in text, text)
        val back = Session.decode(text)!!
        assertEquals(20, back.tracks.size)
        assertEquals(7, back.index)
        assertEquals("yt:7", back.tracks[back.index].id)
        assertEquals(61_000, back.positionMs)
        assertEquals("playlist:p1", back.page)
        assertEquals("https://img.example.com/7.jpg", back.tracks[7].coverUrl)
        assertNull(back.tracks[7].streamUrl)
    }

    @Test fun aLongQueueKeepsTheTitlesAroundTheCurrentOne() {
        val saved = Session.Saved((0 until 2_000).map(::t), 1_500, 0, null)
        val back = Session.decode(Session.encode(saved))!!
        assertEquals(Session.MAX_QUEUE, back.tracks.size)
        assertEquals("yt:1500", back.tracks[back.index].id)
        assertNull(Session.decode("{\"version\":2}"))
        assertNull(Session.decode("garbage"))
    }

    @Test fun pagesComeBackWhenTheyNeedNothingFetched() {
        assertEquals("settings", LastPage.key(Route.Settings))
        assertEquals("playlist:p1", LastPage.key(Route.Playlist("p1")))
        assertNull(LastPage.key(Route.Search))
        assertEquals(Route.Favorites, LastPage.route("favorites"))
        assertEquals(Route.Playlist("p1"), LastPage.route("playlist:p1") { it == "p1" })
        assertNull(LastPage.route("playlist:gone") { false }, "a deleted playlist does not come back")
        assertNull(LastPage.route("nonsense"))
    }

    @Test fun warmingTakesFavouritesThenPlaylistsOnceEachWithoutDownloads() {
        val list = CacheWarmer.candidates(listOf(t(1), t(2)), listOf(listOf(t(2), t(3), t(4)), listOf(t(5))), downloaded = { it == "yt:4" })
        assertEquals(listOf("yt:1", "yt:2", "yt:3", "yt:5"), list.map { it.id })
        val many = CacheWarmer.candidates((0 until 400).map(::t), emptyList()) { false }
        assertEquals(CacheWarmer.MAX_TITLES, many.size)
        assertEquals(512L shl 20, CacheWarmer.budget(2L shl 30))
    }
}
