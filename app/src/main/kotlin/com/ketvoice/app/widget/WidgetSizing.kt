package com.ketvoice.app.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The sizes of the widgets (6.4 redesign). Each widget declares a few breakpoints to Glance
 * ([androidx.glance.appwidget.SizeMode.Responsive]); on Android 12+ Glance hands the launcher one layout per
 * breakpoint as a RemoteViews size mapping, so a resize switches layouts at once on the launcher's side, without
 * waiting for Ketvoice (and older launchers get the best-fitting one). Every layout fills what it is given, so a
 * size between two breakpoints only adds air, never clips.
 *
 * The breakpoints follow Android's cell grid (portrait / landscape): one row is about 102 / 51 dp tall, two rows
 * 220 / 117 dp, four 441 / 250 dp; two columns 130 / 269 dp, four 276 / 554 dp.
 */
internal enum class WidgetSize {
    /** One row, narrow (2×1): a pill with the cover and one button. */
    StripNarrow,
    /** One row (3×1, 4×1): a pill with labels and controls. */
    Strip,
    /** About 2×2: a square card. */
    Small,
    /** About 4×2: a wide card. */
    Medium,
    /** About 4×4: a tall card. */
    Large,
}

internal object WidgetSizing {
    /** Below this height a widget is one row tall. */
    const val ROW_MAX_H = 110f
    /** From this width a one-row widget has room for its labels. */
    const val STRIP_LABELS_W = 180f
    /** From this width a one-row player has the full transport. */
    const val STRIP_FULL_W = 250f
    /** From this width a card is wide (Medium/Large). */
    const val WIDE_W = 250f
    /** From this height a wide card is tall (Large). */
    const val TALL_H = 260f
    /** A wide card this tall has room for an extra line (the sung line, a bigger cover). */
    const val ROOMY_H = 170f

    val STRIP_NARROW = DpSize(100.dp, 40.dp)
    val STRIP = DpSize(STRIP_LABELS_W.dp, 40.dp)
    val STRIP_FULL = DpSize(STRIP_FULL_W.dp, 40.dp)
    val SMALL = DpSize(100.dp, ROW_MAX_H.dp)
    val MEDIUM = DpSize(WIDE_W.dp, ROW_MAX_H.dp)
    val MEDIUM_ROOMY = DpSize(WIDE_W.dp, ROOMY_H.dp)
    val LARGE = DpSize(WIDE_W.dp, TALL_H.dp)

    /** The players: every shape, with a full-transport strip and a roomier medium card. */
    val players: Set<DpSize> = setOf(STRIP_NARROW, STRIP, STRIP_FULL, SMALL, MEDIUM, MEDIUM_ROOMY, LARGE)
    /** The mini controls: one row at any width (taller places just centre it). */
    val mini: Set<DpSize> = setOf(STRIP_NARROW, STRIP, STRIP_FULL)
    /** Lyrics, Ketmix: a strip, a square and the two wide cards. */
    val live: Set<DpSize> = setOf(STRIP_NARROW, STRIP, SMALL, MEDIUM, MEDIUM_ROOMY, LARGE)
    /** Week, mix, Replay: cards only (a one-row place shows the square card's layout). */
    val cards: Set<DpSize> = setOf(SMALL, MEDIUM, MEDIUM_ROOMY, LARGE)

    /** The layout for a size (as Glance gives it in LocalSize: one of the breakpoints). */
    fun classify(widthDp: Float, heightDp: Float): WidgetSize = when {
        heightDp < ROW_MAX_H -> if (widthDp < STRIP_LABELS_W) WidgetSize.StripNarrow else WidgetSize.Strip
        widthDp < WIDE_W -> WidgetSize.Small
        heightDp < TALL_H -> WidgetSize.Medium
        else -> WidgetSize.Large
    }

    /**
     * The breakpoint Glance picks for a place of [widthDp] × [heightDp]: the biggest one that fits (by area), or
     * the smallest when none fits. The same rule as Glance's Responsive mode, kept here to test the breakpoints
     * against real launcher cells.
     */
    fun pick(sizes: Set<DpSize>, widthDp: Float, heightDp: Float): DpSize {
        val fits = sizes.filter { it.width.value <= widthDp + .5f && it.height.value <= heightDp + .5f }
        return fits.maxByOrNull { it.width.value * it.height.value }
            ?: sizes.minBy { it.width.value * it.height.value }
    }

    /** The layout a place of this size shows, for a widget declaring [sizes]. */
    fun layoutFor(sizes: Set<DpSize>, widthDp: Float, heightDp: Float): WidgetSize =
        pick(sizes, widthDp, heightDp).let { classify(it.width.value, it.height.value) }
}
