package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test

class QualityHandoffTest {
    @Test fun onlySameDurationRecordingsAwayFromTheEndCanSwitch() {
        assertTrue(QualityHandoff.compatible(180000, 180400, 60000))
        assertFalse(QualityHandoff.compatible(180000, 185000, 60000))
        assertFalse(QualityHandoff.compatible(180000, 180000, 179000))
        assertFalse(QualityHandoff.compatible(0, 180000, 0))
        assertFalse(QualityHandoff.compatible(180000, -1, 0))
    }
    @Test fun audibleHandoffRequiresTightPositionAlignment() {
        assertTrue(QualityHandoff.aligned(12000, 12040))
        assertFalse(QualityHandoff.aligned(12000, 12200))
    }

    /** A music-like signal: notes of random loudness and length, stereo, 44.1 kHz. */
    private fun song(seconds: Double, seed: Int, rate: Int = 44_100): ShortArray {
        val rnd = java.util.Random(seed.toLong())
        val frames = (seconds * rate).toInt()
        val out = ShortArray(frames * 2)
        var i = 0
        while (i < frames) {
            val len = rate / 16 + rnd.nextInt(rate / 3)
            val amp = 1_000 + rnd.nextInt(12_000)
            val hz = 110.0 + rnd.nextInt(800)
            for (k in 0 until len) {
                if (i + k >= frames) break
                val decay = Math.exp(-3.0 * k / len)
                val v = (amp * decay * Math.sin(2 * Math.PI * hz * (i + k) / rate)).toInt().toShort()
                out[2 * (i + k)] = v; out[2 * (i + k) + 1] = v
            }
            i += len
        }
        return out
    }

    @Test fun theSoundTellsHowFarApartTwoRenditionsAre() {
        val rate = 44_100
        val whole = song(8.0, 7)
        // The second rendition is the same song 137 ms later (an encoder's priming, an estimated seek), a bit noisier.
        val lagFrames = (rate * .137).toInt()
        val rnd = java.util.Random(3)
        val second = ShortArray(whole.size) { i ->
            val src = i - 2 * lagFrames
            ((if (src >= 0) whole[src].toInt() else 0) + rnd.nextInt(400) - 200).coerceIn(-32768, 32767).toShort()
        }
        // Both read over the same stretch of title positions: 3 s to 5 s.
        val from = 3 * rate; val until = 5 * rate
        val a = QualityHandoff.envelope(whole.copyOfRange(2 * from, 2 * until), rate, 2)
        val b = QualityHandoff.envelope(second.copyOfRange(2 * from, 2 * until), rate, 2)
        val (ms, likeness) = QualityHandoff.offset(a, 3_000_000L, b, 3_000_000L)!!
        assertEquals("offset found", 137.0, ms, 2.5)
        assertTrue("same recording: $likeness", likeness > QualityHandoff.SAME_RECORDING)
        // Read from slightly different places, the answer is the same.
        val b2 = QualityHandoff.envelope(second.copyOfRange(2 * (from + 4410), 2 * (until + 4410)), rate, 2)
        assertEquals(137.0, QualityHandoff.offset(a, 3_000_000L, b2, 3_100_000L)!!.first, 2.5)
        // Another song is not taken for the same recording.
        val other = QualityHandoff.envelope(song(8.0, 99).copyOfRange(2 * from, 2 * until), rate, 2)
        assertTrue(QualityHandoff.offset(a, 3_000_000L, other, 3_000_000L)!!.second < QualityHandoff.SAME_RECORDING)
    }

    @Test fun renditionsAlignedByTheirSoundMayDifferMoreInLength() {
        assertFalse(QualityHandoff.compatible(180000, 183000, 60000))
        assertTrue(QualityHandoff.compatible(180000, 183000, 60000, QualityHandoff.ALIGNED_DURATION_MS))
    }
}
