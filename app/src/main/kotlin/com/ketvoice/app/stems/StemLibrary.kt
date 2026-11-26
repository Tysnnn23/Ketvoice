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
import com.ketvoice.app.automix.SongSections
import com.ketvoice.app.automix.PcmCapture
import com.ketvoice.app.automix.PcmTap
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * low priority in the work process (6.1, [StemWork]); nothing is done on battery, and nothing while a mix is playing.
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
    /** Files that could not be read: not tried again by this process. */
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

    internal fun pending(id: String, trackDurationMs: Long, depth: AnalysisDepth, separate: Boolean, durationMs: Long): Boolean {
        val introLength = trackDurationMs.takeIf { it > 0 } ?: durationMs
        val measured = AnalysisStore.intro(id, introLength, depth) != null && AnalysisStore.outro(id, durationMs, depth) != null
        // 6.0.0: downloaded titles are separated whole (the player's voice level works from it, and every mix can use it).
        val separated = !separate || StemEngine.wanted()?.let { q -> (StemStore.wholeQuality(id) ?: -1) >= q } != false
        return !(measured && separated)
    }

    fun wake() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(20_000)
            val outcome = runCatching { run() }.onFailure { android.util.Log.w("KetvoiceStems", "library preparation stopped: ${it.javaClass.simpleName}") }
                .getOrNull()
            _progress.value = _progress.value.copy(running = false)
            // The work process was stopped by Android (6.1): the music went on, the preparation starts again a little later.
            if (outcome == StemWork.Outcome.Lost) scope.launch { delay(LOST_RETRY_MS); wake() }
        }
    }

    private const val LOST_RETRY_MS = 60_000L

    // A title the listener is waiting for (the voice slider) comes first.
    private fun goAhead() = allowed(AppSettings.automix.value, AppSettings.ketmixLibrary.value, StemModel.charging()) && !VoiceSeparation.busy

    /**
     * Hands the downloads to the work process (6.1: decoding, measuring and separating them takes the most
     * memory of all, and a phone's battery manager stops the biggest process first: now never the music's).
     * While a mix is prepared or played it holds the work between two titles; it stops when the phone is unplugged.
     */
    private suspend fun run(): StemWork.Outcome? {
        if (!goAhead()) return null
        val context = appContext ?: return null
        AnalysisStore.init(context)
        // The most recent downloads first, and no more than the measurements Ketmix keeps (see AnalysisStore).
        val items = Downloader.items.value.filter { it.dashManifestUrl == null && it.file.isFile }.take(MAX_TITLES)
            .map { LibraryItem(it.track.id, it.file.absolutePath, it.track.durationMs) }
        _progress.value = Progress(0, items.size, running = true)
        val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
        val separate = AppSettings.ketmixStems.value && StemModel.anyReady
        return kotlinx.coroutines.coroutineScope {
            val work = async {
                StemWork.library(context, items, depth, separate) { done, total ->
                    _progress.value = Progress(done, total, running = true)
                    if (done % 8 == 0) AnalysisStore.refresh()
                }
            }
            val watch = launch {
                while (isActive) {
                    StemWork.hold(Automix.busy)
                    if (!allowed(AppSettings.automix.value, AppSettings.ketmixLibrary.value, StemModel.charging())) { work.cancel(); break }
                    delay(2_000)
                }
            }
            try {
                runCatching { work.await() }.getOrNull()
            } finally {
                watch.cancel()
                StemWork.hold(false)
                // What the work process measured joins what Ketmix knows here.
                AnalysisStore.refresh()
            }
        }
    }

    /** A downloaded file the work process prepares: its title, its path and the length the catalogue gives it. */
    internal data class LibraryItem(val id: String, val path: String, val trackDurationMs: Long)

    /**
     * Prepares [items] one after the other (in the work process, or here in the host tests). [goOn] stops it
     * (unplugged), [held] holds it between two titles (a mix, a voice the listener waits for), [separating]
     * stops a whole-title separation half-way (that voice), [progress] hears titles done and found.
     */
    internal suspend fun runItems(items: List<LibraryItem>, depth: AnalysisDepth, separate: Boolean, goOn: () -> Boolean,
                                  held: () -> Boolean, progress: (Int, Int) -> Unit, separating: () -> Boolean = goOn) {
        var done = 0
        for (item in items) {
            if (!kotlin.coroutines.coroutineContext.isActive || !goOn()) break
            // The title being mixed comes first: the phone's processor is left to it.
            while (held() && kotlin.coroutines.coroutineContext.isActive) delay(2_000)
            if (item.path !in unreadable) {
                val ok = runCatching { one(item, depth, separate, separating) }.getOrDefault(false)
                if (!ok) unreadable += item.path
            }
            done++
            progress(done, items.size)
            if (done % 8 == 0) AnalysisStore.save()
        }
        AnalysisStore.save()
    }

    /** Measures and separates the intro and the ending of one downloaded file. False when the file cannot be decoded. */
    private fun one(item: LibraryItem, depth: AnalysisDepth, separate: Boolean, goOn: () -> Boolean): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(item.path)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(index)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else return false
            if (durationMs < 30_000L) return true
            if (!pending(item.id, item.trackDurationMs, depth, separate, durationMs)) return true
            extractor.selectTrack(index)
            val id = item.id
            val introLength = item.trackDurationMs.takeIf { it > 0 } ?: durationMs
            part(extractor, format, id, introLength, true, 0L, minOf(depth.introMs, durationMs), depth, separate = false)
            val from = maxOf(0L, durationMs - depth.outroMs)
            part(extractor, format, id, durationMs, false, from, durationMs, depth, separate = false)
            if (separate && channels(format) == 2) whole(extractor, format, id, durationMs, goOn)
            return true
        } finally {
            runCatching { extractor.release() }
        }
    }

    internal fun channels(format: MediaFormat) = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0

    private fun whole(extractor: MediaExtractor, format: MediaFormat, id: String, durationMs: Long, goOn: () -> Boolean) {
        val quality = StemEngine.wanted() ?: return
        val stored = StemStore.wholeQuality(id)
        if (stored != null && stored >= quality) return
        separateWhole(extractor, format, id, durationMs, quality, goOn, {})
    }

    /**
     * Separates a whole title with the model of [quality], about a minute at a time (6.0.0), and stores
     * it ([StemStore.saveWhole]). Each piece is read with a margin on both sides so that its edges are
     * separated like the middle of the title, and only its own frames are kept: the result is the same
     * separation, frame for frame, as one of the whole title. Nothing is stored when [goOn] turns false
     * half-way. [progress] hears the share done, 0..1. True when the separation was stored.
     */
    internal fun separateWhole(extractor: MediaExtractor, format: MediaFormat, id: String, durationMs: Long, quality: Int,
                               goOn: () -> Boolean, progress: (Float) -> Unit, startAtMs: Long = 0L,
                               partial: ((StemMask) -> Unit)? = null, rest: (Long, Boolean) -> Unit = { _, _ -> }): Boolean {
        val hop = StemDsp.HOP
        val samples = (durationMs * StemDsp.RATE / 1000L).toInt()
        val total = StemDsp.frames(samples)
        if (total <= 0) return false
        val packed = ByteArray(total * StemDsp.NET_BINS / 2)
        val share = ByteArray(total); val level = ByteArray(total)
        val pieces = (total + WHOLE_PIECE_FRAMES - 1) / WHOLE_PIECE_FRAMES
        val per = (total + pieces - 1) / pieces
        // 7.6 (voice76): on a stream the piece under the playhead goes first, and each piece done is taken up live.
        val start = StreamSeparation.pieceAt(startAtMs, per.toLong() * hop * 1000L / StemDsp.RATE, pieces)
        val half = StemDsp.NET_BINS / 2
        val done = StreamSeparation.run(total, per, start, goOn, { System.nanoTime() / 1_000_000L }, rest, work = { first, last ->
            val regionFrame = maxOf(0, first - WHOLE_MARGIN_FRAMES)
            val regionStart = regionFrame * hop
            val regionEnd = minOf(samples, (last + WHOLE_MARGIN_FRAMES - 1) * hop + StemDsp.N_FFT)
            val fromUs = regionStart * 1_000_000L / StemDsp.RATE
            val untilUs = regionEnd * 1_000_000L / StemDsp.RATE
            val capture = PcmCapture(fromUs, untilUs)
            decode(extractor, format, fromUs, untilUs, capture)
            val pcm = capture.pcm() ?: return@run false
            val l = Resampler.resample(pcm, 0, 2, capture.rate, StemDsp.RATE)
            val r = Resampler.resample(pcm, 1, 2, capture.rate, StemDsp.RATE)
            // The recording starts on the decoder's buffer that holds the region's start, a little before it.
            val skip = Math.round((fromUs - capture.startUs) * StemDsp.RATE / 1_000_000.0).toInt().coerceAtLeast(0)
            val length = regionEnd - regionStart
            val left = FloatArray(length) { i -> l.getOrElse(i + skip) { 0f } }
            val right = FloatArray(length) { i -> r.getOrElse(i + skip) { 0f } }
            val mask = StemEngine.separate(left, right, fromUs, quality) { !goOn() } ?: return@run false
            for (f in first until last) {
                val j = f - regionFrame
                if (j !in 0 until mask.frames) continue
                System.arraycopy(mask.packed, j * half, packed, f * half, half)
                mask.voiceShare?.let { share[f] = it[j] }; mask.voiceLevel?.let { level[f] = it[j] }
            }
            true
        }, publish = { from, until ->
            if (partial != null) runCatching {
                partial(StemMask(from.toLong() * hop * 1_000_000L / StemDsp.RATE, until - from, packed.copyOfRange(from * half, until * half),
                    share.copyOfRange(from, until), level.copyOfRange(from, until), quality))
            }
        }, progress = progress)
        if (!done) return false
        StemStore.saveWhole(id, StemMask(0L, total, packed, share, level, quality))
        return true
    }

    private fun part(extractor: MediaExtractor, format: MediaFormat, id: String, length: Long, intro: Boolean,
                     fromMs: Long, untilMs: Long, depth: AnalysisDepth, separate: Boolean) {
        val measured = (if (intro) AnalysisStore.intro(id, length, depth) else AnalysisStore.outro(id, length, depth)) != null
        val wantStem = separate && StemEngine.needs(id, length, intro)
        if (measured && !wantStem) return
        val stemFrom = if (intro) 0L else maxOf(fromMs, untilMs - StemEngine.MAX_PART_MS)
        val stemUntil = if (intro) minOf(untilMs, StemEngine.MAX_PART_MS) else untilMs
        val analysis = if (measured) null else AnalysisTap(fromMs * 1000L, untilMs * 1000L, depth.key, depth.thorough, null,
            sectionsOf = (!intro).takeIf { SongSections.wanted(depth) })
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
    internal fun decode(extractor: MediaExtractor, format: MediaFormat, fromUs: Long, untilUs: Long, tap: PcmTap) {
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
