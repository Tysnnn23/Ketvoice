package com.ketvoice.desktop.platform

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.CRC32

/** The system side of playback: SMTC on Windows, the media-key fallback, or nothing (Linux, tests). */
interface MediaControls {
    /** A short name for the log and `--smoke`. */
    val name: String
    /** [hwnd] is the main window's handle, or null for none. Returns false when it could not start. */
    fun start(hwnd: Pointer?, onCommand: (MediaCommand) -> Unit): Boolean
    /** Null clears the now-playing card. */
    fun update(nowPlaying: NowPlaying?)
    fun stop()

    companion object {
        /** SMTC when the DLL loads and initialises, else the RegisterHotKey fallback; nothing off Windows. */
        fun start(hwnd: Pointer?, onCommand: (MediaCommand) -> Unit): MediaControls {
            if (!Os.isWindows) return NoMediaControls
            SmtcNative.library()?.let { lib ->
                val smtc = SmtcMediaControls(lib)
                if (smtc.start(hwnd, onCommand)) return smtc
            }
            AppLog.w("media", "SMTC unavailable (${SmtcNative.error ?: "init failed"}); media keys through RegisterHotKey")
            val keys = HotkeyMediaControls()
            return if (keys.start(hwnd, onCommand)) keys else NoMediaControls
        }

        /** Keeps [controls]' now-playing card in step with the player's [state]. */
        fun bind(scope: CoroutineScope, controls: MediaControls, state: StateFlow<com.ketvoice.app.player.PlayerUiState>) {
            scope.launch {
                var last: NowPlaying? = null
                var cleared = true
                state.map { NowPlaying.of(it) }.collect { now ->
                    if (now == null) {
                        if (!cleared) controls.update(null)
                        cleared = true; last = null
                    } else if (now.differsFrom(last)) {
                        controls.update(now)
                        cleared = false; last = now
                    }
                }
            }
        }
    }
}

object NoMediaControls : MediaControls {
    override val name = "none"
    override fun start(hwnd: Pointer?, onCommand: (MediaCommand) -> Unit) = true
    override fun update(nowPlaying: NowPlaying?) {}
    override fun stop() {}
}

internal object Os {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
}

// ---------------------------------------------------------------------------------------------
// SMTC through ketvoice_smtc.dll

@Suppress("FunctionName")
internal interface SmtcLibrary : Library {
    fun kv_smtc_init(hwnd: Pointer?, callback: SmtcCallback?): Int
    fun kv_smtc_update(title: WString, artist: WString, album: WString, artPath: WString?, playing: Int, positionMs: Long, durationMs: Long): Int
    fun kv_smtc_clear()
    fun kv_smtc_shutdown()
}

internal fun interface SmtcCallback : Callback {
    fun invoke(command: Int, value: Long)
}

/** Extracts the DLL packaged at `/native/ketvoice_smtc.dll` into the data folder and loads it. */
internal object SmtcNative {
    const val RESOURCE = "/native/ketvoice_smtc.dll"
    @Volatile var error: String? = null
        private set
    private var loaded: SmtcLibrary? = null
    private var tried = false

    @Synchronized fun library(): SmtcLibrary? {
        if (tried) return loaded
        tried = true
        loaded = runCatching {
            val file = extract() ?: error("$RESOURCE is not in the app")
            Native.load(file.absolutePath, SmtcLibrary::class.java)
        }.onFailure { error = it.toString() }.getOrNull()
        return loaded
    }

    /** A copy per content hash, so an update never overwrites a DLL another instance has open. */
    fun extract(dir: File = File(android.content.Context.app.dataDir, "native")): File? {
        val bytes = SmtcNative::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: return null
        val crc = CRC32().apply { update(bytes) }.value
        val file = File(dir, "smtc-%08x/ketvoice_smtc.dll".format(crc))
        if (file.isFile && file.length() == bytes.size.toLong()) return file
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "ketvoice_smtc.dll.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { tmp.delete(); if (!file.isFile) error("cannot write $file") }
        return file
    }
}

internal class SmtcMediaControls(private val lib: SmtcLibrary) : MediaControls {
    override val name = "smtc"
    // All native calls on one thread (it joins the MTA in the DLL; thumbnails load with blocking calls).
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ketvoice-smtc").apply { isDaemon = true } }
    private var callback: SmtcCallback? = null // kept reachable: JNA would free a collected callback

    override fun start(hwnd: Pointer?, onCommand: (MediaCommand) -> Unit): Boolean {
        val cb = SmtcCallback { code, value ->
            MediaCommands.fromNative(code, value)?.let { cmd -> runCatching { onCommand(cmd) }.onFailure { AppLog.e("media", "command $cmd", it) } }
        }
        callback = cb
        val hr = worker.submit<Int> { lib.kv_smtc_init(hwnd, cb) }.get()
        if (hr != 0) AppLog.w("media", "kv_smtc_init: 0x%08X".format(hr))
        return hr == 0
    }

    override fun update(nowPlaying: NowPlaying?) {
        worker.execute {
            runCatching {
                if (nowPlaying == null) lib.kv_smtc_clear()
                else {
                    val hr = lib.kv_smtc_update(WString(nowPlaying.title), WString(nowPlaying.artist), WString(nowPlaying.album),
                        nowPlaying.art?.let(::WString), if (nowPlaying.playing) 1 else 0, nowPlaying.positionMs, nowPlaying.durationMs)
                    if (hr != 0) AppLog.w("media", "kv_smtc_update: 0x%08X".format(hr))
                }
            }.onFailure { AppLog.e("media", "SMTC update", it) }
        }
    }

    override fun stop() {
        runCatching { worker.submit { lib.kv_smtc_shutdown() }.get() }
        worker.shutdown()
        callback = null
    }
}

// ---------------------------------------------------------------------------------------------
// Fallback: the four media keys as global hotkeys, with a message loop thread.

internal class HotkeyMediaControls : MediaControls {
    override val name = "hotkeys"
    @Volatile private var threadId = 0
    private var thread: Thread? = null

    override fun start(hwnd: Pointer?, onCommand: (MediaCommand) -> Unit): Boolean {
        val ready = java.util.concurrent.CountDownLatch(1)
        var registered = 0
        thread = Thread({
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            MediaCommands.hotkeys.forEachIndexed { i, vk ->
                if (User32.INSTANCE.RegisterHotKey(null, MediaCommands.HOTKEY_BASE + i, MOD_NOREPEAT, vk)) registered++
                else AppLog.w("media", "RegisterHotKey 0x%02X failed (%d)".format(vk, Kernel32.INSTANCE.GetLastError()))
            }
            ready.countDown()
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WM_HOTKEY) {
                    MediaCommands.fromHotkeyId(msg.wParam.toInt())?.let { cmd ->
                        runCatching { onCommand(cmd) }.onFailure { AppLog.e("media", "hotkey $cmd", it) }
                    }
                }
            }
            MediaCommands.hotkeys.indices.forEach { User32.INSTANCE.UnregisterHotKey(null, MediaCommands.HOTKEY_BASE + it) }
        }, "ketvoice-media-keys").apply { isDaemon = true; start() }
        ready.await()
        if (registered == 0) stop()
        return registered > 0
    }

    override fun update(nowPlaying: NowPlaying?) {}

    override fun stop() {
        if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, null, null)
        thread = null
    }

    private companion object {
        const val MOD_NOREPEAT = 0x4000
        const val WM_HOTKEY = 0x0312
    }
}
