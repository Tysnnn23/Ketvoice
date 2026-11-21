package com.ketvoice.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.audio.FfmpegAudioEngine
import com.ketvoice.desktop.library.DesktopLibrary
import com.ketvoice.desktop.platform.AppLog
import com.ketvoice.desktop.platform.Autostart
import com.ketvoice.desktop.platform.CrashHandler
import com.ketvoice.desktop.platform.DesktopPlatform
import com.ketvoice.desktop.platform.DesktopPrefs
import com.ketvoice.desktop.platform.InstanceMessage
import com.ketvoice.desktop.platform.MediaCommands
import com.ketvoice.desktop.platform.MediaControls
import com.ketvoice.desktop.platform.SingleInstance
import com.ketvoice.desktop.platform.WindowChrome
import com.ketvoice.desktop.platform.WindowStore
import com.ketvoice.desktop.player.DesktopStreams
import com.ketvoice.desktop.ui.KetvoiceShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.EventQueue
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--smoke" in args) exitProcess(Smoke.run())
    AppLog.init()
    CrashHandler.install()
    val argList = args.toList()
    // One Ketvoice per user: a second launch hands its arguments to the first and exits.
    val instance = when (val r = SingleInstance.acquire(android.content.Context.app.dataDir, argList, WindowChrome::allowOthersToForeground)) {
        is SingleInstance.Result.Primary -> r.instance
        SingleInstance.Result.Forwarded -> { AppLog.i("instance", "handed to the running Ketvoice"); exitProcess(0) }
        SingleInstance.Result.Unreachable -> { AppLog.w("instance", "the running Ketvoice did not answer; starting alone"); null }
    }
    // French is the source language; the others come from the app's translations.json on the classpath.
    val context = android.content.Context.app
    // Every setting as the listener left it (the language included, before the texts load).
    com.ketvoice.app.data.SettingsStore.load(context)
    AppText.init(context)
    runCatching { MusicApi.init(context) }.onFailure { AppLog.w("start", "catalogue: $it") }
    DesktopLibrary.init(context)
    com.ketvoice.app.data.ArtistPrefs.init(context)
    // The real engine plays what the Player resolves (downloads first, then the app's sources).
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = FfmpegAudioEngine()
    Player.attach(engine, DesktopStreams::source, scope)
    com.ketvoice.app.data.SettingsStore.watch(scope)
    com.ketvoice.desktop.library.LocalFolders.init(context, scope)
    com.ketvoice.desktop.library.DesktopAccount.start(context)
    // Ketmix: the shared planning played by the engine's two decks (off until the listener switches it on).
    com.ketvoice.desktop.ketmix.DesktopAutomix.attach(engine, scope)
    // Tray, media keys and the Windows media controls drive the player.
    DesktopPlatform.bridge = object : com.ketvoice.desktop.platform.PlatformBridge {
        override fun toggle() = Player.toggle()
        override fun play() = Player.play()
        override fun pause() = Player.pause()
        override fun next() = Player.next()
        override fun previous() = Player.previous()
        override fun seekTo(ms: Long) = Player.seekTo(ms)
    }
    DesktopPlatform.open(InstanceMessage.openable(argList))
    DesktopPrefs.autostart.refresh()
    val autostarted = Autostart.FLAG in argList
    val saved = WindowStore.fit(WindowStore.load(), WindowStore.awtScreens())
    val icon = runCatching {
        BitmapPainter(javax.imageio.ImageIO.read(Smoke::class.java.getResourceAsStream("/icons/ketvoice.png")).toComposeImageBitmap())
    }.getOrNull()

    application {
        // Started with Windows: straight to the tray when closing goes there, else minimised.
        var visible by remember { mutableStateOf(!(autostarted && DesktopPrefs.closeToTray.value)) }
        val state = rememberWindowState(
            placement = if (saved.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            isMinimized = autostarted && !DesktopPrefs.closeToTray.value,
            position = if (saved.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition(Alignment.Center),
            size = DpSize(saved.width.dp, saved.height.dp),
        )
        val lastSaved = remember { arrayOf(saved) }
        var awtWindow by remember { mutableStateOf<ComposeWindow?>(null) }
        val playing = Player.state.collectAsState().value.isPlaying
        fun show() {
            visible = true
            EventQueue.invokeLater { awtWindow?.let(WindowChrome::bringToFront) }
        }
        fun quit() {
            WindowStore.save(lastSaved[0])
            runCatching { Player.pause() }
            runCatching { com.ketvoice.app.data.SettingsStore.save() }
            runCatching { com.ketvoice.desktop.library.DesktopAccount.quit() }
            runCatching { android.content.FileSharedPreferences.flush() }
            runCatching { engine.release() }
            instance?.close()
            exitApplication()
        }

        if (icon != null) Tray(
            icon = icon,
            tooltip = "Ketvoice",
            onAction = ::show,
            menu = {
                Item(AppText.t(if (playing) "Pause" else "Lire"), onClick = { DesktopPlatform.bridge.toggle() })
                Item(AppText.t("Suivant"), onClick = { DesktopPlatform.bridge.next() })
                Item(AppText.t("Précédent"), onClick = { DesktopPlatform.bridge.previous() })
                Separator()
                Item(AppText.t("Ouvrir"), onClick = ::show)
                Item(AppText.t("Quitter"), onClick = ::quit)
            },
        )

        Window(
            onCloseRequest = { if (DesktopPrefs.closeToTray.value) visible = false else quit() },
            visible = visible,
            title = "Ketvoice",
            icon = icon,
            state = state,
        ) {
            val dark = when (com.ketvoice.app.data.AppSettings.themeMode.value) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(900, 600)
                // Back at the front: what other devices changed comes in (at most once a minute).
                window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = com.ketvoice.desktop.library.DesktopAccount.foreground()
                })
            }
            LaunchedEffect(dark) {
                while (!window.isDisplayable) delay(50)
                WindowChrome.setDarkTitleBar(window, dark)
            }
            LaunchedEffect(Unit) {
                awtWindow = window
                // A later launch: open what it was given and come forward.
                instance?.onActivate { args ->
                    DesktopPlatform.open(InstanceMessage.openable(args))
                    EventQueue.invokeLater { show() }
                }
            }
            // System media controls (SMTC, or the media keys as hotkeys), fed by the player's state.
            LaunchedEffect(Unit) {
                withTimeoutOrNull(3000) { while (!window.isDisplayable) delay(50) }
                val controls = withContext(Dispatchers.IO) {
                    MediaControls.start(WindowChrome.hwnd(window)) { cmd ->
                        EventQueue.invokeLater { MediaCommands.dispatch(cmd, DesktopPlatform.bridge) }
                    }
                }
                AppLog.i("media", "media controls: ${controls.name}")
                try {
                    MediaControls.bind(this, controls, Player.state)
                    awaitCancellation()
                } finally {
                    controls.stop()
                }
            }
            // Size, position and maximised state, kept for the next launch.
            LaunchedEffect(state) {
                snapshotFlow { listOf(state.placement, state.isMinimized, state.position, state.size) }.collectLatest {
                    val pos = state.position
                    val floating = state.placement == WindowPlacement.Floating && !state.isMinimized
                    val next = WindowStore.merge(lastSaved[0], floating, state.placement == WindowPlacement.Maximized,
                        pos.x.value.takeIf { pos.isSpecified }, pos.y.value.takeIf { pos.isSpecified },
                        state.size.width.value, state.size.height.value)
                    if (next != lastSaved[0]) {
                        lastSaved[0] = next
                        delay(500)
                        withContext(Dispatchers.IO) { WindowStore.save(next) }
                    }
                }
            }
            KetvoiceShell(dark = dark)
        }
        com.ketvoice.desktop.ui.MiniPlayerWindow(icon, when (com.ketvoice.app.data.AppSettings.themeMode.value) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }, ::show)
    }
}

