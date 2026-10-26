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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ketvoice.app.MainActivity
import com.ketvoice.app.data.AppSettings

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var engine: AutoMixEngine

    override fun onCreate() {
        super.onCreate()

        com.ketvoice.app.data.SettingsStore.load(applicationContext)
        val first = buildDeck(true)
        val session = MediaSession.Builder(this, first.player)
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
                        .add(SessionCommand(AutoMixCommands.MIX, Bundle.EMPTY))
                        .add(SessionCommand(AutoMixCommands.PREPARE, Bundle.EMPTY))
                        .add(SessionCommand(AutoMixCommands.CANCEL, Bundle.EMPTY))
                        .add(SessionCommand(AutoMixCommands.DISABLE, Bundle.EMPTY))
                        .add(SessionCommand(AutoMixCommands.EVENT, Bundle.EMPTY))
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
                    if (command.customAction == CMD_SKIP_SILENCE) {
                        val on = args.getBoolean("on", false)
                        engine.applySkipSilence(on)
                        AppSettings.skipSilence.value = on
                    }
                    if (command.customAction == CMD_EQUALIZER) AudioEqualizer.apply()
                    if (command.customAction == AutoMixCommands.CANCEL) {
                        engine.cancel()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction == AutoMixCommands.DISABLE) {
                        engine.disable()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction == AutoMixCommands.PREPARE) {
                        val request = request(args)
                        return Futures.immediateFuture(SessionResult(if (request != null && engine.prepare(request))
                            SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE))
                    }
                    if (command.customAction == AutoMixCommands.MIX) {
                        val request = request(args)
                        val started = request != null && engine.start(request)
                        return Futures.immediateFuture(SessionResult(
                            if (started) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE
                        ))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        mediaSession = session
        engine = AutoMixEngine(
            session = session,
            first = first,
            createDeck = { buildDeck(false) },
            onAudioSession = { _, id -> if (id > 0) AudioEqualizer.bind(id) },
            onFinished = { success, token ->
                val bundle = Bundle().apply {
                    putLong(AutoMixCommands.TOKEN, token)
                    putBoolean(AutoMixCommands.SUCCESS, success)
                }
                runCatching { session.broadcastCustomCommand(SessionCommand(AutoMixCommands.EVENT, Bundle.EMPTY), bundle) }
            }
        )
        engine.attach(first)
    }

    private fun request(args: Bundle): MixRequest? {
        val url = args.getString("url") ?: return null
        val id = args.getString("id") ?: return null
        val fade = args.getLong("fadeMs", 0L)
        if (fade <= 0L) return null
        return MixRequest(
            trackId = id,
            url = url,
            cacheKey = args.getString("cacheKey"),
            title = args.getString("title").orEmpty(),
            artist = args.getString("artist").orEmpty(),
            album = args.getString("album").orEmpty(),
            artwork = args.getString("artwork"),
            startPositionMs = args.getLong("startMs", 0L).coerceAtLeast(0L),
            fadeMs = fade,
            delayMs = args.getLong("delayMs", 0L).coerceAtLeast(0L),
            incomingGain = args.getFloat("gain", 1f),
            curve = if (args.getString("curve") == "linear") AutoMixCurve.LINEAR else AutoMixCurve.EQUAL_POWER,
            token = args.getLong("token", 0L)
        )
    }

    private fun buildDeck(audioFocus: Boolean): AutoMixEngine.Deck {
        val gain = AutoMixGain()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gain, TeeAudioProcessor(SonicMeter))).build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(AudioCache.factory(this)))
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
                audioFocus
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.skipSilenceEnabled = AppSettings.skipSilence.value
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && engineReady() && engine.isSessionPlayer(player)) SonicMeter.reset()
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                if (engineReady() && engine.isSessionPlayer(player)) SonicMeter.reset()
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (engineReady() && engine.isSessionPlayer(player)) AudioEqualizer.bind(audioSessionId)
            }
            override fun onVolumeChanged(volume: Float) {
                if (engineReady()) engine.onVolumeChanged(player, volume)
            }
        })
        return AutoMixEngine.Deck(player, gain)
    }

    private fun engineReady(): Boolean = this::engine.isInitialized

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        SonicMeter.observe(false)
        AudioEqualizer.release()
        if (engineReady()) engine.release()
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CMD_SKIP_SILENCE = "ketvoice.set_skip_silence"
        const val CMD_EQUALIZER = "ketvoice.apply_equalizer"
    }
}
