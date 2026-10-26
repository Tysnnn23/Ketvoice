package com.ketvoice.app.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.common.util.UnstableApi
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

sealed class AutoMixSource {
    data class Remote(val url: String, val cacheKey: String?, val identity: String? = null) : AutoMixSource()
    data class Local(val path: String) : AutoMixSource()
    data class ContentUri(val uri: String) : AutoMixSource()
    val fingerprint: String get() = when (this) {
        is Remote -> "r:${identity ?: url.substringBefore('?')}"
        is Local -> "l:$path|${File(path).length()}|${File(path).lastModified()}"
        is ContentUri -> "c:$uri"
    }
}

object AutoMixAnalysisCodec {
    fun encode(analysis: AutoMixAnalysis): String = JSONObject()
        .put("track", analysis.trackId)
        .put("window", analysis.window)
        .put("level", analysis.level)
        .put("duration", analysis.durationMs)
        .put("start", analysis.windowStartMs)
        .put("bpm", analysis.bpm.toDouble())
        .put("confidence", analysis.confidence.toDouble())
        .put("beat", analysis.beatOffsetMs)
        .put("rms", analysis.rms.toDouble())
        .put("peak", analysis.peak.toDouble())
        .put("vocal", analysis.vocalRisk.toDouble())
        .put("quiet", analysis.quietTailMs)
        .put("sampled", analysis.sampledMs)
        .put("structure", analysis.structureStartMs)
        .toString()

    fun decode(raw: String): AutoMixAnalysis? = runCatching {
        val json = JSONObject(raw)
        AutoMixAnalysis(
            trackId = json.getString("track"),
            window = json.getInt("window"),
            level = json.getInt("level"),
            durationMs = json.getLong("duration"),
            windowStartMs = json.getLong("start"),
            bpm = json.getDouble("bpm").toFloat(),
            confidence = json.getDouble("confidence").toFloat(),
            beatOffsetMs = json.getLong("beat"),
            rms = json.getDouble("rms").toFloat(),
            peak = json.getDouble("peak").toFloat(),
            vocalRisk = json.getDouble("vocal").toFloat(),
            quietTailMs = json.getLong("quiet"),
            sampledMs = json.getLong("sampled"),
            structureStartMs = json.optLong("structure", 0L)
        )
    }.getOrNull()
}

@OptIn(UnstableApi::class)
object AutoMixAnalyzer {
    const val VERSION = 2
    const val HEAD = 0
    const val TAIL = 1
    private const val MAX_CACHE_FILES = 240

    private var context: Context? = null
    private val memory = ConcurrentHashMap<String, AutoMixAnalysis>()
    private val running = ConcurrentHashMap<String, Boolean>()

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    fun effectiveLevel(requested: Int = AutoMixSettings.performance()): Int {
        var level = requested.coerceIn(AutoMixSettings.LOW, AutoMixSettings.HIGH)
        val cores = Runtime.getRuntime().availableProcessors()
        if (AppSettings.highPerformanceMode.value || cores <= 4) level = min(level, AutoMixSettings.MEDIUM)
        return level
    }

    fun cached(trackId: String, source: AutoMixSource, level: Int, window: Int): AutoMixAnalysis? {
        val key = key(trackId, source, level, window)
        memory[key]?.let { return it }
        val file = file(key) ?: return null
        if (!file.exists()) return null
        val analysis = runCatching { AutoMixAnalysisCodec.decode(file.readText(Charsets.UTF_8)) }.getOrNull()
        if (analysis != null) {
            file.setLastModified(System.currentTimeMillis())
            if (memory.size > 128) memory.clear()
            memory[key] = analysis
        }
        return analysis
    }

    suspend fun analyze(trackId: String, source: AutoMixSource, level: Int, window: Int): AutoMixAnalysis? {
        val effective = effectiveLevel(level)
        if (effective <= AutoMixSettings.LOW) return null
        cached(trackId, source, effective, window)?.let { return it }
        val key = key(trackId, source, effective, window)
        if (running.putIfAbsent(key, true) != null) return null
        return try {
            val analysis = withContext(Dispatchers.Default) {
                decodeWindow(trackId, source, effective, window, coroutineContext[Job])
            }
            if (analysis != null) {
                if (memory.size > 128) memory.clear()
                memory[key] = analysis
                write(key, analysis)
            }
            analysis
        } finally {
            running.remove(key)
        }
    }

    private data class Params(val tailSeconds: Int, val headSeconds: Int, val tempo: Boolean)

