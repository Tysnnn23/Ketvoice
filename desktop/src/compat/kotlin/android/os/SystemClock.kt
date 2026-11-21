package android.os

/** Monotonic clocks, as on Android (the JVM has no deep sleep to tell apart). */
object SystemClock {
    private val origin = System.nanoTime()

    @JvmStatic fun elapsedRealtime(): Long = (System.nanoTime() - origin) / 1_000_000L
    @JvmStatic fun uptimeMillis(): Long = elapsedRealtime()
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - origin
}
