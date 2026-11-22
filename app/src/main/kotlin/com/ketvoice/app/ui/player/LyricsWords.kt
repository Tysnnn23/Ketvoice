package com.ketvoice.app.ui.player

import androidx.compose.ui.graphics.Color
import com.ketvoice.app.data.MusicApi

/** How sung words move (5.9.0), in the manner of Apple Music. */
internal object LyricsWords {
    /** How high a sung word rises, in dp. */
    const val LIFT_DP = 2.5f
    /** Notes held at least this long glow while they are sung. */
    const val GLOW_FROM_MS = 900L

    /** The sung line's breath for a bass level of 0..1: at most one and a half percent. */
    fun breath(low: Float): Float = if (!low.isFinite()) 0f else (((low - .2f) / .8f).coerceIn(0f, 1f).let { it * it }) * .015f

    /** 8.3: a very short word only nudges up by this part of [LIFT_DP]; one of [LIFT_FULL_MS] or more goes the whole way. */
    const val LIFT_FLOOR = .25f
    const val LIFT_QUICK_MS = 160L
    const val LIFT_FULL_MS = 900L
    /** The rise takes the word's own time, within these bounds; the word settles back over [FALL_MS] once sung. */
    const val RISE_MIN_MS = 250L
    const val RISE_MAX_MS = 700L
    const val FALL_MS = 450L

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** How high a word of [durationMs] goes at most, 0..1 of [LIFT_DP]: smooth between a quick word and a held one. */
    fun liftHeight(durationMs: Long): Float =
        LIFT_FLOOR + (1f - LIFT_FLOOR) * smooth((durationMs - LIFT_QUICK_MS).toFloat() / (LIFT_FULL_MS - LIFT_QUICK_MS))

    /**
     * 8.3: how far a word is lifted, 0..1 of [LIFT_DP], [sinceStartMs] after it starts: it rises over its own time
     * (kept between [RISE_MIN_MS] and [RISE_MAX_MS]) to [liftHeight], then settles back over [FALL_MS] after its end.
     */
    fun lift(sinceStartMs: Long, durationMs: Long): Float {
        if (sinceStartMs <= 0L) return 0f
        val duration = durationMs.coerceAtLeast(0L)
        val up = smooth(sinceStartMs.toFloat() / duration.coerceIn(RISE_MIN_MS, RISE_MAX_MS))
        val down = if (sinceStartMs <= duration) 0f else smooth((sinceStartMs - duration).toFloat() / FALL_MS)
        return liftHeight(duration) * up * (1f - down)
    }

    /** 8.3: the fill's leading edge is feathered over this many dp (a soft front instead of a cut). */
    const val FEATHER_DP = 30f
    /** Once a word is sung, the feather behind its edge finishes within these bounds (during the pause, if any). */
    const val TAIL_MIN_MS = 60L
    const val TAIL_MAX_MS = 300L

    /**
     * 8.3: where the fill's leading edge is, in px from the word's leading side, [sinceStartMs] into a word of
     * [durationMs] and [widthPx]: it moves at one constant pace through the word (no easing inside it). Past the
     * word's end it goes on for one feather ([featherPx]), so the word's last letters light fully: at the word's
     * own pace, but within the pause before the next word ([pauseMs], negative when unknown) and [TAIL_MIN_MS]..
     * [TAIL_MAX_MS]. 0 before the word; widthPx + featherPx once it is all lit.
     */
    fun edgePx(sinceStartMs: Long, durationMs: Long, widthPx: Float, featherPx: Float, pauseMs: Long = -1L): Float {
        if (sinceStartMs < 0L || !(widthPx > 0f)) return 0f
        val feather = featherPx.coerceAtLeast(0f)
        if (durationMs <= 0L) return widthPx + feather
        if (sinceStartMs <= durationMs) return widthPx * sinceStartMs / durationMs
        val tail = tailMs(durationMs, widthPx, feather, pauseMs)
        return widthPx + feather * ((sinceStartMs - durationMs).toFloat() / tail).coerceIn(0f, 1f)
    }

    /** How long a word's feather takes to finish after its end (see [edgePx]). */
    fun tailMs(durationMs: Long, widthPx: Float, featherPx: Float, pauseMs: Long = -1L): Long {
        val pace = if (widthPx > 0f) featherPx * durationMs / widthPx else 0f
        val within = if (pauseMs > 0L) minOf(pace, pauseMs.toFloat()) else pace
        return within.toLong().coerceIn(TAIL_MIN_MS, TAIL_MAX_MS)
    }

    /** The ink, 0..1, [d] px from the word's leading side when the edge is at [edgePx]: full a feather behind it. */
    fun ink(d: Float, edgePx: Float, featherPx: Float): Float =
        if (featherPx <= 0f) (if (d < edgePx) 1f else 0f) else ((edgePx - d) / featherPx).coerceIn(0f, 1f)

    /** 8.2: how much larger the word being sung is drawn, at most (owner: « très légèrement », ≤ 4 %). */
    const val GROW = .035f

    /**
     * The scale of a word [progress] (0..1) into its singing, [sinceEndMs] after its end (negative before): it grows
     * over the first third of the word and settles back over [SETTLE_MS] once sung. 1 for a word not reached yet.
     */
    fun grow(progress: Float, sinceEndMs: Long): Float {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (p <= 0f) return 1f
        val settle = if (sinceEndMs <= 0L) 1f else 1f - smooth(sinceEndMs / SETTLE_MS.toFloat())
        return 1f + GROW * smooth(p / .3f) * settle
    }
    const val SETTLE_MS = 320L

