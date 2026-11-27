package com.ketvoice.app.tv

import kotlin.math.max
import kotlin.math.min

/** A rectangle in dp: where a block of Now Playing goes. */
data class TvRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val centerX: Float get() = x + w / 2f
    val bottom: Float get() = y + h

    fun lerp(to: TvRect, t: Float): TvRect = TvRect(mix(x, to.x, t), mix(y, to.y, t), mix(w, to.w, t), mix(h, to.h, t))

    companion object {
        fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}

/**
 * Where Now Playing's blocks go on the TV (1.1.0). Two layouts and every step between them, so that showing or
 * hiding the lyrics moves the blocks instead of swapping screens:
 *  - [solo] (no lyrics, or lyrics hidden): the artwork large and centred, title and artist under it, the progress
 *    and one row of controls along the bottom;
 *  - [withLyrics]: the artwork, title, progress and controls (on two rows) in a column on the left, the lyrics filling
 *    the right side.
 * The artwork frame always takes the media's own shape (a square cover, a 9:16 or 16:9 Canvas), never letterboxed
 * inside a fixed square; only its size is capped. Pure, host-tested.
 */
object TvPlayerLayout {
    const val SAFE_X = 48f
    const val SAFE_Y = 27f

    /** The column of the layout with lyrics. */
    const val COLUMN_W = 380f
    const val COLUMN_GAP = 56f

    /** Title and artist under the artwork (drawn at [Frame.titleScale] of their large size). */
    const val INFO_H = 76f
    /** Ketmix's line (its sign while it mixes, the 👍/👎 prompt after a transition), never scaled. */
    const val KETMIX_H = 38f
    /** The progress line and the times under it. */
    const val PROGRESS_H = 30f
    /** One row of controls (the play button is the tallest). */
    const val ROW_H = 64f
    const val SECOND_ROW_H = 44f
    const val ROW_GAP = 10f

    /** The widest and narrowest shapes a frame takes; past them the media is cropped a little, never boxed. */
    const val MIN_ASPECT = .5f
    const val MAX_ASPECT = 2.2f

    data class Frame(val art: TvRect, val info: TvRect, val ketmix: TvRect, val progress: TvRect, val controls: TvRect,
                     val lyrics: TvRect,
                     /** Whether the controls sit on two rows (the column) or one (the bottom bar), 0..1. */
                     val stacked: Float, val titleScale: Float)

    fun aspect(value: Float?): Float = value?.takeIf { it.isFinite() && it > 0f }?.coerceIn(MIN_ASPECT, MAX_ASPECT) ?: 1f

    /** The largest size of [aspect] inside [maxW] × [maxH]. */
    fun fit(aspect: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        val a = aspect(aspect)
        val w = min(maxW, maxH * a)
        return w to w / a
    }

    fun solo(width: Float, height: Float, aspect: Float): Frame {
        val controlsH = PROGRESS_H + ROW_GAP + ROW_H
        val controls = TvRect(SAFE_X, height - SAFE_Y - ROW_H, width - 2 * SAFE_X, ROW_H)
        val progress = TvRect(SAFE_X, controls.y - ROW_GAP - PROGRESS_H, controls.w, PROGRESS_H)
        val ketmix = TvRect(SAFE_X, progress.y - 4f - KETMIX_H, controls.w, KETMIX_H)
        val info = TvRect(SAFE_X, ketmix.y - 2f - INFO_H, controls.w, INFO_H)
        val (w, h) = fit(aspect, width * .62f, max(80f, info.y - 14f - SAFE_Y))
        // The artwork sits just above its title; what room is left goes above it.
        val art = TvRect((width - w) / 2f, info.y - 14f - h, w, h)
        val lyrics = TvRect(width - SAFE_X - COLUMN_W + 80f, SAFE_Y, COLUMN_W, height - 2 * SAFE_Y - controlsH)
        return Frame(art, info, ketmix, progress, controls, lyrics, stacked = 0f, titleScale = 1f)
    }

    fun withLyrics(width: Float, height: Float, aspect: Float): Frame {
        val column = TvRect(SAFE_X, SAFE_Y, COLUMN_W, height - 2 * SAFE_Y)
        val controlsH = ROW_H + ROW_GAP + SECOND_ROW_H
        val controls = TvRect(column.x, column.bottom - controlsH, column.w, controlsH)
        val progress = TvRect(column.x, controls.y - ROW_GAP - PROGRESS_H, column.w, PROGRESS_H)
        val ketmix = TvRect(column.x, progress.y - 4f - KETMIX_H, column.w, KETMIX_H)
        val info = TvRect(column.x, ketmix.y - 2f - INFO_H * TITLE_SMALL, column.w, INFO_H * TITLE_SMALL)
        val (w, h) = fit(aspect, column.w, max(80f, info.y - 12f - column.y))
        val art = TvRect(column.centerX - w / 2f, info.y - 12f - h, w, h)
        val lyricsX = column.x + column.w + COLUMN_GAP
        val lyrics = TvRect(lyricsX, SAFE_Y, width - SAFE_X - lyricsX, height - 2 * SAFE_Y)
        return Frame(art, info, ketmix, progress, controls, lyrics, stacked = 1f, titleScale = TITLE_SMALL)
    }

