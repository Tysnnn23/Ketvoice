package com.ketvoice.desktop.canvas

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * A Canvas video read by FFmpeg (avformat, avcodec, swscale; the LGPL build) as BGRA frames already scaled to the
 * size they are shown at: [width] × [height], the video's shape kept, at most [maxWidth] × [maxHeight] and never
 * larger than the source. At the end it starts again from the first frame, so [next] never runs out. Not thread
 * safe: one thread owns it.
 */
class VideoFrames(file: File, maxWidth: Int, maxHeight: Int) : Closeable {
    private val fmt: AVFormatContext = avformat_alloc_context()
    private val codecCtx: AVCodecContext
    private val stream: Int
    private val timeBase: Double
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var sws: SwsContext? = null
    private val out: BytePointer
    private val stride: Int
    private val outData = PointerPointer<BytePointer>(4L)
    private val outLines = IntPointer(4L)
    private var inputDone = false
    private var loopBaseMs = 0L
    private var lastPtsMs = 0L
    private var firstPtsMs = Long.MIN_VALUE

    val sourceWidth: Int
    val sourceHeight: Int
    val width: Int
    val height: Int
    /** Width over height of the picture as shown (the sample aspect ratio applied). */
    val aspect: Float

    init {
        av_log_set_level(AV_LOG_ERROR)
        var opened = false
        try {
            if (avformat_open_input(fmt, BytePointer(file.absolutePath, "UTF-8"), null, null) < 0) throw IOException("cannot open ${file.name}")
            opened = true
            if (avformat_find_stream_info(fmt, null as PointerPointer<*>?) < 0) throw IOException("no stream info")
            stream = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
            if (stream < 0) throw IOException("no video stream")
            val st = fmt.streams(stream)
            timeBase = av_q2d(st.time_base())
            val par = st.codecpar()
            val codec = avcodec_find_decoder(par.codec_id()) ?: throw IOException("no decoder")
            codecCtx = avcodec_alloc_context3(codec)
            avcodec_parameters_to_context(codecCtx, par)
            codecCtx.thread_count(2)
            if (avcodec_open2(codecCtx, codec, null as PointerPointer<*>?) < 0) throw IOException("cannot open decoder")
            sourceWidth = codecCtx.width(); sourceHeight = codecCtx.height()
            if (sourceWidth <= 0 || sourceHeight <= 0) throw IOException("no picture size")
            val sar = par.sample_aspect_ratio().let { if (it.num() > 0 && it.den() > 0) it.num().toFloat() / it.den() else 1f }
            aspect = sourceWidth * sar / sourceHeight
            val size = fitSize(aspect, sourceWidth, sourceHeight, maxWidth, maxHeight)
            width = size.first; height = size.second
            // swscale writes whole SIMD blocks: rows padded to 64 bytes and a spare row, so it never writes past the buffer.
            stride = (width * 4 + 63) / 64 * 64
            out = BytePointer(av_malloc(stride.toLong() * (height + 1)))
            // One packed plane; the other three entries are allocated uninitialised and must read as empty.
            for (i in 1L..3L) { outData.put(i, null as BytePointer?); outLines.put(i, 0) }
            outData.put(0L, out); outLines.put(0L, stride)
        } catch (e: Throwable) {
            if (opened) avformat_close_input(fmt) else avformat_free_context(fmt)
            av_packet_free(packet); av_frame_free(frame)
            throw e
        }
    }

    /**
     * Decodes the next frame into [into] (at least `width * height * 4` bytes, BGRA, rows of `width * 4`) and returns
     * its time in ms from the first start, counting each loop on; null only when the video holds no frame at all.
     */
    fun next(into: ByteArray): Long? {
        var restarts = 0
        while (true) {
            val got = avcodec_receive_frame(codecCtx, frame)
            if (got == 0) {
                val pts = frame.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE } ?: frame.pts()
                val ms = if (pts == AV_NOPTS_VALUE) lastPtsMs + 33 else (pts * timeBase * 1000).toLong()
                if (firstPtsMs == Long.MIN_VALUE) firstPtsMs = ms
                lastPtsMs = ms
                scale(into)
                av_frame_unref(frame)
                return loopBaseMs + (ms - firstPtsMs).coerceAtLeast(0)
            }
            if (got == AVERROR_EOF()) {
                // The end: from the start again, one frame's time after the last one.
                if (++restarts > 2) return null
                loopBaseMs += (lastPtsMs - firstPtsMs).coerceAtLeast(0) + 33
                firstPtsMs = Long.MIN_VALUE
                av_seek_frame(fmt, stream, 0, AVSEEK_FLAG_BACKWARD)
                avcodec_flush_buffers(codecCtx)
                inputDone = false
                continue
            }
            if (got != AVERROR_EAGAIN()) return null
            if (inputDone) { avcodec_send_packet(codecCtx, null as AVPacket?); continue }
            if (av_read_frame(fmt, packet) < 0) { inputDone = true; avcodec_send_packet(codecCtx, null as AVPacket?); continue }
            if (packet.stream_index() == stream) avcodec_send_packet(codecCtx, packet)
            av_packet_unref(packet)
        }
    }

    private fun scale(into: ByteArray) {
        sws = sws_getCachedContext(sws, frame.width(), frame.height(), frame.format(), width, height, AV_PIX_FMT_BGRA,
            SWS_BILINEAR, null, null, null as DoublePointer?)
        sws_scale(sws, frame.data(), frame.linesize(), 0, frame.height(), outData, outLines)
        val row = width * 4
        if (stride == row) out.position(0).get(into, 0, row * height)
        else for (y in 0 until height) out.position(y.toLong() * stride).get(into, y * row, row)
        out.position(0)
    }

    override fun close() {
        sws?.let { sws_freeContext(it) }; sws = null
        av_free(out)
        avcodec_free_context(codecCtx)
        avformat_close_input(fmt)
        av_packet_free(packet); av_frame_free(frame)
    }

    companion object {
        /** The size a [aspect]-shaped picture is decoded at to fill a [maxW] × [maxH] place, never above the source. */
        fun fitSize(aspect: Float, srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
            val a = if (aspect.isFinite() && aspect > 0f) aspect else srcW.toFloat() / srcH.coerceAtLeast(1)
            // Cover the place: the side that limits is the one that fills it.
            var h = maxOf(maxH.toFloat(), maxW / a).coerceAtMost(srcH.toFloat())
            var w = h * a
            if (w > srcW) { w = srcW.toFloat(); h = w / a }
            fun even(v: Float) = (v.toInt() and 1.inv()).coerceAtLeast(2)
            return even(w) to even(h)
        }

        /** The first frame of [file], at most [longSide] px on its long side, or null when it cannot be read. */
        fun firstFrame(file: File, longSide: Int = 960): Frame? = runCatching {
            VideoFrames(file, longSide, longSide).use { probe ->
                val scale = minOf(1f, longSide.toFloat() / maxOf(probe.sourceWidth, probe.sourceHeight))
                val w = (probe.sourceWidth * scale).toInt(); val h = (probe.sourceHeight * scale).toInt()
                VideoFrames(file, w, h).use { v ->
                    val pixels = ByteArray(v.width * v.height * 4)
                    v.next(pixels) ?: return null
                    Frame(v.width, v.height, pixels)
                }
            }
        }.getOrNull()
    }

    /** One BGRA picture. */
    class Frame(val width: Int, val height: Int, val pixels: ByteArray)
}
