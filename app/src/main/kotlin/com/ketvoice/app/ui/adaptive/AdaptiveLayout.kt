package com.ketvoice.app.ui.adaptive

import androidx.compose.runtime.staticCompositionLocalOf

data class AdaptiveLayout(val widthDp: Float, val heightDp: Float, val preferPhone: Boolean = false) {
    val hasRail: Boolean get() = !preferPhone && widthDp >= 600f && heightDp >= 480f
    val expandedRail: Boolean get() = hasRail && widthDp >= 1200f
    val railWidthDp: Float get() = if (!hasRail) 0f else if (expandedRail) 184f else 104f
    val contentWidthDp: Float get() = (widthDp - railWidthDp).coerceAtMost(1280f)
    val bottomPaddingDp: Float get() = if (hasRail) 112f else 205f
    val trackColumns: Int get() = if (!preferPhone && contentWidthDp >= 760f) 2 else 1
    val collectionSizeDp: Float get() = if (hasRail) 176f else 142f
    val recentPageWidthDp: Float get() = if (hasRail) ((contentWidthDp - 52f) / trackColumns).coerceAtMost(420f)
        else (contentWidthDp - 38f).coerceAtLeast(180f)
    val adaptivePlayer: Boolean get() = !preferPhone && widthDp >= 600f && heightDp >= 480f
    val playerPanes: Boolean get() = adaptivePlayer &&
        (widthDp >= 720f || (widthDp >= 640f && widthDp > heightDp))
}

val LocalAdaptiveLayout = staticCompositionLocalOf { AdaptiveLayout(400f, 800f) }
