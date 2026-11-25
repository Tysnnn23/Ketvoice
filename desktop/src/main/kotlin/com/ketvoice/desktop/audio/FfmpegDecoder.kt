package com.ketvoice.desktop.audio

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.LongPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One source decoded in-process by FFmpeg (avformat, avcodec, swresample), read through a custom
 * AVIOContext over a [ByteSource]. Output is interleaved float stereo at [OUT_RATE] Hz. Not thread
 * safe: one decoding thread owns it.
 */
class FfmpegDecoder(
    private val source: ByteSource,
    /**
     * « Lecture sans blanc » (true gapless, Windows 2.0): the resampler is drained to its last sample, and an MP4/M4A
     * whose container states its exact length (the edit list's, without the encoder's padding) ends on that sample.
     */
    private val exactEnd: Boolean = false,
    /** Output rate; 0 keeps the source's own rate (« Mode exclusif » bit-perfect: no resampling). */
    requestedRate: Int = OUT_RATE,
) : Closeable {
    private val id = ids.incrementAndGet().also { load() }
    private val opaque = LongPointer(1).put(id)
    private var position = 0L // byte position of the AVIO reader
    private val scratch = ByteArray(IO_BUFFER)

    private val fmt: AVFormatContext
    private val avio: AVIOContext
    private lateinit var codecCtx: AVCodecContext
    private val stream: Int
    private val timeBase: AVRational
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var swr: SwrContext? = null
    private var swrKey = ""
    private var outBuf = FloatPointer(0L)
    private var outCap = 0
    private val outPP = PointerPointer<Pointer>(1L)

    /** The source's real format, for the Lossless badge. */
    val format: AudioEvent.Format
    /** Duration in ms, 0 when unknown. */
    val durationMs: Long
    /** The rate of what [read] gives: [OUT_RATE], or the source's own rate when it was asked for (0). */
    var outRate: Int = if (requestedRate > 0) requestedRate else OUT_RATE; private set
    private var outTb: AVRational = OUT_TB
    /** With [exactEnd]: the output frame the title ends on (its container's stated length), else -1. */
    private var endFrame = -1L

    private var pending = FloatArray(0)
    private var pendingStart = 0
    private var pendingEnd = 0
    private var inputDone = false
    private var drained = false
    private var swrFlushed = false
    /** Output frames (at 48 kHz) from the start of the track of the next sample to return. */
    var outPosition = 0L; private set
    private var skipTo = -1L
    private var resync = false
    /**
     * Stream time of the first sample heard: the encoder delay an MP3's LAME header trims, or a
     * stream that does not start at 0. Seeks and timestamps are taken relative to it.
     */
    private var startPts = 0L
    private var first = true

    init {
        sources[id] = this
        val buffer = av_malloc(IO_BUFFER.toLong())
        avio = avio_alloc_context(BytePointer(buffer), IO_BUFFER, 0, opaque, Read, null, if (source.size >= 0) Seek else null)
        fmt = avformat_alloc_context()
        fmt.pb(avio) // avformat_open_input marks it custom I/O and leaves it to us
        try {
            check(avformat_open_input(fmt, null as String?, null, null as AVDictionary?), "open")
            check(avformat_find_stream_info(fmt, null as PointerPointer<*>?), "stream info")
            stream = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
            if (stream < 0) throw IOException("no audio stream")
            val st = fmt.streams(stream)
            val par = st.codecpar()
            timeBase = st.time_base()
            val codec = avcodec_find_decoder(par.codec_id()) ?: throw IOException("no decoder for ${avcodec_get_name(par.codec_id()).string}")
            codecCtx = avcodec_alloc_context3(codec)
            check(avcodec_parameters_to_context(codecCtx, par), "codec parameters")
            check(avcodec_open2(codecCtx, codec, null as AVDictionary?), "codec open")
            val name = avcodec_get_name(par.codec_id()).string
            val lossy = name !in AudioEvent.LOSSLESS && !name.startsWith("pcm_")
            val depth = if (lossy) 0 else par.bits_per_raw_sample().takeIf { it > 0 }
                ?: (av_get_bytes_per_sample(codecCtx.sample_fmt()) * 8)
            val bitrate = par.bit_rate().takeIf { it > 0 } ?: fmt.bit_rate().coerceAtLeast(0)
            format = AudioEvent.Format(if (name.startsWith("pcm_")) "pcm" else name, par.sample_rate(), depth, par.ch_layout().nb_channels(), bitrate)
            durationMs = when {
                fmt.duration() > 0 -> fmt.duration() / 1000
                st.duration() > 0 -> av_rescale_q(st.duration(), timeBase, MS)
                else -> 0
            }
            if (requestedRate <= 0 && par.sample_rate() > 0) { outRate = par.sample_rate(); outTb = AVRational().num(1).den(outRate) }
            if (exactEnd && lossy && st.duration() > 0 && fmt.iformat()?.name()?.string.orEmpty().contains("mp4"))
                endFrame = av_rescale_q(st.duration(), timeBase, outTb)
            decodeMore() // learns [startPts]; the first frame stays pending
        } catch (t: Throwable) {
            freeNative(); throw t
        }
    }

    /**
     * Fills [out] from [offset] with up to [frames] stereo frames; returns how many, or -1 at the end.
     * Blocks while the source's bytes are on their way.
     */
    fun read(out: FloatArray, offset: Int, frames: Int): Int {
        var written = 0
        while (written < frames) {
            // True gapless: the container's stated end is the last sample (the encoder's padding stays unheard).
            if (endFrame in 0..outPosition) { pendingStart = pendingEnd; swrFlushed = true; break }
            if (pendingStart < pendingEnd) {
                var n = minOf(frames - written, (pendingEnd - pendingStart) / 2)
                if (endFrame >= 0) n = minOf(n.toLong(), endFrame - outPosition).toInt()
                System.arraycopy(pending, pendingStart, out, offset + written * 2, n * 2)
                pendingStart += n * 2; written += n; outPosition += n
                continue
            }
            if (!decodeMore()) break
        }
        return if (written == 0 && swrFlushed) -1 else written
    }

    /** Seeks to [ms]; the next sample returned is the one at [ms] (sample-accurate, decoding from the key frame before it). */
    fun seek(ms: Long) {
        val target = ms.coerceAtLeast(0)
        val ts = av_rescale_q(target, MS, timeBase) + startPts
        if (av_seek_frame(fmt, stream, ts, AVSEEK_FLAG_BACKWARD) < 0) av_seek_frame(fmt, -1, target * 1000, AVSEEK_FLAG_BACKWARD)
        avcodec_flush_buffers(codecCtx)
        swr?.let { swr_free(it) }; swr = null; swrKey = ""
        pendingStart = 0; pendingEnd = 0
        inputDone = false; drained = false; swrFlushed = false
        outPosition = target * outRate / 1000
        skipTo = outPosition
        resync = true
    }

    /** Decodes one more frame into [pending]; false at the very end. */
    private fun decodeMore(): Boolean {
        while (true) {
            val r = avcodec_receive_frame(codecCtx, frame)
            if (r >= 0) { convert(frame); av_frame_unref(frame); return true }
            if (r == AVERROR_EOF()) {
                if (swrFlushed) return false
                // True gapless: the resampler is asked again until it gives nothing more (it may hold more than it says).
                val more = flushResampler(); if (!exactEnd || !more) swrFlushed = true
                return true
            }
            if (r != AVERROR_EAGAIN()) throw IOException("decode: ${err(r)}")
            if (inputDone) { if (!drained) { avcodec_send_packet(codecCtx, null as AVPacket?); drained = true }; continue }
            val rr = av_read_frame(fmt, packet)
            if (rr < 0) {
                if (rr != AVERROR_EOF() && source.size >= 0 && position < source.size) throw IOException("read: ${err(rr)}")
                inputDone = true; continue
            }
            if (packet.stream_index() == stream) {
                val s = avcodec_send_packet(codecCtx, packet)
                if (s < 0 && s != AVERROR_EAGAIN()) { av_packet_unref(packet); continue } // a damaged packet is skipped
            }
            av_packet_unref(packet)
        }
    }

    private fun convert(f: AVFrame) {
        val layout = f.ch_layout()
        val key = "${f.format()}/${f.sample_rate()}/${layout.nb_channels()}/${layout.u_mask()}"
        if (swr == null || key != swrKey) {
            swr?.let { swr_free(it) }
            val outLayout = AVChannelLayout(); av_channel_layout_default(outLayout, 2)
            val inLayout = AVChannelLayout()
            if (layout.nb_channels() > 0 && av_channel_layout_check(layout) == 1) av_channel_layout_copy(inLayout, layout)
            else av_channel_layout_default(inLayout, codecCtx.ch_layout().nb_channels().coerceAtLeast(1))
            val ctx = SwrContext(null as Pointer?) // filled in by swr_alloc_set_opts2 (SwrContext**)
            check(swr_alloc_set_opts2(ctx, outLayout, AV_SAMPLE_FMT_FLT, outRate, inLayout, f.format(), f.sample_rate(), 0, null), "resampler")
            if (ctx.isNull) throw IOException("resampler: no context")
            check(swr_init(ctx), "resampler init")
            swr = ctx; swrKey = key
        }
        val ts = f.best_effort_timestamp()
        if (first) {
            first = false
            val st = fmt.streams(stream).start_time()
            startPts = if (st != AV_NOPTS_VALUE()) st else if (ts != AV_NOPTS_VALUE()) ts else 0
        }
        if (resync) {
            // After a seek, the first frame's time says where decoding really restarted.
            if (ts != AV_NOPTS_VALUE()) outPosition = av_rescale_q(ts - startPts, timeBase, outTb)
            resync = false
        }
        val cap = swr_get_out_samples(swr, f.nb_samples())
        ensure(cap)
        val n = swr_convert(swr, outPP, cap, f.extended_data(), f.nb_samples())
        if (n < 0) throw IOException("resample: ${err(n)}")
        take(n)
    }

    /** What the resampler still holds; true when it gave something. */
    private fun flushResampler(): Boolean {
        val s = swr ?: return false
        val cap = swr_get_out_samples(s, 0).coerceAtLeast(0).let { if (exactEnd) maxOf(it, 1024) else it }
        if (cap == 0) return false
        ensure(cap)
        val n = swr_convert(s, outPP, cap, null as PointerPointer<*>?, 0)
        if (n > 0) take(n)
        return n > 0
    }

    private fun ensure(frames: Int) {
        if (frames > outCap) {
            outBuf.close(); outCap = frames + 1024
            outBuf = FloatPointer(outCap * 2L); outPP.put(0, outBuf)
        }
        if (pending.size < outCap * 2) pending = FloatArray(outCap * 2)
    }

    /** Moves [n] converted frames into [pending], dropping those before a seek target. */
    private fun take(n: Int) {
        outBuf.position(0).get(pending, 0, n * 2)
        pendingStart = 0; pendingEnd = n * 2
        if (skipTo >= 0) {
            val drop = (skipTo - outPosition).coerceIn(0, n.toLong()).toInt()
            pendingStart = drop * 2; outPosition += drop
            if (outPosition >= skipTo) skipTo = -1
        }
    }

    override fun close() { freeNative() }

    private var freed = false
    private fun freeNative() {
        if (freed) return; freed = true
        sources.remove(id)
        runCatching { swr?.let { swr_free(it) } }
        runCatching { av_frame_free(frame) }
        runCatching { av_packet_free(packet) }
        if (::codecCtx.isInitialized) runCatching { avcodec_free_context(codecCtx) }
        runCatching { avformat_close_input(fmt) }
        runCatching { av_free(avio.buffer()); avio_context_free(avio) }
        outBuf.close(); opaque.close()
    }

    private fun readPacket(buf: BytePointer, size: Int): Int = try {
        val n = source.readAt(position, scratch, 0, minOf(size, scratch.size))
        if (n <= 0) AVERROR_EOF() else { buf.position(0).put(scratch, 0, n); position += n; n }
    } catch (e: IOException) { lastIoError = e; AVERROR_EIO }

    private fun seekBytes(offset: Long, whence: Int): Long {
        val size = source.size
        if (whence and AVSEEK_SIZE_FLAG != 0) return size
        val to = when (whence and 3) { 0 -> offset; 1 -> position + offset; 2 -> if (size < 0) return -1 else size + offset; else -> return -1 }
        if (to < 0) return -1
        position = to
        return to
    }

    /** The I/O error behind a failed read, for the message. */
    var lastIoError: IOException? = null; private set

    private fun check(code: Int, what: String) {
        if (code < 0) throw IOException("$what: ${lastIoError?.message ?: err(code)}")
    }

    companion object {
        const val OUT_RATE = 48_000
        private const val IO_BUFFER = 64 * 1024
        private const val AVSEEK_SIZE_FLAG = 0x10000
        private const val AVERROR_EIO = -5
        private val MS = AVRational().num(1).den(1000)
        private val OUT_TB = AVRational().num(1).den(OUT_RATE)
        private val ids = AtomicLong()
        private val sources = ConcurrentHashMap<Long, FfmpegDecoder>()

        private fun err(code: Int): String {
            val b = ByteArray(256)
            av_strerror(code, b, b.size.toLong())
            return String(b).substringBefore('\u0000')
        }

        private fun of(opaque: Pointer): FfmpegDecoder? = sources[LongPointer(opaque).get()]

        // JavaCPP keeps a small pool of native thunks per callback class: one shared pair, routed by `opaque`.
        private val Read = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer, buf: BytePointer, size: Int): Int = of(opaque)?.readPacket(buf, size) ?: AVERROR_EOF()
        }
        private val Seek = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer, offset: Long, whence: Int): Long = of(opaque)?.seekBytes(offset, whence) ?: -1
        }

        @Volatile private var loaded = false
        /** Loads the FFmpeg natives once (the first decoder pays for it). */
        fun load() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avutil::class.java)
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avcodec::class.java)
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
                org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.swresample::class.java)
                av_log_set_level(AV_LOG_ERROR)
                loaded = true
            }
        }
    }
}
