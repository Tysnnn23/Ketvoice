package com.ketvoice.app.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import com.ketvoice.app.MainActivity
import com.ketvoice.app.data.AppSettings

/**
 * Service de lecture Media3 : ExoPlayer + MediaSession (notification système,
 * lockscreen contrôles automatiques via DefaultMediaNotificationProvider).
 * Commande custom « Ignorer les silences » (absente de MediaController sur
 * Media3 1.6) : le contrôleur l'envoie, le service l'applique à ExoPlayer.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.skipSilenceEnabled = AppSettings.skipSilence.value

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setCallback(object : MediaSession.Callback {
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    command: SessionCommand,
                    args: Bundle
                ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                    if (command.customAction == CMD_SKIP_SILENCE) {
                        val on = args.getBoolean("on", false)
                        player.skipSilenceEnabled = on
                        AppSettings.skipSilence.value = on
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(
                            androidx.media3.session.SessionResult.RESULT_SUCCESS
                        )
                    )
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CMD_SKIP_SILENCE = "ketvoice.set_skip_silence"
    }
}
