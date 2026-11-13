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
        player = buildPlayer()
        getSystemService(android.media.AudioManager::class.java).registerAudioDeviceCallback(audioDevices, android.os.Handler(mainLooper))
        val session = MediaSession.Builder(this, player)
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
    }

    private fun buildPlayer(standby: Boolean = false): ExoPlayer {
        val exactRequested = AppSettings.bitPerfect.value
        if (exactRequested) { AudioEqualizer.release(); ReverbControl.amount = 0f }
        else ReverbControl.amount = AppSettings.reverbAmount.value / 100f
        lateinit var owner: ExoPlayer
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
                val fallback = ProcessingAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(if (exactRequested) emptyArray() else arrayOf(ReverbAudioProcessor {
                        if (::player.isInitialized && owner === this@PlaybackService.player) ReverbControl.notePcm()
                    }, TeeAudioProcessor(meter))).build())
                if (exactRequested && android.os.Build.VERSION.SDK_INT >= 34) return BitPerfectAudioSink(context, fallback)
                if (exactRequested) BitPerfectState.report(ExactAudioStatus(ExactAudioPhase.Unavailable, "Android 14 et un mixeur compatible sont requis"))
                return fallback
            }
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(playbackMediaSourceFactory(this))
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(20_000, 60_000, 500, 1_200)
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
        player.skipSilenceEnabled = AppSettings.skipSilence.value && !exactRequested
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            private var manifestReported = false
            private var mediaReported = false
            override fun onMediaItemTransition(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                               mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                manifestReported = false
                mediaReported = false
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
        mediaSession?.setPlayer(player)
        old.release()
        if (item != null) { player.setMediaItem(item, position); player.prepare(); player.playWhenReady = play }
    }

    /** Prepare and decode in isolation. A failed/late candidate never replaces live audio.
     * This is a same-recording quality handoff, never a transition between songs. */
    private fun prepareUpgrade(args: Bundle): ListenableFuture<SessionResult> {
        if (AppSettings.bitPerfect.value) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
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
                success = withTimeoutOrNull(15_000) {
                    val next = buildPlayer(standby = true)
                    candidate = next
                    next.volume = 0f
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
                    if (!QualityHandoff.compatible(old.duration, next.duration, old.currentPosition)) return@withTimeoutOrNull false
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
                    val volume = old.volume
                    // Short amplitude ramps suppress clicks without mixing different recordings.
                    for (step in 1..4) {
                        if (!valid()) return@withTimeoutOrNull false
                        old.volume = volume * (1f - step / 4f)
                        delay(10)
                    }
                    if (!valid() || !QualityHandoff.aligned(old.currentPosition, next.currentPosition)) return@withTimeoutOrNull false
                    next.repeatMode = old.repeatMode
                    next.shuffleModeEnabled = old.shuffleModeEnabled
                    next.skipSilenceEnabled = old.skipSilenceEnabled
                    val resume = old.playWhenReady
                    val attributes = old.audioAttributes
                    player = next
                    mediaSession?.setPlayer(next)
                    promoted = true
                    old.setAudioAttributes(attributes, false)
                    old.pause()
                    next.setAudioAttributes(attributes, true)
                    next.playWhenReady = resume
                    AudioEqualizer.bind(next.audioSessionId)
                    old.release()
                    // Once promoted, complete the tiny ramp even if preparation is cancelled.
                    withContext(NonCancellable) {
                        for (step in 1..4) {
                            if (player !== next || stopping) break
                            next.volume = volume * step / 4f
                            delay(10)
                        }
                    }
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        stopping = true
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
