@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Receives the decoded sound of a deck, before any level or filter is applied to it. */
internal interface PcmTap {
    /** [buffer] is interleaved 16-bit PCM whose first frame is at [positionUs] in the title. Must not consume it. */
    fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int)
}

/**
 * What a deck's audio sink, its processor and the Automix engine share. The sink and the
 * processor run on the deck's playback thread, the engine on the main thread.
 */
internal class DeckControl {
    @Volatile var automation: DeckAutomation? = null
    @Volatile var tap: PcmTap? = null
    /** Player time of the buffer the processor receives next, announced by the sink. */
    @Volatile var pendingTimeUs: Long = C.TIME_UNSET
    /** Player time being heard, and when it was read (System.nanoTime). */
    @Volatile var heardUs: Long = C.TIME_UNSET
    @Volatile var heardAtNanos: Long = 0L
    /** True while 16-bit PCM flows through the processor: levels and filters can be applied. */
    @Volatile var active: Boolean = false
    /**
     * Difference between the player's internal clock and positions in the title. Media3
     * starts its internal clock at this value for the first item of a queue, and every
     * deck here holds a single item.
     */
    @Volatile var offsetUs: Long = PLAYER_CLOCK_OFFSET_US

    fun reset() { automation = null; tap = null }

    companion object { const val PLAYER_CLOCK_OFFSET_US = 1_000_000_000_000L }
}

/** The per-deck processor: taps the sound for analysis, then applies the transition's level and filter. */
internal class DeckMixProcessor(private val control: DeckControl) : BaseAudioProcessor() {
    private var dsp: DeckDsp? = null
    private var cursorUs = C.TIME_UNSET

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 ||
            inputAudioFormat.sampleRate <= 0) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        // Media3 flushes every processor, also the ones a format left out of the path.
        val usable = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.sampleRate > 0 &&
            inputAudioFormat.channelCount in 1..8
        dsp = if (usable) DeckDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        cursorUs = C.TIME_UNSET
        control.active = usable
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        // The first buffer after a flush says where the sound is in the title; from there the
        // frames are counted, which is exact. Later announcements may be one buffer ahead of
        // the sound that reaches this stage, so they only count when they reveal a real jump.
        val announced = control.pendingTimeUs
        if (announced != C.TIME_UNSET) {
            control.pendingTimeUs = C.TIME_UNSET
            val position = announced - control.offsetUs
            if (cursorUs == C.TIME_UNSET || kotlin.math.abs(position - cursorUs) > 300_000L) cursorUs = position
        }
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val startUs = if (cursorUs == C.TIME_UNSET) 0L else cursorUs
        control.tap?.let { tap -> runCatching { tap.pcm(inputBuffer.asReadOnlyBuffer(), startUs, rate, channels) } }
        val output = replaceOutputBuffer(size)
        val engine = dsp
        if (engine == null) output.put(inputBuffer)
        else try {
            engine.process(inputBuffer, output, startUs, if (cursorUs == C.TIME_UNSET) null else control.automation)
        } catch (_: RuntimeException) {
            // A transition must never cost the playback: drop it and pass the rest of the sound through.
            control.automation = null
            if (inputBuffer.remaining() <= output.remaining()) output.put(inputBuffer) else inputBuffer.position(inputBuffer.limit())
        }
        if (cursorUs != C.TIME_UNSET) cursorUs += (size / (2 * channels)) * 1_000_000L / rate
        output.flip()
    }

    /**
     * The title ends while its echo or reverb still rings (see [MixPlan.tail]): what rings is
     * played out on silence, to the end of the automation, so that a last note is never cut short.
     */
    override fun onQueueEndOfStream() {
        val engine = dsp ?: return
        val automation = control.automation ?: return
        if (!automation.usesEcho && !automation.usesReverb) return
        val at = cursorUs.takeIf { it != C.TIME_UNSET } ?: return
        val tailUs = (automation.endUs - at).coerceIn(0L, TransitionPlanner.MAX_TAIL_MS * 1000L)
        if (tailUs < 40_000L) return
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val silence = ByteBuffer.allocate((tailUs * rate / 1_000_000L).toInt() * 2 * channels)
        val output = replaceOutputBuffer(silence.remaining())
        try { engine.process(silence, output, at, automation) } catch (_: RuntimeException) { output.clear(); output.limit(0); return }
        cursorUs = at + tailUs
        output.flip()
    }

    override fun onReset() { dsp = null; control.active = false }
}
