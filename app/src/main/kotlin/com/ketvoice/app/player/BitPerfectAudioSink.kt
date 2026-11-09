@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.ketvoice.app.data.AppSettings
import java.nio.ByteBuffer

/**
 * Separate native PCM path for Android's public BIT_PERFECT mixer contract.
 *
 * DefaultAudioSink also converts bit depth, ramps volume and may resample, so it
 * cannot provide this guarantee. This sink writes the decoded PCM straight to an
 * AudioTrack built from the mixer attributes the device advertises as bit-perfect.
 *
 * It never claims success from a capability or a preference alone: [BitPerfectState]
 * only reports Active after the mixer ownership, the routed device and the real
 * AudioTrack format have all been re-read while bytes are playing. When any of
 * those cannot be confirmed, playback falls back to the normal sink instead of
 * looping on recoverable errors.
 *
 * All methods execute on Media3's playback thread.
 */
@RequiresApi(34)
internal class BitPerfectAudioSink(
    context: Context,
    private val standard: AudioSink
) : ForwardingAudioSink(standard) {

    private val manager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val attributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var native: AudioTrack? = null
    private var device: AudioDeviceInfo? = null
    private var preferred: AudioMixerAttributes? = null

    private var input: Format? = null
    private var bufferSize = 0
    private var outputChannels: IntArray? = null
    private var configured = false

    private var sessionId = 0
    private var playing = false
    private var ended = false
    private var unity = true
    private var lastVolume = 1f

    private var source: PcmSpec? = null
    private var baseUs = C.TIME_UNSET
    private var writtenBytes = 0L
    private var lastHead = 0L
    private var wraps = 0L
    private var firstWriteAt = 0L
    private var nextProofAt = 0L

    override fun setListener(listener: AudioSink.Listener) {
        standard.setListener(listener)
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        sessionId = audioSessionId
        standard.setAudioSessionId(audioSessionId)
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.pcmEncoding in encodings &&
            format.sampleRate > 0 &&
            format.channelCount in 1..8
        ) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else standard.getFormatSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configured = true
        input = inputFormat
        bufferSize = specifiedBufferSize
        this.outputChannels = outputChannels
        releaseNative()
        standard.flush()

        val pcm = PcmSpec(inputFormat.sampleRate, inputFormat.pcmEncoding, inputFormat.channelCount)
        source = pcm.takeIf { it.isValid }

        if (!AppSettings.bitPerfect.value) {
            BitPerfectState.reset()
            standard.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }

        BitPerfectState.report(ExactAudioStatus(ExactAudioPhase.Pending, "Vérification du chemin audio…", source))
        val refused = runCatching { openExact(inputFormat) }.getOrElse { "Chemin exact refusé par Android" }
        if (refused != null || native == null) {
            releaseNative()
            standard.configure(inputFormat, specifiedBufferSize, outputChannels)
            BitPerfectState.report(
                ExactAudioStatus(ExactAudioPhase.Unavailable, refused ?: "Format exact indisponible", source)
            )
        }
    }

    /** Returns null when the exact route is open, otherwise the honest reason. */
    private fun openExact(format: Format): String? {
        val am = manager ?: return "Service audio indisponible"
        if (!AppSettings.bitPerfect.value) return "Sortie standard"
        if (!unity) return "Volume numérique incompatible avec le chemin exact"
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return "Le décodeur ne fournit pas de PCM"
        if (format.pcmEncoding !in encodings) return "Profondeur PCM non prise en charge"
        if (format.sampleRate <= 0 || format.channelCount !in 1..8) return "Format PCM invalide"
        // Gapless trimming or channel remapping would modify the samples.
        if (outputChannels != null) return "Remappage audio nécessaire"
        if (format.encoderDelay > 0 || format.encoderPadding > 0) return "Découpage gapless nécessaire"

        // Follow the user's real system route. Never steal playback from Bluetooth
        // just because an unrelated USB output happens to be connected.
        val routes = am.getAudioDevicesForAttributes(attributes)
        if (routes.size != 1) return "Sortie unique vérifiable requise"
        val target = routes.single()
        val allowed = target.type in intArrayOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
        if (!allowed) return "Cette sortie ne propose pas de chemin Bit-Perfect vérifiable"

        val channelMask = Util.getAudioTrackChannelConfig(format.channelCount)
        if (channelMask == AudioFormat.CHANNEL_INVALID) return "Configuration de canaux inconnue"
        val match = am.getSupportedMixerAttributes(target).firstOrNull {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                it.format.sampleRate == format.sampleRate &&
                it.format.encoding == format.pcmEncoding &&
                it.format.channelMask == channelMask
        } ?: return "Le périphérique ne propose pas ce format en Bit-Perfect"

        if (!am.setPreferredMixerAttributes(attributes, target, match)) {
            return "Android a refusé le mixeur Bit-Perfect"
        }
        preferred = match
        device = target // Also retained on early refusal, so cleanup clears the preference.
        if (am.getPreferredMixerAttributes(attributes, target) != match) {
            return "Préférence du mixeur non confirmée"
        }

        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, format.pcmEncoding)
        if (minBuffer <= 0) return "Format AudioTrack indisponible"
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(match.format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 4)
        if (sessionId > 0) builder.setSessionId(sessionId)
        val track = runCatching { builder.build() }.getOrElse { return "Création AudioTrack refusée" }
        native = track // Retain immediately so even a throwing setup is released.
        if (track.state != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(target)) {
            return "Routage audio exact indisponible"
        }

        device = target
        if (track.setVolume(1f) != AudioTrack.SUCCESS) return "Gain unitaire AudioTrack non confirmé"
        writtenBytes = 0
        lastHead = 0
        wraps = 0
        baseUs = C.TIME_UNSET
        ended = false
        firstWriteAt = 0
        nextProofAt = 0
        if (playing) runCatching { track.play() }
        return null
    }

    private fun fallbackToStandard(reason: String) {
        val format = input
        releaseNative()
        standard.setVolume(lastVolume)
        if (format != null && configured) {
            runCatching { standard.configure(format, bufferSize, outputChannels) }
            if (playing) runCatching { standard.play() }
        }
        BitPerfectState.report(ExactAudioStatus(ExactAudioPhase.Unavailable, reason, source))
    }

    private fun playedFrames(): Long {
        val track = native ?: return 0
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (head < lastHead) wraps++
        lastHead = head
        return head + (wraps shl 32)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val track = native ?: return standard.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (baseUs == C.TIME_UNSET) {
            val spec = source!!
            val frames = writtenBytes / Util.getPcmFrameSize(spec.encoding, spec.channels)
            baseUs = presentationTimeUs - frames * 1_000_000L / spec.sampleRate
        }
        if (firstWriteAt == 0L) firstWriteAt = SystemClock.elapsedRealtime()

        val count = runCatching {
            track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        }.getOrElse {
            fallbackToStandard("Écriture audio interrompue")
            return standard.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (count < 0) {
            fallbackToStandard("Sortie audio déconnectée")
            return standard.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (count == 0) { verifyExactPath(); return false }
        writtenBytes += count
        verifyExactPath()
        return !buffer.hasRemaining()
    }

    /** Re-read the real route instead of trusting what was requested once. */
    private fun verifyExactPath() {
        val now = SystemClock.elapsedRealtime()
        if (now < nextProofAt) return
        nextProofAt = now + 500
        val track = native ?: return
        val target = device ?: return
        val am = manager ?: return
        val src = source ?: return

        val actual = PcmSpec(track.format.sampleRate, track.format.encoding, track.format.channelCount)
        val owned = runCatching { am.getPreferredMixerAttributes(attributes, target) == preferred }.getOrDefault(false)
        val routed = runCatching { track.routedDevice?.id == target.id }.getOrDefault(false)
        val playingNow = playing && track.playState == AudioTrack.PLAYSTATE_PLAYING && (!ended || hasPendingData())
        val name = target.productName?.toString().orEmpty()

        val active = exactAudioProof(
            requested = AppSettings.bitPerfect.value,
            mixerAccepted = preferred != null,
            mixerStillOwned = owned,
            routedDeviceMatches = routed,
            playing = playingNow,
            writtenBytes = writtenBytes,
            source = src,
            actual = actual,
            unityGain = unity,
            dspBypassed = true
        )
        if (active) {
            BitPerfectState.report(
                ExactAudioStatus(ExactAudioPhase.Active, "Bit-Perfect actif · confirmé par Android", src, actual, name)
            )
            return
        }

        BitPerfectState.report(
            ExactAudioStatus(
                ExactAudioPhase.Pending,
                if (playingNow) "Chemin exact en attente de confirmation" else "Bit-Perfect en pause",
                src,
                actual.takeIf { routed },
                name
            )
        )
        // Stay honest and audible: if the contract cannot be confirmed while
        // playing, switch to the normal sink instead of stalling or looping.
        if (!playingNow) return
        if (now - firstWriteAt < 2_500) return
        when {
            !owned -> fallbackToStandard("Le mixeur Bit-Perfect a été repris par Android")
            !routed -> fallbackToStandard("Routage audio exact non confirmé")
            actual != src -> fallbackToStandard("Format négocié différent du PCM décodé")
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (native != null) verifyExactPath()
        val track = native ?: return standard.getCurrentPositionUs(sourceEnded)
        val spec = source ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (baseUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val frameSize = Util.getPcmFrameSize(spec.encoding, spec.channels).coerceAtLeast(1)
        val writtenFrames = writtenBytes / frameSize
        val played = minOf(playedFrames(), writtenFrames)
        return baseUs + played * 1_000_000L / spec.sampleRate
    }

    override fun play() {
        playing = true
        val track = native
        if (track == null) standard.play()
        else runCatching { track.play() }.onFailure { fallbackToStandard("Démarrage audio exact refusé") }
    }

    override fun pause() {
        playing = false
        native?.let { runCatching { it.pause() } } ?: standard.pause()
        if (native != null) {
            BitPerfectState.report(
                ExactAudioStatus(
                    ExactAudioPhase.Pending,
                    "Bit-Perfect en pause",
                    source,
                    device = device?.productName?.toString().orEmpty()
                )
            )
        }
    }

    override fun playToEndOfStream() {
        if (native == null) standard.playToEndOfStream() else ended = true
    }

    override fun hasPendingData(): Boolean {
        val track = native ?: return standard.hasPendingData()
        val spec = source ?: return false
        val frameSize = Util.getPcmFrameSize(spec.encoding, spec.channels).coerceAtLeast(1)
        return writtenBytes / frameSize > playedFrames()
    }

    override fun isEnded(): Boolean {
        if (native == null) return standard.isEnded()
        val drained = ended && !hasPendingData()
        if (drained && BitPerfectState.status.value.phase == ExactAudioPhase.Active) {
            BitPerfectState.report(ExactAudioStatus(ExactAudioPhase.Pending, "En attente de lecture", source))
        }
        return drained
    }

    override fun handleDiscontinuity() {
        if (native == null) standard.handleDiscontinuity() else baseUs = C.TIME_UNSET
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (native != null && playbackParameters != PlaybackParameters.DEFAULT) {
            fallbackToStandard("Vitesse de lecture incompatible avec le chemin exact")
        }
        if (native == null) standard.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        if (native == null) standard.playbackParameters else PlaybackParameters.DEFAULT

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        if (native == null) standard.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean =
        if (native == null) standard.skipSilenceEnabled else false

    override fun setVolume(volume: Float) {
        lastVolume = volume
        unity = volume == 1f
        if (native == null) {
            standard.setVolume(volume)
            return
        }
        // Any app-level attenuation would alter the samples. Fall back to the
        // normal sink so playback continues at the requested volume, unclaimed.
        if (!unity) fallbackToStandard("Volume numérique incompatible avec le chemin exact")
    }

    override fun flush() {
        native?.let { runCatching { it.pause(); it.flush(); if (playing) it.play() } }
        writtenBytes = 0
        lastHead = 0
        wraps = 0
        baseUs = C.TIME_UNSET
        ended = false
        firstWriteAt = 0
        nextProofAt = 0
        standard.flush()
        if (native != null) {
            BitPerfectState.report(ExactAudioStatus(ExactAudioPhase.Pending, "Vérification du chemin audio…", source))
        }
    }

    private fun releaseNative() {
        native?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        native = null
        val target = device
        val exact = preferred
        if (target != null && exact != null) {
            runCatching {
                if (manager?.getPreferredMixerAttributes(attributes, target) == exact) {
                    manager.clearPreferredMixerAttributes(attributes, target)
                }
            }
        }
        preferred = null
        device = null
        writtenBytes = 0
        lastHead = 0
        wraps = 0
        baseUs = C.TIME_UNSET
        ended = false
        firstWriteAt = 0
        nextProofAt = 0
    }

    override fun reset() {
        releaseNative()
        configured = false
        if (AppSettings.bitPerfect.value) BitPerfectState.report(
            ExactAudioStatus(ExactAudioPhase.Pending, "En attente de lecture"))
        standard.reset()
    }

    override fun release() {
        releaseNative()
        if (BitPerfectState.status.value.phase == ExactAudioPhase.Active) {
            BitPerfectState.report(ExactAudioStatus(ExactAudioPhase.Pending, "En attente de lecture"))
        }
        standard.release()
    }

    companion object {
        private val encodings = setOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT
        )
    }
}
