package com.ketvoice.desktop.lyrics

import com.ketvoice.app.data.LyricsRank
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.stems.StemEngine
import com.ketvoice.app.stems.StemMask
import com.ketvoice.app.stems.StemStore
import com.ketvoice.desktop.ketmix.IdleWatch
import com.ketvoice.desktop.ketmix.KetmixPower
import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * When the word sync by AI runs (Windows 2.0, desktop PCs): on a title that plays with lyrics timed by line only (or
 * not at all), a few seconds after it starts, and over the downloads and the local files while the PC is idle
 * ([IdleWatch]). One title at a time on a background thread of low priority, never on the player's: playback is never
 * held up. Only in « Puissance de Ketmix » « Maximum » (Auto on a desktop PC), with the setting on and the model there.
 */
object AiSync {
    sealed interface Status {
        data object Off : Status
        /** The model is not downloaded yet ([AlignModels]). */
        data object NeedsModel : Status
        data object Ready : Status
        data class Working(val trackId: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status

    private const val PLAY_DELAY_MS = 6_000L
    private const val IDLE_POLL_MS = 60_000L
    private const val MAX_MS = 12 * 60_000L

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ketvoice-lyrics-ai").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val running = Mutex()

    // Seams (tests).
    internal var eligible: () -> Boolean = { LyricsPcSettings.aiSync.value && KetmixPower.current() == KetmixPower.Tier.Maximum }
    internal var openModel: () -> AlignModel? = { AlignModels.open() }
    internal var modelReady: () -> Boolean = { AlignModels.ready }
    internal var voiceOf: suspend (Track) -> FloatArray? = { voiceFor(it) }

    @OptIn(FlowPreview::class)
    fun start(context: android.content.Context, scope: CoroutineScope) {
        AiLyrics.init(context.filesDir)
        AlignModels.init(context.filesDir)
        scope.launch {
            AlignModels.pack.state.collect { refreshStatus() }
        }
        // The title playing: once its lyrics are known and it has played a few seconds.
        scope.launch {
            LyricsEngine.raw.debounce(500).collectLatest { raw ->
                raw ?: return@collectLatest
                refreshStatus()
                if (!wanted(raw.track, raw.state)) return@collectLatest
                delay(PLAY_DELAY_MS)
                if (Player.state.value.current?.id != raw.track.id) return@collectLatest
                // Not a child of this collector: a newer state of the same lyrics must not stop the work.
                scope.launch(worker) { run(raw.track, raw.state.lines) }
            }
        }
        scope.launch(worker) {
            while (isActive) {
                delay(IDLE_POLL_MS)
                if (eligible() && modelReady() && IdleWatch.idle(IdleWatch.snapshot())) runCatching { idlePass() }.onFailure { AppLog.w("lyrics", "idle word sync: $it") }
            }
        }
    }

    fun refreshStatus() {
        if (_status.value is Status.Working) return
        _status.value = when { !eligible() -> Status.Off; !modelReady() -> Status.NeedsModel; else -> Status.Ready }
    }

    /** Downloads the model (« Télécharger », about 970 MB). */
    fun download() { AlignModels.pack.download() }

    /** Whether these lyrics of [track] are for the AI: timed by line or not at all, not the listener's own, not tried. */
    internal fun wanted(track: Track, state: LyricsRepo.State): Boolean {
        if (!eligible() || !modelReady() || state.loading || state.trackId != track.id) return false
        if (state.sourceKey == LyricsRepo.SOURCE_CUSTOM || state.lines.size < 2) return false
        if (LyricsRank.level(state.lines) > LyricsRank.LINE) return false
        return !AiLyrics.tried(track.id, AiLyrics.basis(state.lines))
    }

    /** Times [lines] of [track] word by word and keeps them; shows them at once if the title is still playing. */
    internal suspend fun run(track: Track, lines: List<MusicApi.LyricLine>): Boolean = running.withLock {
        val basis = AiLyrics.basis(lines)
        if (AiLyrics.tried(track.id, basis)) return false
        _status.value = Status.Working(track.id)
        try {
            val result = withContext(worker) { timed(track, lines) }
            if (result == null) { AiLyrics.failed(track.id, basis); return false }
            AiLyrics.save(track.id, basis, result, track.title, track.artist)
            AppLog.i("lyrics", "words timed by AI: ${result.count { it.words.size >= 2 }}/${lines.size} lines")
            if (Player.state.value.current?.id == track.id) LyricsRepo.load(track, force = true)
            return true
        } finally { _status.value = Status.Ready; refreshStatus() }
    }

    private suspend fun timed(track: Track, lines: List<MusicApi.LyricLine>): List<MusicApi.LyricLine>? {
        val voice = voiceOf(track) ?: return null
        val model = openModel() ?: return null
        return model.use { m -> runCatching { WordAligner(m).align(lines, voice) }.onFailure { AppLog.w("lyrics", "word sync: $it") }.getOrNull() }
    }

    /** The voice of [track] at 16 kHz: its separation when there is one (made now with the best model there otherwise). */
    private suspend fun voiceFor(track: Track): FloatArray? {
        val source = Player.loadedSource()?.takeIf { Player.state.value.current?.id == track.id }
            ?: com.ketvoice.desktop.player.DesktopStreams.source(track) ?: return null
        val pcm = com.ketvoice.desktop.stems.DesktopVoice.decode(source, 0L, MAX_MS) ?: return null
        val mask = StemStore.loadWhole(track.id) ?: separate(track, pcm)
        return VoiceAudio.voice16k(pcm, mask)
    }

    private fun separate(track: Track, pcm: ShortArray): StemMask? {
        val quality = if (StemEngine.studioReady()) StemMask.STUDIO else StemEngine.forMix() ?: return null
        return runCatching { com.ketvoice.desktop.stems.DesktopVoice.separatePcm(pcm, quality, {}) { false } }.getOrNull()
            ?.also { StemStore.saveWhole(track.id, it) }
    }

    /** While the PC is idle: the downloads and local files whose lyrics are timed by line, one after the other. */
    private suspend fun idlePass() {
        for (track in libraryTracks()) {
            if (!eligible() || IdleWatch.interrupted(IdleWatch.snapshot())) return
            val lines = (com.ketvoice.app.data.Downloader.localLyrics(track.id) ?: com.ketvoice.app.data.LocalLyrics.lines(track.id))?.second ?: continue
            if (lines.size < 2 || LyricsRank.level(lines) > LyricsRank.LINE || AiLyrics.tried(track.id, AiLyrics.basis(lines))) continue
            run(track, lines)
        }
    }

    private fun libraryTracks(): List<Track> =
        com.ketvoice.desktop.library.DesktopDownloader.items.value.map { it.track } + com.ketvoice.desktop.library.LocalFolders.tracks.value
}
