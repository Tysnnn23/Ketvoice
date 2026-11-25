package com.ketvoice.desktop.platform

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser

/**
 * A window the pointer goes through (Windows 2.0's floating lyrics while locked): `WS_EX_TRANSPARENT` on the layered
 * window AWT makes for a transparent frame, plus `WS_EX_NOACTIVATE` so it never takes the focus from the game or the
 * document under it. Nothing off Windows (the band then stays a normal window above the others).
 */
object ClickThrough {
    private const val WS_EX_TRANSPARENT = 0x00000020
    private const val WS_EX_LAYERED = 0x00080000
    private const val WS_EX_NOACTIVATE = 0x08000000
    private const val WS_EX_TOOLWINDOW = 0x00000080

    /** The extended style for [through] (clicks pass) or not, from the window's [current] one. */
    fun style(current: Int, through: Boolean): Int {
        val base = current or WS_EX_LAYERED or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW
        return if (through) base or WS_EX_TRANSPARENT else base and WS_EX_TRANSPARENT.inv()
    }

    fun apply(window: java.awt.Window, through: Boolean): Boolean {
        val pointer = WindowChrome.hwnd(window) ?: return false
        return runCatching {
            val hwnd = HWND(pointer)
            val now = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
            User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, style(now, through))
            true
        }.onFailure { AppLog.w("lyrics", "floating lyrics click-through: $it") }.getOrDefault(false)
    }
}
