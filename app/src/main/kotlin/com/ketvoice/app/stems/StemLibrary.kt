package com.ketvoice.app.stems

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.AnalysisTap
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.BothTaps
import com.ketvoice.app.automix.PcmCapture
import com.ketvoice.app.automix.PcmTap
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Downloaded titles are made ready for Ketmix before they are ever played (5.11.0): while the
 * phone charges, the intro and the ending of each downloaded file are decoded, measured (tempo,
 * key, levels) and, with the separation model, separated. One title at a time, on a thread of
 * low priority; nothing is done on battery, and nothing while a mix is playing.
 */
internal object StemLibrary {
    /** Titles done and titles found, for Settings. */
    data class Progress(val done: Int = 0, val total: Int = 0, val running: Boolean = false)

    const val MAX_TITLES = 300
    /** A whole title is separated about a minute at a time, with two seconds of margin on each side. */
    private const val WHOLE_PIECE_FRAMES = 2560
    private const val WHOLE_MARGIN_FRAMES = 86
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    /** One thread of low priority for all of it: decoding and separating never compete with the music. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ketvoice-library")
            .apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var job: Job? = null
    @Volatile private var appContext: Context? = null
    /** Files that could not be read: not tried again in this process. */
    private val unreadable = HashSet<String>()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        // Plugging the phone in starts the work; the list of downloads changing does too.
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) { wake() }
            }, IntentFilter(Intent.ACTION_POWER_CONNECTED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        scope.launch { Downloader.items.collect { wake() } }
    }

    /** What stands between the library and its preparation, in the order it is checked. */
    internal fun allowed(ketmix: Boolean, library: Boolean, charging: Boolean): Boolean = ketmix && library && charging

    internal fun pending(item: Downloader.Item, depth: AnalysisDepth, separate: Boolean, durationMs: Long): Boolean {
        val id = item.track.id
        val introLength = item.track.durationMs.takeIf { it > 0 } ?: durationMs
        val measured = AnalysisStore.intro(id, introLength, depth) != null && AnalysisStore.outro(id, durationMs, depth) != null
        // 6.0.0: downloaded titles are separated whole (the player's voice level works from it, and every mix can use it).
        val separated = !separate || StemEngine.wanted()?.let { q -> (StemStore.wholeQuality(id) ?: -1) >= q } != false
        return !(measured && separated)
    }

    fun wake() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(20_000)
            runCatching { run() }.onFailure { android.util.Log.w("KetvoiceStems", "library preparation stopped: ${it.javaClass.simpleName}") }
            _progress.value = _progress.value.copy(running = false)
        }
    }

    private fun goAhead() = allowed(AppSettings.automix.value, AppSettings.ketmixLibrary.value, StemModel.charging())

    private suspend fun run() {
        if (!goAhead()) return
        AnalysisStore.init(appContext ?: return)
        // The most recent downloads first, and no more than the measurements Ketmix keeps (see AnalysisStore).
        val items = Downloader.items.value.filter { it.dashManifestUrl == null && it.file.isFile && it.file.absolutePath !in unreadable }.take(MAX_TITLES)
        var done = 0
        _progress.value = Progress(0, items.size, running = true)
        for (item in items) {
            if (!kotlin.coroutines.coroutineContext.isActive || !goAhead()) return
            // The title being mixed comes first: the phone's processor is left to it.
            while (Automix.busy) delay(2_000)
            val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
            val separate = AppSettings.ketmixStems.value && StemModel.anyReady
            val ok = runCatching { one(item, depth, separate) }.getOrDefault(false)
            if (!ok) unreadable += item.file.absolutePath
            done++
            _progress.value = Progress(done, items.size, running = true)
            if (done % 8 == 0) AnalysisStore.save()
        }
        AnalysisStore.save()
    }

    /** Measures and separates the intro and the ending of one downloaded file. False when the file cannot be decoded. */
    private fun one(item: Downloader.Item, depth: AnalysisDepth, separate: Boolean): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(item.file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(index)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else return false
            if (durationMs < 30_000L) return true
            if (!pending(item, depth, separate, durationMs)) return true
            extractor.selectTrack(index)
            val id = item.track.id
            val introLength = item.track.durationMs.takeIf { it > 0 } ?: durationMs
            part(extractor, format, id, introLength, true, 0L, minOf(depth.introMs, durationMs), depth, separate = false)
            val from = maxOf(0L, durationMs - depth.outroMs)
            part(extractor, format, id, durationMs, false, from, durationMs, depth, separate = false)
            if (separate && channels(format) == 2) whole(extractor, format, id, durationMs)
            return true
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun channels(format: MediaFormat) = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0

    /**
     * Separates a whole downloaded title, about a minute at a time (6.0.0). Each piece is read with a
     * margin on both sides so that its edges are separated like the middle of the title, and only its
     * own frames are kept: the result is the same separation, frame for frame, as one of the whole title.
     * Nothing is stored when the phone is unplugged half-way.
     */
    private fun whole(extractor: MediaExtractor, format: MediaFormat, id: String, durationMs: Long) {
        val quality = StemEngine.wanted() ?: return
        val stored = StemStore.wholeQuality(id)
        if (stored != null && stored >= quality) return
        val hop = StemDsp.HOP
        val samples = (durationMs * StemDsp.RATE / 1000L).toInt()
        val total = StemDsp.frames(samples)
        if (total <= 0) return
        val packed = ByteArray(total * StemDsp.NET_BINS / 2)
        val share = ByteArray(total); val level = ByteArray(total)
        val pieces = (total + WHOLE_PIECE_FRAMES - 1) / WHOLE_PIECE_FRAMES
        val per = (total + pieces - 1) / pieces
        var first = 0
        while (first < total) {
            if (!goAhead()) return
            val last = minOf(total, first + per)
            val regionFrame = maxOf(0, first - WHOLE_MARGIN_FRAMES)
            val regionStart = regionFrame * hop
            val regionEnd = minOf(samples, (last + WHOLE_MARGIN_FRAMES - 1) * hop + StemDsp.N_FFT)
            val fromUs = regionStart * 1_000_000L / StemDsp.RATE
            val untilUs = regionEnd * 1_000_000L / StemDsp.RATE
            val capture = PcmCapture(fromUs, untilUs)
            decode(extractor, format, fromUs, untilUs, capture)
            val pcm = capture.pcm() ?: return
            val l = Resampler.resample(pcm, 0, 2, capture.rate, StemDsp.RATE)
            val r = Resampler.resample(pcm, 1, 2, capture.rate, StemDsp.RATE)
            // The recording starts on the decoder's buffer that holds the region's start, a little before it.
            val skip = Math.round((fromUs - capture.startUs) * StemDsp.RATE / 1_000_000.0).toInt().coerceAtLeast(0)
            val length = regionEnd - regionStart
            val left = FloatArray(length) { i -> l.getOrElse(i + skip) { 0f } }
            val right = FloatArray(length) { i -> r.getOrElse(i + skip) { 0f } }
            val mask = StemEngine.separate(left, right, fromUs, quality) { !goAhead() } ?: return
            for (f in first until last) {
                val j = f - regionFrame
                if (j !in 0 until mask.frames) continue
                System.arraycopy(mask.packed, j * StemDsp.NET_BINS / 2, packed, f * StemDsp.NET_BINS / 2, StemDsp.NET_BINS / 2)
                mask.voiceShare?.let { share[f] = it[j] }; mask.voiceLevel?.let { level[f] = it[j] }
            }
            first = last
        }
        StemStore.saveWhole(id, StemMask(0L, total, packed, share, level, quality))
    }

    private fun part(extractor: MediaExtractor, format: MediaFormat, id: String, length: Long, intro: Boolean,
                     fromMs: Long, untilMs: Long, depth: AnalysisDepth, separate: Boolean) {
        val measured = (if (intro) AnalysisStore.intro(id, length, depth) else AnalysisStore.outro(id, length, depth)) != null
        val wantStem = separate && StemEngine.needs(id, length, intro)
        if (measured && !wantStem) return
        val stemFrom = if (intro) 0L else maxOf(fromMs, untilMs - StemEngine.MAX_PART_MS)
        val stemUntil = if (intro) minOf(untilMs, StemEngine.MAX_PART_MS) else untilMs
        val analysis = if (measured) null else AnalysisTap(fromMs * 1000L, untilMs * 1000L, depth.key, depth.thorough, null)
        val capture = if (wantStem) PcmCapture(stemFrom * 1000L, stemUntil * 1000L) else null
        val tap: PcmTap = when {
            analysis != null && capture != null -> BothTaps(analysis, capture)
            analysis != null -> analysis
            else -> capture ?: return
        }
        decode(extractor, format, fromMs * 1000L, untilMs * 1000L, tap)
        analysis?.result()?.let { AnalysisStore.put(id, length, intro, it) }
        capture?.pcm()?.let { pcm -> StemEngine.prepare(id, length, intro, pcm, capture.rate, capture.startUs) }
    }

    /** Decodes [fromUs]..[untilUs] of the selected track and hands the 16-bit sound to [tap], buffer by buffer. */
    private fun decode(extractor: MediaExtractor, format: MediaFormat, fromUs: Long, untilUs: Long, tap: PcmTap) {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return
        val decoder = MediaCodec.createDecoderByType(mime)
        val info = MediaCodec.BufferInfo()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var inputDone = false
            var outputDone = false
            val deadline = System.nanoTime() + 120_000_000_000L
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
                if (buffer != null && info.size > 0 && info.presentationTimeUs + 100_000L >= fromUs) {
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    tap.pcm(littleEndian(buffer), info.presentationTimeUs, rate, channels)
                }
                if (info.presentationTimeUs > untilUs) outputDone = true
                decoder.releaseOutputBuffer(index, false)
            }
        } finally {
            runCatching { decoder.stop() }; runCatching { decoder.release() }
        }
    }

    /** The taps read little-endian 16-bit sound, which is what Android's decoders give on every phone sold. */
    private fun littleEndian(buffer: ByteBuffer): ByteBuffer =
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) buffer.slice()
        else ByteBuffer.allocate(buffer.remaining()).also { out ->
            val s = buffer.slice().order(ByteOrder.BIG_ENDIAN).asShortBuffer()
            out.order(ByteOrder.LITTLE_ENDIAN); while (s.hasRemaining()) out.putShort(s.get()); out.flip()
        }
}
