@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A local file through the same audio chain as the playback service: it must play without an error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioChainPlaybackTest {
    private fun wav(seconds: Int, rate: Int = 44_100): File {
        val frames = seconds * rate
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i -> val v = (Math.sin(i * 2 * Math.PI * 220 / rate) * 12_000).toInt().toShort(); data.putShort(v); data.putShort(v) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 4); put("WAVE".toByteArray()); put("fmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(frames * 4)
        }
        return File.createTempFile("chain", ".wav").apply { writeBytes(header.array() + data.array()); deleteOnExit() }
    }

    @Test fun aLocalFilePlaysThroughKetvoicesAudioChain() = play()

    /** The same with every effect of the chain working: volume leveler and reverb on. */
    @Test fun aLocalFilePlaysWithLevelingAndReverbOn() {
        com.ketvoice.app.data.AppSettings.volumeLeveling.value = true
        ReverbControl.amount = .4f
        try { play() } finally { com.ketvoice.app.data.AppSettings.volumeLeveling.value = false; ReverbControl.amount = 0f }
    }

    private fun play() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val deck = com.ketvoice.app.automix.DeckControl()
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val buffers = androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(PlaybackService.AUDIO_BUFFER_MIN_US).setMaxPcmBufferDurationUs(PlaybackService.AUDIO_BUFFER_MAX_US).build()
                return ProcessingAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                    .setAudioTrackBufferSizeProvider(buffers)
                    .setAudioProcessors(arrayOf(com.ketvoice.app.automix.DeckMixProcessor(deck), LevelingAudioProcessor(), ReverbAudioProcessor {},
                        TeeAudioProcessor(SonicMeter))).build(), deck)
            }
        }
        SonicMeter.observe("test", true)
        val player = ExoPlayer.Builder(context, renderers).build()
        var error: PlaybackException? = null
        var ready = false
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = e }
            override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_READY) ready = true }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav(3))))
        player.prepare(); player.play()
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until && error == null && player.currentPosition < 1_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); Thread.sleep(5)
        }
        val position = player.currentPosition
        player.release(); SonicMeter.observe("test", false)
        assertNull("playback error: ${error?.errorCodeName} ${error?.cause?.stackTraceToString()?.take(2500)}", error)
        assertTrue("player reached READY", ready)
        println("position reached: $position ms")
    }
}
