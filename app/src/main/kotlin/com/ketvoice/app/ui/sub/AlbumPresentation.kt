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

    /**
     * The colour an album page takes: that of the Canvas while one plays in its hero (the
     * cover's until a frame of the Canvas has been read), that of the cover otherwise.
     */
    fun <T> pageAccent(canvasShown: Boolean, canvasAccent: T?, coverAccent: T?): T? =
        if (canvasShown) canvasAccent ?: coverAccent else coverAccent

    /**
     * Width over height of the artwork in the music player on a window of this size: on a phone the full
     * width over the tallest the artwork goes (down to the controls, 8.3), a square on a wide or lying window.
     */
    fun playerArtworkAspect(widthDp: Float, heightDp: Float): Float =
        if (widthDp >= 720f || widthDp >= heightDp || heightDp <= 0f) 1f
        else (widthDp / com.ketvoice.app.ui.player.PlayerArtworkSize.capDp(heightDp)).coerceIn(.5f, 1.6f)

    /**
     * The total length of an album, written in French as the page writes it (« 1 h 17 min », « 42 min »),
     * translated at display; null when no title knows its length.
     */
    fun totalDuration(tracks: List<com.ketvoice.app.data.Track>): String? {
        val total = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
        return when {
            total <= 0L -> null
            total >= 3_600_000L -> "${total / 3_600_000L} h ${(total % 3_600_000L) / 60_000L} min"
            else -> "${(total / 60_000L).coerceAtLeast(1L)} min"
        }
    }

    /**
     * The release date at the foot of an album page: a full date written as [locale] writes it
     * (« 31 octobre 2026 »), a year as it is; anything else is left out rather than shown raw.
     */
    fun releaseDate(date: String?, locale: java.util.Locale): String? {
        val value = date?.trim().orEmpty()
        if (Regex("(?:19|20)\\d{2}").matches(value)) return value
        return runCatching {
            java.time.LocalDate.parse(value.take(10)).format(
                java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale))
        }.getOrNull()
    }

    /** The other releases of the artist for the « Plus de … » shelf: not this album, at most twelve. */
    fun moreBy(albums: List<com.ketvoice.app.data.Album>, albumId: String, title: String): List<com.ketvoice.app.data.Album> =
        albums.filter { it.id != albumId && !it.title.trim().equals(title.trim(), ignoreCase = true) }.distinctBy { it.id }.take(12)

    fun sideBySide(widthDp: Float, fontScale: Float): Boolean =
        widthDp >= 720f && widthDp / fontScale.coerceAtLeast(1f) >= 540f
}
