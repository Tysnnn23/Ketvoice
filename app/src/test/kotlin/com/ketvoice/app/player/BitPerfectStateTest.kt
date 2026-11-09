@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class BitPerfectStateTest {
    private val pcm = PcmSpec(96000, C.ENCODING_PCM_24BIT, 2)

    private fun proof(flags: List<Boolean> = List(7) { true }, bytes: Long = 1024,
                      source: PcmSpec? = pcm, output: PcmSpec? = pcm): Boolean = exactAudioProof(
        flags[0], flags[1], flags[2], flags[3], flags[4], bytes, source, output, flags[5], flags[6])

    @Test fun everyIndependentProofIsRequired() {
        assertTrue(proof())
        repeat(7) { index ->
            assertFalse("Evidence $index must never be optional", proof(List(7) { it != index }))
        }
        assertFalse(proof(bytes = 0))
        assertFalse(proof(bytes = -1))
    }

    @Test fun requestedOrAdvertisedCapabilityIsNotProof() {
        assertFalse(proof(flags = listOf(true, true, false, false, false, true, true)))
        assertFalse(proof(source = null))
        assertFalse(proof(output = null))
    }

    @Test fun resamplingBitDepthConversionAndChannelRemappingInvalidateProof() {
        assertFalse(proof(output = pcm.copy(sampleRate = 48000)))
        assertFalse(proof(output = pcm.copy(encoding = C.ENCODING_PCM_16BIT)))
        assertFalse(proof(output = pcm.copy(channels = 1)))
        assertFalse(proof(source = pcm.copy(sampleRate = 0), output = pcm.copy(sampleRate = 0)))
    }

    @Test fun floatIsNotInterchangeableWithInteger32() {
        val float = pcm.copy(encoding = C.ENCODING_PCM_FLOAT)
        assertEquals(32, float.bits)
        assertTrue(float.describe().contains("float"))
        assertFalse(proof(source = float, output = float.copy(encoding = C.ENCODING_PCM_32BIT)))
    }

    @Test fun pcmDescriptionsUseOnlyKnownDecoderValues() {
        assertEquals("96000 Hz · 24-bit · 2 ch", pcm.describe())
        assertFalse(pcm.copy(encoding = C.ENCODING_INVALID).isValid)
        assertFalse(pcm.copy(channels = 0).isValid)
        assertFalse(pcm.copy(channels = 9).isValid)
        assertFalse(pcm.copy(sampleRate = -1).isValid)
    }
}
