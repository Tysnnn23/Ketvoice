package com.ketvoice.desktop.platform

import org.json.JSONObject
import java.io.File

/**
 * The main window's last floating bounds (in dp, as Compose's WindowState and AWT's scaled
 * coordinates use them, so a display-scale change keeps the same visual size) and whether it was
 * maximised. Kept in `Data\window.json`.
 */
data class SavedWindow(
    val x: Float? = null,
    val y: Float? = null,
    val width: Float = DEFAULT_WIDTH,
    val height: Float = DEFAULT_HEIGHT,
    val maximized: Boolean = false,
) {
    companion object {
        const val DEFAULT_WIDTH = 1180f
        const val DEFAULT_HEIGHT = 760f
        const val MIN_WIDTH = 720f
        const val MIN_HEIGHT = 480f
    }
}

/** A screen's usable area, in the same units. */
data class ScreenArea(val x: Float, val y: Float, val width: Float, val height: Float)

object WindowStore {
    fun file(dataDir: File = android.content.Context.app.dataDir) = File(dataDir, "window.json")

    fun load(file: File = file()): SavedWindow = runCatching {
        val o = JSONObject(file.readText())
        SavedWindow(
            x = o.optDouble("x").takeUnless { it.isNaN() }?.toFloat(),
            y = o.optDouble("y").takeUnless { it.isNaN() }?.toFloat(),
            width = o.optDouble("width", SavedWindow.DEFAULT_WIDTH.toDouble()).toFloat(),
            height = o.optDouble("height", SavedWindow.DEFAULT_HEIGHT.toDouble()).toFloat(),
            maximized = o.optBoolean("maximized"),
        )
    }.getOrDefault(SavedWindow())

    fun save(window: SavedWindow, file: File = file()) {
        runCatching {
            val o = JSONObject().put("width", window.width.toDouble()).put("height", window.height.toDouble()).put("maximized", window.maximized)
            window.x?.let { o.put("x", it.toDouble()) }
            window.y?.let { o.put("y", it.toDouble()) }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { AppLog.w("window", "save: $it") }
    }

    /**
     * The state to remember after a change. Only a floating window's bounds are kept, so leaving
     * maximised (or minimised) restores the size it had before.
     */
    fun merge(previous: SavedWindow, floating: Boolean, maximized: Boolean, x: Float?, y: Float?, width: Float, height: Float): SavedWindow =
        if (floating && width > 0 && height > 0) SavedWindow(x, y, width, height, maximized = false)
        else previous.copy(maximized = maximized)

    /**
     * Fits a saved window to the screens there are now: a size no larger than the biggest screen and
     * no smaller than the minimum, and no position (centred) when its title bar would be off-screen,
     * for example after a monitor was unplugged.
     */
    fun fit(saved: SavedWindow, screens: List<ScreenArea>): SavedWindow {
        if (screens.isEmpty()) return saved
        val maxW = screens.maxOf { it.width }
        val maxH = screens.maxOf { it.height }
        val w = saved.width.coerceIn(SavedWindow.MIN_WIDTH.coerceAtMost(maxW), maxW)
        val h = saved.height.coerceIn(SavedWindow.MIN_HEIGHT.coerceAtMost(maxH), maxH)
        val x = saved.x
        val y = saved.y
        val placed = x != null && y != null && screens.any { s ->
            // At least 120 × 32 of the title bar's strip must land on one screen.
            val left = maxOf(x, s.x); val right = minOf(x + w, s.x + s.width)
            val top = maxOf(y, s.y); val bottom = minOf(y + 32f, s.y + s.height)
            right - left >= 120f && bottom - top >= 32f
        }
        return if (placed) saved.copy(width = w, height = h) else saved.copy(x = null, y = null, width = w, height = h)
    }

    /** The screens' usable areas from AWT (taskbar excluded). */
    fun awtScreens(): List<ScreenArea> = runCatching {
        val env = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        if (java.awt.GraphicsEnvironment.isHeadless()) return emptyList()
        env.screenDevices.map { d ->
            val c = d.defaultConfiguration
            val b = c.bounds
            val i = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(c)
            ScreenArea((b.x + i.left).toFloat(), (b.y + i.top).toFloat(), (b.width - i.left - i.right).toFloat(), (b.height - i.top - i.bottom).toFloat())
        }
    }.getOrDefault(emptyList())
}
