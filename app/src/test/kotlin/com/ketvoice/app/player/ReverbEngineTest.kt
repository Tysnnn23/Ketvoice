package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ReverbEngineTest {
    @Test fun disabledEffectIsBitExact() {
        val room = ReverbEngine(48000, 2)
        for (i in 0..20000) {
            val input = ((i % 65536) - 32768) / 32768f
            assertEquals(input, room.sample(input, i % 2, 0f), 0f)
        }
    }
    @Test fun impulseProducesADecayingDiffuseTailAndClearRemovesIt() {
        val room = ReverbEngine(44100, 1)
        repeat(4410) { room.sample(0f, 0, 1f) }
        val response = FloatArray(132300) { room.sample(if (it == 0) 1f else 0f, 0, 1f) }
        val early = response.sliceArray(1000..22050).sumOf { abs(it).toDouble() }
        val late = response.sliceArray(110250..132299).sumOf { abs(it).toDouble() }
        assertTrue(early > .1)
        assertTrue(late < early * .1)
        room.clear()
        repeat(10000) { assertEquals(0f, room.sample(0f, 0, 1f), 0f) }
    }
    @Test fun stereoHistoryDoesNotLeakAndOutputStaysFiniteAndBounded() {
        val room = ReverbEngine(48000, 2)
        repeat(240000) { frame ->
            val left = room.sample(if (frame % 2 == 0) 1f else -1f, 0, 1f)
            val right = room.sample(0f, 1, 1f)
            assertTrue(left.isFinite() && abs(left) <= 1f)
            assertEquals(0f, right, 0f)
        }
    }
}
