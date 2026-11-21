package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

/** 8.0 duet lyrics: singers read from each format, and the side each line takes. */
class LyricsDuetTest {
    private fun line(text: String, singer: String? = null) = MusicApi.LyricLine(-1L, text, singer = singer)
    private fun sides(lines: List<MusicApi.LyricLine>, title: String, artist: String) = LyricsDuet.sides(lines, title, artist).toList()

    @Test fun ttmlAgentsWithNamesAndGroups() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata>
              <ttm:agent type="person" xml:id="v1"><ttm:name type="full">Nova Lune</ttm:name></ttm:agent>
              <ttm:agent type="person" xml:id="v2"/>
              <ttm:agent type="group" xml:id="v1000"/>
            </metadata></head>
            <body><div>
              <p begin="1" end="2" ttm:agent="v1">First voice</p>
              <p begin="3" end="4" ttm:agent="v2"><span begin="3" end="3.5">Second</span> <span begin="3.5" end="4">voice</span></p>
              <p begin="5" end="6" ttm:agent="v1000">Everyone</p>
            </div><div ttm:agent="v2"><p begin="7" end="8">From the div</p></div></body></tt>"""
        val lines = LyricsFormats.ttml(xml)
        assertEquals(listOf("Nova Lune", "v2", null, "v2"), lines.map { it.singer })
        assertEquals(2, lines[1].words.size)
        assertEquals(listOf(false, true, false, true), sides(lines, "Les heures bleues", "Nova Lune"))
    }

    @Test fun ttmlWithoutAgentsHasNoSinger() {
        val lines = LyricsFormats.ttml("""<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1">Hello</p></div></body></tt>""")
        assertNull(lines.single().singer)
    }

    @Test fun enhancedLrcMarkersAreReadAndRemoved() {
        val lines = MusicApi.parseLrc("[00:01.00]v1: Hello there\n[00:02.00]v2:<00:02.00>Second <00:02.50>voice\n[00:03.00]D: Both of us\n[00:04.00]No marker")
        assertEquals(listOf("Hello there", "Second voice", "Both of us", "No marker"), lines.map { it.text })
        assertEquals(listOf("v1", "v2", null, null), lines.map { it.singer })
        assertEquals(2, lines[1].words.size)
        assertEquals(listOf(false, true, false, false), sides(lines, "Duo", "Someone"))
    }

    @Test fun customJsonAgent() {
        val (_, lines) = CustomLyrics.parse("""[{"startTimeMs":1000,"words":"Hi","agent":"v1"},{"startTimeMs":2000,"words":"Yo","agent":"v2"}]""")
        assertEquals(listOf("v1", "v2"), lines.map { it.singer })
    }

    @Test fun namedSingerMatchingTheArtistStaysLeftEvenWhenNotFirst() {
        val lines = listOf(line("a", "Rihanna"), line("b", "Drake"), line("c", "Rihanna"))
        assertEquals(listOf(true, false, true), sides(lines, "Take Care", "Drake"))
    }

    @Test fun oneSingerOnlyIsNoDuet() {
        assertEquals(listOf(false, false), sides(listOf(line("a", "v1"), line("b", "v1")), "x", "y"))
    }

    @Test fun featuredArtistsFromTitleAndArtist() {
        assertEquals(listOf("Rihanna"), LyricsDuet.featured("Take Care (feat. Rihanna)", "Drake"))
        assertEquals(listOf("Khalid"), LyricsDuet.featured("lovely (with Khalid)", "Billie Eilish feat. Khalid"))
        assertEquals(listOf("Khalid"), LyricsDuet.featured("lovely (with Khalid)", "Billie Eilish"))
        assertEquals(listOf("Lil Wayne", "Future"), LyricsDuet.featured("Love Me ft. Lil Wayne & Future", "Drake"))
        assertEquals(listOf("SZA"), LyricsDuet.featured("Kill Bill", "Kendrick Lamar, SZA"))
        assertEquals("Drake", LyricsDuet.mainArtist("Drake feat. Rihanna"))
        // « Stay With Me » names nobody: « with » counts only inside brackets.
        assertTrue(LyricsDuet.featured("Stay With Me", "Sam Smith").isEmpty())
    }

    @Test fun sectionHeadersNamingTheFeaturedArtistMoveTheirLines() {
        val lines = listOf("[Verse 1: Drake]", "my verse", "[Chorus]", "hook", "[Verse 2: Rihanna]", "her verse", "her second line",
            "[Bridge: Drake & Rihanna]", "together", "[Rihanna:]", "alone again", "(Drake)", "back to me", "(yeah)", "still me").map { line(it) }
        assertEquals(listOf(false, false, false, false, true, true, true, false, false, true, true, false, false, false, false),
            sides(lines, "Take Care (feat. Rihanna)", "Drake"))
    }

    @Test fun parenthesisAtLineStartCountsOnlyForKnownArtists() {
        val lines = listOf("(Rihanna) I am here", "next line", "(ooh) backing", "(Drake) mine").map { line(it) }
        assertEquals(listOf(true, true, true, false), sides(lines, "Take Care (feat. Rihanna)", "Drake"))
    }

    @Test fun neverGuessesWithoutAFeaturedArtistOrAHeader() {
        // Headers naming someone not in the title stay on the main side, and a title without a feature has no duet.
        val lines = listOf("[Verse 2: Somebody Else]", "a", "[Verse 3: Rihanna]", "b").map { line(it) }
        assertEquals(listOf(false, false, false, false), sides(lines, "Take Care", "Drake"))
        assertEquals(listOf(false, false, true, true), sides(lines, "Take Care (feat. Rihanna)", "Drake"))
        assertEquals(listOf(false, false), sides(listOf(line("hello"), line("world")), "Take Care (feat. Rihanna)", "Drake"))
    }

    @Test fun singerSurvivesTheDiskCacheShape() {
        // The cache and the downloads write the singer next to the words; a missing one reads back as null.
        val original = MusicApi.LyricLine(1000, "Hi", singer = "v2")
        assertEquals("v2", original.copy(text = "Hi!").singer)
        assertNull(org.json.JSONObject("{}").optString("singer").ifBlank { null })
    }
}