    /** The glow of a held note: strongest in the middle of the note, nothing for short words. */
    fun glow(progress: Float, durationMs: Long): Float {
        if (durationMs < GLOW_FROM_MS || !(progress > 0f && progress < 1f)) return 0f
        return kotlin.math.sin(progress * Math.PI).toFloat() * heldStrength(durationMs)
    }

    /** The colour of the lower-cost glow of a held note (below Android 12 and in performance mode). */
    fun glowColor(glow: Float): Color = Color.White.copy(alpha = .42f * glow.coerceIn(0f, 1f))

    /** 8.3: a held note's letters swell one after another; only short words held at least [GLOW_FROM_MS] do. */
    const val HELD_MAX_LETTERS = 7
    /** How much larger a letter is at the top of its swell, how far it leans out from the word's middle and up. */
    const val SWELL = .045f
    const val LEAN_DP = 1.4f
    const val LETTER_RISE_DP = 1.2f
    /** The last letters finish their swell this long after the word's end. */
    const val HELD_TAIL_MS = 200L
    /** Each letter starts at most this long after the one before it. */
    const val STAGGER_MAX_MS = 110L
    /** The glow of a held note: a blurred copy of its letters at about this alpha, blurred by this radius. */
    const val GLOW_ALPHA = .6f
    const val GLOW_RADIUS_DP = 9f
    /** In, hold and out of each letter's swell, as parts of its window. */
    private const val SWELL_IN = .32f
    private const val SWELL_HOLD = .58f
    /**
     * Everything a word does (fill tail, lift, growth, swell, glow) is over this long after its end, so a line is
     * drawn exactly the same from then on.
     */
    const val REST_MS = 600L

    /** How strong a held note's swell and glow are for its length: half at [GLOW_FROM_MS], all from two seconds. */
    fun heldStrength(durationMs: Long): Float = ((durationMs - GLOW_FROM_MS) / 1_100f + .5f).coerceIn(.5f, 1f)

    /**
     * 8.3: whether a word swells letter by letter: held [GLOW_FROM_MS] or longer, one to [HELD_MAX_LETTERS] letters,
     * and written in Latin, Greek or Cyrillic only (never Chinese, Japanese, Korean, Arabic, Hebrew or other scripts
     * whose letters join or stack: their glyphs cannot be drawn one by one).
     */
    fun holds(text: String, durationMs: Long): Boolean {
        if (durationMs < GLOW_FROM_MS) return false
        val letters = text.filterNot { it.isWhitespace() }
        if (letters.isEmpty() || letters.length > HELD_MAX_LETTERS || letters.none { it.isLetter() }) return false
        return letters.all(::separable)
    }

    private fun separable(c: Char): Boolean = !c.isSurrogate() && when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.COMMON -> true
        else -> false
    }

    /** While a held word's letters may still move: from its start to [HELD_TAIL_MS] after its end. */
    fun inHold(sinceStartMs: Long, durationMs: Long): Boolean = sinceStartMs >= 0L && sinceStartMs < durationMs + HELD_TAIL_MS

    /**
     * The swell of letter [index] of [count], 0..1, [sinceStartMs] into a held word of [durationMs]: each letter
     * starts a little after the one before, rises, holds, then falls back to 0 by [HELD_TAIL_MS] after the end.
     */
    fun swell(index: Int, count: Int, sinceStartMs: Long, durationMs: Long): Float {
        if (count <= 0 || durationMs <= 0L) return 0f
        val total = (durationMs + HELD_TAIL_MS).toFloat()
        val stagger = if (count > 1) minOf(STAGGER_MAX_MS.toFloat(), total * .35f / (count - 1)) else 0f
        val window = total - stagger * (count - 1)
        val p = (sinceStartMs - index.coerceIn(0, count - 1) * stagger) / window
        return when {
            !(p > 0f) || p >= 1f -> 0f
            p < SWELL_IN -> smooth(p / SWELL_IN)
            p < SWELL_HOLD -> 1f
            else -> 1f - smooth((p - SWELL_HOLD) / (1f - SWELL_HOLD))
        }
    }

    /** The time a karaoke line changes in: from just before its first word to [REST_MS] after its last. */
    fun window(line: MusicApi.LyricLine): LongRange {
        val first = line.words.minOfOrNull { it.startMs } ?: line.timeMs
        val last = maxOf(line.words.maxOfOrNull { maxOf(it.endMs, it.startMs) } ?: first, first)
        return (first - 50L)..(last + REST_MS)
    }

    /** -1 before [window], 0 inside it, 1 after it. */
    fun side(now: Long, window: LongRange): Int = when { now < window.first -> -1; now > window.last -> 1; else -> 0 }

    /** True when the words, joined, hold every visible letter of the line (so drawing words alone loses nothing). */
    fun covers(line: String, words: List<String>): Boolean {
        val letters = line.count { !it.isWhitespace() }
        if (letters == 0 || words.isEmpty()) return false
        return words.sumOf { w -> w.count { !it.isWhitespace() } } >= letters && words.joinToString("").length <= line.length
    }
}

/** How far into a word, 0..1, at [now]: one constant pace from its start to its end (8.3: no easing inside a word). */
internal fun segmentProgress(now: Long, start: Long, end: Long): Float = when {
    now < start -> 0f
    end <= start -> 1f
    else -> ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}
