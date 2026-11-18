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
    /** From this position in the title an end of stream is the title's end, after which an echo may ring on (see [MixPlan.tail]). */
    @Volatile var tailFromUs: Long = Long.MAX_VALUE
    /** The instrumental of the part of the title this deck mixes, when its voice was separated (5.10.0). */
    @Volatile var stem: com.ketvoice.app.stems.StemAudio? = null
    /** The separation of the whole title on this deck, when it has one: the voice level works from it (6.0.0). */
    @Volatile var voiceMask: com.ketvoice.app.stems.StemMask? = null
    /** True while the stream on this deck runs through the voice stage: a separation is then taken up live. */
    @Volatile var voiceStageOn: Boolean = false
    /** The deck heard as the player (not a deck preparing a mix): its delay is the one the lyrics follow. */
    @Volatile var main: Boolean = true
    /** « Boucle » (6.9): a bar of this title looped under a blend, played only while an [automation] is set. */
    @Volatile var loop: BarLoop? = null

    fun reset() { automation = null; tap = null; tailFromUs = Long.MAX_VALUE; stem = null; loop = null }

    companion object { const val PLAYER_CLOCK_OFFSET_US = 1_000_000_000_000L }
}

/** The per-deck processor: taps the sound for analysis, then applies the transition's level and filter. */
internal class DeckMixProcessor(private val control: DeckControl) : BaseAudioProcessor() {
    private var dsp: DeckDsp? = null
    /** Plays a [DeckControl.loop]; kept, like the filters, through the flushes of a change of speed. */
    private var loopFx: LoopFx? = null
    /** Turns the voice down when the title was separated whole; chosen at the start of a stream and kept to its end. */
    private var voice: com.ketvoice.app.stems.VoiceStage? = null
    private var cursorUs = C.TIME_UNSET
    /** Where the sound was when the processor was last flushed. */
    private var resumeUs = C.TIME_UNSET

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 ||
            inputAudioFormat.sampleRate <= 0) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        // Media3 flushes every processor, also the ones a format left out of the path.
        val usable = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.sampleRate > 0 &&
            inputAudioFormat.channelCount in 1..8
        // Media3 also flushes its processors each time a deck's speed is nudged, many times during a mix
        // played in time. The filters, the echo and the reverb must live through that: started afresh
        // each time, a filtered deck clicked and an echo lost its tail. They only start afresh after a jump in the title.
        val kept = dsp?.takeIf { usable && it.sampleRate == inputAudioFormat.sampleRate && it.channels == inputAudioFormat.channelCount }
        dsp = kept ?: if (usable) DeckDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        loopFx = if (kept != null) loopFx else if (usable) LoopFx(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        if (cursorUs != C.TIME_UNSET) resumeUs = cursorUs
        cursorUs = C.TIME_UNSET
        control.active = usable
        // The voice stage delays the sound by a fixed amount: it starts or stops only where the sound starts again,
        // and, once running, takes up a separation that arrives while the title plays (6.0.0 Beta 4).
        val stage = usable && inputAudioFormat.channelCount == 2 &&
            runCatching { com.ketvoice.app.stems.VoiceLevel.stageWanted(control.voiceMask != null) }.getOrDefault(false)
        // Media3 flushes on every change of speed too (6.0.0 Beta 4: a title glides back to its tempo after a
        // mix): the stage lives through those, like the filters, and only starts afresh after a jump in the title.
        voice = if (stage) voice?.takeIf { it.rate == inputAudioFormat.sampleRate }
            ?: com.ketvoice.app.stems.VoiceStage(inputAudioFormat.sampleRate, 2) else null
        control.voiceStageOn = stage
        if (control.main) com.ketvoice.app.stems.VoiceLevel.stageDelay(inputAudioFormat.sampleRate, stage)
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
            if (cursorUs == C.TIME_UNSET || kotlin.math.abs(position - cursorUs) > 300_000L) {
                // A real jump (a seek, another title): what the filters and the echo remember belongs elsewhere.
                val from = if (cursorUs != C.TIME_UNSET) cursorUs else resumeUs
                if (from != C.TIME_UNSET && kotlin.math.abs(position - from) > JUMP_US) { dsp?.reset(); voice?.reset(); loopFx?.reset() }
                // A stream that starts from nothing (a first title, after a pause-free gap) starts the stage from silence.
                if (from == C.TIME_UNSET) voice?.reset()
                cursorUs = position
            }
        }
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val startUs = if (cursorUs == C.TIME_UNSET) 0L else cursorUs
        control.tap?.let { tap -> runCatching { tap.pcm(inputBuffer.asReadOnlyBuffer(), startUs, rate, channels) } }
        val output = replaceOutputBuffer(size)
        val engine = dsp
        if (engine == null) output.put(inputBuffer)
        else {
            val automation = if (cursorUs == C.TIME_UNSET) null else control.automation
            // The loop of a « boucle » blend stands in for the title's own sound before levels and filters apply to it.
            val source = if (automation == null) inputBuffer
                else runCatching { loopFx?.process(inputBuffer, startUs, control.loop) }.getOrNull() ?: inputBuffer
            try {
                engine.process(source, output, startUs, automation, control.stem)
            } catch (_: RuntimeException) {
                // A transition must never cost the playback: drop it and pass the rest of the sound through.
                control.automation = null
                if (source.remaining() <= output.remaining()) output.put(source) else source.position(source.limit())
            }
            if (inputBuffer.hasRemaining()) inputBuffer.position(inputBuffer.limit())
        }
        val frames = size / (2 * channels)
        val stage = voice
        if (stage != null) {
            // Once running, the stage processes every buffer (its delay must stay the same); the separation is read live.
            val mask = if (cursorUs != C.TIME_UNSET) control.voiceMask else null
            try { stage.process(output, 0, frames, startUs, mask, com.ketvoice.app.stems.VoiceLevel.level) }
            catch (_: RuntimeException) { control.voiceMask = null }
        }
        if (cursorUs != C.TIME_UNSET) cursorUs += frames * 1_000_000L / rate
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
        // Media3 also ends the stream of its processors to apply a change of speed: that is no end of the title.
        if (at < control.tailFromUs) return
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

    override fun onReset() { dsp = null; resumeUs = C.TIME_UNSET; control.active = false }

    private companion object { const val JUMP_US = 1_500_000L }
}
