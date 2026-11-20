@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.player

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.StreamResolver
import com.ketvoice.app.data.StreamQuality
import com.ketvoice.app.data.SourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

data class PlayerUiState(
    val queue: List<Track> = listOf(),
    val queueEntryIds: List<String> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatOne: Boolean = false,
    val autoplay: Boolean = true,
    val quality: String = "",
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val failed: Boolean = false,
    val audioSource: String = "",
    val searchingBetter: Boolean = false,
    val audioFallback: Boolean = false,
    val audioFallbackHttpStatus: Int? = null,
    /** Media3's name for the last playback error and what caused it, shown in the audio details to tell one failure from another. */
    val lastErrorCode: String? = null,
    /** Replays the whole queue from its first entry once the last one ends. */
    val repeatAll: Boolean = false,
    /** Entry ids in the order the queue had before shuffle; empty while not shuffled. */
    val linearOrder: List<String> = emptyList(),
    /** Entries Ketvoice added itself after the listener's queue (« Lecture automatique », 7.0). */
    val autoEntryIds: Set<String> = emptySet()
) {
    val current: Track? get() = if (index in queue.indices) queue[index] else null
    /**
     * Where the « Lecture automatique » section starts: the run of [autoEntryIds] that ends the queue after
     * the current title (an automatic title moved in front of the listener's ones joins their queue).
     * [queue].size when there is none.
     */
    val autoStart: Int get() {
        var i = queue.size
        while (i - 1 > index && queueEntryIds.getOrNull(i - 1) in autoEntryIds) i--
        return i
    }
}

/**
 * Contrôleur unique de lecture : MediaController -> PlaybackService.
 *
 * Stratégie de démarrage rapide :
 *   1. téléchargement local si présent,
 *   2. recherche du match Ultra MAX (MP3) + Last Babe (FLAC) en PARALLÈLE,
 *   3. la lecture démarre dès que le MP3 est prêt (sans attendre le FLAC),
 *   4. si la qualité « Sans perte » est visée et que le FLAC aboutit,
 *      bascule transparente à la position courante.
 */
object Player {

    /** Wall-clock budget for the whole parallel resolution pass. */
    private const val TOTAL_RESOLVE_BUDGET_MS = 40_000L
    /** The next entry is resolved again when the current track has this long left. */
    private const val WARM_NEXT_WINDOW_MS = 25_000L
    /** How long better streams found for a title may still be handed over (7.2). */
    private const val UPGRADE_WINDOW_MS = 120_000L

    private var controller: MediaController? = null
    /** How a play request relates to the queue order already on screen. */
    private enum class QueueOrigin { External, Keep, Internal }
    private data class PendingPlay(val track: Track, val queue: List<Track>, val index: Int?, val position: Long,
                                   val playing: Boolean, val origin: QueueOrigin, val shuffledFrom: List<Track>?)
    private var pendingPlay: PendingPlay? = null
    private var connected = false
    private var keepAliveJob: Job? = null
    private var sessionTrackingJob: Job? = null
    private var restoredSessionPending = false
    private var appContext: Context? = null
    private var generation = 0
    private var resolutionJob: Job? = null
    private var desiredPlay = true
    private var activeStream: MusicApi.StreamInfo? = null
    private var activeSourceId: String? = null
    private var fallbackAttempts = 0
    /** Times the current title waited for the network to come back (see [NetworkRecovery]). */
    private var networkRetries = 0
    private val failedStreamUrls = mutableSetOf<String>()
    private val failedSourceIds = mutableSetOf<String>()
    private var refreshedExpiredStream = false
    private var historyGeneration = -1
    private var resolving = false
    private var resolveStartedAt = 0L
    private data class Prefetched(val candidate: StreamResolver.Candidate, val at: Long)
    /** Streams resolved ahead of time for upcoming entries, by track id. */
    private val prefetched = java.util.concurrent.ConcurrentHashMap<String, Prefetched>()
    /** Entry already warmed for the end of the current track. */
    private var warmedNextEntry: String? = null
    private val history = PlayHistory()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Position courante (230 ms) : consommée uniquement par la barre de temps
     *  et les paroles, pour éviter de recomposer toute la fenêtre à chaque tic. */
    private val _position = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _position
    private val _seekEpoch = MutableStateFlow(0L)
    val seekEpoch: StateFlow<Long> = _seekEpoch
    val playbackEpoch: Int get() = generation

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    /** Decoder-selected format only. Provider labels are not proof of lossless audio. */
    fun decodedAudioInfo(): String {
        val format = decodedAudioFormat() ?: return "Format audio en attente"
        return listOfNotNull(format.sampleMimeType,
            format.sampleRate.takeIf { it > 0 }?.let { "$it Hz" },
            format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kb/s" },
            format.channelCount.takeIf { it > 0 }?.let { "$it ch" }).joinToString(" · ")
    }

    /** The decoder-selected audio format, or null before one is chosen (the Lossless badge, ui/common/LosslessBadge). */
    fun decodedAudioFormat(): androidx.media3.common.Format? {
        val group = controller?.currentTracks?.groups?.firstOrNull {
            it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected
        } ?: return null
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return null
        return group.getTrackFormat(index)
    }

