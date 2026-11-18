package com.ketvoice.app.ui.adaptive

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** How the app is navigated in a window: the phone's floating tab bar, a rail of symbols, or the full sidebar. */
enum class NavigationMode { TabBar, Rail, Sidebar }

/** Where a sheet appears: from the bottom edge (phones), or as a floating panel (wide windows). */
enum class SheetStyle { Bottom, Centered, Anchored }

/**
 * What a large window adds to the app, worked out once at the root. Null on phones (compact width or
 * short window) and on Quest: every large-screen layout reads it, so a phone never sees any of them.
 */
@Immutable
data class LargeScreenInfo(
    val windowWidthDp: Float,
    val windowHeightDp: Float,
    val navigation: NavigationMode,
    /** Width taken by the rail or the sidebar at the start of the window. */
    val navigationWidthDp: Float,
    /** Width left to the pages, before their own maximum. */
    val contentWidthDp: Float,
    /** Whether the listener may open the sidebar here (the rail of a medium window cannot). */
    val sidebarAllowed: Boolean
)

val LocalLargeScreen = staticCompositionLocalOf<LargeScreenInfo?> { null }

/**
 * The geometry of Ketvoice on tablets, foldables and desktop windows. Everything follows the width
 * the window really has (split screen, freeform windows and an unfolded foldable included), never
 * the kind of device. Pure functions, checked without a screen.
 */
object LargeScreen {
    /** Below this width the phone layout is kept, untouched. */
    const val COMPACT_UNTIL = 600f
    /** A window shorter than this is a phone on its side: the phone layout is kept. */
    const val SHORT_UNTIL = 480f
    /** From this width the sidebar may be opened. */
    const val SIDEBAR_FROM = 1000f
    /** From this width the sidebar is open unless the listener closed it. */
    const val SIDEBAR_BY_DEFAULT = 1180f
    const val RAIL_DP = 88f
    const val SIDEBAR_DP = 272f
    /** Pages never grow wider than this, however large the window. */
    const val PAGE_MAX_DP = 1280f
    /** The page gutter beside a rail or a sidebar (AppRoot pads the page by it on each side). */
    const val PAGE_GUTTER_DP = 16f

    fun isLarge(widthDp: Float, heightDp: Float, quest: Boolean): Boolean =
        !quest && widthDp.isFinite() && heightDp.isFinite() && widthDp >= COMPACT_UNTIL && heightDp >= SHORT_UNTIL

    /**
     * The navigation for a window. [collapsed] is the listener's choice for the sidebar (null: not chosen yet).
     * Quest keeps its own landscape window and the phone keeps its tab bar.
     */
    fun navigation(widthDp: Float, heightDp: Float, quest: Boolean, collapsed: Boolean?): NavigationMode = when {
        !isLarge(widthDp, heightDp, quest) -> NavigationMode.TabBar
        widthDp < SIDEBAR_FROM -> NavigationMode.Rail
        collapsed == true -> NavigationMode.Rail
        collapsed == false -> NavigationMode.Sidebar
        widthDp >= SIDEBAR_BY_DEFAULT -> NavigationMode.Sidebar
        else -> NavigationMode.Rail
    }

    fun navigationWidth(mode: NavigationMode): Float = when (mode) {
        NavigationMode.TabBar -> 0f
        NavigationMode.Rail -> RAIL_DP
        NavigationMode.Sidebar -> SIDEBAR_DP
    }

    /** Everything a large window needs at the root, or null for a phone or Quest. */
    fun info(widthDp: Float, heightDp: Float, quest: Boolean, collapsed: Boolean?): LargeScreenInfo? {
        val mode = navigation(widthDp, heightDp, quest, collapsed)
        if (mode == NavigationMode.TabBar) return null
        val nav = navigationWidth(mode)
        return LargeScreenInfo(widthDp, heightDp, mode, nav, pageWidth(widthDp, nav), widthDp >= SIDEBAR_FROM)
    }

    /** The width a page really gets: what the navigation leaves, less the gutters, never more than [PAGE_MAX_DP]. */
    fun pageWidth(windowWidthDp: Float, navigationWidthDp: Float): Float =
        ((windowWidthDp - navigationWidthDp).coerceAtMost(PAGE_MAX_DP) - 2f * PAGE_GUTTER_DP).coerceAtLeast(0f)

    /**
     * How many columns of cells at least [minCellDp] wide, [gapDp] apart, fit in [widthDp]: at least
     * [min], at most [max]. Follows the room the pane has, never the device.
     */
    fun columns(widthDp: Float, minCellDp: Float, gapDp: Float, min: Int = 1, max: Int = 8): Int {
        if (!widthDp.isFinite() || widthDp <= 0f || minCellDp <= 0f) return min
        return ((widthDp + gapDp) / (minCellDp + gapDp)).toInt().coerceIn(min, max)
    }

    /** Width of each of [columns] cells filling [widthDp] with [gapDp] between them. */
    fun cellWidth(widthDp: Float, columns: Int, gapDp: Float): Float =
        ((widthDp - gapDp * (columns - 1).coerceAtLeast(0)) / columns.coerceAtLeast(1)).coerceAtLeast(0f)

    /**
     * The cell of a horizontal shelf on a wide page: as many whole cards as fit at about the phone's size
     * ([phoneDp]), sized so that the row ends on a card, with a sliver of the next one showing past the edge
     * (a shelf has to say it scrolls). [edgeDp] is the shelf's padding on each side, [gapDp] between cards.
     */
    fun shelfCell(pageDp: Float, phoneDp: Float, edgeDp: Float, gapDp: Float): Float {
        if (!pageDp.isFinite() || pageDp <= 0f || phoneDp <= 0f) return phoneDp
        val room = pageDp - 2f * edgeDp
        // The sliver: a quarter of a card stays visible at the end of the row. Cards may shrink a little so one more fits.
        val sliver = phoneDp * .25f
        val count = kotlin.math.round((room - sliver + gapDp) / (phoneDp + gapDp)).toInt().coerceIn(2, 10)
        val cell = (room - gapDp * count - sliver) / count
        return cell.coerceIn(phoneDp * .9f, phoneDp * 1.35f)
    }

