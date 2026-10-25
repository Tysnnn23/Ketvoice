package com.ketvoice.app.ui.player

object LyricsMotion {
    fun showIntro(positionMs: Long, firstLineMs: Long): Boolean = firstLineMs > 0 && positionMs < firstLineMs
    fun litDots(positionMs: Long, firstLineMs: Long): Int = if (firstLineMs <= 0) 3 else
        (1 + (positionMs.coerceAtLeast(0).toDouble() / firstLineMs * 3).toInt()).coerceIn(1, 3)
    fun targetIndex(active: Int, timed: Boolean): Int = if (active < 0) 0 else active + if (timed) 1 else 0
}