    private var lastEndedKey: Pair<String?, Int>? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!resolving && !_state.value.failed) {
                desiredPlay = controller?.playWhenReady ?: playWhenReady
                _state.value = _state.value.copy(playWhenReady = desiredPlay)
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            val track = _state.value.current ?: return
            val failed = activeStream
            val sourceId = activeSourceId
            val status = generateSequence(error as Throwable?) { it.cause }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            val code0 = error.errorCodeName
            com.ketvoice.app.data.PlaybackJournal.add("error $code0${status?.let { " · HTTP $it" } ?: ""} · ${track.title}")
            // The network went away (a tunnel, Wi-Fi to mobile data, the phone asleep behind other apps): the stream is not
            // at fault. The same stream starts again where it stopped once the network is back, instead of the next
            // sources being tried, and failed, one after the other until playback stops (5.10.0).
            if (failed != null && status == null && NetworkRecovery.isNetworkError(error.errorCode) && networkRetries < NetworkRecovery.MAX_RETRIES) {
                networkRetries++
                resolving = true
                _state.value = _state.value.copy(isPlaying = false, buffering = true, failed = false, lastErrorCode = code0)
                resolutionJob?.cancel()
                cancelQualityUpgrade()
                val gen = generation
                val pos = controller?.currentPosition?.coerceAtLeast(0) ?: 0L
                val attempt = networkRetries
                resolutionJob = scope.launch {
                    val waitedFrom = android.os.SystemClock.elapsedRealtime()
                    while (!hasInternet() && android.os.SystemClock.elapsedRealtime() - waitedFrom < NetworkRecovery.WAIT_MS) delay(1_000)
                    delay(NetworkRecovery.backoffMs(attempt))
                    if (gen != generation) return@launch
                    com.ketvoice.app.data.PlaybackJournal.add("network back after ${(android.os.SystemClock.elapsedRealtime() - waitedFrom) / 1000} s · resuming at ${pos / 1000} s")
                    startItem(controller ?: return@launch, track, failed.url, pos, desiredPlay, AudioCache.key(track.id, failed), failed)
                }
                return
            }
            failed?.url?.let { failedStreamUrls += it }
            sourceId?.let { failedSourceIds += it }
            resolving = true
            val code = error.errorCodeName + (generateSequence(error.cause) { it.cause }.lastOrNull()?.let { " · ${it.javaClass.simpleName}" } ?: "")
            _state.value = _state.value.copy(audioFallback = true, audioFallbackHttpStatus = status, lastErrorCode = code,
                isPlaying = false, buffering = true, failed = false)
            resolutionJob?.cancel()
            cancelQualityUpgrade()
            if (fallbackAttempts++ >= SourcePriority.entries().count { it.enabled }.coerceAtLeast(1)) {
                if (tryOfflineFallback(track, controller?.currentPosition?.coerceAtLeast(0) ?: 0L, failed)) return
                resolving = false
                _state.value = _state.value.copy(isPlaying = false, buffering = false, playWhenReady = false,
                    quality = "Erreur de lecture · réessayez", failed = true)
                return
            }
            val gen = generation
            val pos = controller?.currentPosition?.coerceAtLeast(0) ?: 0L
            resolutionJob = scope.launch {
                val refresh = if (!refreshedExpiredStream && status in setOf(401, 403) && sourceId != null) {
                    refreshedExpiredStream = true
                    try {
                        withTimeoutOrNull(14_000) {
                            StreamResolver.sources(track, download = false, lossless = wantsLossless())
                                .firstOrNull { it.id == sourceId }?.resolve?.invoke()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } else null
                val alternative = refresh?.let { StreamResolver.Candidate(sourceId!!, 0, it) } ?: withTimeoutOrNull(25_000) {
                    if (track.id.startsWith("kv:upload:") || track.addon == "upload") null
                    else StreamResolver.candidates(track, download = false, lossless = wantsLossless(),
                        race = AppSettings.progressiveQuality.value,
                        excluded = failedStreamUrls.toSet(), excludedSources = failedSourceIds.toSet()).firstOrNull()
                }
                if (gen != generation) return@launch
                if (alternative != null) {
                    val stream = alternative.stream
                    activeStream = stream
                    activeSourceId = alternative.sourceId
                    startItem(controller ?: return@launch, track, stream.url, pos, desiredPlay, AudioCache.key(track.id, stream), stream)
                    _state.value = _state.value.copy(quality = label(stream), audioSource = sourceLabel(alternative.sourceId, stream))
                    AudioCache.cacheWhole(track.id, stream)
                } else {
                    if (tryOfflineFallback(track, pos, failed)) return@launch
                    resolving = false
                    _state.value = _state.value.copy(isPlaying = false, buffering = false, playWhenReady = false,
                        quality = "Flux indisponible · réessayez", failed = true)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (resolving) return
            _state.value = _state.value.copy(isPlaying = isPlaying)
            com.ketvoice.app.data.ActiveListenerTracker.onPlaybackStateChanged(_state.value.current, isPlaying)
            if (isPlaying && historyGeneration != generation) {
                android.util.Log.i("KetvoicePlayback", "playing_state_ms=${android.os.SystemClock.elapsedRealtime() - resolveStartedAt}")
                _state.value.current?.let { com.ketvoice.app.data.ListeningHistory.record(it) }
                historyGeneration = generation
                if (radio) topUpRadio() else topUpAutoplay()
            }
            if (isPlaying) _state.value.current?.let { track ->
                if (recordedListens.size > 500) recordedListens.clear()
                if (recordedListens.add(track.id)) com.ketvoice.app.data.YouTubeMusicSession.recordListen(track)
            }
            positionNow()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            positionNow()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (resolving) return
            _state.value = _state.value.copy(buffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING)
            positionNow()
            if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                val s = _state.value
                val key = s.current?.id to s.index
                // Right after a mixed hand-over, an end can only be the previous title's, reported late.
                if (awaitingDeck != null || android.os.SystemClock.elapsedRealtime() - adoptedAt < 1_500L) return
                // The next title already plays, mixed in: Automix hands over instead of loading it again.
                if (key != lastEndedKey && com.ketvoice.app.automix.Automix.takeOverEnd()) return
                if (key != lastEndedKey) {
                    lastEndedKey = key
                    if (SleepTimer.consumeEndOfTrack()) {
                        // The timer asked for silence after this title: stay on the next one, paused.
                        val step = QueueSteps.next(s.queue.size, s.index, repeatOne = false, s.repeatAll, autoplay = false, ended = true)
                        if (step is QueueStep.Jump) startPlayback(s.queue[step.index], s.queue, step.index, 0L, false, QueueOrigin.Internal)
                        else { desiredPlay = false; _state.value = s.copy(isPlaying = false, playWhenReady = false) }
                    } else advance(ended = true)
                }
            }
        }
    }

    fun connect(context: Context) {
        if (connected) return
        connected = true
        appContext = context.applicationContext
        observeSystemVolume(context.applicationContext)
        CanvasRepo.init(context.applicationContext)
        ArtworkRepo.init(context.applicationContext)
        com.ketvoice.app.data.MediaOverrides.init(context.applicationContext)
        Downloader.init(context.applicationContext)
        com.ketvoice.app.data.OfflineCapsule.init(context.applicationContext)
        com.ketvoice.app.data.PlaylistStore.init(context.applicationContext)
        com.ketvoice.app.data.ListeningHistory.init(context.applicationContext)
        com.ketvoice.app.data.ActiveListenerTracker.init(context.applicationContext)
        com.ketvoice.app.data.HomeFeed.start(context.applicationContext)
        MusicApi.init(context.applicationContext)
        com.ketvoice.app.data.CustomSources.init(context.applicationContext)
        com.ketvoice.app.data.LyricsOffsets.init(context.applicationContext)
        if (keepAliveJob?.isActive != true) keepAliveJob = com.ketvoice.app.data.LosslessKeepAlive.start(scope)
        AudioCache.init(context.applicationContext)
        if (sessionTrackingJob?.isActive != true) {
            val store = PlaybackSessionStore(context.applicationContext)
            val initialGeneration = generation
            sessionTrackingJob = scope.launch {
                val saved = store.load()
                if (saved != null && generation == initialGeneration && _state.value.queue.isEmpty() &&
                    pendingPlay == null && controller?.currentMediaItem == null) {
                    _state.value = saved.toPlayerState()
                    _position.value = saved.positionMs
                    lastListenedPosition = saved.positionMs
                    restoredSessionPending = true
                }
                // While playing, the position is also saved every ten seconds: the state no longer changes with it.
                launch {
                    var savedAt = -1L
                    while (true) {
                        delay(10_000)
                        val position = _position.value
                        if (controller?.isPlaying == true && position != savedAt) { store.save(_state.value, position); savedAt = position }
                    }
                }
                _state.collectLatest { current ->
                    delay(750)
                    store.save(current, _position.value)
                }
            }
        }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                c.volume = 1f
                pendingPlay?.let { pending ->
                    pendingPlay = null
                    startPlayback(pending.track, pending.queue, pending.index, pending.position, pending.playing,
                        pending.origin, pending.shuffledFrom)
                }
            } catch (_: Exception) {
                connected = false
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))

        // Lightweight position updates for the time rail and synchronized lyrics.
        // Behind other apps nothing shows the position: a second is enough for the listening time; paused, no tick.
        scope.launch {
            PositionTicks.run(_state, { controller?.isPlaying == true }, { com.ketvoice.app.automix.Automix.appInFront }) { positionNow() }
        }
    }

    private fun positionNow() {
        if (resolving) return
        val c = controller ?: return
        // After a mixed hand-over the controller still describes the previous deck for a moment.
        awaitingDeck?.let { expected ->
            if (c.currentMediaItem?.mediaId != expected) return
            awaitingDeck = null
        }
        val pos = c.currentPosition.coerceAtLeast(0L)
        // Temps d'écoute RÉEL : delta de position entre deux tics pendant la
        // lecture. Un tic normal ≈ 200 ms ; un saut (seek, piste suivante,
        // buffering) produit un delta hors fenêtre et est ignoré.
        val delta = pos - lastListenedPosition
        lastListenedPosition = pos
        if (delta in 1..2_500L) {
            com.ketvoice.app.data.ListeningHistory.addListenedMs(delta)
        }
        _position.value = pos
        val dur = if (c.duration > 0) c.duration else _state.value.durationMs
        if (dur > 0 && dur - pos in 1..WARM_NEXT_WINDOW_MS && c.isPlaying) warmNextEntry()
        // Only a new duration changes the shared state. The position has its own flow: copying it into the
        // state every few seconds recomposed every screen that follows the player (the whole app) while playing.
        if (dur != _state.value.durationMs) _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
    }

    /** Dernière position vue par le tic : base du delta de temps d'écoute réel. */
    private var lastListenedPosition = 0L

    // ---------------------------------------------------------------- Automix

    /** Media id the controller must report before its position is trusted again. */
    private var awaitingDeck: String? = null
    /** When the queue last moved to a title that was already playing (SystemClock.elapsedRealtime). */
    private var adoptedAt = -10_000L

    /** The queue entry that plays when the current title ends. */
    internal class NextEntry(val index: Int, val track: Track, val entryId: String)

    /** How to load a title on a deck without asking the network: what [startPlayback] would use first. */
    internal class DeckSource(val url: String, val cacheKey: String?, val stream: MusicApi.StreamInfo?, val offlineDash: Boolean,
                              val quality: String, val audioSource: String, val sourceId: String?)

    internal fun automixNext(): NextEntry? {
        val s = _state.value
        // A title on repeat comes back as its own next title: Ketmix may mix its end into its start.
        if (s.repeatOne) {
            if (!AppSettings.ketmixLoops.value) return null
            val current = s.current ?: return null
            return NextEntry(s.index, current, s.queueEntryIds.getOrNull(s.index) ?: return null)
        }
        val step = QueueSteps.next(s.queue.size, s.index, s.repeatOne, s.repeatAll, autoplay = false, ended = true)
        val target = (step as? QueueStep.Jump)?.index ?: return null
        if (target == s.index) return null
        return NextEntry(target, s.queue[target], s.queueEntryIds.getOrNull(target) ?: return null)
    }

    /**
     * The queue entries that follow the current title, in the order they will play, at most [count]
     * (5.11.0: Ketmix prepares several titles ahead). Empty on repeat-one.
     */
    internal fun automixUpcoming(count: Int): List<NextEntry> {
        val s = _state.value
        if (s.repeatOne) return listOfNotNull(automixNext())
        val out = ArrayList<NextEntry>(count)
        var index = s.index
        while (out.size < count) {
            val step = QueueSteps.next(s.queue.size, index, false, s.repeatAll, autoplay = false, ended = true)
            val target = (step as? QueueStep.Jump)?.index ?: break
            if (target == s.index || out.any { it.index == target }) break
            out += NextEntry(target, s.queue[target], s.queueEntryIds.getOrNull(target) ?: break)
            index = target
        }
        return out
    }

    private val pinnedEntries = HashSet<String>()
    /** The listener asked for this entry to play next (see [playNext]). */
    internal fun pinnedNext(entryId: String): Boolean = entryId in pinnedEntries

    /** True on a connection the listener pays by the megabyte: Ketmix then prepares one title ahead only. */
    internal fun onMeteredNetwork(): Boolean = isMetered()

    /** Resolves the next title early enough for Automix to read and prepare it. */
    internal fun warmForAutomix(track: Track) {
        val current = _state.value.current ?: return
        prefetchNext(generation, current, listOf(track), margin = 100_000L)
    }

    /**
     * [deckSource], and when nothing is ready yet the title is resolved right now, the way a normal
     * start would resolve it: Automix does not wait for a prefetch that may never come.
     */
    internal suspend fun resolveDeckSource(track: Track): DeckSource? {
        deckSource(track)?.let { return it }
        if (track.sourceOverride != null) return null
        if (track.addon == "upload" || track.id.startsWith("kv:upload:")) {
            val stream = withTimeoutOrNull(20_000) { runCatching { PublicationApi.stream(track, null) }.getOrNull() } ?: return null
            return DeckSource(stream.url, AudioCache.key(track.id, stream), stream, false, label(stream), "Publication Ketvoice", null)
        }
        if (!hasInternet()) return null
        val candidate = withTimeoutOrNull(25_000) {
            runCatching {
                StreamResolver.candidates(track, download = false, lossless = wantsLossless(), race = AppSettings.progressiveQuality.value).firstOrNull()
            }.getOrNull()
        } ?: return null
        return DeckSource(candidate.stream.url, if (candidate.stream.isDash) null else AudioCache.key(track.id, candidate.stream),
            candidate.stream, false, label(candidate.stream), sourceLabel(candidate.sourceId, candidate.stream), candidate.sourceId)
    }

    internal fun deckSource(track: Track): DeckSource? {
        if (track.id.startsWith("local:")) return track.streamUrl?.let { DeckSource(it, null, null, false, "Fichier local", "Fichier local", null) }
        // A source the listener picked by hand is resolved by the usual path.
        if (track.sourceOverride != null) return null
        Downloader.itemFor(track.id)?.let { item ->
            val dash = item.dashManifestUrl?.let {
                MusicApi.StreamInfo(it, "dash", "Sans perte", mimeType = androidx.media3.common.MimeTypes.APPLICATION_MPD, codec = "flac")
            }
            return DeckSource(item.dashManifestUrl ?: item.file.toURI().toString(), null, dash, item.dashManifestUrl != null,
                "Téléchargé · ${item.format}", "Téléchargé", null)
        }
        if (com.ketvoice.app.data.ImportedSong.id(track) == null) AudioCache.complete(track.id, wantsLossless())?.let { cached ->
            return DeckSource(cached.url, AudioCache.key(track.id, cached), cached, false, "En cache · ${label(cached)}", "En cache", null)
        }
        val ahead = prefetched[track.id]?.takeIf {
            // Still valid for the whole transition, not only for its start.
            StreamResolver.prefetchedFresh(it.candidate.stream.isDash, android.os.SystemClock.elapsedRealtime() - it.at + 20_000L) &&
                SourcePriority.entries().any { entry -> entry.id == it.candidate.sourceId && entry.enabled }
        }?.candidate ?: return null
        return DeckSource(ahead.stream.url, if (ahead.stream.isDash) null else AudioCache.key(track.id, ahead.stream), ahead.stream, false,
            label(ahead.stream), sourceLabel(ahead.sourceId, ahead.stream), ahead.sourceId)
    }

    /**
     * The next title is already playing on the deck that just became the session's: the
     * queue moves to it without loading anything.
     */
    internal fun adoptAutomixed(next: NextEntry, source: DeckSource) {
        val s = _state.value
        val target = s.queueEntryIds.indexOf(next.entryId).takeIf { it >= 0 } ?: next.index
        val track = s.queue.getOrNull(target) ?: return
        // A title looping into itself is not a step back in the history.
        if (target != s.index) s.queueEntryIds.getOrNull(s.index)?.let { history.push(it) }
        resolutionJob?.cancel()
        generation++
        resolving = false
        desiredPlay = true
        activeStream = source.stream
        activeSourceId = source.sourceId
        fallbackAttempts = 0
        networkRetries = 0
        failedStreamUrls.clear()
        failedSourceIds.clear()
        refreshedExpiredStream = false
        lastEndedKey = null
        warmedNextEntry = null
        prefetched.remove(track.id)
        awaitingDeck = track.id
        adoptedAt = android.os.SystemClock.elapsedRealtime()
        // The incoming title is a few seconds in already; the exact position follows with the controller.
        _position.value = 0L
        lastListenedPosition = 0L
        _state.value = s.copy(index = target, isPlaying = true, playWhenReady = true, positionMs = 0L,
            durationMs = track.durationMs, buffering = false, failed = false, quality = source.quality,
            audioSource = source.audioSource, searchingBetter = false, audioFallback = false, audioFallbackHttpStatus = null, lastErrorCode = null)
        _seekEpoch.value++
        com.ketvoice.app.data.ListeningHistory.rememberTrack(track)
        com.ketvoice.app.data.ListeningHistory.record(track)
        historyGeneration = generation
        if (recordedListens.size > 500) recordedListens.clear()
        if (recordedListens.add(track.id)) com.ketvoice.app.data.YouTubeMusicSession.recordListen(track)
        com.ketvoice.app.data.ActiveListenerTracker.onPlaybackStateChanged(track, true)
        scope.launch { CanvasRepo.resolve(track) }
        scope.launch { LyricsRepo.load(track) }
        scope.launch { ArtworkRepo.enrich(listOf(track)) }
        prefetchNext(generation, track)
        searchBetterAfterMix(track, source)
    }

    /**
     * A title Ketmix brought in plays the stream resolved ahead, which is the first that answered: it still
     * looks for better, like a title started by hand (7.2; before, a mixed-in title never got better). The
     * handoff waits for the end of the transition.
     */
    private fun searchBetterAfterMix(track: Track, source: DeckSource) {
        val stream = source.stream ?: return
        val sourceId = source.sourceId ?: return
        if (stream.isDash || !AppSettings.progressiveQuality.value || qualitySetting() == "Normal" ||
            AppSettings.bitPerfect.value || !hasInternet()) return
        val c = controller ?: return
        val gen = generation
        val better = QualityHandoff.BetterStream<StreamResolver.Candidate>({ StreamQuality.rank(it.stream) },
            StreamResolver.Candidate(sourceId, 0, stream))
        val searchOver = java.util.concurrent.atomic.AtomicBoolean(false)
        resolutionJob = scope.launch {
            try {
                withTimeoutOrNull(TOTAL_RESOLVE_BUDGET_MS) {
                    StreamResolver.candidates(track, download = false, lossless = wantsLossless(), race = true, excluded = setOf(stream.url))
                        .collect { if (gen == generation) better.offer(it) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* The stream heard stays. */ }
            finally { searchOver.set(true) }
        }
        handOverBetter(c, gen, track.id, better) { !searchOver.get() }
    }

    private fun tryOfflineFallback(track: Track, position: Long, failed: MusicApi.StreamInfo?): Boolean {
        if (failed == null) return false
        val c = controller ?: return false
        val local = Downloader.itemFor(track.id)
        if (local != null && local.file.toURI().toString() !in failedStreamUrls) {
            startDownloaded(c, track, local, position, desiredPlay)
            _state.value = _state.value.copy(quality = "Téléchargé · ${local.format}", audioSource = "Téléchargé", audioFallback = true)
            return true
        }
        val cached = AudioCache.complete(track.id, wantsLossless())
        if (cached != null && cached.url !in failedStreamUrls) {
            activeStream = cached
            activeSourceId = null
            startItem(c, track, cached.url, position, desiredPlay, AudioCache.key(track.id, cached), cached)
            _state.value = _state.value.copy(quality = "En cache · ${label(cached)}", audioSource = "En cache", audioFallback = true)
            return true
        }
        return false
    }

    // ------------------------------------------------------------ Qualité
    /** Réglage qualité courant selon le réseau réellement mesuré. */
    private fun qualitySetting(): String {
        return if (isMetered()) AppSettings.mobileQuality.value else AppSettings.wifiQuality.value
    }

    /** true si on doit monter en FLAC (Last Babe) après le démarrage MP3. */
    private fun wantsLossless(): Boolean = qualitySetting() == "Sans perte"

    private fun isMetered(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return !(wifi && unmetered)
    }

    private fun hasInternet(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------ Lecture
    /**
     * Plays [track] inside [queue]. With shuffle on, a queue the player does not have
     * yet is shuffled around the chosen track; [keepOrder] plays it exactly as given
     * (a session received from another device).
     */
    fun playTrack(track: Track, queue: List<Track> = listOf(track), startIndex: Int? = null,
                  startPositionMs: Long = 0L, autoPlay: Boolean = true, keepOrder: Boolean = false) =
        run { radio = false; radioMemory = null; startPlayback(track, queue, startIndex, startPositionMs, autoPlay,
            if (keepOrder) QueueOrigin.Keep else QueueOrigin.External) }

    /** Plays [tracks] in a random order; turning shuffle off restores the given order. */
    fun playShuffled(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled()
        val order = if (AppSettings.smartShuffle.value) QueueOrder.spread(shuffled, QueueOrder::artistKey, { it.id in recentlyHeard() })
            else shuffled
        startPlayback(order.first(), order, 0, 0L, true, QueueOrigin.External, shuffledFrom = tracks)
    }

    private fun startPlayback(track: Track, queue: List<Track>, startIndex: Int?, startPositionMs: Long, autoPlay: Boolean,
                              origin: QueueOrigin, shuffledFrom: List<Track>? = null, fromPrevious: Boolean = false) {
        restoredSessionPending = false
        // Whatever the listener chooses takes over from a transition in progress.
        com.ketvoice.app.automix.Automix.cancel()
        awaitingDeck = null
        val c = controller ?: run {
            pendingPlay = PendingPlay(track, queue, startIndex, startPositionMs, autoPlay, origin, shuffledFrom)
            appContext?.let { if (!connected) connect(it) }
            return
        }
        resolutionJob?.cancel()
        resolveStartedAt = android.os.SystemClock.elapsedRealtime()
        cancelQualityUpgrade()
        resolving = true
        desiredPlay = autoPlay
        activeStream = null
        activeSourceId = null
        fallbackAttempts = 0
        networkRetries = 0
        failedStreamUrls.clear()
        failedSourceIds.clear()
        refreshedExpiredStream = false
        c.pause()
        lastEndedKey = null
        // 1) Correspondance addon déjà présente dans la file : zéro recherche réseau.
        val localMatch = if (track.addonId.isNullOrBlank() && track.sourceOverride == null) {
            MusicApi.addonMatchIn(queue, track)?.takeIf { it.addon == "um" }
        } else null
        val seeded = if (localMatch?.addonId != null) {
            track.copy(
                addon = "um",
                addonId = localMatch.addonId,
                coverUrl = track.coverUrl ?: localMatch.coverUrl,
                album = track.album.ifBlank { localMatch.album },
                durationMs = if (track.durationMs > 0) track.durationMs else localMatch.durationMs
            )
        } else track

        // 2) Pochette déjà résolue par la recherche : affichage immédiat.
        val cachedCover = ArtworkRepo.coverForTrack(seeded)
        val resolvedTrack = if (cachedCover != null && seeded.coverUrl.isNullOrBlank()) {
            seeded.copy(coverUrl = cachedCover)
        } else seeded
        val initialQueue = if (resolvedTrack !== track) {
            queue.map { if (it.id == track.id) resolvedTrack else it }
        } else queue
        com.ketvoice.app.data.ListeningHistory.rememberTrack(resolvedTrack)
        val idx = startIndex?.takeIf { it in initialQueue.indices && initialQueue[it].id == track.id }
            ?: initialQueue.indexOfFirst { it.id == track.id }.let { if (it >= 0) it else 0 }
        val gen = ++generation
        val prev = _state.value
        val queueIds = initialQueue.map { it.id }
        val sameQueue = queueIds == prev.queue.map { it.id }
        val reconciled = QueueEntryIds.reconcile(prev.queue.map { it.id }, prev.queueEntryIds, queueIds)
        // Visible shuffle: the queue itself carries the playing order.
        val ordered = when {
            shuffledFrom != null ->
                QueueOrder.Result(initialQueue, reconciled, idx, QueueOrder.linearOf(shuffledFrom, initialQueue, reconciled))
            origin == QueueOrigin.Internal || sameQueue ->
                QueueOrder.Result(initialQueue, reconciled, idx, QueueOrder.syncLinear(prev.linearOrder, reconciled))
            origin == QueueOrigin.External && prev.shuffle -> QueueOrder.shuffle(initialQueue, reconciled, idx)
            else -> QueueOrder.Result(initialQueue, reconciled, idx, emptyList())
        }
        val leaving = prev.queueEntryIds.getOrNull(prev.index)
        if (origin == QueueOrigin.External && !sameQueue) history.clear()
        else if (!fromPrevious && leaving != null && leaving != ordered.entryIds.getOrNull(ordered.index)) history.push(leaving)
        warmedNextEntry = null
        _state.value = PlayerUiState(
            queue = ordered.queue,
            queueEntryIds = ordered.entryIds,
            index = ordered.index,
            isPlaying = false,
            playWhenReady = autoPlay,
            positionMs = startPositionMs.coerceAtLeast(0),
            buffering = true,
            shuffle = prev.shuffle || shuffledFrom != null,
            repeatOne = prev.repeatOne,
            repeatAll = prev.repeatAll,
            autoplay = prev.autoplay,
            linearOrder = ordered.linearOrder,
            autoEntryIds = if (origin == QueueOrigin.External && !sameQueue) emptySet()
                else prev.autoEntryIds.intersect(ordered.entryIds.toSet()),
            quality = "Connexion…",
            durationMs = resolvedTrack.durationMs
        )
        _position.value = startPositionMs.coerceAtLeast(0)

        // Canvas + paroles démarrent sans attendre la résolution audio. 7.2: a Canvas already on the device shows at
        // once; one still to fetch waits for the first sound (a few seconds at most), so its video never takes the
        // network from the audio's first bytes.
        scope.launch {
            if (!StartupOrder.mayLoadBeforeSound(StartupOrder.Load.Canvas, CanvasRepo.localPath(resolvedTrack.id) != null))
                awaitSound(gen, StartupOrder.WAIT_FOR_SOUND_MS)
            if (gen == generation) CanvasRepo.resolve(resolvedTrack)
        }
        scope.launch { LyricsRepo.load(resolvedTrack) }
        scope.launch { ArtworkRepo.enrich(listOf(resolvedTrack)) }

        resolutionJob = scope.launch(Dispatchers.Main) {
            // Fichier local (MediaStore) : lecture directe, aucune résolution réseau.
            if (resolvedTrack.id.startsWith("local:") && resolvedTrack.streamUrl != null) {
                activeStream = null
                startItem(c, resolvedTrack, resolvedTrack.streamUrl, startPositionMs.coerceAtLeast(0), desiredPlay)
                _state.value = _state.value.copy(quality = "Fichier local", buffering = false, audioSource = "Fichier local")
                resolving = false
                return@launch
            }
            val local = Downloader.itemFor(track.id)
            val explicit = resolvedTrack.sourceOverride != null && hasInternet() &&
                StreamResolver.sources(resolvedTrack, download = false, lossless = wantsLossless())
                    .any { it.id == resolvedTrack.sourceOverride }
            if (local != null && !explicit) {
                startDownloaded(c, resolvedTrack, local, startPositionMs.coerceAtLeast(0), desiredPlay)
                _state.value = _state.value.copy(quality = "Téléchargé · ${local.format}", audioSource = "Téléchargé")
                // 7.2: a streamed title after a downloaded one is resolved ahead too (it waited for the last 25 s).
                afterFirstSound(gen, resolvedTrack, null)
                return@launch
            }

            val cached = AudioCache.complete(track.id, wantsLossless())
            val waitForLossless = cached != null && !StreamResolver.useCachedImmediately(
                AppSettings.progressiveQuality.value, wantsLossless(), hasInternet(),
                SourcePriority.entries().any { it.enabled && (it.id == "api:last" || it.id.startsWith("cs:")) }, cached)
            fun start(stream: MusicApi.StreamInfo, position: Long = startPositionMs.coerceAtLeast(0), sourceId: String? = null) {
                val latest = _state.value.current ?: resolvedTrack
                activeStream = stream
                activeSourceId = sourceId
                startItem(c, latest, stream.url, position, desiredPlay, AudioCache.key(track.id, stream), stream)
                _state.value = _state.value.copy(quality = label(stream), isPlaying = c.isPlaying)
            }
            fun fallbackOffline(): Boolean {
                if (!explicit && !waitForLossless) return false
                if (local != null) {
                    startDownloaded(c, resolvedTrack, local, startPositionMs.coerceAtLeast(0), desiredPlay)
                    _state.value = _state.value.copy(quality = "Téléchargé · ${local.format}", audioSource = "Téléchargé", audioFallback = true)
                    return true
                }
                if (cached != null && com.ketvoice.app.data.ImportedSong.id(resolvedTrack) == null) {
                    start(cached)
                    _state.value = _state.value.copy(quality = "En cache · ${label(cached)}", audioSource = "En cache", audioFallback = true)
                    return true
                }
                return false
            }
            // Ordered mode respects the source priority; progressive mode races
            // sources and may upgrade the already playing stream.
            val progressive = AppSettings.progressiveQuality.value
            val highQuality = progressive && qualitySetting() != "Normal" && !AppSettings.bitPerfect.value
            var seededStream: MusicApi.StreamInfo? = null
            var seededSource = ""
            if (cached != null && !explicit && !waitForLossless && com.ketvoice.app.data.ImportedSong.id(resolvedTrack) == null) {
                start(cached)
                _state.value = _state.value.copy(quality = "En cache · ${label(cached)}", audioSource = "En cache")
                afterFirstSound(gen, resolvedTrack, null)
                // 7.2: a title in the cache at a lower quality than asked still looks for better, on a free network.
                if (!QualityHandoff.keepLookingFromCache(highQuality, hasInternet(), isMetered(), wantsLossless(), StreamQuality.rank(cached)))
                    return@launch
                seededStream = cached
            }
            val ahead = if (seededStream != null) null else prefetched.remove(track.id)?.takeIf {
                StreamResolver.prefetchedFresh(it.candidate.stream.isDash, android.os.SystemClock.elapsedRealtime() - it.at) &&
                    SourcePriority.entries().any { entry -> entry.id == it.candidate.sourceId && entry.enabled } &&
                    StreamResolver.canUsePrefetched(AppSettings.progressiveQuality.value,
                        StreamResolver.sources(resolvedTrack, download = false, lossless = wantsLossless()).firstOrNull()?.id,
                        it.candidate.sourceId)
            }?.candidate
            // A stream resolved ahead of time starts at once. Outside DASH it is only the
            // first candidate, so the better sources are still collected below.
            if (ahead != null && (!explicit || ahead.sourceId == resolvedTrack.sourceOverride) &&
                com.ketvoice.app.data.ImportedSong.id(resolvedTrack) == null) {
                start(ahead.stream, sourceId = ahead.sourceId)
                _state.value = _state.value.copy(audioSource = sourceLabel(ahead.sourceId, ahead.stream))
                val keepLooking = !ahead.stream.isDash && highQuality
                afterFirstSound(gen, resolvedTrack, ahead.stream.takeUnless { keepLooking })
                if (!keepLooking) return@launch
                seededStream = ahead.stream
                seededSource = ahead.sourceId
            }
            if (resolvedTrack.addon == "upload" || resolvedTrack.id.startsWith("kv:upload:")) {
                if (seededStream != null) return@launch
                val stream = PublicationApi.stream(resolvedTrack, cached)
                if (stream == null) {
                    resolving = false
                    _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false, quality = "Aucun flux disponible", failed = true)
                    return@launch
                }
                start(stream)
                _state.value = _state.value.copy(audioSource = "Publication Ketvoice")
                afterFirstSound(gen, resolvedTrack, stream)
                return@launch
            }
            _state.value = _state.value.copy(searchingBetter = highQuality)
            // The handoffs run beside this search (not inside it) and stop once it is over and nothing better waits.
            val searchOver = java.util.concurrent.atomic.AtomicBoolean(false)
            kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion {
                searchOver.set(true)
                if (gen == generation) _state.value = _state.value.copy(searchingBetter = false)
            }
            var currentStream: MusicApi.StreamInfo? = seededStream
            // 7.2: better streams wait here and are handed over by their own coroutine, the best first. Before,
            // each was tried inside the search (up to 15 s of waiting, then a handoff), which held back the next
            // answers, so a lossless stream found meanwhile could reach the 40 s budget and be cancelled.
            val better = QualityHandoff.BetterStream<StreamResolver.Candidate>({ StreamQuality.rank(it.stream) },
                seededStream?.let { StreamResolver.Candidate(seededSource, 0, it) })
            if (highQuality && seededStream != null) handOverBetter(c, gen, track.id, better) { !searchOver.get() }
            val candidates = StreamResolver.candidates(resolvedTrack, download = false, lossless = wantsLossless(), race = progressive)
            if (StreamResolver.sources(resolvedTrack, download = false, lossless = wantsLossless()).isEmpty()) {
                if (fallbackOffline()) return@launch
                resolving = false
                _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false,
                    quality = "Aucune source activée · ouvrez Sources", failed = true)
                return@launch
            }
            // Hard budget: a stalled provider must never leave an endless spinner.
            // Audio starts on the very first candidate; the remaining sources keep
            // resolving in the same window so a better stream can still upgrade it.
            val collected = try {
                withTimeoutOrNull(TOTAL_RESOLVE_BUDGET_MS) {
                    candidates.collect { candidate ->
                        if (gen != generation) throw kotlinx.coroutines.CancellationException()
                        if (currentStream == null) {
                            android.util.Log.i("KetvoicePlayback", "resolution_ms=${android.os.SystemClock.elapsedRealtime() - resolveStartedAt}")
                            currentStream = candidate.stream
                            _state.value = _state.value.copy(audioSource = sourceLabel(candidate.sourceId, candidate.stream))
                            // Playback starts on the first real audio byte; ExoPlayer keeps
                            // filling its buffer, and once the sound is heard the rest of the file
                            // and the next two queue items are prepared in the background.
                            start(candidate.stream, sourceId = candidate.sourceId)
                            afterFirstSound(gen, resolvedTrack, candidate.stream.takeUnless { highQuality })
                            if (highQuality) {
                                better.heard(candidate)
                                handOverBetter(c, gen, track.id, better) { !searchOver.get() }
                            }
                        } else if (highQuality && candidate.stream.url != currentStream?.url) better.offer(candidate)
                    }
                    true
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentStream == null && gen == generation) {
                    if (fallbackOffline()) return@launch
                    resolving = false
                    _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false,
                        quality = "Lecture impossible · réessayez", failed = true)
                }
                return@launch
            }
            if (collected == null && currentStream == null && gen == generation) {
                if (fallbackOffline()) return@launch
                resolving = false
                _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false,
                    quality = "Flux lent · réessayez", failed = true)
                return@launch
            }
            if (currentStream == null && gen == generation) {
                if (fallbackOffline()) return@launch
                // Last-resort fallback: the previous custom-source path, so a
                // manifest that works outside the resolver is never blocked here.
                val legacy = withTimeoutOrNull(15_000) {
                    runCatching { com.ketvoice.app.data.CustomSources.streamForTrack(resolvedTrack) }.getOrNull()
                }
                if (legacy != null && gen == generation) {
                    currentStream = legacy
                    start(legacy)
                    AudioCache.cacheWhole(track.id, legacy)
                    return@launch
                }
                resolving = false
                _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false, quality = "Aucun flux disponible", failed = true)
            }
        }
    }

    /** Waits, at most [maxMs], until the title of [gen] is heard. */
    private suspend fun awaitSound(gen: Int, maxMs: Long) {
        withTimeoutOrNull(maxMs) { while (gen == generation && controller?.isPlaying != true) kotlinx.coroutines.delay(100) }
    }

    /**
     * Once the title is heard (or after a few seconds): the next titles are prepared and [whole], when given, is
     * kept whole in the audio cache. Before 7.2 both started with the first byte: a second download of the same
     * file and the next titles' files took the network from the bytes the first sound waits for.
     */
    private fun afterFirstSound(gen: Int, track: Track, whole: MusicApi.StreamInfo?) {
        scope.launch {
            awaitSound(gen, StartupOrder.WAIT_FOR_SOUND_MS)
            if (gen != generation) return@launch
            whole?.let { AudioCache.cacheWhole(track.id, it) }
            prefetchNext(gen, track)
        }
    }

    /** Asks the service to hand the title over to [stream] (same recording, better quality); true once it is heard. */
    private suspend fun handOver(c: MediaController, gen: Int, trackId: String, stream: MusicApi.StreamInfo, sourceId: String): Boolean {
        if (gen != generation) return false
        val future = c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_UPGRADE, Bundle.EMPTY),
            Bundle().apply {
                putString("id", trackId); putString("url", stream.url)
                putString("cacheKey", if (stream.isDash) null else AudioCache.key(trackId, stream))
                putString("mimeType", if (stream.isDash) androidx.media3.common.MimeTypes.APPLICATION_MPD
                    else stream.mimeType?.substringBefore(';')?.trim())
            })
        val switched = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
            future.addListener({
                if (continuation.isActive) continuation.resumeWith(Result.success(
                    runCatching { future.get().resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS }.getOrDefault(false)))
            }, androidx.core.content.ContextCompat.getMainExecutor(appContext!!))
            continuation.invokeOnCancellation { future.cancel(false) }
        }
        // A failed standby decoder never interrupts the audible stream.
        if (!switched || gen != generation) return false
        activeStream = stream
        activeSourceId = sourceId
        _state.value = _state.value.copy(quality = label(stream), audioSource = sourceLabel(sourceId, stream))
        return true
    }

    /**
     * Hands the title over to the better streams [better] receives, one at a time and the best first, once the sound
     * plays and no Ketmix transition runs (7.2). Ends when [searching] is over and nothing waits; the stream that
     * stays is then kept whole in the audio cache.
     */
    private fun handOverBetter(c: MediaController, gen: Int, trackId: String,
                               better: QualityHandoff.BetterStream<StreamResolver.Candidate>, searching: () -> Boolean) = scope.launch {
        withTimeoutOrNull(UPGRADE_WINDOW_MS) {
            while (gen == generation) {
                val target = better.next()
                if (target == null) { if (!searching()) break; kotlinx.coroutines.delay(150); continue }
                withTimeoutOrNull(15_000) {
                    while (gen == generation && (com.ketvoice.app.automix.Automix.mixing ||
                            !(c.isPlaying || (!c.playWhenReady && c.playbackState == androidx.media3.common.Player.STATE_READY))))
                        kotlinx.coroutines.delay(150)
                }
                if (gen != generation) break
                if (handOver(c, gen, trackId, target.stream, target.sourceId)) better.heard(target)
                else if (better.failed(target)) kotlinx.coroutines.delay(QualityHandoff.BetterStream.RETRY_MS)
            }
        }
        if (gen != generation) return@launch
        awaitSound(gen, StartupOrder.WAIT_FOR_SOUND_MS)
        activeStream?.takeIf { gen == generation }?.let { AudioCache.cacheWhole(trackId, it) }
    }

    /**
     * Warms the next two queue items after playback actually started: their stream
     * is resolved and its bytes enter the audio cache, so the transition is instant.
     * Resolution follows the user's source mode; byte caching honours metered connections.
     */
    private fun prefetchNext(gen: Int, current: Track, targets: List<Track>? = null, margin: Long = 0L) {
        val state = _state.value
        // The next titles' Canvas are fetched while this one plays: each opens with its Canvas already on the device.
        if (targets == null) {
            scope.launch { CanvasRepo.aheadOf(state.queue, state.index) }
        }
        val upcoming = (targets ?: (1..2).mapNotNull { offset -> state.queue.getOrNull(state.index + offset) })
            .filter { it.id != current.id && !it.id.startsWith("local:") && Downloader.itemFor(it.id) == null }
        if (upcoming.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (item in upcoming) {
                if (gen != generation) return@launch
                if (AudioCache.complete(item.id, wantsLossless()) != null) continue
                // Still valid when the track ends: no second resolution.
                if (prefetched[item.id]?.let { StreamResolver.prefetchedFresh(it.candidate.stream.isDash,
                        android.os.SystemClock.elapsedRealtime() - it.at + margin) } == true) continue
                runCatching {
                    StreamResolver.candidates(item, download = false, lossless = wantsLossless(),
                        race = AppSettings.progressiveQuality.value)
                        .firstOrNull()
                }.getOrNull()?.let { candidate ->
                    val reusableDash = candidate.stream.isDash &&
                        com.ketvoice.app.data.CustomSources.reusableManifestUrl(candidate.stream.url)
                    if ((reusableDash || !candidate.stream.isDash) && gen == generation) {
                        if (prefetched.size >= 10) prefetched.clear()
                        prefetched[item.id] = Prefetched(candidate, android.os.SystemClock.elapsedRealtime())
                    }
                    if (!reusableDash && !isMetered()) AudioCache.cacheWhole(item.id, candidate.stream)
                    if (gen == generation) runCatching { ArtworkRepo.enrich(listOf(item)) }
                }
            }
        }
    }

    /**
     * In the last seconds of a track, what plays next is resolved (again if the first
     * resolution is too old), so the transition does not wait for the network.
     */
    private fun warmNextEntry() {
        val s = _state.value
        val step = QueueSteps.next(s.queue.size, s.index, s.repeatOne, s.repeatAll, autoplay = false, ended = true)
        val target = (step as? QueueStep.Jump)?.index ?: return
        val key = s.queueEntryIds.getOrNull(target) ?: return
        if (key == warmedNextEntry) return
        warmedNextEntry = key
        prefetchNext(generation, s.current ?: return, listOf(s.queue[target]), margin = WARM_NEXT_WINDOW_MS + 5_000L)
    }

    /** The queue is a radio: it never runs out, each new batch built from the title playing then. */
    @Volatile var radio = false
        private set
    /** What the radio has played in this session (6.1): no title, recording or artist comes back too soon. */
    @Volatile private var radioMemory: RadioMemory? = null

    /**
     * A radio from the title [seed]: the title, then titles of artists like it, of its artist and a
     * few of the listener's favourites of that circle, more of them before the queue runs out.
     * Never an artist the listener blocked, never a title twice.
     */
    fun startRadio(seed: Track) = launchRadio(RadioSeed.Title(seed), listOf(seed))

    /** An artist's radio: led by one of [tracks] (the artist's own), then the same mix as a title radio around the artist. */
    fun startArtistRadio(name: String, catalogId: String?, tracks: List<Track>) {
        val lead = tracks.firstOrNull() ?: return
        launchRadio(RadioSeed.Artist(name, catalogId), listOf(lead))
    }

    private fun launchRadio(seed: RadioSeed, lead: List<Track>) {
        val gen = generation
        scope.launch {
            val memory = RadioMemory(seed).apply { rememberAll(lead) }
            val first = radioBatch(lead.last(), memory)
            if (gen != generation && _state.value.current?.id != lead.first().id) return@launch
            playQueue(lead + first, 0)
            radioMemory = memory
            radio = true
            setAutoplay(true)
        }
    }

    private suspend fun radioBatch(from: Track, memory: RadioMemory): List<Track> = kotlinx.coroutines.coroutineScope {
        val catalog = com.ketvoice.app.data.CatalogRepo
        val seedArtist = memory.seed.artist
        val seedId = (memory.seed as? RadioSeed.Artist)?.catalogId
        // A title radio drifts with what plays; an artist radio stays around its artist.
        val current = if (memory.seed is RadioSeed.Artist) seedArtist else com.ketvoice.app.data.MediaOverrides.mainArtist(from.artist)
        val currentId = if (memory.seed is RadioSeed.Artist) seedId
            else from.artistId?.takeIf { from.addon == "um" && it.all(Char::isDigit) }
        val style = async { runCatching { catalog.artistRadio(current, currentId) }.getOrDefault(emptyList()) }
        val anchor = async {
            if (current.equals(seedArtist, true)) emptyList()
            else runCatching { catalog.artistRadio(seedArtist, seedId) }.getOrDefault(emptyList())
        }
        val related = async { runCatching { catalog.relatedArtists(current, currentId) }.getOrDefault(emptyList()).take(6) }
        val own = async {
            runCatching { catalog.artistTop(current, currentId) }.getOrDefault(emptyList())
                .ifEmpty { runCatching { MusicApi.searchYtMusic(current) }.getOrDefault(emptyList()) }
        }
        val close = related.await()
        val closeTops = close.shuffled().take(2).map { artist ->
            async { runCatching { catalog.artistTop(artist.name, artist.id) }.getOrDefault(emptyList()).take(6) }
        }.flatMap { it.await() }
        val ownTracks = own.await()
        val currentKey = com.ketvoice.app.data.MediaOverrides.normalize(current)
        var fresh = (style.await() + anchor.await() + closeTops).filter { RadioMix.artistKey(it) != currentKey }
        // Titles the catalogue does not know (YouTube, publications): the search, other artists only.
        if (fresh.isEmpty()) fresh = runCatching { MusicApi.searchYtMusic("${from.title} ${from.artist}") + MusicApi.searchYtMusic(current) }
            .getOrDefault(emptyList()).filter { RadioMix.artistKey(it) != currentKey }
        // The listener's favourites of the same circle: their artists, and the artists like them.
        val circle = (listOf(seedArtist, current) + close.map { it.name } + fresh.map { it.artist })
            .mapTo(HashSet()) { com.ketvoice.app.data.MediaOverrides.normalize(com.ketvoice.app.data.MediaOverrides.mainArtist(it)) }
        val counts = com.ketvoice.app.data.ListeningHistory.playCounts.value
        val favorites = AppSettings.favoriteIds.value
        val familiar = com.ketvoice.app.data.ListeningHistory.knownTracks.value
            .filter { ((counts[it.id] ?: 0) >= 2 || it.id in favorites) && RadioMix.artistKey(it) in circle }
            .let { com.ketvoice.app.data.PersonalMixes.withLocalFiles(it, com.ketvoice.app.data.LocalMusic.tracks.value) }
        RadioMix.pick(fresh, ownTracks.filter { RadioMix.artistKey(it) == currentKey }, familiar, memory,
            com.ketvoice.app.data.ArtistPrefs::allows, kotlin.random.Random(from.id.hashCode().toLong() * 31 + memory.size),
            lately = NoRepeatWindow.recent(com.ketvoice.app.data.ListeningHistory.heardAt.value, System.currentTimeMillis()))
    }

    /** A radio grows before it runs out: a few titles before the end of the queue, the next batch joins it. */
    private fun topUpRadio() {
        val s = _state.value
        val memory = radioMemory ?: return
        if (!radio || extending || s.queue.size - 1 - s.index > RADIO_AHEAD) return
        val track = s.current ?: return
        val gen = generation
        extending = true
        scope.launch {
            try {
                memory.rememberAll(s.queue)
                val more = radioBatch(track, memory)
                if (radio && radioMemory === memory && more.isNotEmpty() && (gen == generation || _state.value.current?.id == track.id)) enqueue(more)
            } finally { extending = false }
        }
    }

    /** Titles left in a radio's queue when the next batch is fetched. */
    private val RADIO_AHEAD = 3

    fun playQueue(tracks: List<Track>, start: Int = 0) {
        if (tracks.isEmpty()) return
        val index = start.coerceIn(tracks.indices)
        playTrack(tracks[index], tracks, index)
    }

    /** Insère un morceau juste après celui en cours (sans interrompre la lecture). */
    fun playNext(track: Track) {        val s = _state.value
        if (s.queue.isEmpty()) {
            playTrack(track)
            return
        }
        val q = s.queue.toMutableList()
        val insertAt = (s.index + 1).coerceIn(0, q.size)
        q.add(insertAt, track)
        val keys = s.queueEntryIds.toMutableList()
        val entry = java.util.UUID.randomUUID().toString()
        keys.add(insertAt.coerceAtMost(keys.size), entry)
        // Asked for by the listener: Ketmix's smart queue never moves another title in front of it.
        if (pinnedEntries.size > 200) pinnedEntries.clear()
        pinnedEntries += entry
        _state.value = _state.value.copy(queue = q, queueEntryIds = keys,
            linearOrder = QueueOrder.syncLinear(s.linearOrder, keys, afterKey = s.queueEntryIds.getOrNull(s.index)))
        appContext?.let {
            android.widget.Toast.makeText(
                it, com.ketvoice.app.data.AppText.t("« ${track.title} » jouera ensuite"),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Ajoute plusieurs titres à la suite de la file, sans toasts ni interruption. */
    fun enqueue(tracks: List<Track>) {
        val s = _state.value
        if (tracks.isEmpty()) return
        if (s.queue.isEmpty()) {
            playQueue(tracks)
            return
        }
        val q = s.queue.toMutableList()
        val keys = s.queueEntryIds.toMutableList()
        // The listener's titles go before « Lecture automatique », as in Spotify.
        var at = s.autoStart.coerceIn(0, q.size)
        tracks.forEach { track ->
            q.add(at, track)
            keys.add(at.coerceAtMost(keys.size), java.util.UUID.randomUUID().toString())
            at++
        }
        _state.value = s.copy(queue = q, queueEntryIds = keys, linearOrder = QueueOrder.syncLinear(s.linearOrder, keys))
    }

    /** Return the inserted occurrence, not a track id (duplicates are valid). */
    fun queueWithUndo(track: Track, next: Boolean): String? {
        val before = _state.value.queueEntryIds.toSet()
        if (next) playNext(track) else enqueue(listOf(track))
        return _state.value.queueEntryIds.firstOrNull { it !in before }
    }

    fun undoQueuedEntry(entryId: String): Boolean {
        val s = _state.value
        val at = s.queueEntryIds.indexOf(entryId)
        // Undo must never stop a title the listener has since started.
        if (at < 0 || at == s.index) return false
        removeAt(at)
        return true
    }

    /** Saute directement à une piste de la file. */
    fun jumpTo(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        startPlayback(s.queue[index], s.queue, index, 0L, true, QueueOrigin.Internal)
    }

    /** Retire une piste ; si c'est la piste courante, on enchaîne proprement. */
    fun removeTrack(trackId: String) {
        val s = _state.value
        val i = s.queue.indexOfFirst { it.id == trackId }
        removeAt(i)
    }

    /** Remove exactly this occurrence, even when the queue contains duplicates. */
    fun removeAt(i: Int) {
        val s = _state.value
        if (i !in s.queue.indices) return
        val q = s.queue.toMutableList()
        q.removeAt(i)
        val keys = s.queueEntryIds.toMutableList().apply { if (i in indices) removeAt(i) }
        if (q.isEmpty()) {
            generation++
            restoredSessionPending = false
            resolutionJob?.cancel()
            desiredPlay = false
            controller?.stop()
            history.clear()
            _state.value = PlayerUiState(
                shuffle = s.shuffle,
                repeatOne = s.repeatOne,
                repeatAll = s.repeatAll,
                autoplay = s.autoplay
            )
            _position.value = 0L
            return
        }
        val linear = QueueOrder.syncLinear(s.linearOrder, keys)
        if (i == s.index) {
            val nextIndex = i.coerceAtMost(q.lastIndex)
            _state.value = s.copy(queue = q, queueEntryIds = keys, index = nextIndex, linearOrder = linear)
            startPlayback(q[nextIndex], q, nextIndex, 0L, true, QueueOrigin.Internal)
        } else {
            _state.value = s.copy(
                queue = q,
                queueEntryIds = keys,
                index = if (i < s.index) (s.index - 1).coerceAtLeast(0) else s.index,
                linearOrder = linear
            )
        }
    }

    /** Keeps the current title and drops everything else from the queue. */
    fun clearUpcoming() {
        val s = _state.value
        val track = s.current ?: return
        val key = s.queueEntryIds.getOrNull(s.index) ?: return
        if (s.queue.size <= 1) return
        history.clear()
        warmedNextEntry = null
        _state.value = s.copy(queue = listOf(track), queueEntryIds = listOf(key), index = 0, linearOrder = emptyList())
    }

    /** Puts back a queue the listener just cleared, as long as the same title is still current. */
    fun restoreQueue(queue: List<Track>, entryIds: List<String>, index: Int, linearOrder: List<String>): Boolean {
        val s = _state.value
        val key = s.queueEntryIds.getOrNull(s.index) ?: return false
        if (s.queue.size != 1 || entryIds.getOrNull(index) != key || queue.size != entryIds.size) return false
        _state.value = s.copy(queue = queue, queueEntryIds = entryIds, index = index, linearOrder = linearOrder)
        return true
    }

    /** Re-inserts an entry the listener just swiped away. */
    fun insertAt(position: Int, track: Track): String? {
        val s = _state.value
        if (s.queue.isEmpty()) return null
        val at = position.coerceIn(0, s.queue.size)
        val key = java.util.UUID.randomUUID().toString()
        val q = s.queue.toMutableList().apply { add(at, track) }
        val keys = s.queueEntryIds.toMutableList().apply { add(at.coerceAtMost(size), key) }
        _state.value = s.copy(queue = q, queueEntryIds = keys, index = if (at <= s.index) s.index + 1 else s.index,
            linearOrder = QueueOrder.syncLinear(s.linearOrder, keys))
        return key
    }

    /** Réordonne la file (glisser la poignée) sans interrompre la lecture. */
    fun moveQueue(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices || from == to) return
        val q = s.queue.toMutableList()
        val item = q.removeAt(from)
        q.add(to, item)
        val keys = s.queueEntryIds.toMutableList()
        if (from in keys.indices && to in keys.indices) keys.add(to, keys.removeAt(from))
        val newIndex = when {
            from == s.index -> to
            from < s.index && to >= s.index -> s.index - 1
            from > s.index && to <= s.index -> s.index + 1
            else -> s.index
        }
        warmedNextEntry = null
        _state.value = s.copy(queue = q, queueEntryIds = keys, index = newIndex)
    }

    fun setAutoplay(on: Boolean) {
        _state.value = (if (on) _state.value else withoutAutoplay(_state.value)).copy(autoplay = on)
        if (on) { autoplayHeldFor = null; topUpAutoplay() } else autoplayJob?.cancel()
    }

    /** Clears the « Lecture automatique » section; it comes back only once the next title starts. */
    fun clearAutoplay() {
        autoplayJob?.cancel()
        autoplayHeldFor = _state.value.queueEntryIds.getOrNull(_state.value.index)
        _state.value = withoutAutoplay(_state.value)
    }

    /** Puts back the section [clearAutoplay] removed from [before], as long as nothing else changed since. */
    fun restoreAutoplay(before: PlayerUiState): Boolean {
        val s = _state.value
        if (s.queueEntryIds != before.queueEntryIds.take(before.autoStart) || s.index != before.index) return false
        _state.value = s.copy(queue = before.queue, queueEntryIds = before.queueEntryIds, autoEntryIds = before.autoEntryIds,
            linearOrder = before.linearOrder)
        autoplayHeldFor = null
        return true
    }

    /** [s] without the automatic titles still to come. */
    private fun withoutAutoplay(s: PlayerUiState): PlayerUiState {
        val start = s.autoStart
        if (start >= s.queue.size) return s
        warmedNextEntry = null
        val keys = s.queueEntryIds.take(start)
        return s.copy(queue = s.queue.take(start), queueEntryIds = keys, autoEntryIds = s.autoEntryIds.intersect(keys.toSet()),
            linearOrder = s.linearOrder.filter { it in keys })
    }

    // ------------------------------------------------------------ Lecture automatique (7.0)
    /** One « Lecture automatique » run: the title it grows from, its candidates and what it already queued. */
    private class AutoplaySession(val seedEntry: String) {
        val pool = ArrayList<Track>()
        val fetched = HashSet<String>()
        val heard = LinkedHashSet<String>()
    }
    @Volatile private var autoplaySession: AutoplaySession? = null
    @Volatile private var autoplayJob: Job? = null
    /** The entry playing when the listener cleared the section: no refill before the next title. */
    @Volatile private var autoplayHeldFor: String? = null

    /** True while this phone follows someone else's Ketogether session: the host decides what comes next. */
    private fun ketogetherGuest(): Boolean = runCatching {
        val ui = com.ketvoice.app.together.Ketogether.ui.value
        ui.role == com.ketvoice.app.together.TogetherRole.Guest &&
            ui.phase != com.ketvoice.app.together.TogetherPhase.Idle && ui.phase != com.ketvoice.app.together.TogetherPhase.Ended
    }.getOrDefault(false)

    /** Adds titles of the same style a few titles before the queue runs out, silently. */
    private fun topUpAutoplay() {
        val s = _state.value
        if (!s.autoplay || radio || s.repeatAll || ketogetherGuest() || s.current == null) return
        if (s.queue.size - 1 - s.index > AutoplayRules.AHEAD) return
        val playing = s.queueEntryIds.getOrNull(s.index)
        if (autoplayHeldFor != null && autoplayHeldFor == playing) return
        autoplayHeldFor = null
        if (autoplayJob?.isActive == true) return
        autoplayJob = scope.launch {
            val more = autoplayBatch() ?: return@launch
            if (!_state.value.autoplay || radio || more.isEmpty()) return@launch
            appendAutoplay(more)
        }
    }

    private fun appendAutoplay(tracks: List<Track>) {
        val s = _state.value
        val keys = tracks.map { java.util.UUID.randomUUID().toString() }
        val all = s.queueEntryIds + keys
        _state.value = s.copy(queue = s.queue + tracks, queueEntryIds = all,
            autoEntryIds = (s.autoEntryIds + keys).intersect(all.toSet()), linearOrder = QueueOrder.syncLinear(s.linearOrder, all))
    }

    /**
     * The next automatic titles: YouTube Music's radio of the title the run grows from, then the
     * catalogue's radio of its artist, then downloads alike when there is no network. Null when the
     * queue changed meanwhile.
     */
    private suspend fun autoplayBatch(): List<Track>? {
        val s = _state.value
        if (s.queue.isEmpty()) return null
        val seedAt = (s.autoStart - 1).coerceIn(0, s.queue.lastIndex)
        val seedEntry = s.queueEntryIds.getOrNull(seedAt) ?: return null
        val session = autoplaySession?.takeIf { it.seedEntry == seedEntry } ?: AutoplaySession(seedEntry).also { autoplaySession = it }
        val seed = s.queue[seedAt]
        val allows: (Track) -> Boolean = { com.ketvoice.app.data.ArtistPrefs.allows(it) }
        val feel: (Track) -> AutoplayRules.Feel? = { t ->
            runCatching { com.ketvoice.app.smartlib.SmartFacts.signals(t) }.getOrNull()?.let { AutoplayRules.Feel(it.bpm, it.loudnessDb) }
        }
        // 7.1: never a title heard in the last hours, unless too few are left (NoRepeatWindow).
        fun recent() = NoRepeatWindow.recent(com.ketvoice.app.data.ListeningHistory.heardAt.value, System.currentTimeMillis())
        fun take(): List<Track> = NoRepeatWindow.pick(session.pool, recent()) { pool ->
            AutoplayRules.pick(pool, seed, _state.value.queue, session.heard, allows, feel)
        }
        var batch = take()
        // Once the seed's radio is used up, the titles the run queued lead it further (the latest first).
        val leads = listOf(seed) + s.queue.drop(seedAt + 1).reversed()
        for (lead in leads) {
            if (batch.size >= AutoplayRules.BATCH / 2) break
            if (!session.fetched.add(lead.id)) continue
            session.pool += autoplayCandidates(lead)
            batch = take()
        }
        if (batch.isEmpty()) batch = runCatching {
            val downloads = com.ketvoice.app.data.Downloader.items.value.map { it.track }
            val facts = com.ketvoice.app.smartlib.SmartFacts.build(downloads + seed)
            val random = kotlin.random.Random(System.nanoTime())
            NoRepeatWindow.pick(downloads, recent()) { pool ->
                AutoplayRules.offline(seed, pool, facts, _state.value.queue, session.heard, allows, random)
            }
        }.getOrDefault(emptyList())
        if (seedEntry !in _state.value.queueEntryIds) return null
        if (session.heard.size > 1_500) session.heard.clear()
        batch.forEach { session.heard += AutoplayRules.heardKeys(it) }
        return batch
    }

    /** Titles of the same style as [seed]: its YouTube Music radio, else its artist's catalogue radio (also « Dans le même style », ui/sub/MoreLikeThis). */
    internal suspend fun autoplayCandidates(seed: Track): List<Track> {
        val video = seed.videoId?.takeIf { it.isNotBlank() } ?: seed.id.removePrefix("yt:").takeIf { seed.id.startsWith("yt:") }
            ?: runCatching { autoplayVideoOf(seed) }.getOrNull()
        val radio = video?.let { v ->
            com.ketvoice.app.data.YouTubeApi.request("next", org.json.JSONObject().put("videoId", v).put("playlistId", "RDAMVM$v").put("isAudioOnly", true),
                authenticated = false)?.let { runCatching { AutoplayRules.parseRadio(it, v) }.getOrNull() }
        }.orEmpty()
        if (radio.isNotEmpty()) return radio
        val artist = com.ketvoice.app.data.MediaOverrides.mainArtist(seed.artist)
        return runCatching { com.ketvoice.app.data.CatalogRepo.artistRadio(artist, seed.artistId?.takeIf { seed.addon == "um" }) }
            .getOrDefault(emptyList())
    }

    /** The YouTube Music video of a title that has none (a catalogue or local title), when a search finds the same recording. */
    private suspend fun autoplayVideoOf(seed: Track): String? {
        val artist = com.ketvoice.app.data.MediaOverrides.mainArtist(seed.artist)
        val found = MusicApi.searchYtMusic("${seed.title} $artist")
        val same = com.ketvoice.app.data.Recordings.of(seed)
        return (found.firstOrNull { com.ketvoice.app.data.Recordings.of(it) == same }
            ?: found.firstOrNull { AutoplayRules.artistKey(it) == AutoplayRules.artistKey(seed) })?.videoId
    }

    private fun label(s: MusicApi.StreamInfo): String = when {
        s.format.contains("flac", true) || s.codec?.contains("flac", true) == true || s.quality.contains("lossless", true) ->
            "FLAC \u00b7 Sans perte"
        s.format.contains("mp3", true) -> "MP3 ${s.quality}"
        s.quality.contains("kbps") -> "${s.format.uppercase()} \u00b7 ${s.quality}"
        s.quality == "YouTube Music" -> "YouTube Music \u00b7 AAC"
        else -> s.format.ifEmpty { s.quality }
    }

    private fun sourceLabel(id: String, stream: MusicApi.StreamInfo? = null): String {
        val name = when (id) {
            "api:um" -> "Ultra MAX"
            "api:last" -> "Last Babe"
            "youtube" -> "YouTube Music"
            else -> SourcePriority.entries().firstOrNull { it.id == id }?.name ?: "Source personnalisée"
        }
        return listOfNotNull(name, stream?.provider).distinct().joinToString(" · ")
    }

    private val recordedListens = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun startDownloaded(c: MediaController, track: Track, item: Downloader.Item, position: Long, playing: Boolean) {
        activeStream = null
        activeSourceId = null
        val url = item.dashManifestUrl ?: item.file.toURI().toString()
        val stream = item.dashManifestUrl?.let {
            MusicApi.StreamInfo(it, "dash", "Sans perte", mimeType = androidx.media3.common.MimeTypes.APPLICATION_MPD, codec = "flac")
        }
        startItem(c, track, url, position, playing, stream = stream, offlineDash = item.dashManifestUrl != null)
    }

    private fun startItem(c: MediaController, t: Track, url: String, pos: Long, autoPlay: Boolean = true,
                          cacheKey: String? = null, stream: MusicApi.StreamInfo? = null, offlineDash: Boolean = false) {
        val item = playbackMediaItem(t, url, cacheKey, stream, offlineDash)
        if (pos > 0) c.setMediaItem(item, pos) else c.setMediaItem(item)
        c.prepare()
        c.playWhenReady = autoPlay
        resolving = false
        _state.value = _state.value.copy(playWhenReady = autoPlay, buffering = true)
    }

    fun pause() {
        cancelQualityUpgrade()
        desiredPlay = false
        _state.value = _state.value.copy(playWhenReady = false)
        controller?.pause()
    }

    /**
     * Plays the session restored from the disk again at [positionMs] (6.1: playback that Android ended is brought
     * back by [PlaybackRevival]). False when no restored session waits: then the listener already chose what plays.
     */
    internal fun resumeRestored(positionMs: Long): Boolean {
        val state = _state.value
        val track = state.current ?: return false
        if (!restoredSessionPending) return state.playWhenReady
        startPlayback(track, state.queue, state.index, positionMs, true, QueueOrigin.Internal)
        return true
    }

    /**
     * Ketogether (6.1): on a guest's phone the transport buttons go to the session first, which sends them to the
     * host or refuses them; true when it took the action. Null outside a session.
     */
    @Volatile internal var transportGate: ((action: String, positionMs: Long) -> Boolean)? = null

    fun toggle() {
        val state = _state.value
        if (transportGate?.invoke(if (state.playWhenReady) "pause" else "play", 0L) == true) return
        if (restoredSessionPending) {
            val track = state.current ?: return
            startPlayback(track, state.queue, state.index, _position.value, true, QueueOrigin.Internal)
            return
        }
        if (resolving) {
            desiredPlay = !state.playWhenReady
            _state.value = state.copy(playWhenReady = desiredPlay)
            return
        }
        val c = controller ?: return
        if (!state.isPlaying && state.failed) {
            state.current?.let { startPlayback(it, state.queue, state.index, 0L, true, QueueOrigin.Internal) }
            return
        }
        desiredPlay = !_state.value.playWhenReady
        _state.value = _state.value.copy(playWhenReady = desiredPlay)
        if (!desiredPlay) c.pause()
        else {
            if (c.playbackState == androidx.media3.common.Player.STATE_ENDED) c.seekTo(0L)
            if (c.playbackState == androidx.media3.common.Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        if (transportGate?.invoke("next", 0L) == true) return
        _state.value.let { s -> com.ketvoice.app.data.TasteSignals.skipped(s.queue.getOrNull(s.index), _position.value) }
        // Ketmix au skip (5.9.0): with Ketmix on, a manual skip is a short transition instead of a hard
        // cut — the title fades out in a third of a second, the next one rises in under one.
        val c = controller
        if (!AppSettings.automix.value || AppSettings.bitPerfect.value || c == null || !c.isPlaying ||
            SleepTimer.remainingSeconds.value in 1..30) { skipFade?.cancel(); c?.volume = 1f; advance(ended = false); return }
        // A second press while the first skip is still on its way goes straight to the title after.
        if (skipFade?.isActive == true || com.ketvoice.app.automix.Automix.mixing) {
            skipFade?.cancel(); com.ketvoice.app.automix.Automix.cancel(); c.volume = 1f; advance(ended = false); return
        }
        skipFade = scope.launch {
            try {
                // 5.11.0: a prepared next title is mixed in for real, over a few seconds, instead of faded to.
                // 7.5: « Mixer quand je passe au suivant » off: no mix, a cut with the short fade of a pause.
                val mix = AppSettings.ketmixOnSkip.value
                if (mix && runCatching { com.ketvoice.app.automix.Automix.skipMix() }.getOrDefault(false)) return@launch
                SkipFade.ramp(c.volume, 0f, if (mix) SkipFade.OUT_MS else PauseFade.MS) { c.volume = it }
                advance(ended = false)
                // The next title starts silent and rises once it really plays (or after a few seconds anyway).
                c.volume = 0f
                kotlinx.coroutines.withTimeoutOrNull(6_000) { _state.first { it.isPlaying } }
                SkipFade.ramp(0f, 1f, if (mix) SkipFade.IN_MS else PauseFade.MS) { c.volume = it }
            } finally {
                runCatching { c.volume = 1f }
            }
        }
    }
    private var skipFade: kotlinx.coroutines.Job? = null

    /** One rule for a manual skip and for the end of a track. */
    private fun advance(ended: Boolean) {
        val s = _state.value
        when (val step = QueueSteps.next(s.queue.size, s.index, s.repeatOne, s.repeatAll, s.autoplay, ended)) {
            is QueueStep.Jump -> startPlayback(s.queue[step.index], s.queue, step.index, 0L, true, QueueOrigin.Internal)
            // The same title ends again later: its end must be handled again.
            QueueStep.Replay -> { lastEndedKey = null; seekTo(0L); controller?.play() }
            QueueStep.Extend -> extendQueue()
            QueueStep.Stop -> if (ended) _state.value = s.copy(isPlaying = false, playWhenReady = false)
        }
    }

    /**
     * Restarts the title after its first seconds; otherwise returns to the title the
     * listener really came from, then to the one before it in the queue.
     */
    fun previous() {
        if (transportGate?.invoke("previous", 0L) == true) return
        val c = controller ?: return
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (c.currentPosition > 3_000) {
            seekTo(0)
            return
        }
        val target = history.popInto(s.queueEntryIds, s.index) ?: QueueSteps.previous(s.queue.size, s.index, s.repeatAll)
        if (target == null) {
            seekTo(0)
            return
        }
        startPlayback(s.queue[target], s.queue, target, 0L, true, QueueOrigin.Internal, fromPrevious = true)
    }

    private var extending = false
    private fun extendQueue() {
        if (extending) return
        val s = _state.value
        val track = s.current ?: return
        val gen = generation
        extending = true
        scope.launch {
            try {
                val memory = radioMemory
                // Blocked artists are never added by Ketvoice itself (both the radio and autoplay filter them).
                val related = if (radio && memory != null) radioBatch(track, memory.apply { rememberAll(s.queue) })
                    else { autoplayJob?.cancel(); autoplayBatch().orEmpty() }
                if (gen != generation) return@launch
                if (related.isNotEmpty()) {
                    val end = _state.value.queue.size
                    if (radio) enqueue(related) else appendAutoplay(related)
                    val grown = _state.value
                    startPlayback(grown.queue[end], grown.queue, end, 0L, true, QueueOrigin.Internal)
                }
                else _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false)
            } finally { extending = false }
        }
    }

    fun seekTo(ms: Long) {
        if (transportGate?.invoke("seek", ms.coerceAtLeast(0L)) == true) return
        cancelQualityUpgrade()
        com.ketvoice.app.automix.Automix.cancel()
        val position = ms.coerceAtLeast(0L)
        controller?.seekTo(position)
        _position.value = position
        _state.value = _state.value.copy(positionMs = position)
        _seekEpoch.value++
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceIn(0L, c.duration.coerceAtLeast(0L))
        seekTo(target)
    }

    /** Position immédiate pour le rendu karaoké (sans attendre le tic global). */
    fun currentPositionMs(): Long =
        (if (awaitingDeck != null) _position.value else controller?.currentPosition ?: _position.value).coerceAtLeast(0L)

    /**
     * Visible shuffle: turning it on moves the current title first and shuffles every
     * other entry of the queue; turning it off puts the queue back in its order.
     */
    fun setShuffle(on: Boolean) {
        controller?.shuffleModeEnabled = on
        val s = _state.value
        if (on == s.shuffle) return
        if (s.queue.isEmpty()) {
            _state.value = s.copy(shuffle = on, linearOrder = emptyList())
            return
        }
        // Only the listener's titles are shuffled: « Lecture automatique » is built again after them.
        val mine = if (on) withoutAutoplay(s) else s
        val order = if (on) QueueOrder.shuffle(mine.queue, mine.queueEntryIds, mine.index,
                recent = recentlyHeard(), spread = AppSettings.smartShuffle.value)
            else QueueOrder.restore(s.queue, s.queueEntryIds, s.index, s.linearOrder)
        warmedNextEntry = null
        _state.value = mine.copy(shuffle = on, queue = order.queue, queueEntryIds = order.entryIds, index = order.index,
            linearOrder = order.linearOrder)
        // The new order has another next title: warm it, as at the start of a track.
        if (controller != null && !resolving) s.current?.let { prefetchNext(generation, it) }
    }

    /** The titles heard last, which a shuffle with fewer repeats keeps for later. */
    private fun recentlyHeard(): Set<String> =
        com.ketvoice.app.data.ListeningHistory.tracks.value.take(RECENT_FOR_SHUFFLE).mapTo(HashSet()) { it.id }
    private const val RECENT_FOR_SHUFFLE = 25

    /** Sets the modes of a session received as is, without reordering its queue. */
    fun adoptModes(shuffle: Boolean, repeatOne: Boolean, autoplay: Boolean) {
        controller?.shuffleModeEnabled = shuffle
        _state.value = _state.value.copy(shuffle = shuffle, autoplay = autoplay, linearOrder = emptyList())
        setRepeat(all = false, one = repeatOne)
    }

    fun setRepeatOne(on: Boolean) = setRepeat(all = false, one = on)

    /** Off, then the whole queue, then the current title. */
    fun cycleRepeat() {
        val s = _state.value
        when {
            s.repeatOne -> setRepeat(all = false, one = false)
            s.repeatAll -> setRepeat(all = false, one = true)
            else -> setRepeat(all = true, one = false)
        }
    }

    private fun setRepeat(all: Boolean, one: Boolean) {
        // On gère nous-mêmes la file : OFF permet STATE_ENDED -> next().
        // REPEAT_MODE_ONE ne sert qu'au vrai mode « répéter le titre ».
        controller?.repeatMode = if (one) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        warmedNextEntry = null
        _state.value = _state.value.copy(repeatOne = one, repeatAll = all && !one)
    }

    /** Playback gain for the sleep timer's fade; the user's volume stays the system stream. */
    internal fun setFadeGain(gain: Float) {
        if (AppSettings.bitPerfect.value) return
        controller?.volume = gain.coerceIn(0f, 1f)
    }

    /** Réglage « Ignorer les silences » : appliqué dans le service (moteur audio). */
    fun setSkipSilence(on: Boolean) {
        AppSettings.skipSilence.value = on
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlaybackService.CMD_SKIP_SILENCE, Bundle.EMPTY),
            // The service reads the command arguments; extras were never forwarded there.
            Bundle().apply { putBoolean("on", on) }
        )
    }

    private fun cancelQualityUpgrade() {
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_CANCEL_UPGRADE, Bundle.EMPTY), Bundle.EMPTY)
    }

    /**
     * Enables the verified native PCM path. The service rebuilds its player so
     * the exact sink is installed before the next buffer; the status is never
     * set to Active here, only after the sink has confirmed the real route.
     */
    fun setBitPerfect(enabled: Boolean) {
        if (AppSettings.bitPerfect.value == enabled) return
        AppSettings.bitPerfect.value = enabled
        if (enabled) {
            cancelQualityUpgrade()
            _state.value = _state.value.copy(searchingBetter = false)
        }
        if (enabled && android.os.Build.VERSION.SDK_INT < 34) {
            BitPerfectState.report(
                ExactAudioStatus(ExactAudioPhase.Unavailable, "Android 14 et un mixeur compatible sont requis")
            )
        } else if (enabled) {
            BitPerfectState.report(
                ExactAudioStatus(ExactAudioPhase.Pending, "Vérification du chemin audio…")
            )
        } else {
            BitPerfectState.reset()
        }
        val c = controller ?: run {
            if (enabled && android.os.Build.VERSION.SDK_INT >= 34) {
                BitPerfectState.report(
                    ExactAudioStatus(ExactAudioPhase.Pending, "En attente de lecture")
                )
            }
            return
        }
        c.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlaybackService.CMD_BIT_PERFECT, Bundle.EMPTY),
            Bundle().apply { putBoolean("enabled", enabled) }
        )
    }

    fun setReverb(amount: Int, size: Int, damping: Int) {
        val percent = amount.coerceIn(0, 100)
        val room = size.coerceIn(0, 100)
        val damp = damping.coerceIn(0, 100)
        AppSettings.reverbAmount.value = percent
        AppSettings.reverbSize.value = room
        AppSettings.reverbDamping.value = damp
        ReverbControl.amount = percent / 100f
        ReverbControl.size = room / 100f
        ReverbControl.damping = damp / 100f
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_REVERB, Bundle.EMPTY),
            Bundle().apply {
                putInt("amount", percent)
                putInt("size", room)
                putInt("damping", damp)
            })
    }

    fun setReverbAmount(amount: Int) = setReverb(amount, AppSettings.reverbSize.value, AppSettings.reverbDamping.value)

    // ------------------------------------------------------------ Volume natif
    // Le curseur pilote le volume média SYSTÈME (AudioManager.STREAM_MUSIC) :
    // mêmes gradations que les boutons physiques, même valeur que le système.
    private val audioManager: android.media.AudioManager?
        get() = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

    private var volumeObserver: android.database.ContentObserver? = null

    fun systemVolumeFraction(): Float {
        val am = audioManager ?: return 1f
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(v: Float) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val target = (v.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0) }
        _volume.value = systemVolumeFraction()
    }

    fun volumeValue(): Float = systemVolumeFraction()

    /** Observe le volume média : les boutons physiques mettent le curseur à jour. */
    private fun observeSystemVolume(context: Context) {
        if (volumeObserver != null) return
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                _volume.value = systemVolumeFraction()
            }
        }
        // Observation générale des réglages système : les changements de volume
        // média y sont publiés, y compris depuis les boutons physiques.
        runCatching {
            context.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer)
        }
        volumeObserver = observer
        _volume.value = systemVolumeFraction()
    }

    fun refreshPublication(track: Track) {
        if (com.ketvoice.app.data.ImportedSong.id(track) == null) return
        scope.launch(Dispatchers.Main.immediate) {
            _state.value = _state.value.copy(queue = _state.value.queue.map { if (it.id == track.id) track else it })
        }
    }
    fun applyEqualizer() {
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_EQUALIZER, Bundle.EMPTY), Bundle.EMPTY)
    }

    suspend fun awaitReady(trackId: String): Boolean = withTimeoutOrNull(45_000) {
        state.first { it.current?.id == trackId && !it.buffering && controller?.playbackState == Player.STATE_READY }
        true
    } ?: false
}

