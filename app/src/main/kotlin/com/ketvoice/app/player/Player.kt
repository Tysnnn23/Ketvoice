@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.player

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.first

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
    val failed: Boolean = false
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

    private var controller: MediaController? = null
    private data class PendingPlay(val track: Track, val queue: List<Track>, val index: Int?, val position: Long, val playing: Boolean)
    private var pendingPlay: PendingPlay? = null
    private var connected = false
    private var appContext: Context? = null
    private var generation = 0
    private var resolutionJob: Job? = null
    private var desiredPlay = true
    private var activeStream: MusicApi.StreamInfo? = null
    private var fallbackAttempts = 0
    private var historyGeneration = -1
    private var resolving = false

    // ------------------------------------------------------------ AutoMix
    private data class MixStream(
        val info: MusicApi.StreamInfo,
        val cacheKey: String?,
        val localPath: String?,
        val label: String
    )

    private data class ArmedMix(
        val index: Int,
        val track: Track,
        val stream: MixStream,
        val source: AutoMixSource,
        val plan: AutoMixPlan
    )

    private var armed: ArmedMix? = null
    private var armJob: Job? = null
    private var mixing = false
    private var mixToken = 0L
    private var mixTransition: AutoMix.Transition? = null
    private var mixStartedAt = 0L
    private var mixPositionBase = 0L
    private var mixFinalizeJob: Job? = null
    private var armAttemptAt = -12_000L
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

    private var lastEndedKey: Pair<String?, Int>? = null

    private val listener = object : Player.Listener {
        override fun onVolumeChanged(volume: Float) { if (!mixing) _volume.value = volume }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!resolving) {
                if (mixing && !playWhenReady) cancelMix()
                desiredPlay = controller?.playWhenReady ?: playWhenReady
                _state.value = _state.value.copy(playWhenReady = desiredPlay)
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (mixing) return
            val track = _state.value.current ?: return
            val failed = activeStream
            if (fallbackAttempts++ >= 2) {
                _state.value = _state.value.copy(isPlaying = false, quality = "Erreur de lecture · réessayez", failed = true)
                return
            }
            val gen = generation
            val pos = controller?.currentPosition?.coerceAtLeast(0) ?: 0L
            scope.launch {
                val alternative = withTimeoutOrNull(25_000) {
                    if (failed?.format?.contains("flac", true) == true) MusicApi.addonStream(track, "um")
                    else MusicApi.flacStream(track) ?: MusicApi.addonStream(track, "um")
                }
                if (gen != generation) return@launch
                if (alternative != null && alternative.url != failed?.url) {
                    activeStream = alternative
                    startItem(controller ?: return@launch, track, alternative.url, pos, desiredPlay, AudioCache.key(track.id, alternative))
                    _state.value = _state.value.copy(quality = label(alternative))
                    AudioCache.cacheWhole(track.id, alternative)
                } else {
                    _state.value = _state.value.copy(isPlaying = false, quality = "Flux indisponible · réessayez", failed = true)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (resolving || mixing) return
            _state.value = _state.value.copy(isPlaying = isPlaying)
            com.ketvoice.app.data.ActiveListenerTracker.onPlaybackStateChanged(_state.value.current, isPlaying)
            if (isPlaying && historyGeneration != generation) {
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
            if (resolving || mixing) return
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
        CanvasRepo.init(context.applicationContext)
        ArtworkRepo.init(context.applicationContext)
        Downloader.init(context.applicationContext)
        com.ketvoice.app.data.OfflineCapsule.init(context.applicationContext)
        com.ketvoice.app.data.PlaylistStore.init(context.applicationContext)
        com.ketvoice.app.data.ListeningHistory.init(context.applicationContext)
        com.ketvoice.app.data.ActiveListenerTracker.init(context.applicationContext)
        com.ketvoice.app.data.HomeFeed.start(context.applicationContext)
        MusicApi.init(context.applicationContext)
        AudioCache.init(context.applicationContext)
        AutoMixAnalyzer.init(context.applicationContext)
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val builder = MediaController.Builder(context, token)
        builder.setListener(object : MediaController.Listener {
            override fun onCustomCommand(
                controller: MediaController,
                command: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (command.customAction == AutoMixCommands.EVENT) {
                    val eventToken = args.getLong(AutoMixCommands.TOKEN, 0L)
                    val success = args.getBoolean(AutoMixCommands.SUCCESS, false)
                    scope.launch { finishMix(eventToken, success) }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        })
        val future = builder.buildAsync()
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

        // Tic léger : met à jour uniquement le flux de position, et pilote
        // l'armement/le déclenchement AutoMix.
        scope.launch {
            while (true) {
                if (controller?.isPlaying == true) positionNow()
                tickAutoMix()
                delay(if (mixing) 120 else 200)
            }
        }
    }

    private fun positionNow() {
        if (resolving) return
        val c = controller ?: return
        if (mixing) {
            val estimate = mixPositionBase + (android.os.SystemClock.elapsedRealtime() - mixStartedAt)
            _position.value = estimate.coerceAtLeast(0L)
            return
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
        if (dur != _state.value.durationMs ||
            kotlin.math.abs(pos - _state.value.positionMs) > 3_500L
        ) {
            _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
        }
    }

    /** Dernière position vue par le tic : base du delta de temps d'écoute réel. */
    private var lastListenedPosition = 0L

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

    // ------------------------------------------------------------ Lecture
    fun playTrack(track: Track, queue: List<Track> = listOf(track), startIndex: Int? = null,
                  startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        val c = controller ?: run {
            pendingPlay = PendingPlay(track, queue, startIndex, startPositionMs, autoPlay)
            appContext?.let { if (!connected) connect(it) }
            return
        }
        resolutionJob?.cancel()
        cancelMix()
        if (armed != null || armJob?.isActive == true) {
            c.sendCustomCommand(SessionCommand(AutoMixCommands.CANCEL, Bundle.EMPTY), Bundle.EMPTY)
        }
        armJob?.cancel()
        armJob = null
        armAttemptAt = -12_000L
        armed = null
        resolving = true
        desiredPlay = autoPlay
        activeStream = null
        fallbackAttempts = 0
        c.pause()
        lastEndedKey = null
        // 1) Correspondance addon déjà présente dans la file : zéro recherche réseau.
        val localMatch = if (track.addonId.isNullOrBlank()) {
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
                _state.value = _state.value.copy(quality = "Fichier local", buffering = false)
                resolving = false
                return@launch
            }
            val local = Downloader.itemFor(track.id)
            if (local != null) {
                activeStream = null
                startItem(c, resolvedTrack, local.file.toURI().toString(), startPositionMs.coerceAtLeast(0), desiredPlay)
                _state.value = _state.value.copy(quality = "Téléchargé · ${local.format}")
                return@launch
            }

            val cached = AudioCache.complete(track.id, wantsLossless())
            fun start(stream: MusicApi.StreamInfo, position: Long = startPositionMs.coerceAtLeast(0)) {
                val latest = _state.value.current ?: resolvedTrack
                activeStream = stream
                startItem(c, latest, stream.url, position, desiredPlay, AudioCache.key(track.id, stream))
                _state.value = _state.value.copy(quality = label(stream), isPlaying = c.isPlaying)
            }
            if (cached != null && com.ketvoice.app.data.ImportedSong.id(resolvedTrack) == null) {
                start(cached)
                _state.value = _state.value.copy(quality = "En cache · ${label(cached)}")
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
                AudioCache.cacheWhole(track.id, stream)
                return@launch
            }
            val youtube = async(Dispatchers.IO) { runCatching { MusicApi.youtubeStream(resolvedTrack) }.getOrNull() }
            val mp3 = async(Dispatchers.IO) { runCatching { MusicApi.addonStream(resolvedTrack, "um") }.getOrNull() }
            val flac = async(Dispatchers.IO) {
                if (wantsLossless()) runCatching { MusicApi.flacStream(resolvedTrack) }.getOrNull() else null
            }
            val custom = async(Dispatchers.IO) {
                runCatching { com.ketvoice.app.data.CustomSources.streamForTrack(resolvedTrack) }.getOrNull()
            }
            // A track discovered through a personal source must not wait on
            // YouTube. Otherwise YouTube gets a short head start.
            val customTrack = resolvedTrack.addon?.startsWith("cs:") == true
            var initial = if (customTrack) withTimeoutOrNull(3500) { custom.await() }
                else withTimeoutOrNull(1600) { youtube.await() }
            if (initial == null) {
                val candidates = mutableListOf(youtube, mp3, flac, custom)
                while (candidates.isNotEmpty() && initial == null) {
                    val (done, result) = select {
                        candidates.forEach { d -> d.onAwait { d to it } }
                    }
                    candidates.remove(done)
                    initial = result
                }
            }
            if (gen != generation) return@launch
            if (initial == null) {
                resolving = false
                _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false, quality = "Aucun flux disponible", failed = true)
                return@launch
            }
            val first = initial
            start(first)
            AudioCache.cacheWhole(track.id, first)
            suspend fun upgrade(stream: MusicApi.StreamInfo?) {
                if (stream == null || stream.url == first.url || gen != generation) return
                val ready = AudioCache.warm(track.id, stream, c.currentPosition.coerceAtLeast(0), _state.value.durationMs)
                if (!ready || gen != generation) return
                val resumeAt = c.currentPosition.coerceAtLeast(0)
                desiredPlay = c.playWhenReady
                start(stream, resumeAt)
                AudioCache.cacheWhole(track.id, stream)
            }
            if (first.quality == "YouTube Music" && qualitySetting() != "Normal") {
                // A 128 kb/s MP3 is not an improvement over AAC. Only upgrade
                // to this lossy fallback when its declared bitrate is higher.
                val lossy = mp3.await()
                val bitrate = lossy?.quality?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
                if (bitrate >= 256) upgrade(lossy)
            }
            if (wantsLossless() && !first.format.contains("flac", true)) upgrade(flac.await())
            if (customTrack) {
                // Si la source personnalisée a été plus lente que le repli, elle
                // remplace le flux de secours dès qu'elle est réellement prête.
                val sourceStream = withTimeoutOrNull(9_000) { custom.await() }
                if (sourceStream != null) upgrade(sourceStream)
            }
            youtube.cancel()
            mp3.cancel()
            flac.cancel()
            custom.cancel()
        }
    }

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
        if (mixing) cancelMix()
        armed = null
        val q = s.queue.toMutableList()
        q.removeAt(i)
        val keys = s.queueEntryIds.toMutableList().apply { if (i in indices) removeAt(i) }
        if (q.isEmpty()) {
            generation++
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
        s.quality == "YouTube Music" -> "YouTube Music · AAC"
        s.format.contains("flac", true) || s.quality.contains("lossless", true) ->
            "FLAC · Sans perte"
        s.format.contains("mp3", true) -> "MP3 ${s.quality}"
        else -> s.format.ifEmpty { s.quality }
    }

    private val recordedListens = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun startItem(c: MediaController, t: Track, url: String, pos: Long, autoPlay: Boolean = true, cacheKey: String? = null) {
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId(t.id)
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setArtworkUri(t.coverUrl?.let { Uri.parse(it) })
                    .build()
            )
            .build()
        if (pos > 0) c.setMediaItem(item, pos) else c.setMediaItem(item)
        c.prepare()
        c.playWhenReady = autoPlay
        resolving = false
        _state.value = _state.value.copy(playWhenReady = autoPlay, buffering = true)
    }

    fun pause() {
        cancelMix()
        desiredPlay = false
        _state.value = _state.value.copy(playWhenReady = false)
        controller?.pause()
    }

    fun toggle() {
        val c = controller ?: return
        if (mixing) cancelMix()
        val state = _state.value
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
            val armedIndex = armed?.takeIf { it.track.id != s.current?.id && s.queue.getOrNull(it.index)?.id == it.track.id }?.index
            if (armedIndex != null) armedIndex else {
                val recent = shuffleRecent.toSet()
                var pool = s.queue.indices.filter { it != s.index && it !in recent }
                if (pool.isEmpty()) pool = s.queue.indices.filter { it != s.index }
                val chosen = pool.random()
                shuffleRecent.addLast(chosen)
                while (shuffleRecent.size > (s.queue.size / 2).coerceAtLeast(1)) shuffleRecent.removeFirst()
                chosen
            }
        } else {
            if (s.index < s.queue.lastIndex) s.index + 1 else {
                if (s.autoplay) extendQueue()
                return
            }
        }
        if (mixing) {
            cancelMix()
            playTrack(s.queue[nextIndex], s.queue, nextIndex)
            return
        }
        val target = armed
        if (AutoMix.enabled() && !s.repeatOne && target != null && target.index == nextIndex &&
            s.queue.getOrNull(nextIndex)?.id == target.track.id
        ) {
            startMix(target, manual = true)
            return
        }
        playTrack(s.queue[nextIndex], s.queue, nextIndex)
    }

    fun previous() {
        val c = controller ?: return
        val s = _state.value
        if (mixing) cancelMix()
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
        if (mixing) cancelMix()
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
        if (mixing) cancelMix()
        armJob?.cancel()
        armJob = null
        armed = null
        armAttemptAt = -12_000L
        controller?.shuffleModeEnabled = on
        _state.value = _state.value.copy(shuffle = on)
    }

    fun setRepeatOne(on: Boolean) {
        val c = controller ?: return
        // On gère nous-mêmes la file : OFF permet STATE_ENDED -> next().
        // REPEAT_MODE_ONE ne sert qu'au vrai mode « répéter le titre ».
        if (on) {
            cancelMix()
            armJob?.cancel()
            armed = null
        } else armAttemptAt = -12_000L
        c.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(repeatOne = on)
    }

    /** Réglage « Ignorer les silences » : appliqué dans le service (moteur audio). */
    fun setSkipSilence(on: Boolean) {
        AppSettings.skipSilence.value = on
        controller?.sendCustomCommand(
            androidx.media3.session.SessionCommand(PlaybackService.CMD_SKIP_SILENCE, Bundle().apply {
                putBoolean("on", on)
            }),
            Bundle.EMPTY
        )
    }

    fun setVolume(v: Float) {
        _volume.value = v.coerceIn(0f, 1f)
        controller?.volume = _volume.value
    }

    fun volumeValue(): Float = controller?.volume ?: 1f
    fun refreshPublication(track: Track) {
        if (com.ketvoice.app.data.ImportedSong.id(track) == null) return
        scope.launch(Dispatchers.Main.immediate) {
            _state.value = _state.value.copy(queue = _state.value.queue.map { if (it.id == track.id) track else it })
        }
    }
    fun applyEqualizer() {
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_EQUALIZER, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun setAutoMix(on: Boolean) {
        AppSettings.automix.value = on
        SettingsStore.save()
        if (!on) {
            cancelMix()
            controller?.sendCustomCommand(SessionCommand(AutoMixCommands.DISABLE, Bundle.EMPTY), Bundle.EMPTY)
            armJob?.cancel()
            armJob = null
            armed = null
            AutoMix.clear()
        } else {
            armAutoMix()
        }
    }

    private fun effectiveDuration(s: PlayerUiState): Long {
        val fromPlayer = controller?.duration ?: 0L
        return if (fromPlayer > 0) fromPlayer else s.durationMs
    }

    private fun nextIndexFor(s: PlayerUiState): Int? {
        if (s.queue.size < 2 || s.repeatOne) return null
        if (s.index !in s.queue.indices) return null
        if (s.shuffle) {
            return armed?.takeIf { it.track.id != s.current?.id && it.index in s.queue.indices }?.index ?: run {
                var random = kotlin.random.Random.nextInt(s.queue.size)
                if (random == s.index) random = (random + 1) % s.queue.size
                random
            }
        }
        return if (s.index < s.queue.lastIndex) s.index + 1 else null
    }

    private fun analysisSource(track: Track): AutoMixSource? {
        Downloader.itemFor(track.id)?.let { return AutoMixSource.Local(it.file.absolutePath) }
        val localUrl = track.streamUrl
        if (track.id.startsWith("local:") && localUrl != null) {
            val uri = Uri.parse(localUrl)
            if (uri.scheme == "content") return AutoMixSource.ContentUri(localUrl)
            uri.path?.let { return AutoMixSource.Local(it) }
        }
        val stream = activeStream
        if (stream != null && com.ketvoice.app.data.ImportedSong.id(track) == null) {
            return AutoMixSource.Remote(stream.url, AudioCache.key(track.id, stream),
                stream.cacheTag.takeIf { it.isNotBlank() })
        }
        val cached = AudioCache.complete(track.id, wantsLossless())
        if (cached != null) return AutoMixSource.Remote(cached.url, AudioCache.key(track.id, cached),
            cached.cacheTag.takeIf { it.isNotBlank() })
        return null
    }

    private suspend fun resolveMixStream(track: Track): MixStream? = withContext(Dispatchers.IO) { coroutineScope {
        val localUrl = track.streamUrl
        if (track.id.startsWith("local:") && localUrl != null) {
            val uri = Uri.parse(localUrl)
            if (uri.scheme == "content" || uri.path != null) return@coroutineScope MixStream(
                MusicApi.StreamInfo(localUrl, "local", "Fichier local"), null,
                if (uri.scheme == "content") null else uri.path, "Fichier local")
        }
        Downloader.itemFor(track.id)?.let { item ->
            val url = item.file.toURI().toString()
            return@coroutineScope MixStream(MusicApi.StreamInfo(url, item.format, "Téléchargé"), null,
                item.file.absolutePath, "Téléchargé · ${item.format}")
        }
        AudioCache.complete(track.id, wantsLossless())?.let { cached ->
            return@coroutineScope MixStream(cached, AudioCache.key(track.id, cached), null, label(cached))
        }
        if (track.addon == "upload" || track.id.startsWith("kv:upload:")) {
            val stream = PublicationApi.stream(track, null) ?: return@coroutineScope null
            return@coroutineScope MixStream(stream, AudioCache.key(track.id, stream), null, label(stream))
        }
        val customTrack = track.addon?.startsWith("cs:") == true
        val tasks = mutableListOf<kotlinx.coroutines.Deferred<MusicApi.StreamInfo?>>()
        tasks += async { runCatching { MusicApi.youtubeStream(track) }.getOrNull() }
        tasks += async { runCatching { MusicApi.addonStream(track, "um") }.getOrNull() }
        if (wantsLossless()) tasks += async { runCatching { MusicApi.flacStream(track) }.getOrNull() }
        if (customTrack) tasks += async { runCatching { com.ketvoice.app.data.CustomSources.streamForTrack(track) }.getOrNull() }
        val stream = withTimeoutOrNull(9_000) {
            var found: MusicApi.StreamInfo? = null
            while (found == null && tasks.any { it.isActive }) {
                val done = withTimeoutOrNull(400) {
                    select {
                        tasks.filter { it.isActive }.forEach { task -> task.onAwait { it } }
                    }
                }
                if (done != null) found = done
                else if (tasks.none { it.isActive }) break
            }
            found ?: tasks.awaitAll().firstOrNull { it != null }
        }
        tasks.forEach { it.cancel() }
        if (stream == null) return@coroutineScope null
        MixStream(stream, AudioCache.key(track.id, stream), null, label(stream))
    } }

    private fun armAutoMix() {
        if (!AppSettings.automix.value) return
        val s = _state.value
        val current = s.current ?: return
        val index = nextIndexFor(s) ?: return
        val next = s.queue.getOrNull(index) ?: return
        if (next.id == current.id) return
        val gen = generation
        armJob?.cancel()
        armAttemptAt = android.os.SystemClock.elapsedRealtime()
        AutoMix.preparing.value = true
        armJob = scope.launch {
            try {
                val stream = resolveMixStream(next) ?: return@launch
                val level = AutoMixAnalyzer.effectiveLevel()
                val headSource = if (stream.localPath != null) AutoMixSource.Local(stream.localPath)
                    else if (Uri.parse(stream.info.url).scheme == "content") AutoMixSource.ContentUri(stream.info.url)
                    else AutoMixSource.Remote(stream.info.url, stream.cacheKey,
                        stream.info.cacheTag.takeIf { it.isNotBlank() })
                if (gen != generation) return@launch
                val fallback = AutoMixPlanner.plan(level, AutoMixSettings.maxFadeMs(),
                    AutoMixSettings.automatic(), null, null, effectiveDuration(s).takeIf { it > 0 } ?: Long.MAX_VALUE / 2)
                var ready = ArmedMix(index, next, stream, headSource, fallback)
                armed = ready
                val prepare = controller?.sendCustomCommand(SessionCommand(AutoMixCommands.PREPARE, Bundle.EMPTY),
                    mixArgs(ready, fallback.fadeMs, 0, 0, false))
                if (prepare != null) {
                    val code = withContext(Dispatchers.IO) { runCatching { prepare.get().resultCode }.getOrNull() }
                    if (code != SessionResult.RESULT_SUCCESS && gen == generation) armed = null
                }
                if (gen != generation || armed == null) return@launch
                if (level > AutoMixSettings.LOW) {
                    val tail: AutoMixAnalysis?
                    val head: AutoMixAnalysis?
                    if (level <= AutoMixSettings.MEDIUM) {
                        tail = analysisSource(current)?.let { AutoMixAnalyzer.analyze(current.id, it, level, AutoMixAnalyzer.TAIL) }
                        head = AutoMixAnalyzer.analyze(next.id, headSource, level, AutoMixAnalyzer.HEAD)
                    } else {
                        val tailTask = async(Dispatchers.Default) {
                            analysisSource(current)?.let { AutoMixAnalyzer.analyze(current.id, it, level, AutoMixAnalyzer.TAIL) }
                        }
                        val headTask = async(Dispatchers.Default) {
                            AutoMixAnalyzer.analyze(next.id, headSource, level, AutoMixAnalyzer.HEAD)
                        }
                        tail = tailTask.await()
                        head = headTask.await()
                    }
                    val analyzed = AutoMixPlanner.plan(level, AutoMixSettings.maxFadeMs(), AutoMixSettings.automatic(),
                        tail, head, effectiveDuration(s).takeIf { it > 0 } ?: Long.MAX_VALUE / 2)
                    if (gen == generation && armed === ready && !mixing) {
                        ready = ready.copy(plan = analyzed)
                        armed = ready
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (gen == generation) armed = null
            } finally {
                AutoMix.preparing.value = false
            }
        }
    }

    private fun tickAutoMix() {
        if (!AppSettings.automix.value) {
            if (armed != null) armed = null
            return
        }
        val s = _state.value
        if (mixing || resolving) return
        if (controller?.playbackState != Player.STATE_READY) return
        if (s.current == null || s.queue.size < 2) return
        if (!s.playWhenReady && !s.isPlaying) return
        if (armed == null) {
            if ((armJob == null || armJob?.isActive != true) &&
                android.os.SystemClock.elapsedRealtime() - armAttemptAt > 12_000L) armAutoMix()
            return
        }
        val target = armed ?: return
        if (target.track.id == s.current?.id || target.index !in s.queue.indices || s.queue[target.index].id != target.track.id) {
            armed = null
            return
        }
        if (s.repeatOne) {
            armed = null
            return
        }
        val duration = effectiveDuration(s)
        if (duration <= 2_000L) return
        val position = controller?.currentPosition ?: return
        if (position < 1_500L) return
        val remaining = duration - position
        val trigger = target.plan.leadMs + PRE_MIX_MS
        if (remaining <= trigger) startMix(target, manual = false)
    }

    private fun startMix(target: ArmedMix, manual: Boolean): Boolean {
        val c = controller ?: return false
        if (mixing) return false
        val s = _state.value
        val maxFade = if (manual) minOf(AutoMixSettings.MANUAL_FADE_MS, target.plan.fadeMs) else target.plan.fadeMs
        val available = if (manual) maxFade else (effectiveDuration(s) - c.currentPosition - 250L)
        if (available < AutoMixSettings.MIN_FADE_MS) return false
        val fadeMs = minOf(maxFade, available)
        val remaining = if (manual) fadeMs else (effectiveDuration(s) - (c.currentPosition.coerceAtLeast(0L)))
        val delayMs = if (manual) 0L else (remaining - target.plan.leadMs).coerceIn(0L, 3_000L)
        val token = ++mixToken
        val transition = AutoMix.Transition(
            id = token,
            fromId = s.current?.id.orEmpty(),
            toId = target.track.id,
            fromTitle = s.current?.title.orEmpty(),
            toTitle = target.track.title,
            fromCover = s.current?.let { ArtworkRepo.covers.value["t:${it.id}"] ?: it.coverUrl },
            toCover = ArtworkRepo.covers.value["t:${target.track.id}"] ?: target.track.coverUrl,
            fadeMs = fadeMs + delayMs,
            startedAt = android.os.SystemClock.elapsedRealtime(),
            manual = manual,
            strategy = target.plan.reason
        )
        val bundle = mixArgs(target, fadeMs, delayMs, token, manual)
        mixing = true
        mixTransition = transition
        mixStartedAt = android.os.SystemClock.elapsedRealtime()
        mixPositionBase = c.currentPosition.coerceAtLeast(0L)
        AutoMix.transition.value = transition
        val future = c.sendCustomCommand(SessionCommand(AutoMixCommands.MIX, Bundle.EMPTY), bundle)
        val context = appContext ?: run { mixing = false; return false }
        future.addListener({
            val code = runCatching { future.get().resultCode }.getOrNull()
            if (code != SessionResult.RESULT_SUCCESS && token == mixToken) scope.launch { finishMix(token, false) }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
        mixFinalizeJob?.cancel()
        mixFinalizeJob = scope.launch {
            delay(delayMs + fadeMs + 2_500L)
            if (token == mixToken && mixing) finishMix(token, c.currentMediaItem?.mediaId == target.track.id)
        }
        return true
    }

    private fun mixArgs(target: ArmedMix, fadeMs: Long, delayMs: Long, token: Long, manual: Boolean): Bundle = Bundle().apply {
        putString("url", target.stream.info.url)
        putString("id", target.track.id)
        putString("cacheKey", target.stream.cacheKey)
        putString("title", target.track.title)
        putString("artist", target.track.artist)
        putString("album", target.track.album)
        putString("artwork", ArtworkRepo.covers.value["t:${target.track.id}"] ?: target.track.coverUrl)
        putLong("startMs", if (manual) 0L else target.plan.incomingStartMs)
        putLong("fadeMs", fadeMs)
        putLong("delayMs", delayMs)
        putFloat("gain", target.plan.incomingGain)
        putString("curve", if (target.plan.curve == AutoMixCurve.LINEAR) "linear" else "equal")
        putLong(AutoMixCommands.TOKEN, token)
    }

    private suspend fun finishMix(token: Long, success: Boolean) {
        if (token != mixToken) return
        val transition = mixTransition ?: return
        mixFinalizeJob?.cancel()
        mixFinalizeJob = null
        if (!success) {
            mixing = false
            mixTransition = null
            AutoMix.transition.value = null
            armed = null
            _state.value = _state.value.copy(buffering = false)
            if (controller?.playbackState == Player.STATE_ENDED) next()
            return
        }
        val target = armed
        val c = controller
        mixing = false
        mixTransition = null
        AutoMix.transition.value = null
        val s = _state.value
        val index = if (target != null && s.queue.getOrNull(target.index)?.id == target.track.id) target.index
        else s.queue.indexOfFirst { it.id == transition.toId }
        val track = s.queue.getOrNull(index) ?: target?.track ?: return
        val position = c?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val generationNow = ++generation
        fallbackAttempts = 0
        activeStream = target?.stream?.info
        _state.value = s.copy(
            index = index,
            positionMs = position,
            durationMs = if (track.durationMs > 0) track.durationMs else s.durationMs,
            quality = target?.stream?.label ?: s.quality,
            buffering = false,
            isPlaying = c?.isPlaying ?: true,
            playWhenReady = c?.playWhenReady ?: true,
            failed = false
        )
        _position.value = position
        historyGeneration = generationNow
        com.ketvoice.app.data.ListeningHistory.record(track)
        if (track.id !in recordedListens) {
            recordedListens.add(track.id)
            scope.launch(Dispatchers.IO) { com.ketvoice.app.data.YouTubeMusicSession.recordListen(track) }
        }
        target?.stream?.info?.let { AudioCache.cacheWhole(track.id, it) }
        scope.launch(Dispatchers.Main.immediate) {
            CanvasRepo.resolve(track)
            LyricsRepo.load(track)
            ArtworkRepo.enrich(listOf(track))
        }
        armed = null
        armAutoMix()
    }

    private fun cancelMix() {
        if (!mixing) return
        mixToken++
        mixing = false
        mixTransition = null
        mixFinalizeJob?.cancel()
        mixFinalizeJob = null
        AutoMix.transition.value = null
        controller?.sendCustomCommand(SessionCommand(AutoMixCommands.CANCEL, Bundle.EMPTY), Bundle.EMPTY)
    }

    private const val PRE_MIX_MS = 900L

    suspend fun awaitReady(trackId: String): Boolean = withTimeoutOrNull(45_000) {
        state.first { it.current?.id == trackId && !it.buffering && controller?.playbackState == Player.STATE_READY }
        true
    } ?: false
}
