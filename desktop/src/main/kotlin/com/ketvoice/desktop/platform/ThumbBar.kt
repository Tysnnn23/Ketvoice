package com.ketvoice.desktop.platform

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ketvoice.app.data.AppText
import com.ketvoice.app.player.PlayerUiState
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.Executors

/**
 * « Boutons dans la miniature »: previous, play/pause and next under the window's taskbar thumbnail (ITaskbarList3, in
 * ketvoice_smtc.dll), and the cover as that thumbnail while the window is minimised. Windows only; the DLL does the
 * Win32 part on the window's own thread.
 */
class ThumbBar internal constructor(private val lib: SmtcLibrary) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ketvoice-thumbbar").apply { isDaemon = true } }
    private var callback: SmtcCallback? = null // kept reachable: JNA would free a collected callback
    @Volatile private var started = false

    internal fun start(hwnd: Pointer, iconSize: Int, onCommand: (MediaCommand) -> Unit): Boolean {
        val cb = SmtcCallback { code, value ->
            MediaCommands.fromNative(code, value)?.let { cmd -> runCatching { onCommand(cmd) }.onFailure { AppLog.e("thumbbar", "command $cmd", it) } }
        }
        callback = cb
        val icons = ThumbIcons.render(iconSize, ThumbIcons.glyphColor(ThumbIcons.lightTaskbar()))
        val hr = runCatching { worker.submit<Int> { lib.kv_thumb_init(hwnd, cb, iconSize, icons) }.get() }
            .onFailure { AppLog.w("thumbbar", "kv_thumb_init: $it") }.getOrDefault(-1)
        if (hr != 0) AppLog.w("thumbbar", "kv_thumb_init: 0x%08X".format(hr))
        started = hr == 0
        return started
    }

    fun update(playing: Boolean, enabled: Boolean) {
        if (!started) return
        val tips = Tips.of(playing)
        worker.execute { runCatching { lib.kv_thumb_update(if (playing) 1 else 0, if (enabled) 1 else 0, WString(tips.previous), WString(tips.toggle), WString(tips.next)) } }
    }

    /** [argb] null clears it (the window's own picture comes back). */
    fun cover(argb: IntArray?, width: Int, height: Int) {
        if (!started) return
        worker.execute { runCatching { lib.kv_thumb_cover(argb, width, height) } }
    }

    fun stop() {
        if (started) runCatching { worker.submit { lib.kv_thumb_shutdown() }.get() }
        started = false
        worker.shutdown()
        callback = null
    }

    internal data class Tips(val previous: String, val toggle: String, val next: String) {
        companion object {
            fun of(playing: Boolean) = Tips(AppText.t("Précédent"), AppText.t(if (playing) "Pause" else "Lire"), AppText.t("Suivant"))
        }
    }

    companion object {
        private const val E_INVALIDARG = 0x80070057.toInt()
        /** Largest side of the cover handed to the DLL (DWM asks for about 200 px; the peek is scaled from it too). */
        const val COVER_SIDE = 320

        /** The bar on [hwnd], or null off Windows, without the DLL, or when the taskbar refuses it. */
        fun start(hwnd: Pointer?, scale: Double, onCommand: (MediaCommand) -> Unit): ThumbBar? {
            if (!Os.isWindows || hwnd == null) return null
            val lib = SmtcNative.library() ?: return null
            val bar = ThumbBar(lib)
            // The small-icon size at the window's DPI (16 px at 100 %).
            val size = (16 * scale).toInt().coerceIn(16, 64)
            return if (runCatching { bar.start(hwnd, size, onCommand) }.getOrDefault(false)) bar else { bar.stop(); null }
        }

        /** Keeps the buttons and the cover in step with the player (tooltips follow the app language). */
        fun bind(scope: CoroutineScope, bar: ThumbBar, state: StateFlow<PlayerUiState>, loadCover: suspend (String?) -> ImageBitmap?) {
            scope.launch {
                state.map { Triple(it.isPlaying, it.current != null, Tips.of(it.isPlaying)) }.distinctUntilChanged().collect { (playing, has, _) ->
                    bar.update(playing, has)
                }
            }
            scope.launch {
                state.map { it.current?.coverUrl }.distinctUntilChanged().collect { url ->
                    val pixels = loadCover(url)?.let { runCatching { coverPixels(it) }.getOrNull() }
                    if (pixels == null) bar.cover(null, 0, 0) else bar.cover(pixels.first, pixels.second, pixels.third)
                }
            }
        }

        /** The cover reduced to at most [COVER_SIDE] a side, as ARGB pixels (width, height). */
        fun coverPixels(image: ImageBitmap, max: Int = COVER_SIDE): Triple<IntArray, Int, Int> {
            val scale = minOf(1f, max.toFloat() / maxOf(image.width, image.height))
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val small = ImageBitmap(w, h)
            Canvas(small).drawImageRect(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset.Zero, IntSize(w, h),
                Paint().apply { filterQuality = androidx.compose.ui.graphics.FilterQuality.High })
            val pixels = IntArray(w * h)
            small.readPixels(pixels)
            return Triple(pixels, w, h)
        }

        /** `--smoke`: the new exports are in the packaged DLL (an init without a window is refused as it should be). */
        internal fun smoke(lib: SmtcLibrary, say: (String) -> Unit): Boolean = try {
            val hr = lib.kv_thumb_init(null, null, 16, null)
            if (hr != E_INVALIDARG) { say("thumbbar failed: init without a window returned 0x%08X".format(hr)); false }
            else { lib.kv_thumb_shutdown(); say("thumbbar ok (dll exports present)"); true }
        } catch (e: UnsatisfiedLinkError) {
            say("thumbbar failed: $e"); false
        }
    }
}

/** The three buttons' glyphs, drawn as KetIcons' solid transport symbols at the taskbar's icon size. */
object ThumbIcons {
    const val PREVIOUS = 0; const val PLAY = 1; const val PAUSE = 2; const val NEXT = 3

    /** Light taskbar (Settings › Personalisation › Colours › Windows mode = light): dark glyphs. */
    fun lightTaskbar(): Boolean = Os.isWindows && runCatching {
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "SystemUsesLightTheme") == 1
    }.getOrDefault(false)

    fun glyphColor(lightTaskbar: Boolean): Int = if (lightTaskbar) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    /** Previous, play, pause and next: 4 × [size]² ARGB pixels (not premultiplied), top row first. */
    fun render(size: Int, argb: Int): IntArray {
        val out = IntArray(4 * size * size)
        for (i in 0 until 4) {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = java.awt.Color(argb, true)
            g.scale(size / 24.0, size / 24.0)
            when (i) {
                PREVIOUS -> { g.fill(triangle(12.5, 6.0, 12.5, 18.0, 3.0, 12.0)); g.fill(triangle(21.0, 6.0, 21.0, 18.0, 11.5, 12.0)) }
                PLAY -> g.fill(triangle(7.0, 4.5, 7.0, 19.5, 19.5, 12.0))
                PAUSE -> { g.fill(RoundRectangle2D.Double(6.0, 5.0, 4.2, 14.0, 1.6, 1.6)); g.fill(RoundRectangle2D.Double(13.8, 5.0, 4.2, 14.0, 1.6, 1.6)) }
                NEXT -> { g.fill(triangle(3.0, 6.0, 3.0, 18.0, 12.5, 12.0)); g.fill(triangle(11.5, 6.0, 11.5, 18.0, 21.0, 12.0)) }
            }
            g.dispose()
            image.getRGB(0, 0, size, size, out, i * size * size, size)
        }
        return out
    }

    private fun triangle(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
        Path2D.Double().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); closePath() }
}
