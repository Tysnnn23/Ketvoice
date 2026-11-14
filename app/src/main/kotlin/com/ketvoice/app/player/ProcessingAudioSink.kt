@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport

/** Encoded passthrough/offload skips every PCM processor, including Reverb.
 * Request decoded PCM even while the effect is off, so it can be enabled live.
 * Multichannel stays untouched: the reverb only accepts mono/stereo, so surround
 * passthrough must keep working instead of being silently refused. */
internal class ProcessingAudioSink(sink: AudioSink, private val deck: com.ketvoice.app.automix.DeckControl? = null) : ForwardingAudioSink(sink) {
    private var lastTimeUs = androidx.media3.common.C.TIME_UNSET

    /**
     * Tells the deck's processor where in the title each new buffer is, and keeps the
     * position being heard, so that a transition can be tied to the music itself.
     */
    override fun handleBuffer(buffer: java.nio.ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (deck != null) {
            if (presentationTimeUs != lastTimeUs) { lastTimeUs = presentationTimeUs; deck.pendingTimeUs = presentationTimeUs }
            // Sampled a few dozen times a second at most: enough to check the clock, nothing to the sink.
            val now = System.nanoTime()
            if (now - deck.heardAtNanos > 20_000_000L || deck.heardUs == androidx.media3.common.C.TIME_UNSET) {
                val heard = runCatching { super.getCurrentPositionUs(false) }.getOrDefault(AudioSink.CURRENT_POSITION_NOT_SET)
                if (heard != AudioSink.CURRENT_POSITION_NOT_SET) { deck.heardUs = heard; deck.heardAtNanos = now }
            }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        lastTimeUs = androidx.media3.common.C.TIME_UNSET
        deck?.heardUs = androidx.media3.common.C.TIME_UNSET
        super.flush()
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW || (format.channelCount ?: 0) > 2)
            super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        AudioOffloadSupport.DEFAULT_UNSUPPORTED
}
