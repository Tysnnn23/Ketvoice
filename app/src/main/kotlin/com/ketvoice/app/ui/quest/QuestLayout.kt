package com.ketvoice.app.ui.quest

/** Keep the original phone UI readable, even when the Quest window is wide. */
data class QuestLayout(val widthDp: Float, val heightDp: Float) {
    val contentWidthDp: Float get() = widthDp.coerceAtMost(600f)
    val playerWidthDp: Float get() = minOf(widthDp, heightDp * .65f, 600f)
    companion object {
        fun lyricsScale(size: Int): Float = when (size.coerceIn(0, 2)) {
            0 -> 1f
            1 -> 1.16f
            else -> 1.32f
        }
    }
}