    /** The title's size in the column, as a share of its size in the centred layout. */
    const val TITLE_SMALL = .72f

    /** The layout [split] of the way from [solo] (0) to [withLyrics] (1). */
    fun at(split: Float, width: Float, height: Float, aspect: Float): Frame {
        val t = split.coerceIn(0f, 1f)
        val a = solo(width, height, aspect)
        if (t <= 0f) return a
        val b = withLyrics(width, height, aspect)
        if (t >= 1f) return b
        return Frame(a.art.lerp(b.art, t), a.info.lerp(b.info, t), a.ketmix.lerp(b.ketmix, t), a.progress.lerp(b.progress, t),
            a.controls.lerp(b.controls, t), a.lyrics.lerp(b.lyrics, t), t, TvRect.mix(a.titleScale, b.titleScale, t))
    }

    /**
     * The controls, left to right: like and Ketmix, then shuffle, previous, play, next, repeat, then lyrics and queue.
     * In one row (the bottom bar) the first two hug the left edge, the transport is centred and the last two hug the
     * right edge; in two rows (the column) the transport is centred on the first and the four others on the second.
     * Returns each control's left edge and top, in the controls' own box.
     */
    fun controls(sizes: FloatArray, boxW: Float, stacked: Float, gap: Float = 14f): Array<Pair<Float, Float>> {
        require(sizes.size == CONTROL_COUNT)
        val one = row(sizes, boxW, gap)
        val two = rows(sizes, boxW, gap)
        val t = stacked.coerceIn(0f, 1f)
        return Array(sizes.size) { i -> TvRect.mix(one[i].first, two[i].first, t) to TvRect.mix(one[i].second, two[i].second, t) }
    }

    const val CONTROL_COUNT = 9
    private val TRANSPORT = 2..6

    private fun width(sizes: FloatArray, range: IntRange, gap: Float): Float = range.sumOf { sizes[it].toDouble() }.toFloat() + gap * (range.count() - 1)

    private fun centred(size: Float, rowH: Float): Float = (rowH - size) / 2f

    private fun row(sizes: FloatArray, boxW: Float, gap: Float): Array<Pair<Float, Float>> {
        val out = Array(sizes.size) { 0f to 0f }
        var x = 0f
        for (i in 0..1) { out[i] = x to centred(sizes[i], ROW_H); x += sizes[i] + gap }
        x = (boxW - width(sizes, TRANSPORT, gap)) / 2f
        for (i in TRANSPORT) { out[i] = x to centred(sizes[i], ROW_H); x += sizes[i] + gap }
        x = boxW - width(sizes, 7..8, gap)
        for (i in 7..8) { out[i] = x to centred(sizes[i], ROW_H); x += sizes[i] + gap }
        return out
    }

    private fun rows(sizes: FloatArray, boxW: Float, gap: Float): Array<Pair<Float, Float>> {
        val out = Array(sizes.size) { 0f to 0f }
        var x = (boxW - width(sizes, TRANSPORT, gap)) / 2f
        for (i in TRANSPORT) { out[i] = x to centred(sizes[i], ROW_H); x += sizes[i] + gap }
        val second = listOf(0, 1, 7, 8)
        val secondW = second.sumOf { sizes[it].toDouble() }.toFloat() + gap * (second.size - 1)
        x = (boxW - secondW) / 2f
        val y = ROW_H + ROW_GAP
        for (i in second) { out[i] = x to y + (SECOND_ROW_H - sizes[i]) / 2f; x += sizes[i] + gap }
        return out
    }
}

/**
 * How Now Playing's pictures follow a Ketmix blend (1.1.0, « Fondu visuel sur le mix »): the incoming title's colours
 * come in along the whole mix, its artwork over the stretch just before the bass changes hands, whole on that moment,
 * and its name takes the place of the outgoing one there. No decks, no waveforms. Pure, host-tested.
 */
object TvMixVisual {
    /** The stretch of the mix (share) over which the incoming artwork rises before the swap. */
    const val ART_WINDOW = .18f

    /** Where the pictures change hands: the bass swap of the plan, else the hand-over of the controls. */
    fun swapPoint(swapAt: Float): Float = if (swapAt.isFinite() && swapAt in .05f..0.95f) swapAt else com.ketvoice.app.ui.player.MixVisuals.HANDOVER

    fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** Opacity of the incoming artwork over the outgoing one at [progress]. */
    fun artIn(progress: Float, swap: Float): Float {
        val start = (swap - ART_WINDOW).coerceAtLeast(0f)
        if (swap <= start) return if (progress >= swap) 1f else 0f
        return smooth((progress - start) / (swap - start))
    }

    /** Share of the incoming title's colours in the field. */
    fun fieldIn(progress: Float): Float = smooth(progress)

    fun swapped(progress: Float, swap: Float): Boolean = progress >= swap
}
