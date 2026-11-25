package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min

/** The right panel's tabs the island's buttons open (kept for callers of 1.2: [Panel.QUEUE] and [Panel.LYRICS]). */
enum class Panel(val tab: String?) { NONE(null), QUEUE("queue"), LYRICS("lyrics"), ARTIST("artist"), KETMIX("ketmix") }

/**
 * The window's arrangement (Windows 2.0): a floating glass sidebar on the left, the pages edge to edge under it, the
 * player as a glass island at the bottom centre that unfolds into the full player, and a glass panel on the right
 * with tabs. Everything here is plain state, so tests drive it without a window.
 */
@Stable
class ShellState(panel: String? = null, fullPlayer: Boolean = false, panelWidth: Dp = ShellMetrics.PANEL_DEFAULT,
                 sidebarWidth: Dp = ShellMetrics.SIDEBAR_WIDE) {
    /** The right panel's open tab ([PanelTabs] id), or null when it is closed. */
    var panel by mutableStateOf(panel); private set
    var panelWidth by mutableStateOf(ShellMetrics.clampPanel(panelWidth)); private set
    /** The sidebar's width when the window is wide enough to show its labels. */
    var sidebarWidth by mutableStateOf(sidebarWidth.coerceIn(ShellMetrics.SIDEBAR_MIN, ShellMetrics.SIDEBAR_MAX)); private set
    var fullPlayer by mutableStateOf(fullPlayer)
    /** The pointer is over the island (or a seek is being dragged in it): it grows and shows its second row. */
    var islandOpen by mutableStateOf(false)
    /** Where the island is in the window, for the full player to unfold from. */
    var islandBounds by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)

    /** Opens [tab], or closes the panel when it already shows it. */
    fun toggle(tab: String) { panel = if (panel == tab) null else tab }
    fun open(tab: String?) { panel = tab }
    fun close() { panel = null }

    /** The panel's left edge dragged by [dx] (to the left makes it wider). */
    fun dragPanel(dx: Dp) { panelWidth = ShellMetrics.clampPanel(panelWidth - dx) }
    fun dragSidebar(dx: Dp) { sidebarWidth = (sidebarWidth + dx).coerceIn(ShellMetrics.SIDEBAR_MIN, ShellMetrics.SIDEBAR_MAX) }
}

/** The measures of the floating layout, by the window's width. */
object ShellMetrics {
    /** The margin between the window's edges and the floating glass. */
    val MARGIN = 12.dp
    /** Between the glass and the content beside it. */
    val GAP = 12.dp
    val SIDEBAR_WIDE = 248.dp
    val SIDEBAR_MIN = 216.dp
    val SIDEBAR_MAX = 320.dp
    /** The sidebar reduced to its icons. */
    val SIDEBAR_ICONS = 72.dp
    /** Below this window width the sidebar keeps only its icons. */
    val COLLAPSE_BELOW = 960.dp
    val PANEL_MIN = 300.dp
    val PANEL_MAX = 560.dp
    val PANEL_DEFAULT = 360.dp
    /** The island at rest, and the room the pages keep for it at their foot. */
    val ISLAND_HEIGHT = 68.dp
    val ISLAND_OPEN_HEIGHT = 112.dp
    val ISLAND_WIDTH = 680.dp
    /** The same width open: it grows upward only, so nothing moves sideways under the pointer. */
    val ISLAND_OPEN_WIDTH = ISLAND_WIDTH
    val ISLAND_FOOT = 16.dp

    fun clampPanel(w: Dp): Dp = w.coerceIn(PANEL_MIN, PANEL_MAX)

    fun collapsed(window: Dp): Boolean = window < COLLAPSE_BELOW

    fun sidebar(window: Dp, wide: Dp): Dp = if (collapsed(window)) SIDEBAR_ICONS else wide

    /** The panel's width in this window: never more than 40 % of it, so the pages keep room. */
    fun panel(window: Dp, wanted: Dp): Dp = min(clampPanel(wanted), max(PANEL_MIN, window * .4f))

    /** The island's width between the sidebar and the panel: [open] while hovered, never wider than the room. */
    fun island(room: Dp, open: Boolean): Dp = min(if (open) ISLAND_OPEN_WIDTH else ISLAND_WIDTH, room - GAP * 2).coerceAtLeast(320.dp)

    /** Where the pages' content goes so nothing hides under the glass (the pages themselves run under it, edge to edge). */
    fun insets(window: Dp, wide: Dp, panelOpen: Boolean, panelWanted: Dp): PaddingValues = PaddingValues(
        start = MARGIN + sidebar(window, wide) + GAP,
        end = if (panelOpen) MARGIN + panel(window, panelWanted) + GAP else 0.dp,
        bottom = ISLAND_HEIGHT + ISLAND_FOOT * 2,
    )
}

/** What the pages keep clear of: the sidebar, the open panel and the island (see [ShellMetrics.insets]). */
val LocalShellInsets = staticCompositionLocalOf { PaddingValues(0.dp) }

/** The shell's state, for the island, the panel's tabs and the pages that open them. */
val LocalShell = staticCompositionLocalOf { ShellState() }
