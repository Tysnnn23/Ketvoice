package com.ketvoice.app.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ketvoice.app.MainActivity
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.*
import com.google.common.util.concurrent.SettableFuture
import java.nio.ByteBuffer

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var upgradeJob: Job? = null
    private var playbackRevision = 0L
    private var stopping = false
    /** Level, filter and analysis tap of each deck's sound path (absent in exact mode). */
    private val decks = java.util.WeakHashMap<ExoPlayer, com.ketvoice.app.automix.DeckControl>()
    private val released = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ExoPlayer, Boolean>())
    private val automixHost = object : com.ketvoice.app.automix.AutomixHost {
        override val main: ExoPlayer get() = player
        override val exact: Boolean get() = AppSettings.bitPerfect.value
        override fun control(deck: ExoPlayer) = decks[deck]
        override fun standby(): ExoPlayer = buildPlayer(standby = true).also { deck ->
            deck.volume = 0f
            // One audio session for both decks: the equalizer shapes the mix, not one deck then the other.
            if (player.audioSessionId > 0) runCatching { deck.audioSessionId = player.audioSessionId }
        }
        override fun promote(next: ExoPlayer): ExoPlayer {
            val old = player
            upgradeJob?.cancel()
            next.repeatMode = old.repeatMode
            next.shuffleModeEnabled = old.shuffleModeEnabled
            val attributes = old.audioAttributes
            player = next
            mediaSession?.setPlayer(sessionPlayer(next))
            // The outgoing deck keeps playing its fade; audio focus and headset events follow the new one.
            old.setAudioAttributes(attributes, false)
            next.setAudioAttributes(attributes, true)
            if (next.audioSessionId > 0) AudioEqualizer.bind(next.audioSessionId)
            playbackRevision++
            return old
        }
        override fun release(deck: ExoPlayer) {
            if (deck === player || !released.add(deck)) return
            decks.remove(deck)
            runCatching { deck.stop() }
            runCatching { deck.release() }
        }
    }
    private val audioDevices = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            // Re-negotiate when a wired/USB output appears, but never tear down
            // an idle player that has nothing to re-prepare.
            if (AppSettings.bitPerfect.value && ::player.isInitialized && player.currentMediaItem != null &&
                addedDevices.any { it.isSink && it.isExactOutput() }) {
                rebuildAudioMode()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
            if (AppSettings.bitPerfect.value && ::player.isInitialized &&
                removedDevices.any { it.isSink && it.isExactOutput() }) {
                // Never unexpectedly continue on the loudspeaker after a DAC unplug.
                player.pause()
                if (player.currentMediaItem != null) rebuildAudioMode()
            }
        }
    }

    private fun android.media.AudioDeviceInfo.isExactOutput(): Boolean = type in intArrayOf(
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET)

    override fun onCreate() {
        super.onCreate()
        androidx.media3.common.util.Log.setLogger(SafeMedia3Logger)

        com.ketvoice.app.data.SettingsStore.load(applicationContext)
        com.ketvoice.app.data.PlaybackJournal.init(applicationContext)
        com.ketvoice.app.data.PlaybackJournal.add("playback service started")
        // Playback may start from the system's controls without the app's screen: what the listener blocked still holds.
        runCatching { com.ketvoice.app.data.ArtistPrefs.init(applicationContext) }
        player = buildPlayer()
        getSystemService(android.media.AudioManager::class.java).registerAudioDeviceCallback(audioDevices, android.os.Handler(mainLooper))
        // A title separated while it plays (the voice slider, 6.0.0 Beta 3) has its voice level at once.
        serviceScope.launch { com.ketvoice.app.stems.VoiceSeparation.separated.collect { takeUpVoice(it) } }
        val session = MediaSession.Builder(this, sessionPlayer(player))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName) return super.onConnect(session, controller)
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_SKIP_SILENCE, Bundle.EMPTY))
                        .add(SessionCommand(CMD_EQUALIZER, Bundle.EMPTY))
                        .add(SessionCommand(CMD_REVERB, Bundle.EMPTY))
                        .add(SessionCommand(CMD_UPGRADE, Bundle.EMPTY))
                        .add(SessionCommand(CMD_CANCEL_UPGRADE, Bundle.EMPTY))
                        .add(SessionCommand(CMD_BIT_PERFECT, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands).build()
                }
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    command: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (controller.packageName != packageName) return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    if (command.customAction == CMD_BIT_PERFECT) {
                        AppSettings.bitPerfect.value = args.getBoolean("enabled", false)
                        rebuildAudioMode()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction == CMD_CANCEL_UPGRADE) {
                        upgradeJob?.cancel()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction == CMD_UPGRADE) return prepareUpgrade(args)
                    if (command.customAction == CMD_REVERB) {
                        val amount = args.getInt("amount", AppSettings.reverbAmount.value).coerceIn(0, 100)
                        val size = args.getInt("size", AppSettings.reverbSize.value).coerceIn(0, 100)
                        val damping = args.getInt("damping", AppSettings.reverbDamping.value).coerceIn(0, 100)
                        AppSettings.reverbAmount.value = amount
                        AppSettings.reverbSize.value = size
                        AppSettings.reverbDamping.value = damping
                        ReverbControl.amount = if (AppSettings.bitPerfect.value) 0f else amount / 100f
                        ReverbControl.size = size / 100f
                        ReverbControl.damping = damping / 100f
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction != CMD_SKIP_SILENCE && command.customAction != CMD_EQUALIZER)
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    if (command.customAction == CMD_SKIP_SILENCE) {
                        val on = skipSilenceRequested(args, command.customExtras)
                        player.skipSilenceEnabled = on && !AppSettings.bitPerfect.value
                        AppSettings.skipSilence.value = on
                    }
                    if (command.customAction == CMD_EQUALIZER) AudioEqualizer.apply()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        mediaSession = session
        com.ketvoice.app.automix.AnalysisStore.init(applicationContext)
        com.ketvoice.app.automix.Automix.attach(automixHost)
    }

    /**
     * What the session exposes for an engine: the same player plus "next" and
     * "previous", which belong to the app queue. The service shares the app process,
     * so the queue is reached directly; it is connected first for a cold start from
     * a headset or the notification.
     */
    /** Gives the deck playing [trackId] its fresh separation; the sound is picked up again where it is, which flushes the chain. */
    private fun takeUpVoice(trackId: String) {
        if (AppSettings.bitPerfect.value || !::player.isInitialized) return
        serviceScope.launch {
            val mask = withContext(Dispatchers.IO) { runCatching { com.ketvoice.app.stems.StemStore.loadWhole(trackId) }.getOrNull() } ?: return@launch
            val main = player
            if (main.currentMediaItem?.mediaId != trackId || com.ketvoice.app.automix.Automix.mixing) return@launch
            val control = decks[main] ?: return@launch
            control.voiceMask = mask
            // A stream already running through the voice stage takes the separation up live, with no gap; otherwise
            // (the model arrived during the title) the stage starts where the sound starts again: a seek to the same place.
            if (!control.voiceStageOn) main.seekTo(main.currentPosition)
        }
    }

    private fun sessionPlayer(engine: ExoPlayer): androidx.media3.common.Player = QueueBridgePlayer(engine,
        onNext = { Player.connect(applicationContext); Player.next() },
        onPrevious = { Player.connect(applicationContext); Player.previous() })

    private fun buildPlayer(standby: Boolean = false): ExoPlayer {
        val exactRequested = AppSettings.bitPerfect.value
        if (exactRequested) { AudioEqualizer.release(); ReverbControl.amount = 0f }
        else ReverbControl.amount = AppSettings.reverbAmount.value / 100f
        lateinit var owner: ExoPlayer
        val deck = com.ketvoice.app.automix.DeckControl().also { it.main = !standby }
        val meter = object : TeeAudioProcessor.AudioBufferSink {
            private var rate = 0
            private var channels = 0
            private var encoding = 0
            private var promoted = false
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                rate = sampleRateHz; channels = channelCount; this.encoding = encoding
                promoted = false
            }
            override fun handleBuffer(buffer: ByteBuffer) {
                if (!::player.isInitialized || owner !== player) return
                if (!promoted) { SonicMeter.flush(rate, channels, encoding); promoted = true }
                SonicMeter.handleBuffer(buffer)
            }
        }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                // Half a second of sound ahead in the audio track instead of a quarter: once the app is no longer
                // in front Android gives it fewer and slower cores, and a short buffer then runs dry in audible gaps.
                val buffers = androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(AUDIO_BUFFER_MIN_US).setMaxPcmBufferDurationUs(AUDIO_BUFFER_MAX_US).build()
                val fallback = ProcessingAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                    .setAudioTrackBufferSizeProvider(buffers)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(if (exactRequested) emptyArray() else arrayOf(
                        com.ketvoice.app.automix.DeckMixProcessor(deck), LevelingAudioProcessor(), ReverbAudioProcessor {
                        if (::player.isInitialized && owner === this@PlaybackService.player) ReverbControl.notePcm()
                    }, TeeAudioProcessor(meter))).build(), if (exactRequested) null else deck)
                if (exactRequested && android.os.Build.VERSION.SDK_INT >= 34) return BitPerfectAudioSink(context, fallback)
                if (exactRequested) BitPerfectState.report(ExactAudioStatus(ExactAudioPhase.Unavailable, "Android 14 et un mixeur compatible sont requis"))
                return fallback
            }
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(playbackMediaSourceFactory(this))
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(PlaybackBuffer.MIN_MS, PlaybackBuffer.MAX_MS, 500, 1_200)
                    .setTargetBufferBytes(PlaybackBuffer.TARGET_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                !standby
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        owner = player
        if (!exactRequested) decks[player] = deck
        player.skipSilenceEnabled = AppSettings.skipSilence.value && !exactRequested
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            private var manifestReported = false
            private var mediaReported = false
            override fun onMediaItemTransition(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                               mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                manifestReported = false
                mediaReported = false
                // A title separated whole can have its voice turned down (6.0.0): its separation is read before its first sound.
                deck.voiceMask = null
                val id = mediaItem?.mediaId
                // Where its sound comes from, for a separation asked from the voice slider (6.0.0 Beta 3); a stream
                // made of several files (DASH, HLS) cannot be read whole, a downloaded copy is used instead.
                if (id != null) {
                    val local = mediaItem.localConfiguration
                    val uri = local?.uri
                    val pieces = local?.mimeType == androidx.media3.common.MimeTypes.APPLICATION_MPD ||
                        local?.mimeType == androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    if (uri != null && !pieces) com.ketvoice.app.stems.VoiceSeparation.heard(id, uri)
                }
                if (id != null && !exactRequested) serviceScope.launch(Dispatchers.IO) {
                    val mask = runCatching { com.ketvoice.app.stems.StemStore.loadWhole(id) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (owner.currentMediaItem?.mediaId == id) deck.voiceMask = mask
                        // A listener who turned the voice down keeps it down: each new title is separated as it starts.
                        // Not while the app is away on battery (background thrift): the slider asks again when it is shown.
                        if (mask == null && owner.currentMediaItem?.mediaId == id && com.ketvoice.app.stems.VoiceLevel.level < .99f &&
                            com.ketvoice.app.stems.VoiceSeparation.modelReady && !com.ketvoice.app.automix.BackgroundThrift.active())
                            com.ketvoice.app.stems.VoiceSeparation.request(id)
                    }
                }
                // A title that Ketmix played in time with the previous one keeps that speed to its end; the
                // next title starts on its own tempo again, at the boundary between the two where it is not heard.
                // A title that starts over on repeat is the same title, heard on: its speed is left alone.
                if (::player.isInitialized && owner === this@PlaybackService.player && !com.ketvoice.app.automix.Automix.mixing &&
                    reason != androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && owner.playbackParameters.speed != 1f) owner.playbackParameters = androidx.media3.common.PlaybackParameters.DEFAULT
            }
            override fun onLoadCompleted(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                         loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                                         mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
                if (!this@PlaybackService::player.isInitialized || owner !== this@PlaybackService.player) return
                when (mediaLoadData.dataType) {
                    C.DATA_TYPE_MANIFEST -> if (!manifestReported) {
                        manifestReported = true
                        android.util.Log.i("KetvoicePlayback", "manifest_load_ms=${loadEventInfo.loadDurationMs}")
                    }
                    C.DATA_TYPE_MEDIA -> if (!mediaReported) {
                        mediaReported = true
                        android.util.Log.i("KetvoicePlayback", "first_media_load_ms=${loadEventInfo.loadDurationMs}")
                    }
                }
            }
        })
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (owner === this@PlaybackService.player && !isPlaying) SonicMeter.reset()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (owner !== this@PlaybackService.player || playWhenReady) return
                when (reason) {
                    androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                        com.ketvoice.app.data.PlaybackJournal.add("paused: another app took the audio")
                    androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
                        com.ketvoice.app.data.PlaybackJournal.add("paused: headphones or Bluetooth disconnected")
                }
            }
            private var stalledAt = 0L
            override fun onPlaybackStateChanged(state: Int) {
                if (owner !== this@PlaybackService.player) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (state == androidx.media3.common.Player.STATE_BUFFERING && owner.playWhenReady) {
                    if (stalledAt == 0L) stalledAt = now
                } else if (stalledAt != 0L) {
                    PlaybackBuffer.stallNote(now - stalledAt, com.ketvoice.app.automix.Automix.appInFront,
                        restrictedData())?.let { com.ketvoice.app.data.PlaybackJournal.add(it) }
                    stalledAt = 0L
                }
            }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                if (owner === this@PlaybackService.player && reason == androidx.media3.common.Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
                    com.ketvoice.app.data.PlaybackJournal.add("paused for a moment: another app (a call, a notification) took the audio")
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                if (owner === this@PlaybackService.player) {
                    playbackRevision++
                    SonicMeter.reset()
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (owner === this@PlaybackService.player && audioSessionId > 0) AudioEqualizer.bind(audioSessionId)
            }
        })
        return player
    }

    private fun rebuildAudioMode() {
        if (!::player.isInitialized || stopping) return
        com.ketvoice.app.automix.Automix.cancel()
        upgradeJob?.cancel()
        val old = player
        val item = old.currentMediaItem
        val position = old.currentPosition.coerceAtLeast(0)
        val play = old.playWhenReady
        val repeat = old.repeatMode
        old.pause()
        BitPerfectState.report(
            ExactAudioStatus(
                if (AppSettings.bitPerfect.value) ExactAudioPhase.Pending else ExactAudioPhase.Standard,
                if (AppSettings.bitPerfect.value) "Vérification du chemin audio…" else "Sortie standard"
            )
        )
        player = buildPlayer()
        // Media3 player gain stays at unity in both modes: the user-facing volume
        // is the system media stream, which the app never converts to PCM gain.
        player.volume = 1f
        player.repeatMode = repeat
        mediaSession?.setPlayer(sessionPlayer(player))
        old.release()
        if (item != null) { player.setMediaItem(item, position); player.prepare(); player.playWhenReady = play }
    }

    /**
     * How far apart, in ms, the sound of [next] is from that of [old] at the same positions, and how alike
     * (6.0.0 Beta 4): two seconds a little ahead of what is heard are recorded from both decks' processors
     * and their loudness envelopes compared. Null when the decks' processors do not see the sound.
     */
    private suspend fun measureOffset(old: ExoPlayer, next: ExoPlayer): Pair<Double, Float>? {
        val oldControl = decks[old] ?: return null
        val nextControl = decks[next] ?: return null
        val fromUs = (old.currentPosition + 2_500L) * 1000L
        val untilUs = fromUs + 2_000_000L
        val a = com.ketvoice.app.automix.PcmCapture(fromUs, untilUs)
        val b = com.ketvoice.app.automix.PcmCapture(fromUs, untilUs)
        val oldTap = oldControl.tap; val nextTap = nextControl.tap
        oldControl.tap = oldTap?.let { com.ketvoice.app.automix.BothTaps(it, a) } ?: a
        nextControl.tap = nextTap?.let { com.ketvoice.app.automix.BothTaps(it, b) } ?: b
        try {
            withTimeoutOrNull(8_000) { while (!(a.done && b.done)) delay(40) } ?: return null
        } finally {
            oldControl.tap = oldTap; nextControl.tap = nextTap
        }
        val pa = a.pcm(minSeconds = 1.2) ?: return null
        val pb = b.pcm(minSeconds = 1.2) ?: return null
        return withContext(Dispatchers.Default) {
            QualityHandoff.offset(QualityHandoff.envelope(pa, a.rate, a.channels), a.startUs,
                QualityHandoff.envelope(pb, b.rate, b.channels), b.startUs)
        }
    }

    /** Prepare and decode in isolation. A failed/late candidate never replaces live audio.
     * This is a same-recording quality handoff, never a transition between songs. */
    private fun prepareUpgrade(args: Bundle): ListenableFuture<SessionResult> {
        if (AppSettings.bitPerfect.value) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        // Two titles are being mixed: no deck is swapped for a better stream in the middle of it.
        if (com.ketvoice.app.automix.Automix.mixing) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        upgradeJob?.cancel()
        val result = SettableFuture.create<SessionResult>()
        val old = player
        val expectedId = args.getString("id")
        val url = args.getString("url").orEmpty()
        // The user's progressive-quality switch only decides whether a better stream
        // is fetched at all (Player.kt). Once one is offered, the silent handoff is
        // always allowed: it is what turns a fast start into lossless playback.
        if (old.currentMediaItem?.mediaId != expectedId || !url.startsWith("https://"))
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val revision = playbackRevision
        val originalVolume = old.volume
        upgradeJob = serviceScope.launch {
            var candidate: ExoPlayer? = null
            var success = false
            var promoted = false
            try {
                success = withTimeoutOrNull(30_000) {
                    val next = buildPlayer(standby = true)
                    candidate = next
                    next.volume = 0f
                    // The voice level goes on with the better stream (6.0.0 Beta 4).
                    decks[next]?.voiceMask = decks[old]?.voiceMask
                    next.playbackParameters = old.playbackParameters
                    next.skipSilenceEnabled = old.skipSilenceEnabled
                    val item = old.currentMediaItem!!.buildUpon().setUri(url).setMimeType(args.getString("mimeType"))
                        .setCustomCacheKey(args.getString("cacheKey")).build()
                    next.setMediaItem(item, old.currentPosition)
                    next.prepare()
                    fun valid() = player === old && playbackRevision == revision &&
                        old.currentMediaItem?.mediaId == expectedId && old.playerError == null
                    while (next.playbackState != androidx.media3.common.Player.STATE_READY) {
                        if (!valid() || next.playerError != null) return@withTimeoutOrNull false
                        delay(20)
                    }
                    // Two renditions may differ by a few seconds of silence at their ends; once their sound is
                    // measured and aligned (below) that is allowed, otherwise their lengths must agree closely.
                    if (!QualityHandoff.compatible(old.duration, next.duration, old.currentPosition, QualityHandoff.ALIGNED_DURATION_MS)) return@withTimeoutOrNull false
                    // Muted pre-roll uses a separate decoder; seek/buffering never touches old.
                    var aligned = false
                    for (attempt in 0..2) {
                        if (!valid()) return@withTimeoutOrNull false
                        next.seekTo(old.currentPosition)
                        next.playWhenReady = old.isPlaying
                        delay(120)
                        while (next.playbackState != androidx.media3.common.Player.STATE_READY) {
                            if (!valid() || next.playerError != null) return@withTimeoutOrNull false
                            delay(20)
                        }
                        if (QualityHandoff.aligned(old.currentPosition, next.currentPosition)) { aligned = true; break }
                    }
                    if (!aligned || !valid() || (old.playWhenReady && (!old.isPlaying || !next.isPlaying))) return@withTimeoutOrNull false
                    // 6.0.0 Beta 4: positions only roughly agree between two encodes (priming, estimated seeks):
                    // the sound of both is compared and the better stream moved until they are heard as one.
                    var heardAligned = false
                    var measured = false
                    for (attempt in 0..2) {
                        if (!valid()) return@withTimeoutOrNull false
                        val found = measureOffset(old, next) ?: break
                        measured = true
                        val (lagMs, likeness) = found
                        if (likeness < QualityHandoff.SAME_RECORDING) return@withTimeoutOrNull false
                        if (kotlin.math.abs(lagMs) <= QualityHandoff.HEARD_ALIGNED_MS) { heardAligned = true; break }
                        next.seekTo((next.currentPosition + Math.round(lagMs)).coerceAtLeast(0L))
                        delay(120)
                        while (next.playbackState != androidx.media3.common.Player.STATE_READY) {
                            if (!valid() || next.playerError != null) return@withTimeoutOrNull false
                            delay(20)
                        }
                    }
                    // Without a measure (a format the deck's processor does not read), the old rule: close lengths.
                    if (measured && !heardAligned) return@withTimeoutOrNull false
                    if (!measured && !QualityHandoff.compatible(old.duration, next.duration, old.currentPosition)) return@withTimeoutOrNull false
                    val volume = old.volume
                    // The same recording, aligned: a short equal-power crossfade, no dip and no click.
                    for (step in 1..8) {
                        if (!valid()) return@withTimeoutOrNull false
                        val x = step / 8f
                        old.volume = volume * kotlin.math.cos(x * Math.PI / 2).toFloat()
                        next.volume = volume * kotlin.math.sin(x * Math.PI / 2).toFloat()
                        delay(12)
                    }
                    if (!valid()) return@withTimeoutOrNull false
                    next.repeatMode = old.repeatMode
                    next.shuffleModeEnabled = old.shuffleModeEnabled
                    next.skipSilenceEnabled = old.skipSilenceEnabled
                    val resume = old.playWhenReady
                    val attributes = old.audioAttributes
                    player = next
                    mediaSession?.setPlayer(sessionPlayer(next))
                    promoted = true
                    old.setAudioAttributes(attributes, false)
                    old.pause()
                    next.setAudioAttributes(attributes, true)
                    next.playWhenReady = resume
                    AudioEqualizer.bind(next.audioSessionId)
                    old.release()
                    // Once promoted, its level is the listener's even if preparation is cancelled.
                    withContext(NonCancellable) { if (player === next && !stopping) next.volume = volume }
                    true
                } == true
            } catch (_: CancellationException) {
                // New track/seek/stop cancels the candidate, not the audible player.
            } catch (_: Exception) {
                // A provider/decoder failure is not a playback failure.
            } finally {
                if (candidate !== player) candidate?.release()
                if (player === old && !stopping) old.volume = originalVolume
                result.set(SessionResult(if (success || promoted) SessionResult.RESULT_SUCCESS else SessionError.ERROR_UNKNOWN))
            }
        }
        return result
    }

    /** Whether Android's data saver keeps this app off the network in the background. */
    private fun restrictedData(): Boolean = runCatching {
        getSystemService(android.net.ConnectivityManager::class.java).restrictBackgroundStatus ==
            android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        com.ketvoice.app.data.PlaybackJournal.add("app swiped away from recent apps · ${if (::player.isInitialized && player.playWhenReady) "playback goes on" else "nothing playing, service ends"}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        com.ketvoice.app.data.PlaybackJournal.add("playback service stopped")
        stopping = true
        com.ketvoice.app.automix.Automix.detach(automixHost)
        getSystemService(android.media.AudioManager::class.java).unregisterAudioDeviceCallback(audioDevices)
        BitPerfectState.report(ExactAudioStatus())
        serviceScope.cancel()
        SonicMeter.observe(false)
        AudioEqualizer.release()
        com.ketvoice.app.data.ListeningHistory.flushListenedMs()
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val AUDIO_BUFFER_MIN_US = 500_000
        const val AUDIO_BUFFER_MAX_US = 1_000_000
        const val CMD_SKIP_SILENCE = "ketvoice.set_skip_silence"
        const val CMD_EQUALIZER = "ketvoice.apply_equalizer"
        const val CMD_REVERB = "ketvoice.set_reverb"
        const val CMD_UPGRADE = "ketvoice.prepare_quality_upgrade"
        const val CMD_CANCEL_UPGRADE = "ketvoice.cancel_quality_upgrade"
        const val CMD_BIT_PERFECT = "ketvoice.bit_perfect"
    }
}

/** Arguments carry the value; older controllers put it in the command extras. */
internal fun skipSilenceRequested(args: android.os.Bundle, extras: android.os.Bundle): Boolean =
    if (args.containsKey("on")) args.getBoolean("on", false) else extras.getBoolean("on", false)

/**
 * How far ahead the player reads (6.0.0 Beta 5). Three minutes of sound means a phone that cuts the network
 * of apps in the background (MagicOS, data saver) has to hold it off for that long before the music stops,
 * and the radio wakes far less often; the byte cap keeps very high quality files to a sensible size.
 */
internal object PlaybackBuffer {
    const val MIN_MS = 30_000
    const val MAX_MS = 180_000
    const val TARGET_BYTES = 32 * 1024 * 1024

    /** A journal line for a wait for data that a listener could hear, or null for a short one. */
    fun stallNote(waitedMs: Long, inFront: Boolean, restrictedData: Boolean): String? {
        if (waitedMs < 2_000) return null
        val where = if (inFront) "" else " · app in the background"
        val saver = if (restrictedData) " · data saver limits Ketvoice in the background" else ""
        return "waited ${waitedMs / 1000} s for data$where$saver"
    }
}
