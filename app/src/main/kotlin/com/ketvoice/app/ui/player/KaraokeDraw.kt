package com.ketvoice.app.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.MusicApi

/**
 * A piece of a word on one row of the line (or, for a held word, one letter): its glyph outline, its bounds,
 * its direction and how far along the word it starts ([at], px from the word's leading side).
 */
internal data class LyricMaskSlice(val path: Path, val bounds: Rect, val rtl: Boolean, val at: Float = 0f)

internal data class LyricWordMask(
    val start: Long,
    val end: Long,
    val slices: List<LyricMaskSlice>,
    val width: Float,
    /** When the next word starts (-1 for the last word): the feather finishes within the pause. */
    val next: Long = -1L,
    /** A held word's letters (empty for any other word), each with where it sits across the word, -1..1. */
    val letters: List<LyricMaskSlice> = emptyList(),
    val across: FloatArray = FloatArray(0)
) {
    val duration: Long get() = end - start
    val pause: Long get() = if (next >= 0L) next - end else -1L
}

/** The word masks of a laid-out line, made once per layout: glyph paths per row, and per letter for held words. */
internal fun karaokeMasks(measured: TextLayoutResult, text: String, words: List<MusicApi.LyricWord>): List<LyricWordMask> {
    var offset = 0
    return words.mapIndexed { index, word ->
        val start = offset
        val end = (start + word.text.length).coerceAtMost(text.length)
        offset = end
        val slices = wordSlices(measured, start, end)
        val held = slices.isNotEmpty() && LyricsWords.holds(word.text, word.endMs - word.startMs)
        val letters = if (held) heldLetters(measured, text, start, end, slices) else emptyList()
        LyricWordMask(word.startMs, word.endMs, slices, slices.sumOf { it.bounds.width.toDouble() }.toFloat(),
            words.getOrNull(index + 1)?.startMs ?: -1L, letters.map { it.first }, FloatArray(letters.size) { letters[it].second })
    }
}

private fun wordSlices(measured: TextLayoutResult, start: Int, end: Int): List<LyricMaskSlice> {
    if (start >= end) return emptyList()
    var at = 0f
    return (0 until measured.lineCount).mapNotNull { row ->
        val from = maxOf(start, measured.getLineStart(row))
        val to = minOf(end, measured.getLineEnd(row))
        if (from >= to) return@mapNotNull null
        val path = measured.getPathForRange(from, to)
        val bounds = path.getBounds()
        LyricMaskSlice(path, bounds, measured.getBidiRunDirection(from) == ResolvedTextDirection.Rtl, at).also { at += bounds.width }
    }
}

private fun heldLetters(measured: TextLayoutResult, text: String, start: Int, end: Int,
                        slices: List<LyricMaskSlice>): List<Pair<LyricMaskSlice, Float>> {
    val glyphs = (start until end).filter { !text[it].isWhitespace() }.map { i ->
        val path = measured.getPathForRange(i, i + 1)
        path to path.getBounds()
    }.filter { it.second.width > 0f }
    if (glyphs.isEmpty()) return emptyList()
    val left = glyphs.minOf { it.second.left }
    val right = glyphs.maxOf { it.second.right }
    return glyphs.map { (path, box) ->
        val slice = slices.firstOrNull { box.center.y in it.bounds.top..it.bounds.bottom } ?: slices.first()
        val at = slice.at + if (slice.rtl) slice.bounds.right - box.right else box.left - slice.bounds.left
        val across = if (right > left) (box.center.x - left) / (right - left) * 2f - 1f else 0f
        LyricMaskSlice(path, box, slice.rtl, at) to across
    }
}

/**
 * Draws one slice clipped to its glyphs: the pending ink (when given), then the sung ink up to [edge] (px along
 * the word), full a feather behind the edge and fading to nothing at it, in the slice's own direction.
 */
internal fun DrawScope.drawSliceInk(measured: TextLayoutResult, slice: LyricMaskSlice, edge: Float, feather: Float,
                                    ink: Color, pending: Color?, shadow: Shadow? = null) {
    val local = edge - slice.at
    if (pending == null && local <= 0f) return
    clipPath(slice.path) {
        if (pending != null) drawText(measured, color = pending)
        if (local > 0f) drawSungPart(measured, slice.bounds, slice.rtl, local, feather, ink, shadow)
    }
}

private fun DrawScope.drawSungPart(measured: TextLayoutResult, b: Rect, rtl: Boolean, local: Float, feather: Float,
                                   ink: Color, shadow: Shadow?) {
    // Half a pixel short of the end counts as done: rounding never leaves a gradient over a word already sung.
    if (local - feather >= b.width - .5f) { drawText(measured, color = ink, shadow = shadow); return }
    val head = if (rtl) b.right - local else b.left + local
    val full = if (rtl) head + feather else head - feather
    clipRect(if (rtl) head else b.left, b.top, if (rtl) b.right else head, b.bottom) {
        // alpha = 1: the text paint keeps the alpha of the last colour drawn (the pending ink) unless it is given.
        drawText(measured, Brush.horizontalGradient(0f to ink, 1f to ink.copy(alpha = 0f), startX = full, endX = head),
            alpha = 1f, shadow = shadow)
    }
}

