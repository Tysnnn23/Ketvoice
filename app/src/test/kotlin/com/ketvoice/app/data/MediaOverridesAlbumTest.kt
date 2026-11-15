package com.ketvoice.app.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MediaOverridesAlbumTest {
    @After fun clear() = MediaOverrides.replaceForTest(emptyList())

    private fun album(key: String, canvas: String) =
        MediaOverrides.decodeList("""{"overrides":[{"kind":"album","key":"$key","label":"x","canvasURL":"$canvas"}]}""")!!.values.first()

    @Test fun whatTheAlbumPageSavedReachesItsTitlesInThePlayer() {
        // The page keys "Pyramide 2" by its album artist; a title carries a featured performer and a longer album name.
        val page = album("pyramide 2|werenoi", "https://cdn.example.org/new.mp4")
        MediaOverrides.replaceForTest(listOf(page))
        val featured = Track("t1", "Pyramide", "Werenoi, Damso", "Pyramide 2 (Deluxe)")
        assertEquals("found by the album's name alone when only one album has it", "https://cdn.example.org/new.mp4", MediaOverrides.canvas(featured))
        // Once the page has shown it, the title belongs to that album whatever it carries.
        val compilation = Track("t2", "Intro", "Various Artists", "Best of")
        assertNull(MediaOverrides.canvas(compilation))
        MediaOverrides.linkAlbum(listOf(compilation), "pyramide 2|werenoi")
        assertEquals("https://cdn.example.org/new.mp4", MediaOverrides.canvas(compilation))
        assertEquals("pyramide 2|werenoi", MediaOverrides.albumKeyFor(compilation))
        // Two albums of the same name: no guess.
        MediaOverrides.replaceForTest(listOf(page, album("pyramide 2|other", "https://cdn.example.org/other.mp4")))
        assertNull(MediaOverrides.albumOf(Track("t3", "X", "Someone", "Pyramide 2")))
        assertEquals("pyramide 2", MediaOverrides.edition("Pyramide 2 (Deluxe) [Remastered]"))
    }

    @Test fun anAnswerThatIsNotTheListChangesNothing() {
        assertNull("a captive portal's page", MediaOverrides.decodeList("<html>Connexion requise</html>"))
        assertNull(MediaOverrides.decodeList("""{"error":"x"}"""))
        assertEquals(0, MediaOverrides.decodeList("""{"overrides":[]}""")!!.size)
    }
}
