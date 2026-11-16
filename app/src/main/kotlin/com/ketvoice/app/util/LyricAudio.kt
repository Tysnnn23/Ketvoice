package com.ketvoice.app.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The song's sound under a lyrics video (5.10.0): the part of the title the video shows, decoded
 * from the downloaded file or the stream and encoded again in AAC, with short fades at both ends.
 */
internal object LyricAudio {
    class Sample(val data: ByteArray, val timeUs: Long, val flags: Int)
    class Encoded(val format: MediaFormat, val samples: List<Sample>)

    private const val FADE_US = 250_000L
    private const val BITRATE = 192_000

    suspend fun encode(context: Context, track: Track, fromUs: Long, untilUs: Long): Encoded? {
        val source = Player.resolveDeckSource(track) ?: return null
        // A DASH manifest cannot be read by the platform extractor: such a title gets a silent video.
        if (source.stream?.isDash == true || source.offlineDash) return null
        val extractor = MediaExtractor()
        try {
            val url = source.url
            when {
                url.startsWith("http") -> extractor.setDataSource(url, mapOf("User-Agent" to "Mozilla/5.0"))
                url.startsWith("file:") -> extractor.setDataSource(java.io.File(java.net.URI(url)).absolutePath)
                url.startsWith("content:") -> extractor.setDataSource(context, url.toUri(), null)
                else -> extractor.setDataSource(url)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val input = extractor.getTrackFormat(trackIndex)
            val pcm = decode(extractor, input, fromUs, untilUs) ?: return null
            return encodeAac(pcm)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private class Pcm(val data: ShortArray, val rate: Int, val channels: Int)

    /** 16-bit PCM of [fromUs]..[untilUs], with fades in and out. */
    private fun decode(extractor: MediaExtractor, format: MediaFormat, fromUs: Long, untilUs: Long): Pcm? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val decoder = MediaCodec.createDecoderByType(mime)
        val info = MediaCodec.BufferInfo()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = ArrayList<ShortArray>()
        var total = 0
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var inputDone = false
            var outputDone = false
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!outputDone && System.nanoTime() < deadline) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > untilUs + 200_000L) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 5_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (index < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                val buffer = decoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    // Only the frames inside the part are kept, to the frame.
                    val firstUs = info.presentationTimeUs
                    val skip = ((fromUs - firstUs) * rate / 1_000_000L).toInt().coerceIn(0, frames)
                    val keep = (((untilUs - firstUs) * rate / 1_000_000L).toInt().coerceIn(0, frames) - skip).coerceAtLeast(0)
                    if (keep > 0) {
                        val chunk = ShortArray(keep * channels)
                        shorts.position(skip * channels); shorts.get(chunk)
                        out += chunk; total += chunk.size
                    }
                    if (firstUs > untilUs) outputDone = true
                }
                decoder.releaseOutputBuffer(index, false)
            }
        } finally {
            runCatching { decoder.stop() }; runCatching { decoder.release() }
        }
        if (total == 0 || channels !in 1..2) return null
        val data = ShortArray(total)
        var at = 0
        for (chunk in out) { chunk.copyInto(data, at); at += chunk.size }
        fade(data, rate, channels)
        return Pcm(data, rate, channels)
    }

    internal fun fade(data: ShortArray, rate: Int, channels: Int) {
        val frames = data.size / channels
        val ramp = (FADE_US * rate / 1_000_000L).toInt().coerceAtMost(frames / 2)
        for (i in 0 until ramp) {
            val g = i.toFloat() / ramp
            for (c in 0 until channels) {
                data[i * channels + c] = (data[i * channels + c] * g).toInt().toShort()
                val j = (frames - 1 - i) * channels + c
                data[j] = (data[j] * g).toInt().toShort()
            }
        }
    }

    private fun encodeAac(pcm: Pcm): Encoded? {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.rate, pcm.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val info = MediaCodec.BufferInfo()
        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            var offset = 0
            var inputDone = false
            var done = false
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!done && System.nanoTime() < deadline) {
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer: ByteBuffer = encoder.getInputBuffer(index)!!
                        buffer.clear()
                        val room = buffer.remaining() / 2 / pcm.channels * pcm.channels
                        val n = minOf(room, pcm.data.size - offset)
                        val timeUs = (offset / pcm.channels).toLong() * 1_000_000L / pcm.rate
                        if (n <= 0) {
                            encoder.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm.data, offset, n)
                            encoder.queueInputBuffer(index, 0, n * 2, timeUs, 0)
                            offset += n
                        }
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 5_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = encoder.outputFormat; continue }
                if (index < 0) continue
                val buffer = encoder.getOutputBuffer(index)
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buffer != null && info.size > 0 && !config) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset); buffer.get(bytes)
                    samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                encoder.releaseOutputBuffer(index, false)
            }
        } finally {
            runCatching { encoder.stop() }; runCatching { encoder.release() }
        }
        return outFormat?.takeIf { samples.isNotEmpty() }?.let { Encoded(it, samples) }
    }
}
