package com.ketvoice.app.util

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a Canvas video again, smaller (7.2): decoder → GL texture (scaled to the asked size) → encoder's input
 * surface → MP4, video only (a Canvas plays muted). The choices (which files, what size, what bitrate, whether the
 * copy is kept) are [com.ketvoice.app.data.CanvasCompact]'s and host-tested; this needs a real codec and runs on
 * the device only. Any failure returns false and the caller keeps the original.
 */
object CanvasTranscoder {
    data class Probe(val width: Int, val height: Int, val durationUs: Long, val fps: Int, val rotation: Int)

    fun probe(file: File): Probe? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return@runCatching null
            Probe(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L,
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }
                    .getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() } else 30,
                if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0)
        } finally { extractor.release() }
    }.getOrNull()

    fun hasHevcEncoder(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            // Android 9 cannot tell a hardware encoder apart: no HEVC there (H.264 is used, as without one).
            info.isEncoder && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
        }
    }.getOrDefault(false)

    fun transcode(src: File, dst: File, width: Int, height: Int, bitrate: Int, fps: Int, hevc: Boolean): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var gl: Gl? = null
        var frames: FrameWait? = null
        return try {
            extractor.setDataSource(src.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val rotation = if (input.containsKey(MediaFormat.KEY_ROTATION)) input.getInteger(MediaFormat.KEY_ROTATION) else 0
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            encoder = MediaCodec.createEncoderByType(mime).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            gl = Gl(encoder.createInputSurface(), width, height)
            encoder.start()
            frames = FrameWait(gl.texture)
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(input, frames.surface, null, 0); start()
            }
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation) }
            run(extractor, decoder, encoder, muxer, gl, frames)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { frames?.release() }
            runCatching { gl?.release() }
            extractor.release()
        }
    }

    private fun run(extractor: MediaExtractor, decoder: MediaCodec, encoder: MediaCodec, muxer: MediaMuxer, gl: Gl, frames: FrameWait): Boolean {
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var decodeDone = false
        var muxTrack = -1; var written = 0
        val deadline = System.nanoTime() + 5 * 60_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!inputDone) {
                val i = decoder.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                    if (size < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            if (!decodeDone) {
                val o = decoder.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0
                    decoder.releaseOutputBuffer(o, render)
                    if (render) {
                        if (!frames.await()) return false
                        gl.draw(frames.texture, frames.matrix)
                        gl.present(info.presentationTimeUs * 1000L)
                    }
                    if (end) { encoder.signalEndOfInputStream(); decodeDone = true }
                }
            }
            // Drain what the encoder has.
            while (true) {
                val e = encoder.dequeueOutputBuffer(info, if (decodeDone) 10_000 else 0)
                if (e == MediaCodec.INFO_TRY_AGAIN_LATER) { if (decodeDone && System.nanoTime() < deadline) continue else break }
                if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { muxTrack = muxer.addTrack(encoder.outputFormat); muxer.start(); continue }
                if (e < 0) continue
                val buffer: ByteBuffer = encoder.getOutputBuffer(e)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && muxTrack >= 0) { buffer.position(info.offset).limit(info.offset + info.size); muxer.writeSampleData(muxTrack, buffer, info); written++ }
                encoder.releaseOutputBuffer(e, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { muxer.stop(); return written > 0 }
            }
        }
        return false
    }

    /** The decoder's frames arrive on a SurfaceTexture; its callback comes on another thread's looper. */
    private class FrameWait(val texture: Int) {
        private val lock = Object()
        private var ready = false
        val surfaceTexture = SurfaceTexture(texture).apply { setOnFrameAvailableListener { synchronized(lock) { ready = true; lock.notifyAll() } } }
        val surface = Surface(surfaceTexture)
        val matrix = FloatArray(16)
        fun await(): Boolean {
            synchronized(lock) {
                val until = System.currentTimeMillis() + 2_500
                while (!ready) { val left = until - System.currentTimeMillis(); if (left <= 0) return false; lock.wait(left) }
                ready = false
            }
            surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(matrix)
            return true
        }
        fun release() { surface.release(); surfaceTexture.release() }
    }

    /** An EGL context drawing into the encoder's input surface, with one textured quad. */
    private class Gl(private val surface: Surface, private val width: Int, private val height: Int) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val window: EGLSurface
        private val program: Int
        val texture: Int
        private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)).position(0) }

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE),
                0, configs, 0, 1, count, 0)
            check(count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, window, window, context))
            program = link(
                "uniform mat4 m;attribute vec4 p;attribute vec4 t;varying vec2 v;void main(){gl_Position=p;v=(m*t).xy;}",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 v;uniform samplerExternalOES s;" +
                    "void main(){gl_FragColor=texture2D(s,v);}")
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun link(vertex: String, fragment: String): Int {
            fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, code); GLES20.glCompileShader(it) }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
                val ok = IntArray(1); GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0); check(ok[0] == GLES20.GL_TRUE)
            }
        }

        fun draw(texture: Int, matrix: FloatArray) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            val p = GLES20.glGetAttribLocation(program, "p"); val t = GLES20.glGetAttribLocation(program, "t")
            quad.position(0); GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(p)
            quad.position(2); GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(t)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "m"), 1, false, matrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun present(nanos: Long) {
            EGLExt.eglPresentationTimeANDROID(display, window, nanos)
            EGL14.eglSwapBuffers(display, window)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            surface.release()
        }
    }
}
