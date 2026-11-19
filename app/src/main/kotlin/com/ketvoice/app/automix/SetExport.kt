package com.ketvoice.app.automix

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * « Exporter le set » (7.0.0): the set rendered offline to one audio file (AAC in M4A, WAV when the phone has no AAC
 * encoder), faster than it plays, then handed to the share sheet. Only titles on the phone (downloads, local files)
 * are rendered: a streamed title is never recorded, it is left out, and a set with fewer than two titles on the
 * phone is refused.
 */
internal object SetExport {
    sealed interface State {
        data object Idle : State
        data class Working(val fraction: Float, val note: String) : State
        data class Done(val file: File, val mime: String, val skipped: Int) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** What may be exported of [set]: its titles on the phone, and how many streamed ones are left out; null = refused. */
    fun check(set: List<Track>, offline: (Track) -> String?): Pair<List<Track>, Int>? {
        val kept = set.filter { offline(it) != null }
        return if (kept.size < 2) null else kept to set.size - kept.size
    }

    const val REFUSED = "Exportez un set d'au moins deux titres téléchargés ou présents sur le téléphone : les titres en streaming ne sont jamais enregistrés."

    fun start(context: Context, set: List<Track>) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val checked = check(set, DjSet::offline)
        if (checked == null) { _state.value = State.Failed(REFUSED); return }
        job = scope.launch {
            val target = File(File(app.cacheDir, "exports").apply { mkdirs() }, "Ketvoice-DJ-set-${System.currentTimeMillis() / 1000}")
            try {
                _state.value = State.Working(0f, "Préparation des titres…")
                val (titles, plans) = prepare(app, checked.first)
                if (!isActive) return@launch
                val (sink, file, mime) = sink(target)
                val ok = SetRender.render(titles, plans, sink, cancelled = { !isActive }) { done ->
                    _state.value = State.Working(done, "Rendu du set…")
                }
                if (!ok) { file.delete(); _state.value = State.Idle; return@launch }
                val done = State.Done(file, mime, checked.second)
                _state.value = done
                kotlinx.coroutines.withContext(Dispatchers.Main) { share(app, done) }
            } catch (failure: Exception) {
                _state.value = if (isActive) State.Failed("L'export du set a échoué.") else State.Idle
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; _state.value = State.Idle }

    fun dismiss() { if (job?.isActive != true) _state.value = State.Idle }

    /** The rendered set to the Android share sheet. */
    fun share(context: Context, done: State.Done) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", done.file)
        val send = Intent(Intent.ACTION_SEND).setType(done.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Partager le set")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Each title's real length (from its file), measured when Ketmix never did, and the best Ketmix plan of each pair. */
    private fun prepare(context: Context, tracks: List<Track>): Pair<List<SetTitle>, List<MixPlan?>> {
        val titles = tracks.map { track ->
            runCatching { DjSet.measureTitle(context, track) }
            val source = DjSet.offline(track)!!
            val length = DjSet.open(context, source)?.let { (extractor, format) ->
                runCatching { format.getLong(MediaFormat.KEY_DURATION) / 1000L }.getOrNull().also { extractor.release() }
            } ?: track.durationMs
            SetTitle(length) { fromMs -> DecodedSource(context, source, fromMs) }
        }
        AnalysisStore.save()
        return titles to plans(tracks, titles.map { it.durationMs })
    }

    /** The plan of every pair, as Ketmix plays it (lyric rules, loop blend, variety), at the titles' own speed. */
    internal fun plans(tracks: List<Track>, lengths: List<Long>): List<MixPlan?> {
        var recent = emptyList<MixKind>()
        var lastVariant = -1
        var looped = false
        return tracks.indices.map { i ->
            if (i == tracks.lastIndex) return@map null
            val plan = plan(tracks[i], lengths[i], tracks[i + 1], lengths[i + 1],
                MixContext(lastVariant = lastVariant, recentKinds = recent, loopedLast = looped))
            recent = MixVariety.remember(recent, plan)
            if (plan.mixes) lastVariant = plan.variant
            looped = plan.loopUs > 0L
            plan
        }
    }

    private fun plan(out: Track, outLength: Long, into: Track, inLength: Long, context: MixContext): MixPlan {
        val choice = MixPointStore.pair(out.id, into.id)
        val shape = choice?.shape ?: MixShape.DEFAULT
        val style = choice?.style ?: MixStyle.of(AppSettings.ketmixStyle.value)
        val outFacts = facts(out, outLength, intro = false)
        val inFacts = facts(into, inLength, intro = true)
        fun make(s: MixStyle) = MixShaping.apply(TransitionPlanner.plan(outFacts, inFacts,
            context.copy(userOverlapMs = choice?.overlapMs ?: -1L), MixSettings(AppSettings.automixIntensity.value, s, tempoMatch = false)), shape)
        val plan = make(style).let { if (abs(it.rate - 1f) > .0005f) make(MixStyle.Fade) else it }
        // No separated decks here: the voice steps back through the centre cut, as without the separation model.
        return if (abs(plan.rate - 1f) > .0005f) MixPlan(MixKind.None, reason = "tempo") else plan.copy(stems = false, mashup = false)
    }

    private fun facts(track: Track, length: Long, intro: Boolean): MixFacts {
        val analysis = if (intro) AnalysisStore.intro(track.id, length) else AnalysisStore.outro(track.id, length)
        val mask = runCatching { com.ketvoice.app.stems.StemStore.load(track.id, length, intro) }.getOrNull()
        val lines = mask?.let { runCatching { com.ketvoice.app.stems.VoiceActivity.passages(it) }.getOrNull() }.orEmpty()
        return if (intro) MixFacts(length, analysis, vocalStartMs = lines.firstOrNull()?.first, userEntryMs = MixPointStore.entryOf(track.id, length), sungLines = lines)
        else MixFacts(length, analysis, vocalEndMs = lines.lastOrNull()?.last, userExitMs = MixPointStore.exitOf(track.id, length), sungLines = lines)
    }

    /** AAC in M4A when the phone encodes it, else WAV. */
    private fun sink(base: File): Triple<SetSink, File, String> = runCatching {
        val file = File(base.path + ".m4a")
        Triple<SetSink, File, String>(AacSink(file), file, "audio/mp4")
    }.getOrElse {
        val file = File(base.path + ".wav")
        Triple(WavSink(file), file, "audio/wav")
    }
}

/** A title on the phone decoded as it is read, from [fromMs], as stereo at [SetRender.RATE]. */
internal class DecodedSource(context: Context, source: String, fromMs: Long) : SetSource {
    private val opened = DjSet.open(context, source) ?: error("no audio")
    private val extractor: MediaExtractor = opened.first
    private val decoder = MediaCodec.createDecoderByType(opened.second.getString(MediaFormat.KEY_MIME)!!)
    private val fromUs = fromMs * 1000L
    private var channels = opened.second.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    private var resampler = StreamResampler(opened.second.getInteger(MediaFormat.KEY_SAMPLE_RATE))
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var ended = false
    private var idle = 0
    private var scratch = ShortArray(0)

    init {
        decoder.configure(opened.second, null, null, 0)
        decoder.start()
        extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    override fun read(out: ShortArray, at: Int, frames: Int): Int {
        var made = 0
        while (made < frames) {
            made += resampler.pull(out, at + made, frames - made, ended)
            if (made < frames) { if (ended) break; decodeSome() }
        }
        return made
    }

    private fun feed() {
        if (inputDone) return
        val index = decoder.dequeueInputBuffer(2_000)
        if (index < 0) return
        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
        if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
        else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
    }

    private fun decodeSome() {
        feed()
        val index = decoder.dequeueOutputBuffer(info, 5_000)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            resampler = StreamResampler(decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            return
        }
        if (index < 0) { if (++idle > 2_000) ended = true; return }
        idle = 0
        if (info.size > 0) take(decoder.getOutputBuffer(index)!!, info.offset, info.size, info.presentationTimeUs)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
        decoder.releaseOutputBuffer(index, false)
    }

    /** One decoded buffer, without the frames before [fromUs] (the seek lands on the sync frame before it). */
    private fun take(buffer: ByteBuffer, offset: Int, size: Int, timeUs: Long) {
        buffer.position(offset); buffer.limit(offset + size)
        val shorts = buffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
        val total = shorts.remaining() / channels
        val rate = resampler.rate
        val skip = if (timeUs >= fromUs) 0 else ((fromUs - timeUs) * rate / 1_000_000L).toInt().coerceAtMost(total)
        val frames = total - skip
        if (frames <= 0) return
        if (scratch.size < frames * channels) scratch = ShortArray(frames * channels)
        shorts.position(skip * channels); shorts.get(scratch, 0, frames * channels)
        resampler.push(scratch, frames, channels)
    }

    override fun close() {
        runCatching { decoder.stop() }; runCatching { decoder.release() }; runCatching { extractor.release() }
    }
}

/** 16-bit stereo WAV at [SetRender.RATE]; the header gets its sizes once the set is done. */
internal class WavSink(private val file: File) : SetSink {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var bytes = 0L
    private var chunk = ByteArray(0)

    override fun write(pcm: ShortArray, frames: Int) {
        val n = frames * 4
        if (chunk.size < n) chunk = ByteArray(n)
        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, frames * 2)
        out.write(chunk, 0, n)
        bytes += n
    }

    override fun finish() {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(SetRender.RATE).putInt(SetRender.RATE * 4)
            .putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(bytes.toInt())
        out.seek(0); out.write(header.array()); out.close()
    }

    override fun abort() { runCatching { out.close() }; file.delete() }
}

/** AAC-LC 256 kb/s in an M4A file, through the phone's own encoder. */
internal class AacSink(private val file: File) : SetSink {
    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer
    private var track = -1
    private var frames = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SetRender.RATE, 2)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SetRender.BLOCK * 4)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (failure: Exception) { runCatching { encoder.release() }; throw failure }
    }

    override fun write(pcm: ShortArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val index = encoder.dequeueInputBuffer(10_000)
            if (index < 0) { drain(false); continue }
            val buffer = encoder.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            val count = minOf(frames - done, buffer.remaining() / 4)
            buffer.asShortBuffer().put(pcm, done * 2, count * 2)
            encoder.queueInputBuffer(index, 0, count * 4, this.frames * 1_000_000L / SetRender.RATE, 0)
            this.frames += count; done += count
            drain(false)
        }
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start() }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++waits > 300) return
                index >= 0 -> {
                    val data = encoder.getOutputBuffer(index)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && track >= 0) muxer.writeSampleData(track, data, info)
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun finish() {
        val index = encoder.dequeueInputBuffer(100_000)
        if (index >= 0) encoder.queueInputBuffer(index, 0, 0, frames * 1_000_000L / SetRender.RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        release()
    }

    private fun release() {
        runCatching { encoder.stop() }; runCatching { encoder.release() }
        runCatching { if (track >= 0) muxer.stop() }; runCatching { muxer.release() }
    }

    override fun abort() { release(); file.delete() }
}