    private fun params(level: Int): Params = when (level) {
        AutoMixSettings.MEDIUM -> Params(14, 6, false)
        AutoMixSettings.STANDARD -> Params(24, 10, true)
        else -> Params(34, 16, true)
    }

    private fun key(trackId: String, source: AutoMixSource, level: Int, window: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val raw = "$VERSION|$trackId|${source.fingerprint}|$level|$window"
        return digest.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun file(key: String): File? {
        val ctx = context ?: return null
        val dir = File(ctx.filesDir, "automix")
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, "$key.json")
    }

    private fun write(key: String, analysis: AutoMixAnalysis) {
        val ctx = context ?: return
        val dir = File(ctx.filesDir, "automix")
        runCatching {
            file(key)?.writeText(AutoMixAnalysisCodec.encode(analysis), Charsets.UTF_8)
            val files = dir.listFiles() ?: return@runCatching
            if (files.size > MAX_CACHE_FILES) {
                files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHE_FILES)
                    .forEach { it.delete() }
            }
        }
    }

    private class Decoded(val mono: FloatArray, val sampleRate: Int, val windowStartMs: Long, val durationMs: Long)

    private fun decodeWindow(trackId: String, source: AutoMixSource, level: Int, window: Int, job: Job?): AutoMixAnalysis? {
        val parameters = params(level)
        val seconds = if (window == TAIL) parameters.tailSeconds else parameters.headSeconds
        val decoded = runCatching {
            when (source) {
                is AutoMixSource.Local -> decodeLocal(source.path, window, seconds, job)
                is AutoMixSource.ContentUri -> decodeContent(source.uri, window, seconds, job)
                is AutoMixSource.Remote -> decodeRemote(source, window, seconds, job)
            }
        }.getOrNull() ?: return null
        if (decoded.mono.size < decoded.sampleRate) return null

        val rate = decoded.sampleRate
        val mono = decoded.mono
        val analysisRms = AutoMixDsp.rms(mono)
        val analysisPeak = AutoMixDsp.peak(mono)
        var bpm = 0f
        var confidence = 0f
        var beatOffset = 0L
        if (parameters.tempo && mono.size >= rate * 4) {
            val hop = (rate / 86).coerceAtLeast(64)
            val onsets = AutoMixDsp.onsetEnvelope(mono, hop)
            val tempo = AutoMixDsp.tempo(onsets, rate.toFloat() / hop)
            bpm = if (tempo.confidence >= 0.18f) tempo.bpm else 0f
            confidence = tempo.confidence
            beatOffset = (tempo.beatOffsetFrames * 1000.0 / (rate.toFloat() / hop)).toLong()
        }
        val vocal = if (window == HEAD) AutoMixDsp.vocalRisk(mono, rate) else 0f
        val quiet = if (window == TAIL) AutoMixDsp.quietTailMs(mono, rate) else 0L
        return AutoMixAnalysis(
            trackId = trackId,
            window = window,
            level = level,
            durationMs = decoded.durationMs,
            windowStartMs = decoded.windowStartMs,
            bpm = bpm,
            confidence = confidence,
            beatOffsetMs = beatOffset,
            rms = analysisRms,
            peak = analysisPeak,
            vocalRisk = vocal,
            quietTailMs = quiet,
            sampledMs = if (rate > 0) mono.size * 1000L / rate else 0L,
            structureStartMs = if (window == TAIL) AutoMixDsp.structureStartMs(mono, rate,
                decoded.windowStartMs, decoded.durationMs) else 0L
        )
    }

    private fun decodeRemote(source: AutoMixSource.Remote, window: Int, seconds: Int, job: Job?): Decoded? {
        val ctx = context ?: return null
        val factory = AudioCache.factory(ctx)
        val data = CachedMediaDataSource(factory, Uri.parse(source.url), source.cacheKey)
        return try {
            extract(data, window, seconds, job)
        } finally {
            runCatching { data.close() }
        }
    }

    private fun decodeLocal(path: String, window: Int, seconds: Int, job: Job?): Decoded? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            read(extractor, window, seconds, job)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeContent(uri: String, window: Int, seconds: Int, job: Job?): Decoded? {
        val ctx = context ?: return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(ctx, Uri.parse(uri), null)
            read(extractor, window, seconds, job)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun extract(data: MediaDataSource, window: Int, seconds: Int, job: Job?): Decoded? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(data)
            read(extractor, window, seconds, job)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun read(extractor: MediaExtractor, window: Int, seconds: Int, job: Job?): Decoded? {
        var track = -1
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                track = index
                format = candidate
                break
            }
        }
        val audio = format ?: return null
        if (track < 0) return null
        extractor.selectTrack(track)
        val mime = audio.getString(MediaFormat.KEY_MIME) ?: return null
        val sampleRate = audio.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8_000, 192_000)
        val channels = audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
        val durationUs = runCatching { audio.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        val durationMs = if (durationUs > 0) durationUs / 1000 else 0L
        if (window == TAIL && durationMs <= 0L) return null

        var startMs = 0L
        if (window == TAIL && durationMs > 0) {
            startMs = (durationMs - seconds * 1000L).coerceAtLeast(0L)
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        val codec = MediaCodec.createDecoderByType(mime)
        val targetFrames = seconds.toLong() * sampleRate
        val capacity = (targetFrames + 4096).coerceAtMost(8_000_000L).toInt()
        var pcm = FloatArray(capacity)
        var frames = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var floating = false
        var pcmChannels = channels
        val deadline = System.currentTimeMillis() + 25_000L
        try {
            codec.configure(audio, null, null, 0)
            codec.start()
            while (!outputDone) {
                if (job?.isActive == false) return null
                if (System.currentTimeMillis() > deadline) break
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(8_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: break
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 8_000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    floating = runCatching { outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                        .getOrDefault(android.media.AudioFormat.ENCODING_PCM_16BIT) ==
                        android.media.AudioFormat.ENCODING_PCM_FLOAT
                    pcmChannels = runCatching { outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                        .getOrDefault(channels).coerceIn(1, 8)
                    continue
                }
                if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0 && info.presentationTimeUs >= startMs * 1000L) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val width = if (floating) 4 else 2
                        val available = info.size / (width * pcmChannels)
                        val bounded = min(available, (capacity - frames).coerceAtLeast(0))
                        if (bounded > 0) frames += samples(buffer, floating, pcmChannels, pcm, frames, bounded)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                if (frames >= targetFrames || frames >= capacity) outputDone = true
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        if (frames <= 0) return null
        val monoRaw = FloatArray(frames)
        System.arraycopy(pcm, 0, monoRaw, 0, frames)
        val factor = ceil(sampleRate / 24_000.0).toInt().coerceAtLeast(1)
        val mono = AutoMixDsp.decimate(monoRaw, factor)
        if (mono.isEmpty()) return null
        return Decoded(
            mono = mono,
            sampleRate = (sampleRate / factor).coerceAtLeast(1),
            windowStartMs = startMs,
            durationMs = durationMs
        )
    }

    private fun samples(buffer: ByteBuffer, floating: Boolean, channels: Int, out: FloatArray, offset: Int, limit: Int): Int {
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val width = if (floating) 4 else 2
        val frames = min(limit, data.remaining() / (width * channels))
        if (floating) {
            val floats = data.asFloatBuffer()
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) {
                    val value = floats.get(frame * channels + channel)
                    if (value.isFinite()) sum += value.coerceIn(-1f, 1f)
                }
                out[offset + frame] = sum / channels
            }
        } else {
            val shorts = data.asShortBuffer()
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) sum += shorts.get(frame * channels + channel) / 32_768f
                out[offset + frame] = sum / channels
            }
        }
        return frames
    }

    @OptIn(UnstableApi::class)
    private class CachedMediaDataSource(
        private val factory: DataSource.Factory,
        private val uri: Uri,
        private val cacheKey: String?
    ) : MediaDataSource() {

        private var source: DataSource? = null
        private var size = -1L
        private var cursor = -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size <= 0) return 0
            if (this.size >= 0 && position >= this.size) return -1
            val data = ensure(position)
            var read = 0
            while (read < size) {
                val count = data.read(buffer, offset + read, size - read)
                if (count <= 0) break
                read += count
            }
            cursor = position + read
            return if (read == 0) -1 else read
        }

        private fun ensure(position: Long): DataSource {
            val current = source
            if (current != null && position == cursor) return current
            runCatching { source?.close() }
            val next = factory.createDataSource()
            val builder = DataSpec.Builder().setUri(uri).setPosition(position)
            if (!cacheKey.isNullOrBlank()) builder.setKey(cacheKey)
            val length = next.open(builder.build())
            source = next
            cursor = position
            if (size < 0 && length >= 0) size = position + length
            return next
        }

        override fun getSize(): Long = size

        override fun close() {
            runCatching { source?.close() }
            source = null
            cursor = -1L
        }
    }
}
