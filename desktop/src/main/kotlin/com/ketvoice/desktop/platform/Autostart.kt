package com.ketvoice.desktop.platform

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * Start with Windows: the `Ketvoice` value under HKCU `…\CurrentVersion\Run` (off by default).
 * The command adds [FLAG], so an automatic start opens in the tray or minimised.
 */
class Autostart(private val key: RunKey = if (Os.isWindows) RegistryRunKey else RunKey.None, private val exe: () -> String? = ::installedExe) {
    interface RunKey {
        fun get(): String?
        fun set(command: String)
        fun delete()

        object None : RunKey {
            override fun get(): String? = null
            override fun set(command: String) {}
            override fun delete() {}
        }
    }

    /** Only the installed app (a jpackage launcher) can be started by Windows; `gradlew run` cannot. */
    val available: Boolean get() = key !== RunKey.None && exe() != null

    val enabled: Boolean get() = runCatching { key.get() }.getOrNull()?.isNotBlank() == true

    /** Returns whether the change was made. */
    fun setEnabled(on: Boolean): Boolean = runCatching {
        if (on) key.set(commandLine(exe() ?: return false)) else key.delete()
        true
    }.onFailure { AppLog.w("autostart", "set $on: $it") }.getOrDefault(false)

    /** After an update or a move, an existing entry points at the current exe again. */
    fun refresh() {
        val current = runCatching { key.get() }.getOrNull() ?: return
        val wanted = commandLine(exe() ?: return)
        if (current.isNotBlank() && current != wanted) runCatching { key.set(wanted) }
    }

    companion object {
        const val FLAG = "--autostart"
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE = "Ketvoice"

        fun commandLine(exe: String) = "\"$exe\" $FLAG"

        /** jpackage launchers set this to the launcher's own path. */
        fun installedExe(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.endsWith(".exe", ignoreCase = true) }
    }

    private object RegistryRunKey : RunKey {
        override fun get(): String? =
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE))
                Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
            else null

        override fun set(command: String) {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, RUN_KEY)) Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, RUN_KEY)
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, command)
        }

        override fun delete() {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE))
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
        }
    }
}

/** Desktop-only settings (the shared AppSettings stays as on Android). UI reads the states and calls the setters. */
object DesktopPrefs {
    private val prefs by lazy { android.content.Context.app.getSharedPreferences("desktop", android.content.Context.MODE_PRIVATE) }
    private val _closeToTray = mutableStateOf(false)
    private val _autostart = mutableStateOf(false)
    private var loaded = false
    val autostart = Autostart()

    /** Closing the window hides it in the notification area instead of quitting (default off). */
    val closeToTray: State<Boolean> get() = load()._closeToTray
    /** Start with Windows (default off); its truth is the registry entry. */
    val startWithWindows: State<Boolean> get() = load()._autostart

    fun setCloseToTray(on: Boolean) {
        load()._closeToTray.value = on
        prefs.edit { putBoolean("closeToTray", on) }
    }

    fun setStartWithWindows(on: Boolean): Boolean {
        val ok = autostart.setEnabled(on)
        load()._autostart.value = autostart.enabled
        return ok
    }

    @Synchronized private fun load(): DesktopPrefs {
        if (!loaded) {
            loaded = true
            _closeToTray.value = prefs.getBoolean("closeToTray", false)
            _autostart.value = autostart.enabled
        }
        return this
    }
}
