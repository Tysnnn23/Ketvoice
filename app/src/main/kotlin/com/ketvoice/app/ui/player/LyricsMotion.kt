package com.ketvoice.app.ui.player

object LyricsMotion {
    fun showIntro(positionMs: Long, firstLineMs: Long): Boolean = firstLineMs > 0 && positionMs < firstLineMs
    
    /**
     * Returns [0..3] lit dot count based on time progression into intro.
     * The intro is only shown while position < firstLineMs, so thresholds sit at
     * 25% / 50% / 75%: all three dots are lit before the first line starts.
     */
    fun litDots(positionMs: Long, firstLineMs: Long): Int = if (firstLineMs <= 0) 3 else
        ((positionMs.coerceAtLeast(0).toDouble() / firstLineMs) * 4).toInt().coerceIn(0, 3)
    
    /** Returns [0.0..1.0] normalized intro progression. */
    fun introProgress(positionMs: Long, firstLineMs: Long): Float = if (firstLineMs <= 0) 1f else
        (positionMs.coerceAtLeast(0).toFloat() / firstLineMs).coerceIn(0f, 1f)
    
    fun targetIndex(active: Int, timed: Boolean): Int = if (active < 0) 0 else active + if (timed) 1 else 0
}

/** The Cascade lyrics style: how far the other lines step back and how the wave travels. */
internal object LyricsCascade {
    const val INACTIVE_ALPHA = .32f
    const val INACTIVE_SCALE = .955f
    /** Lines further below start a little lower, up to four lines away. */
    fun waveStartDp(distance: Int): Float = 6f * distance.coerceIn(0, 4)
    /** Each line follows the one above after a short delay, so the block moves like a wave. */
    fun waveDelayMs(distance: Int): Long = 26L * distance.coerceIn(0, 5)
    const val WAVE_MS = 560
    val GLIDE = androidx.compose.animation.core.CubicBezierEasing(.2f, .8f, .2f, 1f)
    /** A line before the one being sung has been sung. */
    fun sung(index: Int, active: Int): Boolean = active >= 0 && index < active
}
