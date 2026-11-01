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
internal class ProcessingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW || (format.channelCount ?: 0) > 2)
            super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        AudioOffloadSupport.DEFAULT_UNSUPPORTED
}
