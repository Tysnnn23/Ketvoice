package com.ketvoice.app.player

/** Pure rules of the sleep timer, kept apart from the timer so they run without Android. */
object SleepCountdown {
    /** Delays offered by the menu, in minutes. */
    val CHOICES = listOf(15, 30, 45, 60)
    /** Length of the fade before the pause. */
    const val FADE_MS = 8_000L

    fun remainingSeconds(deadlineMs: Long, nowMs: Long): Long =
        ((deadlineMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L

    /** Gain of the fade for the time left before the pause: 1 until the fade starts, 0 at the end. */
    fun fadeGain(leftMs: Long, fadeMs: Long = FADE_MS): Float =
        if (fadeMs <= 0L) 1f else (leftMs.toFloat() / fadeMs).coerceIn(0f, 1f)
}
