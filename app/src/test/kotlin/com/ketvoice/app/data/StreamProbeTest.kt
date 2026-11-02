package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

/** Regression tests for the "every source is rejected" failure class. */
class StreamProbeTest {
    private val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + ByteArray(20)
    private val flac = "fLaC".toByteArray() + ByteArray(16)
    private val aac = ByteArray(4) + "ftypM4A ".toByteArray() + ByteArray(16)
    private val ogg = "OggS".toByteArray() + ByteArray(20)
    private val wav = "RIFF".toByteArray() + ByteArray(4) + "WAVEfmt ".toByteArray()
    private val html = "<!doctype html><html>error</html>".toByteArray()
    private val json = "{\"error\":\"expired\"}".toByteArray()

    @Test fun audioSignaturesAreDetected() {
        assertEquals("mp3", StreamProbe.detect(mp3, "mp3"))
        assertEquals("flac", StreamProbe.detect(flac, "mp3"))
        assertEquals("aac", StreamProbe.detect(aac, "audio"))
        assertEquals("opus", StreamProbe.detect(ogg, "opus"))
        assertEquals("wav", StreamProbe.detect(wav, "wav"))
        assertNull(StreamProbe.detect(html, "mp3"))
    }

    @Test fun onlyGoneOrTextualAnswersRejectAStream() {
        assertEquals(StreamProbe.Verdict.AUDIO to "mp3", StreamProbe.classify(206, mp3, "mp3"))
        assertEquals(StreamProbe.Verdict.REJECTED to null, StreamProbe.classify(200, html, "mp3"))
        assertEquals(StreamProbe.Verdict.REJECTED to null, StreamProbe.classify(200, json, "mp3"))
        assertEquals(StreamProbe.Verdict.REJECTED to null, StreamProbe.classify(410, ByteArray(0), "flac"))
        assertEquals(StreamProbe.Verdict.REJECTED to null, StreamProbe.classify(404, ByteArray(0), "mp3"))
        // A CDN refusal, an empty read or a server hiccup must NOT disable the source.
        assertEquals(StreamProbe.Verdict.UNKNOWN to null, StreamProbe.classify(403, ByteArray(0), "mp3"))
        assertEquals(StreamProbe.Verdict.UNKNOWN to null, StreamProbe.classify(429, ByteArray(0), "mp3"))
        assertEquals(StreamProbe.Verdict.UNKNOWN to null, StreamProbe.classify(500, ByteArray(0), "mp3"))
        assertEquals(StreamProbe.Verdict.UNKNOWN to null, StreamProbe.classify(206, ByteArray(0), "mp3"))
        assertEquals(StreamProbe.Verdict.REDIRECT to null, StreamProbe.classify(302, ByteArray(0), "mp3"))
    }

    @Test fun textualDetectionIgnoresLeadingWhitespaceAndNulBytes() {
        assertTrue(StreamProbe.looksTextual("\n\n  <html>".toByteArray()))
        assertTrue(StreamProbe.looksTextual(" [\"error\"]".toByteArray()))
        assertFalse(StreamProbe.looksTextual(mp3))
        assertFalse(StreamProbe.looksTextual(ByteArray(0)))
    }
}
