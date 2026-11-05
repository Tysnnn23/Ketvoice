package com.ketvoice.app.ui.adaptive

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Material3-style window size tiers, refined for Ketvoice's device matrix:
 * phones, foldables (inner/outer), tablets, Quest (landscape 2D window).
 */
enum class WindowTier { Compact, Medium, Expanded, Wide }

data class AdaptiveLayout(val widthDp: Float, val heightDp: Float, val preferPhone: Boolean = false) {

    /** Coarse window tier derived from the shortest width breakpoint chain. */
    val tier: WindowTier
        get() = when {
            widthDp < 600f -> WindowTier.Compact     // portrait phone, small fold outer
            widthDp < 840f -> WindowTier.Medium      // large phone landscape, fold inner
            widthDp < 1200f -> WindowTier.Expanded   // tablet portrait
            else -> WindowTier.Wide                  // tablet landscape, desktop, Quest
        }

    /** Whether the window is short (landscape phone) — squeezes vertical chrome. */
    val shortWindow: Boolean get() = heightDp < 480f

    /** Whether the window is roughly square (foldable inner, Quest). */
    val nearSquare: Boolean get() = !shortWindow && heightDp > 0f &&
        (widthDp / heightDp) in 0.85f..1.35f

    val hasRail: Boolean get() = !preferPhone && widthDp >= 600f && heightDp >= 480f
    val expandedRail: Boolean get() = hasRail && widthDp >= 1200f
    val railWidthDp: Float get() = if (!hasRail) 0f else if (expandedRail) 184f else 104f

    /** Reading-width cap: long text never sprawls on tablets/desktop. */
    val contentWidthDp: Float get() = (widthDp - railWidthDp).coerceAtMost(1280f)

    /** Compact vertical chrome when the window is short (landscape phone). */
    val bottomPaddingDp: Float get() = when {
        hasRail -> 112f
        shortWindow -> 132f
        else -> 205f
    }

    val trackColumns: Int
        get() = when {
            preferPhone -> 1
            contentWidthDp >= 1120f -> 3
            contentWidthDp >= 760f -> 2
            else -> 1
        }

    val collectionSizeDp: Float get() = when {
        hasRail -> 176f
        shortWindow -> 120f
        else -> 142f
    }

    val recentPageWidthDp: Float
        get() = if (hasRail) ((contentWidthDp - 52f) / trackColumns).coerceAtMost(420f)
        else (contentWidthDp - 38f).coerceAtLeast(180f)

    val adaptivePlayer: Boolean get() = !preferPhone && widthDp >= 600f && heightDp >= 480f

    /** Two-pane player (artwork/controls + lyrics/queue) only when width truly allows. */
    val playerPanes: Boolean
        get() = adaptivePlayer && (widthDp >= 720f || (widthDp >= 640f && widthDp > heightDp))

    /** Maximum width for a settings/content column, keeping line length readable. */
    val readingWidthDp: Float get() = contentWidthDp.coerceAtMost(720f)

    /** Horizontal page gutter, wider on large windows. */
    val pageGutterDp: Float get() = when (tier) {
        WindowTier.Compact -> 16f
        WindowTier.Medium -> 20f
        WindowTier.Expanded -> 24f
        WindowTier.Wide -> 32f
    }

    /** Whether system bars / navigation pill should be given extra breathing room. */
    val generousInsets: Boolean get() = tier != WindowTier.Compact || shortWindow
}

val LocalAdaptiveLayout = staticCompositionLocalOf { AdaptiveLayout(400f, 800f) }
