@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class AutoMixGain : BaseAudioProcessor() {
    @Volatile var gain: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val output = replaceOutputBuffer(remaining)
        val level = gain
        if (level >= 0.999f) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val scale = level.coerceIn(0f, 1f)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                val source = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val target = output.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (source.hasRemaining()) target.put((source.get() * scale).toInt().coerceIn(-32768, 32767).toShort())
                inputBuffer.position(inputBuffer.limit())
                output.position(remaining).flip()
            }
            C.ENCODING_PCM_FLOAT -> {
                val source = inputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                val target = output.order(ByteOrder.nativeOrder()).asFloatBuffer()
                while (source.hasRemaining()) {
                    val sample = source.get()
                    target.put(if (sample.isFinite()) sample.coerceIn(-1f, 1f) * scale else 0f)
                }
                inputBuffer.position(inputBuffer.limit())
                output.position(remaining).flip()
            }
            else -> {
                output.put(inputBuffer)
                output.flip()
            }
        }
    }
}

data class MixRequest(
    val trackId: String,
    val url: String,
    val cacheKey: String?,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String?,
    val startPositionMs: Long,
    val fadeMs: Long,
    val delayMs: Long,
    val incomingGain: Float,
    val curve: AutoMixCurve,
    val token: Long
)

@UnstableApi
class AutoMixEngine(
    private val session: MediaSession,
    first: Deck,
    private val createDeck: () -> Deck,
    private val onAudioSession: (Deck, Int) -> Unit,
    private val onFinished: (Boolean, Long) -> Unit
) {
    class Deck(val player: ExoPlayer, val gain: AutoMixGain)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var primary: Deck = first
    private var spare: Deck? = null
    private var job: Job? = null
    private var userVolume = 1f
    private var preparedId: String? = null
    private var preparedUrl: String? = null
    private var preparedStartMs = 0L

    fun isSessionPlayer(player: ExoPlayer): Boolean = player === primary.player

    fun attach(deck: Deck) {
        deck.player.volume = userVolume
    }

    fun onVolumeChanged(player: ExoPlayer, volume: Float) {
        if (!isSessionPlayer(player)) return
        userVolume = volume
        spare?.let { if (it.player.volume != volume) it.player.volume = volume }
    }

    fun applySkipSilence(on: Boolean) {
        primary.player.skipSilenceEnabled = on
        spare?.player?.skipSilenceEnabled = on
    }

    fun prepare(request: MixRequest): Boolean {
        if (job != null) return false
        val incoming = ensureSpare()
        if (preparedId == request.trackId && preparedUrl == request.url &&
            incoming.player.playerError == null && incoming.player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
            if (preparedStartMs != request.startPositionMs) {
                incoming.player.seekTo(request.startPositionMs)
                preparedStartMs = request.startPositionMs
            }
            return true
        }
        return runCatching {
            incoming.gain.gain = 0f
            incoming.player.playWhenReady = false
            incoming.player.setMediaItem(item(request), request.startPositionMs)
            incoming.player.prepare()
            preparedId = request.trackId
            preparedUrl = request.url
            preparedStartMs = request.startPositionMs
            true
        }.getOrElse { abort(); false }
    }

    private fun item(request: MixRequest): MediaItem = MediaItem.Builder()
        .setUri(request.url)
        .setMediaId(request.trackId)
        .apply { if (!request.cacheKey.isNullOrBlank()) setCustomCacheKey(request.cacheKey) }
        .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).setArtist(request.artist)
            .setAlbumTitle(request.album).setArtworkUri(request.artwork?.let { Uri.parse(it) }).build())
        .build()

    fun start(request: MixRequest): Boolean {
        if (job != null) return false
        val outgoing = primary
        val incoming = ensureSpare()
        if (!prepare(request)) return false
        incoming.player.volume = userVolume
        incoming.player.skipSilenceEnabled = AppSettings.skipSilence.value
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        job = scope.launch {
            var success = false
            try {
                val ready = awaitReady(incoming, request.delayMs + 2_000L)
                if (!ready) {
                    abort()
                    return@launch
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - requestedAt
                val wait = request.delayMs - elapsed
                if (wait > 60L) delay(wait)
                incoming.player.playWhenReady = true
                crossfade(outgoing, incoming, request)
                swap(outgoing, incoming)
                success = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                abort()
                throw cancelled
            } catch (_: Exception) {
                abort()
            } finally {
                job = null
                if (!success) {
                    primary.player.volume = userVolume
                    primary.gain.gain = 1f
                }
                onFinished(success, request.token)
            }
        }
        return true
    }

    fun cancel() {
        val running = job
        job = null
        running?.cancel()
        abort()
        primary.gain.gain = 1f
        primary.player.volume = userVolume
    }

    fun disable() {
        cancel()
        spare?.player?.release()
        spare = null
    }

    private suspend fun awaitReady(deck: Deck, timeout: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val player = deck.player
            if (player.playerError != null) return false
            if (player.playbackState == androidx.media3.common.Player.STATE_READY) return true
            if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) return false
            delay(16)
        }
        return deck.player.playbackState == androidx.media3.common.Player.STATE_READY
    }

    private suspend fun crossfade(outgoing: Deck, incoming: Deck, request: MixRequest) {
        val started = android.os.SystemClock.elapsedRealtime()
        val fade = request.fadeMs.coerceAtLeast(1L)
        while (true) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val progress = (elapsed.toFloat() / fade).coerceIn(0f, 1f)
            val (out, into) = AutoMixPlanner.fadeGain(progress, request.curve)
            val loudness = AutoMixPlanner.loudnessGain(progress, request.incomingGain)
            val headroom = maxOf(1f, out + into * loudness)
            outgoing.gain.gain = out / headroom
            incoming.gain.gain = into * loudness / headroom
            if (progress >= 1f) break
            delay(20)
        }
        outgoing.gain.gain = 0f
        incoming.gain.gain = 1f
    }

    private fun swap(outgoing: Deck, incoming: Deck) {
        val previousVolume = userVolume
        session.setPlayer(incoming.player)
        incoming.player.setAudioAttributes(incoming.player.audioAttributes, true)
        incoming.player.volume = previousVolume
        primary = incoming
        spare = outgoing
        outgoing.player.pause()
        outgoing.player.setAudioAttributes(outgoing.player.audioAttributes, false)
        outgoing.player.clearMediaItems()
        preparedId = null
        preparedUrl = null
        preparedStartMs = 0L
        spare?.gain?.gain = 1f
        val id = incoming.player.audioSessionId
        if (id > 0) AudioEqualizer.bind(id)
        onAudioSession(incoming, id)
    }

    private fun abort() {
        val incoming = spare ?: return
        runCatching {
            incoming.player.pause()
            incoming.player.clearMediaItems()
        }
        incoming.gain.gain = 1f
        preparedId = null
        preparedUrl = null
        preparedStartMs = 0L
    }

    private fun ensureSpare(): Deck {
        spare?.let { deck ->
            deck.gain.gain = 0f
            return deck
        }
        val deck = createDeck()
        deck.player.volume = userVolume
        spare = deck
        return deck
    }

    fun release() {
        disable()
    }
}
