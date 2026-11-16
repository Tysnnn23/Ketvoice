package com.ketvoice.app.ui.chrome

import androidx.compose.runtime.mutableStateOf

/**
 * The floating search of phones (6.0.0, after Apple Music on iOS 26/27): Search is a glass bubble at the
 * bottom right beside the tab bar, and a touch stretches it into the search field itself, which stays
 * at the bottom (above the keyboard) while the search page shows the results. Tablets, large windows
 * and Quest keep the field at the top of the page.
 */
internal object FloatingSearch {
    /** What is typed in the bottom field; the search page reads and writes the same text. */
    val query = mutableStateOf("")

    /** A touch on the bubble asks the field to take the focus once it has stretched. */
    val focusAsked = mutableStateOf(false)

    /** What the search page does when the keyboard's search key is pressed (set while it is shown). */
    @Volatile var onSubmit: (() -> Unit)? = null

    /** Only phone layouts: never a rail, a sidebar or Quest's window. */
    fun applies(hasRail: Boolean, quest: Boolean): Boolean = !hasRail && !quest

    /** The bubble was touched: the search page opens and its field stretches out of the bubble. */
    fun open() {
        focusAsked.value = true
        com.ketvoice.app.ui.AppUi.openSearch()
    }

    /** Geometry of the bubble for a stretch of 0 (a circle at the right) to 1 (the field), in dp. */
    data class Bubble(val x: Float, val width: Float, val height: Float)

    /**
     * At rest the bubble is a [restSide] circle at the right edge; stretched, it spans from just after the
     * folded tab pill ([pillEnd]) to the right margin. Both ends move on the same curve, so the left edge
     * leaves while the right one stays put, as on iOS.
     */
    fun bubble(stretch: Float, availableWidth: Float, restSide: Float, restX: Float, pillEnd: Float,
               margin: Float = 12f, fieldHeight: Float = 52f): Bubble {
        val s = if (stretch.isFinite()) stretch.coerceIn(0f, 1.08f) else 0f
        val fieldWidth = (availableWidth - margin - pillEnd).coerceAtLeast(restSide)
        fun lerp(a: Float, b: Float) = a + (b - a) * s
        return Bubble(lerp(restX, pillEnd), lerp(restSide, fieldWidth), lerp(restSide, fieldHeight).coerceAtLeast(1f))
    }
}
