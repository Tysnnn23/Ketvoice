package com.ketvoice.desktop.ketmix

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * When the PC is idle enough for the library to be prepared with the large models (Windows 2.0): on mains power, no
 * full-screen application (a game, a film, a presentation: `SHQueryUserNotificationState`), the processor mostly free
 * (other programs under [MAX_CPU]) and nobody at the keyboard or mouse for [IDLE_MS] (`GetLastInputInfo`). Any input
 * stops the work at once ([LibraryPrep] polls [busy] while a network runs).
 */
internal object IdleWatch {
    const val IDLE_MS = 3 * 60_000L
    const val MAX_CPU = .25
    /** Input this recent means the listener is back: the work stops within a poll. */
    const val BACK_MS = 2_000L

    data class Snapshot(val idleMs: Long, val fullScreen: Boolean, val otherCpu: Double, val mains: Boolean)

    /** The rule, pure: start (or go on) only when everything says the PC is free. */
    fun idle(s: Snapshot): Boolean = s.mains && !s.fullScreen && s.idleMs >= IDLE_MS && s.otherCpu < MAX_CPU

    /** While working: the listener came back, a full-screen app started or the PC left mains power. */
    fun interrupted(s: Snapshot): Boolean = !s.mains || s.fullScreen || s.idleMs < BACK_MS

    // Seams over the system calls (tests set them).
    @Volatile internal var read: () -> Snapshot = ::readSystem

    fun snapshot(): Snapshot = runCatching { read() }.getOrDefault(Snapshot(0L, true, 1.0, false))

    private fun readSystem(): Snapshot {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        if (!windows) return Snapshot(0L, true, 1.0, false)
        return Snapshot(idleMs(), fullScreen(), otherCpu(), com.ketvoice.app.stems.StemModel.charging())
    }

    @Structure.FieldOrder("cbSize", "dwTime")
    class LastInput : Structure() {
        @JvmField var cbSize: Int = 8
        @JvmField var dwTime: Int = 0
    }

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun GetLastInputInfo(info: LastInput): Boolean
        companion object { val api: User32 by lazy { Native.load("user32", User32::class.java) } }
    }

    @Suppress("FunctionName")
    private interface Shell32 : StdCallLibrary {
        fun SHQueryUserNotificationState(state: IntByReference): Int
        companion object { val api: Shell32 by lazy { Native.load("shell32", Shell32::class.java) } }
    }

    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun GetTickCount(): Int
        companion object { val api: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) } }
    }

    private fun idleMs(): Long {
        val info = LastInput()
        if (!User32.api.GetLastInputInfo(info)) return 0L
        // Both are 32-bit tick counts that wrap together after 49.7 days.
        return (Kernel32.api.GetTickCount() - info.dwTime).toLong() and 0xffffffffL
    }

    /** QUNS_BUSY (2), QUNS_RUNNING_D3D_FULL_SCREEN (3), QUNS_PRESENTATION_MODE (4), QUNS_APP (7): someone is watching or playing. */
    internal fun fullScreenState(state: Int): Boolean = state == 2 || state == 3 || state == 4 || state == 7

    private fun fullScreen(): Boolean {
        val state = IntByReference()
        return User32Safe.ok(Shell32.api.SHQueryUserNotificationState(state)) && fullScreenState(state.value)
    }

    /** The processor load of everything but Ketvoice, 0..1. */
    private fun otherCpu(): Double {
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val all = os.cpuLoad.takeIf { it >= 0 } ?: return 1.0
        val ours = os.processCpuLoad.takeIf { it >= 0 } ?: 0.0
        return (all - ours).coerceAtLeast(0.0)
    }

    private object User32Safe { fun ok(hresult: Int) = hresult == 0 }

    @Suppress("unused") private val keep = WinDef::class
}
