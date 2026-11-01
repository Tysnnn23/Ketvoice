@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ProcessingAudioSinkTest {
    private var queries = 0
    // Simulate a device advertising encoded passthrough for every format.
    private fun processingSink(): AudioSink = ProcessingAudioSink(Proxy.newProxyInstance(
        AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getFormatSupport" -> { queries++; AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY }
            "supportsFormat" -> true
            "getFormatOffloadSupport" -> throw AssertionError("Must not offer offload around PCM effects")
            else -> throw AssertionError("Unexpected sink method: ${method.name}")
        }
    } as AudioSink)

    @Test fun deviceEncodedPassthroughCannotBypassReverb() {
        val sink = processingSink()
        for (mime in listOf(MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_OPUS)) {
            val format = Format.Builder().setSampleMimeType(mime).setChannelCount(2).setSampleRate(48000).build()
            assertFalse(sink.supportsFormat(format))
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format))
        }
        assertEquals(0, queries)
    }
    @Test fun decodedPcmStillReachesTheProcessingSink() {
        val sink = processingSink()
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(48000).build()
        assertTrue(sink.supportsFormat(format))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(format))
        assertEquals(2, queries)
    }
    @Test fun surroundPassthroughIsPreservedBecauseReverbIsStereoOnly() {
        val sink = processingSink()
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).build()
        assertTrue(sink.supportsFormat(format))
        assertEquals(1, queries)
    }
    @Test fun offloadIsRejectedEvenForADeviceThatWouldAdvertiseIt() {
        assertSame(AudioOffloadSupport.DEFAULT_UNSUPPORTED,
            processingSink().getFormatOffloadSupport(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).build()))
    }
}
