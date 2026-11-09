@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.content.Context
import android.media.*
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import com.ketvoice.app.data.AppSettings
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.*

/** Platform-contract fixtures, NOT USB hardware or end-to-end bit comparisons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [BitPerfectSinkHostTest.MixerManager::class, BitPerfectSinkHostTest.ExactTrack::class])
class BitPerfectSinkHostTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(48000)
        .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
    private val calls = mutableListOf<String>()
    private lateinit var sink: BitPerfectAudioSink
    private lateinit var target: AudioDeviceInfo

    @Before fun setup() {
        AppSettings.bitPerfect.value = true
        BitPerfectState.reset()
        calls.clear()
        MixerManager.current = null
        MixerManager.accept = true
        target = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()
        shadowOf(context.getSystemService(AudioManager::class.java)).setAudioDevicesForAttributes(attributes, ImmutableList.of(target))
        ShadowAudioTrack.setRoutedDevice(target)
        val standard = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "handleBuffer" -> { (args!![0] as ByteBuffer).position((args[0] as ByteBuffer).limit()); true }
                "getCurrentPositionUs" -> 0L
                "hasPendingData" -> false
                "isEnded", "supportsFormat" -> true
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                else -> null
            }
        } as AudioSink
        sink = BitPerfectAudioSink(context, standard)
    }

    @After fun cleanup() {
        sink.release()
        AppSettings.bitPerfect.value = false
        BitPerfectState.reset()
        MixerManager.current = null
    }

    private fun buffer() = ByteBuffer.allocateDirect(4096)
    private fun phase() = BitPerfectState.status.value.phase

    @Test fun preferenceAloneDoesNotActivateAndNativePathBypassesStandardWrites() {
        sink.configure(pcm, 0, null)
        assertEquals(ExactAudioPhase.Pending, phase())
        sink.play()
        assertTrue(sink.handleBuffer(buffer(), 0, 1))
        assertEquals(ExactAudioPhase.Active, phase())
        assertFalse(calls.contains("handleBuffer"))
        sink.pause()
        assertEquals(ExactAudioPhase.Pending, phase())
        sink.play()
        sink.flush()
        sink.handleBuffer(buffer(), 1_000_000, 1)
        assertEquals(ExactAudioPhase.Active, phase())
        assertTrue(sink.getCurrentPositionUs(false) >= 1_000_000)
    }

    @Test fun rejectedContractFallsBackAndNeverClaimsActive() {
        MixerManager.accept = false
        sink.configure(pcm, 0, null)
        sink.play()
        assertTrue(sink.handleBuffer(buffer(), 0, 1))
        assertEquals(ExactAudioPhase.Unavailable, phase())
        assertTrue(calls.contains("configure"))
        assertTrue(calls.contains("handleBuffer"))
    }

    @Test fun incompatiblePcmAndRemappingUseStandardSink() {
        sink.configure(pcm.buildUpon().setSampleRate(96000).build(), 0, null)
        assertEquals(ExactAudioPhase.Unavailable, phase())
        sink.configure(pcm, 0, intArrayOf(1, 0))
        assertEquals(ExactAudioPhase.Unavailable, phase())
        assertNull(MixerManager.current)
    }

    @Test fun nonUnityGainDropsExactContractAndKeepsPlaybackAvailable() {
        sink.configure(pcm, 0, null)
        sink.play()
        sink.handleBuffer(buffer(), 0, 1)
        assertEquals(ExactAudioPhase.Active, phase())
        sink.setVolume(.5f)
        assertEquals(ExactAudioPhase.Unavailable, phase())
        assertNull(MixerManager.current)
        assertTrue(sink.handleBuffer(buffer(), 100_000, 1))
        assertTrue(calls.contains("handleBuffer"))
    }

    @Test fun anAlreadyDuckedPlayerCannotOpenAnExactTrackAtFullVolume() {
        sink.setVolume(.2f)
        sink.configure(pcm, 0, null)
        assertEquals(ExactAudioPhase.Unavailable, phase())
        assertNull(MixerManager.current)
        sink.play()
        assertTrue(sink.handleBuffer(buffer(), 0, 1))
        assertTrue(calls.contains("handleBuffer"))
    }

    @Test fun losingRouteImmediatelyRevokesGreenStatusOnNextProof() {
        sink.configure(pcm, 0, null)
        sink.play()
        sink.handleBuffer(buffer(), 0, 1)
        assertEquals(ExactAudioPhase.Active, phase())
        ShadowAudioTrack.setRoutedDevice(null)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600))
        sink.getCurrentPositionUs(false)
        assertNotEquals(ExactAudioPhase.Active, phase())
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
        sink.getCurrentPositionUs(false)
        assertEquals(ExactAudioPhase.Unavailable, phase())
    }

    @Test fun bluetoothAndSpeakerRoutesAreNeverStolenForAConnectedUsbDevice() {
        val manager = shadowOf(context.getSystemService(AudioManager::class.java))
        for (type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)) {
            manager.setAudioDevicesForAttributes(attributes,
                ImmutableList.of(AudioDeviceInfoBuilder.newBuilder().setType(type).build()))
            sink.configure(pcm, 0, null)
            assertEquals(ExactAudioPhase.Unavailable, phase())
            assertNull(MixerManager.current)
        }
    }

    @Test fun newTrackReconfigurationKeepsTheStartedRendererPlaying() {
        sink.configure(pcm, 0, null)
        sink.play()
        sink.handleBuffer(buffer(), 0, 1)
        sink.configure(pcm, 0, null)
        sink.handleBuffer(buffer(), 0, 1)
        assertEquals(ExactAudioPhase.Active, phase())
    }

    @Test fun bitDepthMismatchAndLostMixerOwnershipNeverRemainActive() {
        sink.configure(pcm.buildUpon().setPcmEncoding(C.ENCODING_PCM_24BIT).build(), 0, null)
        assertEquals(ExactAudioPhase.Unavailable, phase())
        sink.configure(pcm, 0, null)
        sink.play()
        sink.handleBuffer(buffer(), 0, 1)
        assertEquals(ExactAudioPhase.Active, phase())
        MixerManager.current = null
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
        sink.getCurrentPositionUs(false)
        assertEquals(ExactAudioPhase.Unavailable, phase())
    }

    @Implements(AudioManager::class)
    class MixerManager : ShadowAudioManager() {
        companion object {
            var current: AudioMixerAttributes? = null
            var accept = true
        }
        @Implementation(minSdk = 34)
        fun getSupportedMixerAttributes(device: AudioDeviceInfo): List<AudioMixerAttributes> = listOf(
            AudioMixerAttributes.Builder(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build())
        @Implementation(minSdk = 34)
        fun setPreferredMixerAttributes(attrs: AudioAttributes, device: AudioDeviceInfo, mixer: AudioMixerAttributes): Boolean {
            if (accept) current = mixer
            return accept
        }
        @Implementation(minSdk = 34)
        fun getPreferredMixerAttributes(attrs: AudioAttributes, device: AudioDeviceInfo): AudioMixerAttributes? = current
        @Implementation(minSdk = 34)
        fun clearPreferredMixerAttributes(attrs: AudioAttributes, device: AudioDeviceInfo): Boolean {
            current = null
            return true
        }
    }

    @Implements(AudioTrack::class)
    class ExactTrack : ShadowAudioTrack() {
        @Implementation
        fun setPreferredDevice(device: AudioDeviceInfo?): Boolean = true
    }
}
