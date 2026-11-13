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
    val audioFallbackHttpStatus: Int? = null
) {
    val current: Track? get() = if (index in queue.indices) queue[index] else null
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

    private var controller: MediaController? = null
    private data class PendingPlay(val track: Track, val queue: List<Track>, val index: Int?, val position: Long, val playing: Boolean)
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
    private val failedStreamUrls = mutableSetOf<String>()
    private val failedSourceIds = mutableSetOf<String>()
    private var refreshedExpiredStream = false
    private var historyGeneration = -1
    private var resolving = false
    private var resolveStartedAt = 0L
    private data class PrefetchedDash(val candidate: StreamResolver.Candidate, val at: Long)
    private val prefetchedDash = java.util.concurrent.ConcurrentHashMap<String, PrefetchedDash>()

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
        val group = controller?.currentTracks?.groups?.firstOrNull {
            it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected
        } ?: return "Format audio en attente"
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return "Format audio en attente"
        val format = group.getTrackFormat(index)
        return listOfNotNull(format.sampleMimeType,
            format.sampleRate.takeIf { it > 0 }?.let { "$it Hz" },
            format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kb/s" },
            format.channelCount.takeIf { it > 0 }?.let { "$it ch" }).joinToString(" · ")
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
            failed?.url?.let { failedStreamUrls += it }
            sourceId?.let { failedSourceIds += it }
            resolving = true
            _state.value = _state.value.copy(audioFallback = true, audioFallbackHttpStatus = status,
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
                if (key != lastEndedKey) {
                    lastEndedKey = key
                    if (s.repeatOne) { seekTo(0L); controller?.play() }
                    else if (s.index < s.queue.lastIndex) next()
                    else if (s.autoplay) extendQueue()
                    else _state.value = s.copy(isPlaying = false, playWhenReady = false)
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
                    playTrack(pending.track, pending.queue, pending.index, pending.position, pending.playing)
                }
            } catch (_: Exception) {
                connected = false
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))

        // Lightweight position updates for the time rail and synchronized lyrics.
        scope.launch {
            while (true) {
                if (controller?.isPlaying == true) positionNow()
                delay(200)
            }
        }
    }

    private fun positionNow() {
        if (resolving) return
        val c = controller ?: return
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
        if (dur != _state.value.durationMs ||
            kotlin.math.abs(pos - _state.value.positionMs) > 3_500L
        ) {
            _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
        }
    }

    /** Dernière position vue par le tic : base du delta de temps d'écoute réel. */
    private var lastListenedPosition = 0L

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
    fun playTrack(track: Track, queue: List<Track> = listOf(track), startIndex: Int? = null,
                  startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        restoredSessionPending = false
        val c = controller ?: run {
            pendingPlay = PendingPlay(track, queue, startIndex, startPositionMs, autoPlay)
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
        if (initialQueue.map { it.id } != prev.queue.map { it.id }) shuffleRecent.clear()
        _state.value = PlayerUiState(
            queue = initialQueue,
            queueEntryIds = QueueEntryIds.reconcile(prev.queue.map { it.id }, prev.queueEntryIds, initialQueue.map { it.id }),
            index = idx,
            isPlaying = false,
            playWhenReady = autoPlay,
            positionMs = startPositionMs.coerceAtLeast(0),
            buffering = true,
            shuffle = prev.shuffle,
            repeatOne = prev.repeatOne,
            autoplay = prev.autoplay,
            quality = "Connexion…",
            durationMs = resolvedTrack.durationMs
        )
        _position.value = startPositionMs.coerceAtLeast(0)

        // Canvas + paroles démarrent sans attendre la résolution audio.
        scope.launch { CanvasRepo.resolve(resolvedTrack) }
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
            if (cached != null && !explicit && !waitForLossless && com.ketvoice.app.data.ImportedSong.id(resolvedTrack) == null) {
                start(cached)
                _state.value = _state.value.copy(quality = "En cache · ${label(cached)}", audioSource = "En cache")
                return@launch
            }
            val prefetched = prefetchedDash.remove(track.id)?.takeIf {
                android.os.SystemClock.elapsedRealtime() - it.at in 0..60_000 &&
                    SourcePriority.entries().any { entry -> entry.id == it.candidate.sourceId && entry.enabled } &&
                    StreamResolver.canUsePrefetched(AppSettings.progressiveQuality.value,
                        StreamResolver.sources(resolvedTrack, download = false, lossless = wantsLossless()).firstOrNull()?.id,
                        it.candidate.sourceId)
            }?.candidate
            if (prefetched != null && (!explicit || prefetched.sourceId == resolvedTrack.sourceOverride) &&
                com.ketvoice.app.data.ImportedSong.id(resolvedTrack) == null) {
                start(prefetched.stream, sourceId = prefetched.sourceId)
                _state.value = _state.value.copy(audioSource = sourceLabel(prefetched.sourceId, prefetched.stream))
                prefetchNext(gen, resolvedTrack)
                return@launch
            }
            if (resolvedTrack.addon == "upload" || resolvedTrack.id.startsWith("kv:upload:")) {
                val stream = PublicationApi.stream(resolvedTrack, cached)
                if (stream == null) {
                    resolving = false
                    _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false, quality = "Aucun flux disponible", failed = true)
                    return@launch
                }
                start(stream)
                _state.value = _state.value.copy(audioSource = "Publication Ketvoice")
                AudioCache.cacheWhole(track.id, stream)
                return@launch
            }
            // Ordered mode respects the source priority; progressive mode races
            // sources and may upgrade the already playing stream.
            val progressive = AppSettings.progressiveQuality.value
            val highQuality = progressive && qualitySetting() != "Normal" && !AppSettings.bitPerfect.value
            _state.value = _state.value.copy(searchingBetter = highQuality)
            kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion {
                if (gen == generation) _state.value = _state.value.copy(searchingBetter = false)
            }
            var currentStream: MusicApi.StreamInfo? = null
            suspend fun upgrade(stream: MusicApi.StreamInfo, sourceId: String) {
                if (AppSettings.bitPerfect.value || !highQuality || stream.url == currentStream?.url || gen != generation ||
                    StreamQuality.rank(stream) <= (currentStream?.let(StreamQuality::rank) ?: 0)) return
                val future = c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_UPGRADE, Bundle.EMPTY),
                    Bundle().apply {
                        putString("id", track.id); putString("url", stream.url)
                        putString("cacheKey", if (stream.isDash) null else AudioCache.key(track.id, stream))
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
                if (!switched || gen != generation) {
                    // A failed standby decoder must never interrupt the audible stream.
                    return
                }
                currentStream = stream
                activeStream = stream
                activeSourceId = sourceId
                _state.value = _state.value.copy(quality = label(stream), audioSource = sourceLabel(sourceId, stream))
                AudioCache.cacheWhole(track.id, stream)
            }
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
                            // filling its buffer while the rest of the file and the next two
                            // queue items are prepared in the background.
                            start(candidate.stream, sourceId = candidate.sourceId)
                            AudioCache.cacheWhole(track.id, candidate.stream)
                            prefetchNext(gen, resolvedTrack)
                        } else upgrade(candidate.stream, candidate.sourceId)
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

    /**
     * Warms the next two queue items after playback actually started: their stream
     * is resolved and its bytes enter the audio cache, so the transition is instant.
     * Resolution follows the user's source mode; byte caching honours metered connections.
     */
    private fun prefetchNext(gen: Int, current: Track) {
        val state = _state.value
        val upcoming = (1..2).mapNotNull { offset -> state.queue.getOrNull(state.index + offset) }
            .filter { it.id != current.id && !it.id.startsWith("local:") }
        if (upcoming.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (item in upcoming) {
                if (gen != generation) return@launch
                if (AudioCache.complete(item.id, wantsLossless()) != null) continue
                runCatching {
                    StreamResolver.candidates(item, download = false, lossless = wantsLossless(),
                        race = AppSettings.progressiveQuality.value)
                        .firstOrNull()
                }.getOrNull()?.let { candidate ->
                    if (candidate.stream.isDash && com.ketvoice.app.data.CustomSources.reusableManifestUrl(candidate.stream.url)) {
                        if (prefetchedDash.size >= 10) prefetchedDash.clear()
                        if (gen == generation) prefetchedDash[item.id] = PrefetchedDash(candidate, android.os.SystemClock.elapsedRealtime())
                    } else if (!isMetered()) AudioCache.cacheWhole(item.id, candidate.stream)
                    if (gen == generation) runCatching { ArtworkRepo.enrich(listOf(item)) }
                }
            }
        }
    }

    fun playQueue(tracks: List<Track>, start: Int = 0) {        if (tracks.isEmpty()) return
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
        keys.add(insertAt.coerceAtMost(keys.size), java.util.UUID.randomUUID().toString())
        _state.value = _state.value.copy(queue = q, queueEntryIds = keys)
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
        tracks.forEach { track ->
            q.add(track)
            keys.add(java.util.UUID.randomUUID().toString())
        }
        _state.value = s.copy(queue = q, queueEntryIds = keys)
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
        playTrack(s.queue[index], s.queue, index)
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
            _state.value = PlayerUiState(
                shuffle = s.shuffle,
                repeatOne = s.repeatOne,
                autoplay = s.autoplay
            )
            _position.value = 0L
            return
        }
        if (i == s.index) {
            val nextIndex = i.coerceAtMost(q.lastIndex)
            _state.value = s.copy(queue = q, queueEntryIds = keys, index = nextIndex)
            playTrack(q[nextIndex], q, nextIndex)
        } else {
            _state.value = s.copy(
                queue = q,
                queueEntryIds = keys,
                index = if (i < s.index) (s.index - 1).coerceAtLeast(0) else s.index
            )
        }
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
        _state.value = s.copy(queue = q, queueEntryIds = keys, index = newIndex)
    }

    fun setAutoplay(on: Boolean) {
        _state.value = _state.value.copy(autoplay = on)
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

    fun toggle() {
        val state = _state.value
        if (restoredSessionPending) {
            val track = state.current ?: return
            playTrack(track, state.queue, state.index, _position.value, autoPlay = true)
            return
        }
        if (resolving) {
            desiredPlay = !state.playWhenReady
            _state.value = state.copy(playWhenReady = desiredPlay)
            return
        }
        val c = controller ?: return
        if (!state.isPlaying && state.failed) {
            state.current?.let { playTrack(it, state.queue) }
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

    private val shuffleRecent = ArrayDeque<Int>()

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val nextIndex = if (s.shuffle && s.queue.size > 1) {
            val recent = shuffleRecent.toSet()
            var pool = s.queue.indices.filter { it != s.index && it !in recent }
            if (pool.isEmpty()) pool = s.queue.indices.filter { it != s.index }
            val chosen = pool.random()
            shuffleRecent.addLast(chosen)
            while (shuffleRecent.size > (s.queue.size / 2).coerceAtLeast(1)) shuffleRecent.removeFirst()
            chosen
        } else {
            if (s.index < s.queue.lastIndex) s.index + 1 else {
                if (s.autoplay) extendQueue()
                return
            }
        }
        playTrack(s.queue[nextIndex], s.queue, nextIndex)
    }

    fun previous() {
        val c = controller ?: return
        val s = _state.value
        if (c.currentPosition > 3_000 || s.queue.size <= 1) {
            seekTo(0)
            return
        }
        val previous = if (s.index > 0) s.index - 1 else s.queue.lastIndex
        playTrack(s.queue[previous], s.queue, previous)
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
                val related = MusicApi.searchYtMusic(track.artist).filter { candidate ->
                    s.queue.none { it.id == candidate.id || (it.title.equals(candidate.title, true) && it.artist.equals(candidate.artist, true)) }
                }.take(8)
                if (gen != generation) return@launch
                if (related.isNotEmpty()) playQueue(_state.value.queue + related, _state.value.queue.size)
                else _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false)
            } finally { extending = false }
        }
    }

    fun seekTo(ms: Long) {
        cancelQualityUpgrade()
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
        (controller?.currentPosition ?: _position.value).coerceAtLeast(0L)

    fun setShuffle(on: Boolean) {
        controller?.shuffleModeEnabled = on
        _state.value = _state.value.copy(shuffle = on)
    }

    fun setRepeatOne(on: Boolean) {
        val c = controller ?: return
        // On gère nous-mêmes la file : OFF permet STATE_ENDED -> next().
        // REPEAT_MODE_ONE ne sert qu'au vrai mode « répéter le titre ».
        c.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(repeatOne = on)
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
