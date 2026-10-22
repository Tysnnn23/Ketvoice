package com.ketvoice.app.ui.quest

/** Quest is a landscape window; keep the mobile styling, not a portrait frame. */
data class QuestLayout(val widthDp: Float, val heightDp: Float) {
    val contentWidthDp: Float get() = widthDp.coerceAtMost(1440f)
    val playerWidthDp: Float get() = widthDp
    companion object {
        const val CONTROL_PANE_FRACTION = .42f
        fun lyricsScale(size: Int): Float = when (size.coerceIn(0, 2)) {
            0 -> 1f
            1 -> 1.16f
            else -> 1.32f
        }
    }
}
