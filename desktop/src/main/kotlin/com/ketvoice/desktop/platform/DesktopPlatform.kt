package com.ketvoice.desktop.platform

import com.sun.jna.WString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** What the window, tray and media keys share with the rest of the app. */
object DesktopPlatform {
    /** The player's transport; Main sets it to the `Player` facade. */
    @Volatile var bridge: PlatformBridge = PlatformBridge.None

    private val opens = Channel<List<String>>(Channel.UNLIMITED)

    /**
     * Files or links to open, from this launch's arguments and from later launches (single
     * instance). One consumer collects it (the player/library), each list once.
     */
    val openRequests: Flow<List<String>> = opens.receiveAsFlow()

    fun open(paths: List<String>) {
        if (paths.isNotEmpty()) opens.trySend(paths)
    }
}

/** The installed `--smoke`'s SMTC line: the DLL must load on Windows; init runs without a window. */
object PlatformSmoke {
    fun check(say: (String) -> Unit): Boolean {
        if (!Os.isWindows) { say("smtc skipped: not Windows"); return true }
        val lib = SmtcNative.library()
        if (lib == null) { say("smtc failed: ketvoice_smtc.dll did not load (${SmtcNative.error})"); return false }
        val init = lib.kv_smtc_init(null, null)
        if (init != 0) {
            // Some Windows editions (Server Core, N without the Media Feature Pack) have no SMTC.
            say("smtc skipped: dll loaded, init with its own hidden window returned 0x%08X".format(init))
            return true
        }
        val update = lib.kv_smtc_update(WString("Ketvoice"), WString("Smoke"), WString("Ketvoice for Windows"), null, 0, 1_000, 10_000)
        lib.kv_smtc_clear()
        lib.kv_smtc_shutdown()
        if (update != 0) { say("smtc failed: update returned 0x%08X".format(update)); return false }
        say("smtc ok (dll loaded, init/update/clear/shutdown without a window)")
        return true
    }
}
