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
    var collapsed = false
        private set

    /** Feeds the first visible item and its scroll offset; returns whether the bar is folded. */
    fun update(index: Int, offset: Int): Boolean {
        if (index == 0 && offset <= topPx) {
            collapsed = false; travel = 0
        } else if (lastIndex >= 0) {
            val step = when {
                index == lastIndex -> offset - lastOffset
                index > lastIndex -> (index - lastIndex) * rowPx
                else -> -(lastIndex - index) * rowPx
            }
            // A change of direction starts the count again.
            travel = if (step == 0) travel else if ((step > 0) == (travel > 0) || travel == 0) travel + step else step
            if (!collapsed && travel >= foldAfterPx) { collapsed = true; travel = 0 }
            else if (collapsed && -travel >= unfoldAfterPx) { collapsed = false; travel = 0 }
        }
        lastIndex = index; lastOffset = offset
        return collapsed
    }
}