/**
 * `--smoke`: loads the native libraries the app needs and prints their versions, without a window.
 * The installed Ketvoice.exe is a windowed launcher with no console, so the same lines also go to
 * `smoke.log` in the data folder.
 */
internal object Smoke {
    fun run(): Int {
        val lines = ArrayList<String>()
        fun say(line: String) { println(line); lines += line }
        val code = try {
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            say("onnxruntime ${env.version}")
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avutil::class.java)
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.swresample::class.java)
            say("ffmpeg ${org.bytedeco.ffmpeg.global.avutil.av_version_info().string}")
            say("avformat ${version(org.bytedeco.ffmpeg.global.avformat.avformat_version())}, " +
                "swresample ${version(org.bytedeco.ffmpeg.global.swresample.swresample_version())}")
            say("license ${org.bytedeco.ffmpeg.global.avutil.avutil_license().string}")
            say(com.ketvoice.desktop.audio.AudioSmoke.run())
            say("os ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, java ${System.getProperty("java.version")}")
            if (!com.ketvoice.desktop.platform.PlatformSmoke.check(::say)) error("SMTC check failed")
            say("smoke ok")
            0
        } catch (t: Throwable) {
            say("smoke failed: $t")
            t.printStackTrace()
            1
        }
        runCatching { java.io.File(android.content.Context.app.dataDir, "smoke.log").writeText(lines.joinToString("\n", postfix = "\n")) }
        return code
    }

    private fun version(v: Int) = "${v ushr 16}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
}
