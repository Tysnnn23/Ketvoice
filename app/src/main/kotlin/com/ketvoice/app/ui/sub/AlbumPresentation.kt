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

    /**
     * Height of the artwork at the top of an album page: the full square of the cover
     * when the window allows it, never more than 46 % of the window nor 420 dp, so the
     * title and the Play button stay on the first screen.
     */
    fun heroHeight(widthDp: Float, windowHeightDp: Float): Float =
        minOf(widthDp, windowHeightDp * .46f).coerceIn(220f, 420f)

    /**
     * True when filling the width would cut away too much of a square cover: the cover
     * is then shown whole, centred over a blurred copy of itself.
     */
    fun showsWholeCover(widthDp: Float, heightDp: Float): Boolean = heightDp > 0f && widthDp / heightDp > 1.35f

    fun sideBySide(widthDp: Float, fontScale: Float): Boolean =
        widthDp >= 720f && widthDp / fontScale.coerceAtLeast(1f) >= 540f
}
