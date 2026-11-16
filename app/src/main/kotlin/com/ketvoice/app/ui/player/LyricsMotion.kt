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
    
    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** The last moments before the first line, kept for the dots to leave: short intros keep a quarter of theirs. */
    fun exitMs(firstLineMs: Long): Long = (firstLineMs / 4).coerceIn(0L, 720L)

    /**
     * How full dot [index] (0..2) is, 0..1. The three fill one after the other, without steps,
     * and all are full when the exit starts: the wait is read at a glance.
     */
    fun dotFill(index: Int, positionMs: Long, firstLineMs: Long): Float {
        if (firstLineMs <= 0) return 1f
        val span = (firstLineMs - exitMs(firstLineMs)).coerceAtLeast(1L)
        val through = (positionMs.coerceAtLeast(0).toFloat() / span).coerceIn(0f, 1f) * 3f
        return smooth(through - index)
    }

    /** 0 until the dots start leaving, 1 when the first line starts. */
    fun dotExit(positionMs: Long, firstLineMs: Long): Float {
        val exit = exitMs(firstLineMs)
        if (firstLineMs <= 0 || exit <= 0) return 0f
        return ((positionMs - (firstLineMs - exit)).toFloat() / exit).coerceIn(0f, 1f)
    }

    /** The dots take a breath in, then shrink away as the line arrives. */
    fun exitScale(exit: Float): Float =
        if (exit < EXIT_PEAK) 1f + .24f * smooth(exit / EXIT_PEAK) else 1.24f - .74f * smooth((exit - EXIT_PEAK) / (1f - EXIT_PEAK))
    fun exitAlpha(exit: Float): Float = if (exit < EXIT_PEAK) 1f else 1f - smooth((exit - EXIT_PEAK) / (1f - EXIT_PEAK))
    private const val EXIT_PEAK = .42f

    /** A slow breath that travels from the first dot to the last: 1 at rest, a little more at its peak. */
    fun breath(elapsedMs: Long, index: Int): Float {
        val phase = (elapsedMs - index * 170L).toDouble() / BREATH_MS
        return 1f + .09f * (.5 - .5 * kotlin.math.cos(2.0 * Math.PI * phase)).toFloat()
    }
    private const val BREATH_MS = 1_700.0

    /** The dots grow into place when the intro appears. */
    fun enter(elapsedMs: Long): Float = smooth(elapsedMs / 340f)

    fun targetIndex(active: Int, timed: Boolean): Int = if (active < 0) 0 else active + if (timed) 1 else 0
}

/** Cascade, how lyrics are shown: how far the other lines step back and how the wave travels. */
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
