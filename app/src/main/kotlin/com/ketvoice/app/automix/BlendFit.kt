package com.ketvoice.app.automix

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * How well two titles go together, and what a blend in time may therefore do. No model can be trained
 * here: these are the rules Apple Music's AutoMix, Mixxx's Auto DJ and DJs are seen to follow, written out.
 *
 *  - The better two titles fit (tempo, key, level), the longer they may be heard together; a pair that
 *    only just fits gets a short blend ([counts]).
 *  - Two voices never sing over each other ([voiceLeadMs]).
 *  - First beats of bars meet, and the incoming title's first bar opens a phrase of the outgoing one
 *    ([onPhrase]); the bass changes hands on a first beat ([swapBeat]).
 *  - Neither title jumps out: the louder of the two is led to the level of the other ([outGain],
 *    beside [MixPlan.gain]).
 */
internal object BlendFit {
    /**
     * Beyond this the incoming title is heard clearly off its own tempo, and glides back for many
     * seconds afterwards (see [TempoReturn]): such a blend is kept to 8 bars.
     */
    const val LONG_BLEND_STRETCH = .04f
    /** Two titles this far apart in level are not left side by side for 16 bars. */
    const val LONG_BLEND_LEVEL_DB = 6f
    /** The outgoing title is never led down further than this to meet a quieter one (−4.4 dB). */
    const val OUT_FLOOR = .6f
    /** Under this a level was not measured. */
    private const val UNMEASURED_DB = -80f

    /** A build promises a drop: an incoming title more than this under the outgoing one would fall flat after it. */
    const val DROP_LEVEL_DB = 4f

    /** True unless the incoming title is known to be too quiet to follow a build (see [MixKind.Rise]). */
    fun carriesADrop(a: SegmentAnalysis, b: SegmentAnalysis): Boolean =
        a.loudnessDb <= UNMEASURED_DB || b.loudnessDb <= UNMEASURED_DB || b.loudnessDb >= a.loudnessDb - DROP_LEVEL_DB

    /** How far apart the loud parts of the two stretches are, in dB; 0 when one of them was not measured. */
    fun levelGapDb(a: SegmentAnalysis, b: SegmentAnalysis): Float =
        if (a.loudnessDb <= UNMEASURED_DB || b.loudnessDb <= UNMEASURED_DB) 0f else abs(a.loudnessDb - b.loudnessDb)

    /**
     * The lengths, in beats, a blend in time may take for this pair, longest first. [stretch] is how far
     * from its own tempo the incoming title is played (0.03 = 3 %).
     *
     * Keys that clash never rub for long, and a title over its own start neither (the same instruments
     * slightly apart in time colour each other): 4 bars at most. A title stretched by more than
     * [LONG_BLEND_STRETCH], or much louder or quieter than the other, gets 8 bars at most.
     */
    fun counts(settings: MixSettings, stretch: Float, clash: Boolean, loop: Boolean, levelGapDb: Float): IntArray {
        val longest = when {
            clash || loop -> 16
            stretch > LONG_BLEND_STRETCH + 1e-4f || levelGapDb > LONG_BLEND_LEVEL_DB -> 32
            else -> 64
        }
        return settings.beatCounts.filter { it <= longest }.toIntArray()
    }

    /**
     * Level the outgoing title is led down to where the two meet, when the incoming one is the quieter
     * of the two: [MixPlan.gain] can only lower the incoming title, so without this a quiet title would
     * be buried under a loud one for the whole blend.
     */
    fun outGain(a: SegmentAnalysis, b: SegmentAnalysis): Float =
        if (a.loudnessDb <= UNMEASURED_DB || b.loudnessDb <= UNMEASURED_DB) 1f
        else 10f.pow((b.loudnessDb - a.loudnessDb) / 20f).coerceIn(OUT_FLOOR, 1f)

    /**
     * How long before the end of the outgoing title's last sung words a blend may start. The outgoing
     * voice is softened over that stretch, which is fine under an incoming title that does not sing yet;
     * [voiceAfterMs] is how long after its entry the incoming voice starts (null when unknown), and the
     * blend never starts so early that both would sing together.
     */
    fun voiceLeadMs(a: SegmentAnalysis, settings: MixSettings, beatMs: Float, voiceAfterMs: Float?): Long {
        // A title that ends sung on a last note at full level keeps its ending whole.
        if (a.hardEnding && !a.fadeOut) return 0L
        val lead = (settings.vocalLeadBeats * beatMs).roundToLong()
        return if (voiceAfterMs == null) lead else lead.coerceAtMost(max(0L, voiceAfterMs.toLong()))
    }

    /** Which beat of its bar (0..3) [positionMs] is, for a title whose bars are known. */
    fun barIndex(analysis: SegmentAnalysis, positionMs: Double): Int {
        val beats = floor((positionMs - analysis.beatAtMs) / analysis.beatMs + .5).toLong()
        return (((beats - analysis.barOffset) % 4) + 4).toInt() % 4
    }

    /**
     * Where a blend that aims at [phrase] (the start of a phrase of the outgoing title) starts, so that
     * the bars of both titles meet. An incoming title that enters on a pickup (some beats before its
     * first bar) is started that many beats before the phrase: its first bar then opens the phrase, as
     * a DJ drops the one on the one. When there is no room before, it starts as many beats into the
     * phrase as it is into its own bar, so that the first beats still fall together.
     */
    fun onPhrase(a: SegmentAnalysis, b: SegmentAnalysis, inMs: Long, rate: Float, phrase: Double, earliest: Double, latest: Double): Double {
        if (a.barOffset < 0 || b.barOffset < 0 || rate <= 0f) return phrase
        // Read at half or double the pulse of the other, a title's bars say nothing of the other's.
        if (abs(b.beatMs / rate - a.beatMs) > a.beatMs * .02f) return phrase
        val into = barIndex(b, inMs.toDouble())
        if (into == 0) return phrase
        val before = phrase - (4 - into) * a.beatMs.toDouble()
        if (before >= earliest - a.beatMs / 2.0) return before
        val after = phrase + into * a.beatMs.toDouble()
        return if (after <= latest + 1.0) after else phrase
    }

    /**
     * The beat on which the bass changes hands: the first beat of a bar nearest to [middleMs] when bars
     * are known (at most two beats away), the nearest beat otherwise.
     */
    fun swapBeat(a: SegmentAnalysis, middleMs: Double): Double {
        val beat = a.beatAtOrAfter(middleMs - a.beatMs / 2.0)
        if (a.barOffset < 0) return beat
        return when (barIndex(a, beat)) {
            1 -> beat - a.beatMs
            2 -> beat - 2 * a.beatMs.toDouble()
            3 -> beat + a.beatMs
            else -> beat
        }
    }
}