/** The short fades of a manual skip with Ketmix on. */
internal object SkipFade {
    const val OUT_MS = 320L
    const val IN_MS = 850L
    private const val STEP_MS = 16L

    /** The level at [t] (0..1) of a fade from [from] to [to]: an equal-power curve, so the ear hears an even glide. */
    fun level(from: Float, to: Float, t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        val curve = if (to < from) kotlin.math.cos(p * Math.PI / 2).toFloat() else kotlin.math.sin(p * Math.PI / 2).toFloat()
        return if (to < from) to + (from - to) * curve else from + (to - from) * curve
    }

    suspend fun ramp(from: Float, to: Float, durationMs: Long, set: (Float) -> Unit) {
        val steps = (durationMs / STEP_MS).coerceAtLeast(1)
        for (i in 1..steps) { set(level(from, to, i.toFloat() / steps)); kotlinx.coroutines.delay(STEP_MS) }
        set(to)
    }
}



/** What playback does when the network goes away under a stream (5.10.0). */
internal object NetworkRecovery {
    const val MAX_RETRIES = 8
    /** How long the network is waited for before the usual fallbacks take over. */
    const val WAIT_MS = 180_000L

    fun isNetworkError(code: Int): Boolean = code == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        code == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    /** A short pause before the stream is opened again, a little longer each time. */
    fun backoffMs(attempt: Int): Long = (400L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(6_400L)
}
