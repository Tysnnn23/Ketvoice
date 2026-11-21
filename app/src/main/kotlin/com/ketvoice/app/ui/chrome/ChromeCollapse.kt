package com.ketvoice.app.ui.chrome

/**
 * When the floating bar folds and unfolds. Scrolling down folds it; scrolling back up a
 * little unfolds it again wherever the page is, the way iOS lets the tab bar return
 * without going back to the top. Nothing changes until the finger has really travelled,
 * so a small jitter never flips it.
 */
internal class ChromeCollapse(private val foldAfterPx: Int = 36, private val unfoldAfterPx: Int = 56,
                              private val topPx: Int = 24, private val rowPx: Int = 64) {
    private var lastIndex = -1
    private var lastOffset = 0
    private var travel = 0
    private var lastNanos = 0L
    var collapsed = false
        private set
    private var seenRestore = restoreCount

    /** A touch on the folded bar unfolds it where the page is; the next fold needs a new travel down. */
    fun restore() { collapsed = false; travel = 0 }

    /** Feeds the first visible item and its scroll offset; returns whether the bar is folded. */
    fun update(index: Int, offset: Int): Boolean {
        if (seenRestore != restoreCount) { seenRestore = restoreCount; restore() }
        if (index == 0 && offset <= topPx) {
            collapsed = false; travel = 0
        } else if (lastIndex >= 0) {
            val step = when {
                index == lastIndex -> offset - lastOffset
                index > lastIndex -> (index - lastIndex) * rowPx
                else -> -(lastIndex - index) * rowPx
            }
            // 8.0: how fast the page moves (px/s, smoothed), so the bar folds with the scroll's momentum.
            val now = System.nanoTime()
            val dt = (now - lastNanos) / 1e9f
            scrollVelocity = if (step != 0 && dt in .001f..MAX_FRAME_GAP_S) scrollVelocity * .5f + step / dt * .5f
                else if (dt > MAX_FRAME_GAP_S) 0f else scrollVelocity
            // A change of direction starts the count again.
            travel = if (step == 0) travel else if ((step > 0) == (travel > 0) || travel == 0) travel + step else step
            if (!collapsed && travel >= foldAfterPx) { collapsed = true; travel = 0 }
            else if (collapsed && -travel >= unfoldAfterPx) { collapsed = false; travel = 0 }
        }
        lastIndex = index; lastOffset = offset; lastNanos = System.nanoTime()
        return collapsed
    }

    companion object {
        /** Counts the touches that unfolded the bar; every page's fold starts its travel again from there. */
        @Volatile var restoreCount = 0
            private set

        /** The page's latest scroll speed in px/s, positive downwards (read once when the bar starts to move). */
        @Volatile var scrollVelocity = 0f
            private set

        private const val MAX_FRAME_GAP_S = .25f
        /** The most the scroll may push the fold, in fractions of the whole fold per second. */
        const val MAX_FLING = 6f

        /**
         * The speed the fold starts with (fractions per second) when it heads to [target] (1 folded, 0 open): the
         * page's own speed over the bar's travel, only when it goes the same way (down folds, up unfolds), so a
         * quick flick folds the bar quickly and a slow scroll gently. A touch on the bar adds nothing.
         */
        fun flingVelocity(target: Float, scrollPxPerSecond: Float, travelPx: Float): Float {
            if (!scrollPxPerSecond.isFinite() || travelPx <= 0f) return 0f
            val v = (scrollPxPerSecond / travelPx).coerceIn(-MAX_FLING, MAX_FLING)
            return if (target >= .5f && v > 0f || target < .5f && v < 0f) v else 0f
        }

        /** The bar unfolds now (a touch on it), whatever the page's scroll. */
        fun restoreAll() { restoreCount++; scrollVelocity = 0f; com.ketvoice.app.ui.AppUi.bottomCollapsed.value = false }

        /**
         * Whether the floating bar shows folded: only when the listener turned it on (Settings → Appearance,
         * "Barre d'onglets réductible", off by default), a tab page has scrolled down, and no page is on top.
         */
        fun folded(enabled: Boolean, scrolledDown: Boolean, overlay: Int): Boolean =
            enabled && scrolledDown && overlay == com.ketvoice.app.ui.AppUi.NONE
    }
}
