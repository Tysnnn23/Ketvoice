package com.ketvoice.app.ui.sub

/** Geometry is density-independent except the measured scroll offsets, which share one px space. */
internal object AlbumPresentation {
    fun smoothProgress(value: Float): Float {
        val p = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        return p * p * (3f - 2f * p)
    }

    fun collapse(heroOffsetPx: Int?, heroSizePx: Int, firstVisibleIndex: Int): Float {
        if (heroOffsetPx == null) return if (firstVisibleIndex > 0) 1f else 0f
        return smoothProgress(-heroOffsetPx.toFloat() / (heroSizePx * .72f).coerceAtLeast(1f))
    }

    // Translate inside the existing crop, never enlarge an album cover to manufacture parallax.
    fun artworkTranslation(scrollPx: Float, artworkHeightPx: Float, reduced: Boolean): Float {
        if (reduced || !scrollPx.isFinite() || !artworkHeightPx.isFinite()) return 0f
        return (scrollPx.coerceAtLeast(0f) * .06f).coerceAtMost(artworkHeightPx.coerceAtLeast(0f) * .035f)
    }

    fun sideBySide(widthDp: Float, fontScale: Float): Boolean =
        widthDp >= 720f && widthDp / fontScale.coerceAtLeast(1f) >= 540f
}
