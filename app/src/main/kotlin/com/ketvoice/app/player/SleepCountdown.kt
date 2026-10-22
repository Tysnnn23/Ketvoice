package com.ketvoice.app.player

/** Monotonic clock arithmetic; wall-clock changes must not extend a timer. */
object SleepCountdown {
    fun remainingSeconds(deadlineMs: Long, nowMs: Long): Long =
        ((deadlineMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L
}