    /** The narrowest cover of an artwork grid (playlists, albums, Downloads) and the gap between covers. */
    const val GRID_CELL_DP = 150f
    const val GRID_GAP_DP = 18f

    /**
     * Columns of an artwork grid for a pane [paneDp] wide (its margins already taken off): two on a phone,
     * then as many as keep each cover about [GRID_CELL_DP] — four on a tablet standing up, six lying down.
     */
    fun gridColumns(paneDp: Float): Int = columns(paneDp, GRID_CELL_DP, GRID_GAP_DP, min = 2, max = 8)

    /** Rows of a list (playlists, Downloads) side by side: one column, two once the pane is wide enough for both to breathe. */
    fun listColumns(paneDp: Float): Int = if (paneDp.isFinite() && paneDp >= 860f) 2 else 1

    /** Side of the artwork beside the title of a collection page on a wide pane. */
    fun headerArtwork(paneDp: Float): Float = (paneDp * .3f).coerceIn(200f, 300f)

    /** Search with its results beside the query: the width of the query pane, or null for one column. */
    fun searchPane(widthDp: Float, heightDp: Float): Float? {
        if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp < 840f || heightDp < 560f) return null
        return (widthDp * .34f).coerceIn(300f, 380f)
    }

    /** What the full player shows on a large window. */
    @Immutable
    data class PlayerPlan(val landscape: Boolean, val lyrics: Boolean, val queue: Boolean) {
        val queueBesideLyrics: Boolean get() = lyrics && queue
    }

    /** Lyrics and queue together need this much width (landscape / portrait). */
    const val BOTH_PANES_LANDSCAPE = 1240f
    const val BOTH_PANES_PORTRAIT = 960f
    /** Width of the queue panel. */
    const val QUEUE_PANEL_DP = 380f

    /**
     * Lyrics beside the artwork when the window lies, below it when it stands; the queue as a panel of its own,
     * next to the lyrics when the window is wide enough for both, in their place otherwise.
     */
    fun playerPlan(widthDp: Float, heightDp: Float, lyricsWanted: Boolean, queueWanted: Boolean): PlayerPlan {
        val landscape = widthDp > heightDp
        val both = widthDp >= if (landscape) BOTH_PANES_LANDSCAPE else BOTH_PANES_PORTRAIT
        return PlayerPlan(landscape, lyricsWanted && (!queueWanted || both), queueWanted)
    }

    /** Side of the artwork in the player (dp) for a pane [paneWidthDp] × [paneHeightDp] keeping [reservedDp] for the controls. */
    fun playerArtwork(paneWidthDp: Float, paneHeightDp: Float, reservedDp: Float, maxDp: Float = 620f): Float =
        minOf(paneWidthDp, paneHeightDp - reservedDp, maxDp).coerceAtLeast(120f)

    /** Sheets float as panels once the window is neither a phone nor a phone on its side. */
    fun sheetStyle(widthDp: Float, heightDp: Float, quest: Boolean, wholeForm: Boolean, anchored: Boolean): SheetStyle = when {
        !isLarge(widthDp, heightDp, quest) -> SheetStyle.Bottom
        wholeForm || !anchored -> SheetStyle.Centered
        else -> SheetStyle.Anchored
    }

    /** Width of a floating sheet. */
    fun sheetWidth(windowWidthDp: Float): Float = minOf(540f, windowWidthDp - 48f).coerceAtLeast(280f)

    /** Where a floating panel of [panelW] × [panelH] goes (top-left, px) in a [windowW] × [windowH] window, kept [margin] from its edges. */
    fun floatingPlacement(anchorX: Float?, anchorY: Float?, panelW: Float, panelH: Float, windowW: Float, windowH: Float,
                          margin: Float, top: Float, bottom: Float): Pair<Float, Float> {
        val maxX = (windowW - margin - panelW).coerceAtLeast(margin)
        val minY = top + margin
        val maxY = (windowH - bottom - margin - panelH).coerceAtLeast(minY)
        if (anchorX == null || anchorY == null || !anchorX.isFinite() || !anchorY.isFinite())
            return ((windowW - panelW) / 2f).coerceIn(margin, maxX) to ((windowH - panelH) / 2f).coerceIn(minY, maxY)
        // A popover hangs from where it was asked for: under the pointer, or above it when there is no room below.
        val x = (anchorX - panelW / 2f).coerceIn(margin, maxX)
        val below = anchorY + margin
        val y = if (below + panelH <= windowH - bottom - margin) below else anchorY - margin - panelH
        return x to y.coerceIn(minY, maxY)
    }
}

/**
 * Width of a card of a horizontal shelf: [phone] on a phone; on a large window, sized so that the shelf
 * holds as many whole cards as fit with a sliver of the next ([LargeScreen.shelfCell]). [edge] is the
 * padding before the first card (and after the last), [gap] the space between cards.
 */
@androidx.compose.runtime.Composable
fun shelfCell(phone: androidx.compose.ui.unit.Dp, edge: androidx.compose.ui.unit.Dp,
              gap: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(14f)): androidx.compose.ui.unit.Dp {
    val info = LocalLargeScreen.current ?: return phone
    return androidx.compose.ui.unit.Dp(LargeScreen.shelfCell(info.contentWidthDp, phone.value, edge.value, gap.value))
}
