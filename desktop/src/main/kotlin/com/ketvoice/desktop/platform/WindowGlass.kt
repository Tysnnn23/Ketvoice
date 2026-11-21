package com.ketvoice.desktop.platform

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

/**
 * « Verre Windows 11 »: the system's Mica behind the whole window (DWM `DWMWA_SYSTEMBACKDROP_TYPE`), seen through a
 * transparent Compose surface; the shell then draws its pages on a light layer and the sidebar and top bar as
 * Acrylic over it ([com.ketvoice.desktop.ui.theme.KetColors.glass]). Anything short of that keeps today's solid
 * colours: Windows 10, transparency effects off, high contrast, the setting off, or a DWM call that fails.
 */
object WindowGlass {
    enum class Backdrop {
        /** Windows 11 22H2 and later: `DWMWA_SYSTEMBACKDROP_TYPE` = `DWMSBT_MAINWINDOW`. */
        MICA,
        /** Windows 11 21H2 (builds 22000–22620): the undocumented `DWMWA_MICA_EFFECT` it had before. */
        MICA_LEGACY,
        NONE,
    }

    /** What decides it, read from Windows (or given by tests). */
    data class SystemLook(val windows: Boolean, val build: Int, val transparencyEffects: Boolean, val highContrast: Boolean)

    const val BUILD_MICA = 22621
    const val BUILD_WINDOWS_11 = 22000

    fun choose(look: SystemLook, enabled: Boolean): Backdrop = when {
        !enabled || !look.windows || look.highContrast || !look.transparencyEffects -> Backdrop.NONE
        look.build >= BUILD_MICA -> Backdrop.MICA
        look.build >= BUILD_WINDOWS_11 -> Backdrop.MICA_LEGACY
        else -> Backdrop.NONE
    }

    private val _active = mutableStateOf(false)
    /** True while Mica really shows through the window: the shell draws its glass colours only then. */
    val active: State<Boolean> get() = _active

    // ── The setting (Settings › Windows), on by default ──
    private val prefs by lazy { android.content.Context.app.getSharedPreferences("desktop", android.content.Context.MODE_PRIVATE) }
    private val _enabled by lazy { mutableStateOf(runCatching { prefs.getBoolean("windowGlass", true) }.getOrDefault(true)) }
    val enabled: State<Boolean> get() = _enabled
    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean("windowGlass", on).apply()
    }

    fun readSystem(): SystemLook {
        if (!Os.isWindows) return SystemLook(false, 0, false, false)
        val build = runCatching {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "CurrentBuildNumber").trim().toInt()
        }.getOrDefault(0)
        // Settings › Personalisation › Colours › « Effets de transparence » (missing means on).
        val transparency = runCatching {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "EnableTransparency") != 0
        }.getOrDefault(true)
        val contrast = runCatching { java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on") == true }.getOrDefault(false)
        return SystemLook(true, build, transparency, contrast)
    }

    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: IntByReference, size: Int): Int
        fun DwmExtendFrameIntoClientArea(hwnd: HWND, margins: Margins): Int
    }

    @Suppress("unused")
    @Structure.FieldOrder("left", "right", "top", "bottom")
    class Margins(@JvmField var left: Int = 0, @JvmField var right: Int = 0, @JvmField var top: Int = 0, @JvmField var bottom: Int = 0) : Structure()

    private val dwm: Dwm? by lazy { if (Os.isWindows) runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() else null }

    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
    private const val DWMWA_MICA_EFFECT = 1029
    private const val DWMSBT_NONE = 1
    private const val DWMSBT_MAINWINDOW = 2

    /**
     * Puts the window's backdrop in step with the system and the setting; call it again when the theme, the
     * setting or the system's transparency changes. Returns whether Mica shows ([active]).
     */
    fun apply(window: java.awt.Window, look: SystemLook = readSystem()): Boolean {
        val wanted = choose(look, enabled.value)
        val on = if (wanted == Backdrop.NONE) false else runCatching { turnOn(window, wanted) }.onFailure { AppLog.w("glass", "Mica: $it") }.getOrDefault(false)
        if (!on) runCatching { turnOff(window, wanted == Backdrop.NONE && _active.value) }
        if (on != _active.value) AppLog.i("glass", if (on) "Mica on (${wanted.name.lowercase()}, build ${look.build})" else "solid window ($wanted, $look)")
        _active.value = on
        return on
    }

    private fun turnOn(window: java.awt.Window, kind: Backdrop): Boolean {
        val api = dwm ?: return false
        val hwnd = HWND(WindowChrome.hwnd(window) ?: return false)
        val hr = if (kind == Backdrop.MICA) api.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, IntByReference(DWMSBT_MAINWINDOW), 4)
        else api.DwmSetWindowAttribute(hwnd, DWMWA_MICA_EFFECT, IntByReference(1), 4)
        if (hr != 0) { AppLog.w("glass", "backdrop attribute: 0x%08X".format(hr)); return false }
        // The whole client area is "frame", so the backdrop shows wherever the surface is transparent.
        val ext = api.DwmExtendFrameIntoClientArea(hwnd, Margins(-1, -1, -1, -1))
        if (ext != 0) { AppLog.w("glass", "extend frame: 0x%08X".format(ext)); return false }
        if (!surfaceTransparent(window, true)) return false
        redrawFrame(hwnd)
        return true
    }

    private fun turnOff(window: java.awt.Window, wasOn: Boolean) {
        surfaceTransparent(window, false)
        val api = dwm ?: return
        val hwnd = HWND(WindowChrome.hwnd(window) ?: return)
        if (!wasOn) return
        api.DwmExtendFrameIntoClientArea(hwnd, Margins())
        api.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, IntByReference(DWMSBT_NONE), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_MICA_EFFECT, IntByReference(0), 4)
        redrawFrame(hwnd)
    }

    /**
     * Compose draws through a skiko `SkiaLayer`; a transparent layer lets DWM's backdrop through where the shell
     * paints nothing. (ComposeWindow.isTransparent is for undecorated windows only; this window keeps its frame.)
     */
    private fun surfaceTransparent(window: java.awt.Window, on: Boolean): Boolean {
        val layer = findSkiaLayer(window) ?: return false
        if (layer.transparency != on) layer.transparency = on
        (window as? javax.swing.RootPaneContainer)?.let { c ->
            listOf(c.rootPane, c.layeredPane, c.contentPane as? javax.swing.JComponent).forEach { it?.isOpaque = !on }
        }
        return true
    }

    internal fun findSkiaLayer(root: java.awt.Component): org.jetbrains.skiko.SkiaLayer? {
        if (root is org.jetbrains.skiko.SkiaLayer) return root
        if (root is java.awt.Container) for (child in root.components) findSkiaLayer(child)?.let { return it }
        return null
    }

    private fun redrawFrame(hwnd: HWND) {
        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE or 0x0020 /* SWP_FRAMECHANGED */)
    }
}
