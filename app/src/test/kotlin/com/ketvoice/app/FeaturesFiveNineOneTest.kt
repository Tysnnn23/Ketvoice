package com.ketvoice.app

import com.ketvoice.app.automix.DeckAutomation
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.data.CustomLyrics
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.CoverColors
import org.junit.Assert.*
import org.junit.Test

class FeaturesFiveNineOneTest {
    // ---------------------------------------------------------------- custom lyrics formats
    @Test fun lrcWithSeveralTimesPerLineAndMetadataIsRead() {
        val (format, lines) = CustomLyrics.parse("[ar:Nova]\n[ti:Test]\n[00:12.50][01:10.00]Chorus line\n[00:05.00]First line\n")
        assertEquals("lrc", format)
        assertEquals(listOf(5_000L, 12_500L, 70_000L), lines.map { it.timeMs })
        assertEquals("Chorus line", lines.last().text)
    }
    @Test fun enhancedLrcKeepsWordTimes() {
        val (_, lines) = CustomLyrics.parse("[00:01.00]<00:01.00>Hello <00:01.50>world")
        assertEquals("Hello world", lines.single().text); assertEquals(2, lines.single().words.size)
    }
    @Test fun srtAndVttCuesAreRead() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nFirst <i>line</i>\n\n2\n00:00:04,000 --> 00:00:05,000\nSecond\nrow\n"
        val (f1, a) = CustomLyrics.parse(srt)
        assertEquals("srt", f1); assertEquals(listOf(1_500L, 4_000L), a.map { it.timeMs }); assertEquals("First line", a[0].text)
        assertEquals("Second row", a[1].text)
        val (f2, b) = CustomLyrics.parse("WEBVTT\n\n00:02.250 --> 00:03.000\nHi there\n")
        assertEquals("vtt", f2); assertEquals(2_250L, b.single().timeMs)
    }
    @Test fun assKaraokeGivesSyllableTimes() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,{\\k50}Hel{\\k50}lo\n"
        val (format, lines) = CustomLyrics.parse(ass)
        assertEquals("ass", format)
        val line = lines.single()
        assertEquals(10_000L, line.timeMs); assertEquals("Hello", line.text)
        assertEquals(listOf(10_000L, 10_500L), line.words.map { it.startMs }); assertTrue(line.syllableTimed)
    }
    @Test fun jsonShapesOfLyricsServicesAreRead() {
        val spotify = """{"lyrics":{"lines":[{"startTimeMs":"1000","words":"One"},{"startTimeMs":"2500","words":"Two"}]}}"""
        val (f, a) = CustomLyrics.parse(spotify)
        assertEquals("json", f); assertEquals(listOf(1_000L, 2_500L), a.map { it.timeMs })
        val richsync = """[{"ts":3.5,"te":5.0,"x":"Hey you","l":[{"c":"Hey","o":0},{"c":" ","o":0.3},{"c":"you","o":0.4}]}]"""
        val (_, b) = CustomLyrics.parse(richsync)
        assertEquals(3_500L, b.single().timeMs); assertEquals("Hey you", b.single().text); assertEquals(3_900L, b.single().words.last().startMs)
    }
    @Test fun plainTextAndTtmlAndYrcAreRecognised() {
        val (f1, plain) = CustomLyrics.parse("Line one\n\nLine two\n")
        assertEquals("txt", f1); assertEquals(listOf(-1L, -1L), plain.map { it.timeMs })
        val ttml = """<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:01.000" end="00:02.000">Hello</p></div></body></tt>"""
        val (f2, t) = CustomLyrics.parse(ttml)
        assertEquals("ttml", f2); assertEquals("Hello", t.single().text)
        val (f3, y) = CustomLyrics.parse("[1000,2000](1000,500,0)Hel(1500,500,0)lo\n")
        assertEquals("yrc", f3); assertEquals("Hello", y.single().text)
    }
    @Test fun latin1AndUtf16FilesAreDecoded() {
        assertEquals("é", CustomLyrics.decode(byteArrayOf(0xE9.toByte())))
        assertEquals("hi", CustomLyrics.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0, 'i'.code.toByte(), 0)))
    }

    // ---------------------------------------------------------------- cover colours
    private val grey = 0xFF808080.toInt()
    @Test fun aColourfulCoverOnAGreyGroundIsNotGrey() {
        val orange = 0xFFFF8C1A.toInt()
        val r = CoverColors.analyze(IntArray(700) { grey } + IntArray(300) { orange })!!
        assertTrue(r.colourful); assertEquals(orange, r.base)
    }
    @Test fun twoHuesAreNeverAveragedIntoAThird() {
        val red = 0xFFE01818.toInt(); val blue = 0xFF1830E0.toInt(); val yellow = 0xFFF0D020.toInt()
        val a = CoverColors.analyze(IntArray(520) { red } + IntArray(480) { blue })!!
        assertTrue("red or blue, not purple", a.base == red || a.base == blue)
        assertNotNull(a.second)
        val b = CoverColors.analyze(IntArray(500) { yellow } + IntArray(500) { blue })!!
        assertTrue("yellow or blue, not green", b.base == yellow || b.base == blue)
    }
    @Test fun aGreyCoverStaysGreyAndEmptyHasNoColour() {
        val r = CoverColors.analyze(IntArray(1000) { grey })!!
        assertFalse(r.colourful); assertEquals(grey, r.base)
        assertNull(CoverColors.analyze(intArrayOf(0x00FF0000)))
    }

    // ---------------------------------------------------------------- stems in Ketmix
    private fun plan(stems: Boolean, voiceFrom: Long = 40_000) = MixPlan(MixKind.Beatmatch, outMs = 180_000, inMs = 30_000, overlapMs = 16_000,
        swapMs = 188_000, beatMs = 500f, stems = stems, voiceFromMs = voiceFrom)

    @Test fun withStemsTheIncomingVoiceWaitsAndTheOutgoingOneStepsDown() {
        val incoming = DeckAutomation.incoming(plan(true))
        assertTrue(incoming.usesVocalCut)
        assertTrue("instrumental first", incoming.vocalCut.at(31_000_000L) > .8f)
        assertTrue("voice back by the time it sings", incoming.vocalCut.at(39_800_000L) < .05f)
        val outgoing = DeckAutomation.outgoing(plan(true))
        assertTrue("outgoing voice down when the next one sings", outgoing.vocalCut.at(190_000_000L) > .8f)
        assertTrue("not before", outgoing.vocalCut.at(180_500_000L) < .05f)
    }
    @Test fun withoutStemsNothingChanges() {
        assertFalse(DeckAutomation.incoming(plan(false)).usesVocalCut)
    }

    // ---------------------------------------------------------------- playlists
    @Test fun anAlbumAddsEachTitleOnceAndSkipsThoseAlreadyThere() {
        val a = Track("a", "A", "X", "Al"); val b = Track("b", "B", "X", "Al")
        val existing = listOf(PlaylistRules.entry(a))
        val added = PlaylistRules.newEntries(existing, listOf(a, b, b))
        assertEquals(listOf("b"), added.map { it.track?.id })
    }
}
