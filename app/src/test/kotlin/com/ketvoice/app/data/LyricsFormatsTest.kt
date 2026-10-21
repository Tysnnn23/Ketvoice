package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class LyricsFormatsTest {
    private fun doc(content: String) = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>$content</div></body></tt>"""

    @Test fun parsesTimeUnitsExactly() {
        assertEquals(33522L, LyricsFormats.timeMs("33.522"))
        assertEquals(33522L, LyricsFormats.timeMs("00:33.522"))
        assertEquals(33522L, LyricsFormats.timeMs("33522ms"))
        assertEquals(33522L, LyricsFormats.timeMs("33.522s"))
        assertNull(LyricsFormats.timeMs("bad"))
    }

    @Test fun lineOnlyNeverGetsInventedWords() {
        val line = LyricsFormats.ttml(doc("""<p begin="1" end="5">Hello world</p>""")).single()
        assertEquals(1000L, line.timeMs)
        assertEquals("Hello world", line.text)
        assertTrue(line.words.isEmpty())
        assertFalse(line.syllableTimed)
    }

    @Test fun preservesWordTimingAndSpacesButDoesNotCallItSyllables() {
        val line = LyricsFormats.ttml(doc("""<p begin="1"><span begin="1" end="2">Hello</span> <span begin="2" end="3">world</span>!</p>""")).single()
        assertEquals("Hello world!", line.text)
        assertEquals(1000L, line.words[0].startMs)
        assertEquals(2000L, line.words[0].endMs)
        assertEquals("Hello ", line.words[0].text)
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertFalse(line.syllableTimed)
    }

    @Test fun preservesNativeNestedSyllablesAndSkipsTranslation() {
        val line = LyricsFormats.ttml(doc("""<p begin="1"><span begin="1" end="3"><span begin="1" end="2">Hel</span><span begin="2" end="3">lo</span></span><span ttm:role="x-translation">Bonjour</span></p>""")).single()
        assertEquals("Hello", line.text)
        assertEquals(listOf("Hel", "lo"), line.words.map { it.text })
        assertTrue(line.syllableTimed)
        assertEquals(listOf(1000L, 2000L), line.words.map { it.startMs })
    }

    @Test fun alignmentAddsOnlyExistingPunctuationAndWhitespace() {
        val words = listOf(MusicApi.LyricWord(1000, "Hello"), MusicApi.LyricWord(2000, "world"))
        assertEquals("Hello, world!", LyricsFormats.align("Hello, world!", words).joinToString("") { it.text })
        assertTrue(LyricsFormats.align("Hello different world", words).isEmpty())
        assertEquals(listOf(1000L, 2000L), LyricsFormats.align("Hello world", words).map { it.startMs })
    }

    @Test fun adjacentNativeSubwordSpansAreRecognizedWithoutSplittingText() {
        val line = LyricsFormats.ttml(doc("""<p begin="1"><span begin="1" end="2">Hel</span><span begin="2" end="3">lo</span></p>""")).single()
        assertTrue(line.syllableTimed)
        assertEquals("Hello", line.words.joinToString("") { it.text })
    }

    @Test fun rejectsExternalEntities() {
        assertTrue(LyricsFormats.ttml("""<!DOCTYPE tt [<!ENTITY x SYSTEM "file:///etc/passwd">]><tt><p begin="1">&x;</p></tt>""").isEmpty())
    }

    @Test fun providerSubtitlesAreKeptSeparateFromOriginalLyricsAndTheirTimings() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal">
          <head><metadata><translations><translation xml:lang="fr-FR"><text for="L1">Bonjour le monde</text></translation></translations></metadata></head>
          <body><div><p begin="1" end="3" lrc:key="L1"><span begin="1" end="2">Hello</span> <span begin="2" end="3">world</span></p></div></body>
        </tt>"""
        val line = LyricsFormats.ttml(xml).single()
        assertEquals("Hello world", line.text)
        assertEquals("Bonjour le monde", line.translations["fr-fr"])
        assertEquals(1000L, line.timeMs)
        assertEquals(listOf(1000L, 2000L), line.words.map { it.startMs })
    }
}
