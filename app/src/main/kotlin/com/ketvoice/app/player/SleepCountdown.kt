package com.ketvoice.app.player

object SleepCountdown {
    fun remainingSeconds(deadlineMs: Long, nowMs: Long): Long =
        ((deadlineMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L
}
