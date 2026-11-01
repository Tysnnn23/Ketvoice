package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class StreamQualityTest {
    @Test fun losslessRanksAboveLossyWithoutTrustingAGenericHighQualityLabel() {
        fun stream(format: String, quality: String) = MusicApi.StreamInfo("https://cdn.example/audio", format, quality)
        assertTrue(StreamQuality.rank(stream("flac", "16-bit")) > StreamQuality.rank(stream("mp3", "320kbps")))
        assertTrue(StreamQuality.rank(stream("mp3", "320 kb/s")) > StreamQuality.rank(stream("mp3", "128kbps")))
        assertEquals(128, StreamQuality.rank(stream("mp3", "Haute qualité")))
    }
}
