package com.ketvoice.desktop.platform

import androidx.compose.runtime.withFrameNanos

/**
 * Animations at the monitor's own rate (Windows 2.0). Compose draws when skiko's frame dispatcher asks, and with
 * `skiko.vsync.enabled` (set by [Startup.configureRendering]) Direct3D presents on the display's vertical blank: a
 * 144 Hz screen gets 144 frames a second. Nothing in the window paces itself on a timer: every animation reads the
 * frame clock and steps by the time that passed (see `ui/Fluid.kt`), so it is as smooth as the screen allows. When
 * vsync cannot be used, skiko's own limiter follows the display's refresh rate too (`layerFrameLimiter`), never 60.
 *
 * [probe] measures the real rate once after start and logs it beside the display's rate, so a cap shows in the log.
 */
object FrameRate {
    /** The display's refresh rate in Hz, or 0 when Windows does not say. */
    fun displayHz(window: java.awt.Window?): Int = runCatching {
        val device = window?.graphicsConfiguration?.device
            ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        device.displayMode.refreshRate.takeIf { it != java.awt.DisplayMode.REFRESH_RATE_UNKNOWN } ?: 0
    }.getOrDefault(0)

    /** Frames per second from consecutive frame times (ns), the median interval (a dropped frame does not skew it). */
    fun rate(frames: List<Long>): Double {
        if (frames.size < 3) return 0.0
        val gaps = frames.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        if (gaps.isEmpty()) return 0.0
        return 1e9 / gaps[gaps.size / 2]
    }

    /** What the log says: the measured rate against the display's, and whether something holds it back. */
    fun verdict(measured: Double, displayHz: Int): String {
        val m = "%.1f".format(java.util.Locale.ROOT, measured)
        if (displayHz <= 0) return "frames $m/s (display rate unknown)"
        val capped = measured < displayHz * .9 && displayHz > 60 && measured in 55.0..65.0
        return when {
            capped -> "frames $m/s on a $displayHz Hz display: held at 60"
            measured >= displayHz * .9 -> "frames $m/s on a $displayHz Hz display: at the display's rate"
            else -> "frames $m/s on a $displayHz Hz display: below it (a busy machine or a frame budget missed)"
        }
    }

    /** Asks for [count] frames in a row and returns their rate (a short burst, once, after start). */
    suspend fun probe(count: Int = 90): Double {
        val times = ArrayList<Long>(count)
        repeat(count) { withFrameNanos { times += it } }
        return rate(times)
    }

    /** The skiko properties that would cap the rate, as set for this run. */
    fun settings(): String = "vsync ${System.getProperty("skiko.vsync.enabled")}, " +
        "fallback limit ${System.getProperty("skiko.vsync.framelimit.fallback.enabled") ?: "default"}"
}
