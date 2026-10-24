package com.ketvoice.app.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SonicAnalyzerTest {
    private fun tone(hz: Double, stereo: Boolean = false): ByteBuffer {
        val data = ByteBuffer.allocate(48000 * 2 * if(stereo) 2 else 1).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { i -> val sample = (sin(2*PI*hz*i/48000)*12000).toInt().toShort()
            data.putShort(sample); if(stereo) data.putShort(sample) }
        return data.flip() as ByteBuffer
    }
    @Test fun silenceStaysSilent() {
        val analyzer = SonicAnalyzer().apply { configure(48000,1,false) }
        assertEquals(SonicLevels(),analyzer.accept(ByteBuffer.allocate(96000)))
    }
    @Test fun lowAndHighFrequencyEnergyAreDifferentRealSignals() {
        val analyzer = SonicAnalyzer().apply { configure(48000,1,false) }
        val bass = analyzer.accept(tone(60.0))!!
        analyzer.reset()
        val treble = analyzer.accept(tone(8000.0))!!
        assertTrue(bass.low > bass.high);assertTrue(treble.high > treble.low)
        assertTrue(bass.low in 0f..1f);assertTrue(treble.high in 0f..1f)
    }
    @Test fun inputBytesAndPositionAreNeverModified() {
        val analyzer = SonicAnalyzer().apply { configure(48000,2,false) }
        val input = tone(500.0,true)
        val before = input.array().clone()
        analyzer.accept(input)
        assertEquals(0,input.position());assertArrayEquals(before,input.array())
    }
    @Test fun floatNanAndShortBuffersDoNotCreateFakeEnergy() {
        val analyzer = SonicAnalyzer().apply { configure(48000,1,true) }
        val input = ByteBuffer.allocate(48000*4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000){input.putFloat(Float.NaN)};input.flip()
        assertEquals(SonicLevels(),analyzer.accept(input))
        assertNull(analyzer.accept(ByteBuffer.allocate(1)))
    }
    @Test fun oppositeStereoPhasesDoNotCancelVisualEnergy() {
        val analyzer = SonicAnalyzer().apply { configure(48000,2,false) }
        val input = ByteBuffer.allocate(48000*4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { i -> val sample = (sin(2*PI*80*i/48000)*12000).toInt()
            input.putShort(sample.toShort()); input.putShort((-sample).toShort()) }
        input.flip()
        assertTrue(analyzer.accept(input)!!.low > .1f)
    }
}
