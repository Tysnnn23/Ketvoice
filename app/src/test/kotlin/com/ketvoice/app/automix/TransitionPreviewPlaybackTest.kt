@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.automix

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.LevelingAudioProcessor
import com.ketvoice.app.player.PlaybackService
import com.ketvoice.app.player.ProcessingAudioSink
import com.ketvoice.app.player.ReverbAudioProcessor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * « Écouter » of the DJ editor (6.9) through the decks' real audio chain under Robolectric: two generated
 * files, the incoming one must reach its deck's output, past its automation, once the transition starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransitionPreviewPlaybackTest {
    private fun wav(seconds: Int, hz: Int, rate: Int = 44_100): File {
        val frames = seconds * rate
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i -> val v = (Math.sin(i * 2 * Math.PI * hz / rate) * 12_000).toInt().toShort(); data.putShort(v); data.putShort(v) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 4); put("WAVE".toByteArray()); put("fmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(frames * 4)
        }
        return File.createTempFile("preview", ".wav").apply { writeBytes(header.array() + data.array()); deleteOnExit() }
    }

    /** After the deck's own processor: how much of its output is loud (its automation let the title through). */
    private class Meter : BaseAudioProcessor() {
        @Volatile var loudFrames = 0L
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat
        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            val out = replaceOutputBuffer(inputBuffer.remaining())
            val shorts = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var loud = 0
            while (shorts.remaining() >= 4) { val l = shorts.short.toInt(); shorts.short; if (kotlin.math.abs(l) > 2_000) loud++ }
            loudFrames += loud
            out.put(inputBuffer); out.flip()
        }
    }

    private class Host : AutomixHost {
        val controls = HashMap<ExoPlayer, DeckControl>()
        val meters = HashMap<ExoPlayer, Meter>()
        val made = ArrayList<ExoPlayer>()
        override val main: ExoPlayer get() = made.first()
        override val exact = false
        override fun control(deck: ExoPlayer) = controls[deck]
        override fun promote(next: ExoPlayer) = next
        val released = StringBuilder()
        /** Each deck's volume and whether it was started, when the preview gave it back. */
        val endVolume = HashMap<Int, Float>()
        val endStarted = HashMap<Int, Boolean>()
        override fun release(deck: ExoPlayer) {
            endVolume[made.indexOf(deck)] = deck.volume; endStarted[made.indexOf(deck)] = deck.playWhenReady
            released.append("released deck ${made.indexOf(deck)}: state=${deck.playbackState} at=${deck.currentPosition} of ${deck.duration} " +
                "playWhenReady=${deck.playWhenReady} volume=${deck.volume} error=${deck.playerError?.errorCodeName}\n")
            runCatching { deck.release() }
        }
        override fun standby(): ExoPlayer {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val control = DeckControl().also { it.main = false }
            val meter = Meter()
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                    val buffers = androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(PlaybackService.AUDIO_BUFFER_MIN_US).setMaxPcmBufferDurationUs(PlaybackService.AUDIO_BUFFER_MAX_US).build()
                    return ProcessingAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                        .setAudioTrackBufferSizeProvider(buffers)
                        .setAudioProcessors(arrayOf(DeckMixProcessor(control), meter, LevelingAudioProcessor(), ReverbAudioProcessor {})).build(), control)
                }
            }
            return ExoPlayer.Builder(context, renderers).build().also { it.volume = 0f; controls[it] = control; meters[it] = meter; made += it }
        }
    }

    private fun track(id: String, file: File) = Track("local:$id", id, "Test", "Test", 20_000L, streamUrl = android.net.Uri.fromFile(file).toString())

    private class Run(val inLoud: Long, val inVolume: Float, val log: String)

    /**
     * Runs the preview of [plan] to its end. [joinOnPlay]: as on a phone (see [Automix]'s `incomingPending`), the
     * incoming deck's processors only join its sound path once it plays; under Robolectric they join at once, so
     * the test holds them out until then. The host's audio track plays far faster than real time: the outgoing clock
     * leaps, and this cannot show when the incoming title is heard, only that it is started and left audible.
     */
    private fun preview(plan: MixPlan, plain: Boolean, joinOnPlay: Boolean = true): Run {
        val host = Host()
        val from = track("a", wav(20, 220)); val to = track("b", wav(20, 660))
        val scope = MainScope()
        var done = false
        val log = StringBuilder()
        scope.launch {
            try { DeckPreview.play(host, from, to, plan, plain) }
            catch (e: Throwable) { log.append("failed: ${e.stackTraceToString().take(1500)}\n") }
            finally { done = true; log.append("ended: ${DeckPreview.state.value}\n") }
        }
        val until = System.currentTimeMillis() + 60_000
        var lastLog = 0L
        var joined = false
        var listening = false
        while (!done && System.currentTimeMillis() < until) {
            if (joinOnPlay && host.made.size >= 2 && !joined) {
                val into = host.made[1]
                val control = host.controls.getValue(into)
                // Joined the moment it is told to play (Media3 calls its listeners at once on this thread).
                if (!listening) { listening = true; into.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (playWhenReady) { joined = true; control.active = true } }
                }) }
                if (!into.playWhenReady) control.active = false
            }
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); Thread.sleep(5)
            if (host.made.size >= 2 && android.os.SystemClock.elapsedRealtime() - lastLog > 500) {
                lastLog = android.os.SystemClock.elapsedRealtime()
                val (out, into) = host.made[0] to host.made[1]
                log.append("out=${out.currentPosition} in=${into.currentPosition} inPlaying=${into.isPlaying} inVolume=${into.volume} " +
                    "inActive=${host.controls[into]?.active} inLoud=${host.meters[into]?.loudFrames}\n")
            }
        }
        scope.cancel()
        host.made.forEach { d -> d.playerError?.let { log.append("deck error: ${it.errorCodeName}\n") } }
        log.append(host.released)
        assertTrue("incoming deck never started:\n$log", host.endStarted[1] == true)
        return Run(host.meters[host.made[1]]?.loudFrames ?: 0L, host.endVolume[1] ?: -1f, log.toString())
    }

    private fun assertHeard(run: Run) {
        println(run.log)
        assertTrue("incoming deck output stayed silent:\n${run.log}", run.inLoud > 22_050)
        assertTrue("incoming deck left at volume ${run.inVolume}:\n${run.log}", run.inVolume > .99f)
    }

    private val crossfade = MixPlan(MixKind.Crossfade, outMs = 9_000, inMs = 0, overlapMs = 3_000, reason = "test")

    @Test fun theIncomingTitleIsHeardInAMix() = assertHeard(preview(crossfade, plain = false))

    @Test fun theIncomingTitleIsHeardSansMix() = assertHeard(preview(crossfade, plain = true))

    @Test fun theIncomingTitleIsHeardWhenItEntersPastItsStart() =
        assertHeard(preview(crossfade.copy(inMs = 6_000), plain = false))

    /** A blend in time, the incoming title a little faster and entering a few bars in. */
    @Test fun theIncomingTitleIsHeardInABlendInTime() =
        assertHeard(preview(MixPlan(MixKind.Beatmatch, outMs = 9_000, inMs = 2_000, overlapMs = 4_000, rate = 1.02f, swapMs = 11_000,
            beatMs = 500f, reason = "test"), plain = false))

    /** The deck's volume: its level only while its processors are out of the path, full once they carry it. */
    @Test fun aDeckIsGivenItsVolumeBackOnceItsProcessorsJoin() {
        val auto = DeckAutomation.incoming(crossfade)
        assertTrue(TransitionPreview.volume(false, auto, 0L) < .01f)
        assertTrue(TransitionPreview.volume(true, auto, 0L) == 1f)
        assertTrue(TransitionPreview.volume(false, auto, 20_000L) > .99f)
    }
}