/** What one frame of a karaoke line draws with. */
internal class KaraokeInk(val ink: Color, val pending: Color?, val featherPx: Float, val liftPx: Float,
                          val leanPx: Float, val letterRisePx: Float, val shadowGlow: Boolean,
                          /** « Paroles à ton goût »: 0..1 of the 8.3 lift, growth and swell (the lift distances above are already scaled). */
                          val motion: Float = 1f) {
    /** A word's growth scaled by [motion]. */
    fun grown(grow: Float): Float = 1f + (grow - 1f) * motion
}

/** One word lifted, grown and (when held) swelling letter by letter, at [now]. */
internal fun DrawScope.drawLiftedWord(measured: TextLayoutResult, word: LyricWordMask, now: Long, k: KaraokeInk) {
    val since = now - word.start
    val edge = LyricsWords.edgePx(since, word.duration, word.width, k.featherPx, word.pause)
    val rise = LyricsWords.lift(since, word.duration) * k.liftPx
    val grow = k.grown(LyricsWords.grow(segmentProgress(now, word.start, word.end), now - word.end))
    if (word.letters.isNotEmpty() && LyricsWords.inHold(since, word.duration)) {
        drawHeldLetters(measured, word, since, edge, rise, grow, k, k.ink, k.pending, k.shadowGlow)
        return
    }
    translate(top = -rise) {
        word.slices.forEach { slice ->
            scale(grow, grow, slice.bounds.center) { drawSliceInk(measured, slice, edge, k.featherPx, k.ink, k.pending) }
        }
    }
}

/**
 * A held word drawn a letter at a time, each at its own swell: a little larger, leaning out from the word's middle
 * and up. With [ink] at the glow's alpha and no [pending], this draws the copy the glow layer blurs.
 */
internal fun DrawScope.drawHeldLetters(measured: TextLayoutResult, word: LyricWordMask, since: Long, edge: Float,
                                       rise: Float, grow: Float, k: KaraokeInk, ink: Color, pending: Color?, shadowGlow: Boolean,
                                       glowCopy: Boolean = false) {
    val strength = LyricsWords.heldStrength(word.duration)
    val pivot = word.slices.first().bounds.center
    scale(grow, grow, pivot) {
        word.letters.forEachIndexed { i, letter ->
            val e = LyricsWords.swell(i, word.letters.size, since, word.duration) * strength
            if (glowCopy && e <= .005f) return@forEachIndexed
            val letterInk = if (glowCopy) ink.copy(alpha = ink.alpha * e.coerceIn(0f, 1f)) else ink
            val size = 1f + LyricsWords.SWELL * e * k.motion
            val shadow = if (shadowGlow && e > .01f) Shadow(LyricsWords.glowColor(e), Offset.Zero, 14f * e) else null
            translate(word.across[i] * k.leanPx * e, -rise - k.letterRisePx * e) {
                scale(size, size, letter.bounds.center) { drawSliceInk(measured, letter, edge, k.featherPx, letterInk, pending, shadow) }
            }
        }
    }
}

/** The glow copy of every held word now in its hold (drawn into a blurred layer behind the line). */
internal fun DrawScope.drawHeldGlow(measured: TextLayoutResult, masks: List<LyricWordMask>, now: Long, k: KaraokeInk) {
    masks.forEach { word ->
        val since = now - word.start
        if (word.letters.isEmpty() || !LyricsWords.inHold(since, word.duration)) return@forEach
        val edge = LyricsWords.edgePx(since, word.duration, word.width, k.featherPx, word.pause)
        val rise = LyricsWords.lift(since, word.duration) * k.liftPx
        val grow = k.grown(LyricsWords.grow(segmentProgress(now, word.start, word.end), now - word.end))
        drawHeldLetters(measured, word, since, edge, rise, grow, k, k.ink.copy(alpha = LyricsWords.GLOW_ALPHA), null, false, glowCopy = true)
    }
}

/** True while some held word of the line is in its hold (the only time the glow layer exists). */
internal fun heldNow(masks: List<LyricWordMask>, now: Long): Boolean =
    masks.any { it.letters.isNotEmpty() && LyricsWords.inHold(now - it.start, it.duration) }

/** The flat drawing (reduced motion, or words that do not cover the line): the sung ink only, over the Text. */
internal fun DrawScope.drawFlatWord(measured: TextLayoutResult, word: LyricWordMask, now: Long, ink: Color, featherPx: Float) {
    val edge = LyricsWords.edgePx(now - word.start, word.duration, word.width, featherPx, word.pause)
    if (edge <= 0f) return
    word.slices.forEach { drawSliceInk(measured, it, edge, featherPx, ink, null) }
}

internal fun DrawScope.karaokeInk(ink: Color, pending: Color?, shadowGlow: Boolean, motion: Float = 1f): KaraokeInk {
    val m = if (motion.isFinite()) motion.coerceIn(0f, 1f) else 1f
    return KaraokeInk(ink, pending, LyricsWords.FEATHER_DP.dp.toPx(), LyricsWords.LIFT_DP.dp.toPx() * m,
        LyricsWords.LEAN_DP.dp.toPx() * m, LyricsWords.LETTER_RISE_DP.dp.toPx() * m, shadowGlow, m)
}
