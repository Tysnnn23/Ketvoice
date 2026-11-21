package com.ketvoice.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.W32APIOptions

/** Win32 touches on the main window: its HWND, the dark title bar, bringing it forward. */
object WindowChrome {
    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    @Suppress("FunctionName")
    private interface User32Extra : Library {
        fun AllowSetForegroundWindow(processId: Int): Boolean
    }

    private val dwm: Dwm? by lazy { if (Os.isWindows) runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() else null }
    private val user32Extra: User32Extra? by lazy {
        if (Os.isWindows) runCatching { Native.load("user32", User32Extra::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull() else null
    }

    // DWMWINDOWATTRIBUTE: 20 from Windows 10 20H1, 19 on the 1809–1909 builds that had it unnamed.
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19

    /** The window's HWND, or null off Windows or before the window has a native peer. */
    fun hwnd(window: java.awt.Window): Pointer? =
        if (!Os.isWindows || !window.isDisplayable) null else runCatching { Native.getWindowPointer(window) }.getOrNull()

    /** A dark (or light) title bar to match the theme. Returns whether DWM accepted it. */
    fun setDarkTitleBar(window: java.awt.Window, dark: Boolean): Boolean {
        val api = dwm ?: return false
        val hwnd = HWND(hwnd(window) ?: return false)
        val value = IntByReference(if (dark) 1 else 0)
        var hr = api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4)
        if (hr != 0) hr = api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, value, 4)
        if (hr == 0) {
            // Redraw the frame now rather than at the next activation.
            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE or SWP_FRAMECHANGED)
        }
        return hr == 0
    }

    /** A second launch calls this before it messages the first, so that one may take the foreground. */
    fun allowOthersToForeground() {
        runCatching { user32Extra?.AllowSetForegroundWindow(ASFW_ANY) }
    }

    /** Shows, restores and focuses the window (AWT's toFront alone only flashes the taskbar button). */
    fun bringToFront(window: java.awt.Frame) {
        if (!window.isVisible) window.isVisible = true
        if (window.extendedState and java.awt.Frame.ICONIFIED != 0) window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
        window.toFront()
        window.requestFocus()
        hwnd(window)?.let { runCatching { User32.INSTANCE.SetForegroundWindow(HWND(it)) } }
    }

    private const val SWP_FRAMECHANGED = 0x0020
    private const val ASFW_ANY = -1
}
