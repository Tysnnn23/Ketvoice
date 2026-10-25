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
import androidx.media3.session.SessionToken
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PublicationApi
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
    val buffering: Boolean = false
) {
    val current: Track? get() = if (index in queue.indices) queue[index] else null
}

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
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _position = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _position
    private val _seekEpoch = MutableStateFlow(0L)
    val seekEpoch: StateFlow<Long> = _seekEpoch
    val playbackEpoch: Int get() = generation

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private var lastEndedKey: Pair<String?, Int>? = null

    private val listener = object : Player.Listener {
        override fun onVolumeChanged(volume: Float) { _volume.value = volume }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!resolving) {
                desiredPlay = controller?.playWhenReady ?: playWhenReady
                _state.value = _state.value.copy(playWhenReady = desiredPlay)
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            val track = _state.value.current ?: return
            val failed = activeStream
            if (fallbackAttempts++ >= 2) {
                _state.value = _state.value.copy(isPlaying = false, quality = "Erreur de lecture · réessayez")
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
                    _state.value = _state.value.copy(isPlaying = false, quality = "Flux indisponible · réessayez")
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (resolving) return
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (isPlaying && historyGeneration != generation) {
                _state.value.current?.let { com.ketvoice.app.data.ListeningHistory.record(it) }
                historyGeneration = generation
            }
            if (isPlaying) _state.value.current?.let { track ->
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
        CanvasRepo.init(context.applicationContext)
        ArtworkRepo.init(context.applicationContext)
        Downloader.init(context.applicationContext)
        com.ketvoice.app.data.OfflineCapsule.init(context.applicationContext)
        com.ketvoice.app.data.PlaylistStore.init(context.applicationContext)
        com.ketvoice.app.data.ListeningHistory.init(context.applicationContext)
        com.ketvoice.app.data.HomeFeed.start(context.applicationContext)
        MusicApi.init(context.applicationContext)
        AudioCache.init(context.applicationContext)
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

        scope.launch {
            while (true) {
                if (controller?.isPlaying == true) positionNow()
                delay(230)
            }
        }
    }

    private fun positionNow() {
        if (resolving) return
        val c = controller ?: return
        val pos = c.currentPosition.coerceAtLeast(0L)
        _position.value = pos
        val dur = if (c.duration > 0) c.duration else _state.value.durationMs
        if (dur != _state.value.durationMs ||
            kotlin.math.abs(pos - _state.value.positionMs) > 3_500L
        ) {
            _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
        }
    }

    private fun qualitySetting(): String {
        return if (isMetered()) AppSettings.mobileQuality.value else AppSettings.wifiQuality.value
    }

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

    fun playTrack(track: Track, queue: List<Track> = listOf(track), startIndex: Int? = null,
                  startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        val c = controller ?: run {
            pendingPlay = PendingPlay(track, queue, startIndex, startPositionMs, autoPlay)
            appContext?.let { if (!connected) connect(it) }
            return
        }
        resolutionJob?.cancel()
        resolving = true
        desiredPlay = autoPlay
        activeStream = null
        fallbackAttempts = 0
        c.pause()
        lastEndedKey = null

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

        scope.launch { CanvasRepo.resolve(resolvedTrack) }
        scope.launch { LyricsRepo.load(resolvedTrack) }
        scope.launch { ArtworkRepo.enrich(listOf(resolvedTrack)) }

        resolutionJob = scope.launch(Dispatchers.Main) {

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
                    _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false, quality = "Aucun flux disponible")
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
                _state.value = _state.value.copy(isPlaying = false, playWhenReady = false, buffering = false, quality = "Aucun flux disponible")
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

                val lossy = mp3.await()
                val bitrate = lossy?.quality?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 0
                if (bitrate >= 256) upgrade(lossy)
            }
            if (wantsLossless() && !first.format.contains("flac", true)) upgrade(flac.await())
            if (customTrack) {

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

    fun playNext(track: Track) {
        val s = _state.value
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

    fun jumpTo(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        playTrack(s.queue[index], s.queue, index)
    }

    fun removeTrack(trackId: String) {
        val s = _state.value
        val i = s.queue.indexOfFirst { it.id == trackId }
        removeAt(i)
    }

    fun removeAt(i: Int) {
        val s = _state.value
        if (i !in s.queue.indices) return
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
        desiredPlay = false
        _state.value = _state.value.copy(playWhenReady = false)
        controller?.pause()
    }

    fun toggle() {
        val c = controller ?: return
        val state = _state.value
        if (!state.isPlaying && (state.quality.contains("flux", true) || state.quality.contains("erreur", true))) {
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

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val nextIndex = if (s.shuffle && s.queue.size > 1) {
            var random = kotlin.random.Random.nextInt(s.queue.size)
            if (random == s.index) random = (random + 1) % s.queue.size
            random
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
        val previous = (s.index - 1).coerceAtLeast(0)
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

    fun currentPositionMs(): Long =
        (controller?.currentPosition ?: _position.value).coerceAtLeast(0L)

    fun setShuffle(on: Boolean) {
        controller?.shuffleModeEnabled = on
        _state.value = _state.value.copy(shuffle = on)
    }

    fun setRepeatOne(on: Boolean) {
        val c = controller ?: return

        c.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.value = _state.value.copy(repeatOne = on)
    }

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

    suspend fun awaitReady(trackId: String): Boolean = withTimeoutOrNull(45_000) {
        state.first { it.current?.id == trackId && !it.buffering && controller?.playbackState == Player.STATE_READY }
        true
    } ?: false
}
