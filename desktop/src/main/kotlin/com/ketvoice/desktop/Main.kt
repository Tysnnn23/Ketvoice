package com.ketvoice.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import com.ketvoice.desktop.platform.Startup
import com.ketvoice.desktop.platform.WindowChrome
import com.ketvoice.desktop.platform.WindowGlass
import com.ketvoice.desktop.ui.PlayerFullScreen
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
    // Windows 1.1: Direct3D with vsync (skiko falls back by itself), the class-data archive's folder, the probe.
    Startup.begin()
    val renderWanted = Startup.configureRendering()
    val probe = Startup.PROBE in args
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
    // Windows 2.0: the Ketvoice Lossless manifest is offered by the source catalogue, never added by itself.
    com.ketvoice.app.data.CustomSources.seedLossless = false
    com.ketvoice.desktop.audio.PlaybackPrefs.init()
    DesktopLibrary.init(context)
    // The real engine plays what the Player resolves (downloads first, then the app's sources); on Windows its output
    // can go exclusive (« Mode exclusif »).
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sink = com.ketvoice.desktop.audio.ExclusiveOutput.sink()
    val engine = FfmpegAudioEngine(sink)
    // Each resolved source is remembered with its title, for the voice level's separations.
    Player.attach(engine, { t -> DesktopStreams.source(t)?.also { com.ketvoice.desktop.stems.DesktopVoice.remember(it, t.id) } }, scope)
    // Near-instant start (Windows 2.0): the last page, the queue where it stood and its cover, before anything else;
    // everything else starts behind the window, which opens at once on that state.
    restoreSession(context)
    com.ketvoice.app.data.SettingsStore.watch(scope)
    Startup.mark("state")
    Thread({ startBehind(context, engine, sink, scope) }, "ketvoice-start").apply { isDaemon = true; start() }
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
            runCatching { com.ketvoice.desktop.player.Session.save() }
            runCatching { Player.pause() }
            runCatching { com.ketvoice.app.remote.RemoteControl.stop() }
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
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                        com.ketvoice.desktop.library.DesktopAccount.foreground()
                        // Transparency effects or high contrast may have changed while away.
                        if (window.isDisplayable) WindowGlass.apply(window)
                    }
                })
            }
            val glassWanted = WindowGlass.enabled.value
            LaunchedEffect(dark, glassWanted) {
                while (!window.isDisplayable) delay(50)
                WindowChrome.setDarkTitleBar(window, dark)
                // « Verre Windows 11 »: Mica under the window when the system allows it, else the solid colours.
                WindowGlass.apply(window)
            }
            // « Lecteur plein écran »: the window goes full screen with it, and comes back as it was.
            LaunchedEffect(Unit) {
                var before = state.placement
                snapshotFlow { PlayerFullScreen.on.value }.collect { on ->
                    if (on && state.placement != WindowPlacement.Fullscreen) { before = state.placement; state.placement = WindowPlacement.Fullscreen }
                    else if (!on && state.placement == WindowPlacement.Fullscreen) state.placement = before
                }
            }
            LaunchedEffect(Unit) {
                snapshotFlow { state.placement }.collect { if (it != WindowPlacement.Fullscreen) PlayerFullScreen.on.value = false }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }; withFrameNanos { } // the first frame has been drawn once the second one is asked for
                Startup.firstFrame(window.renderApi.name, renderWanted, probe, context.dataDir)
                if (probe) EventQueue.invokeLater { quit() }
            }
            // In the tray or minimised for a while: decoded covers go, and the heap gives memory back to Windows.
            LaunchedEffect(visible, state.isMinimized) {
                if (visible && !state.isMinimized) return@LaunchedEffect
                delay(15_000)
                com.ketvoice.desktop.ui.Covers.trim()
                withContext(Dispatchers.Default) { System.gc() }
            }
            LaunchedEffect(Unit) {
                awtWindow = window
                com.ketvoice.desktop.update.Updates.quit = { EventQueue.invokeLater { quit() } }
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
                // Previous, play/pause and next under the taskbar thumbnail, and the cover as the minimised thumbnail.
                val thumbs = withContext(Dispatchers.IO) {
                    com.ketvoice.desktop.platform.ThumbBar.start(WindowChrome.hwnd(window), window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0) { cmd ->
                        EventQueue.invokeLater { MediaCommands.dispatch(cmd, DesktopPlatform.bridge) }
                    }
                }
                AppLog.i("media", "thumbnail toolbar: ${if (thumbs != null) "on" else "off"}")
                try {
                    MediaControls.bind(this, controls, Player.state)
                    thumbs?.let { com.ketvoice.desktop.platform.ThumbBar.bind(this, it, Player.state) { url -> com.ketvoice.desktop.ui.Covers.load(url) } }
                    awaitCancellation()
                } finally {
                    controls.stop()
                    thumbs?.stop()
                }
            }
            // Size, position and maximised state, kept for the next launch.
            LaunchedEffect(state) {
                snapshotFlow { listOf(state.placement, state.isMinimized, state.position, state.size) }.collectLatest {
                    // Full screen is the player's, not a window state to come back to.
                    if (state.placement == WindowPlacement.Fullscreen) return@collectLatest
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
            KetvoiceShell(dark = dark, glass = WindowGlass.active.value)
        }
        com.ketvoice.desktop.ui.MiniPlayerWindow(icon, when (com.ketvoice.app.data.AppSettings.themeMode.value) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }, ::show)
    }
    // The probe ends here even if a library thread lingers (the class-data archive is written on this exit).
    if (probe) exitProcess(0)
}

/** The saved queue, paused where it stood, and its cover decoded for the first frame. */
private fun restoreSession(context: android.content.Context) {
    val session = com.ketvoice.desktop.player.Session
    val saved = runCatching { session.load(context) }.getOrNull() ?: return
    runCatching { session.restore(saved) }.onFailure { AppLog.w("start", "session: $it") }
    val url = saved.tracks.getOrNull(saved.index)?.coverUrl ?: return
    runCatching { session.coverBytes()?.let { com.ketvoice.desktop.ui.Covers.decode(it) }?.let { com.ketvoice.desktop.ui.Covers.put(url, it) } }
}

/** What the window does not need for its first frame: the catalogue, sound settings, the account, Ketmix, updates. */
private fun startBehind(context: android.content.Context, engine: FfmpegAudioEngine, sink: com.ketvoice.desktop.audio.PcmSink,
                        scope: CoroutineScope) {
    runCatching { MusicApi.init(context) }.onFailure { AppLog.w("start", "catalogue: $it") }
    runCatching { com.ketvoice.app.data.CustomSources.init(context) }
    com.ketvoice.app.data.ArtistPrefs.init(context)
    com.ketvoice.desktop.audio.DesktopEqualizer.attach(engine)
    // « Sortie audio » (the device remembered by name) and « Égaliseur par casque » (the correction linked to it).
    com.ketvoice.desktop.audio.DesktopOutput.attach(engine, scope)
    com.ketvoice.desktop.audio.DesktopHeadphoneEq.attach(engine, scope)
    com.ketvoice.app.stems.StemModel.init(context)
    com.ketvoice.desktop.stems.DesktopVoice.attach(engine, scope)
    // « Mode exclusif » and « Lecture sans blanc » follow their settings; the smart cache warms favourites.
    com.ketvoice.desktop.audio.ExclusiveOutput.attach(engine, scope, sink)
    com.ketvoice.desktop.player.CacheWarmer.start(scope)
    com.ketvoice.desktop.player.Session.follow(scope) { t -> com.ketvoice.desktop.ui.LastPage.coverBytes(t.coverUrl) }
    // « Playlists téléchargées à jour »: new titles of downloaded playlists are offered in the window.
    com.ketvoice.desktop.library.PlaylistWatch.start(context, scope)
    com.ketvoice.desktop.library.LocalFolders.init(context, scope)
    com.ketvoice.desktop.library.DesktopAccount.start(context)
    // Phone control: this PC joins the account's private room while signed in.
    com.ketvoice.desktop.remote.DesktopRemote.start(context, scope)
    // Ketmix: the shared planning played by the engine's two decks (off until the listener switches it on).
    com.ketvoice.desktop.ketmix.DesktopAutomix.attach(engine, scope)
    // Ketmix Studio: the listener's mix points and presets (`ketmix-presets-v1`, as on the phone) and the waves it draws.
    com.ketvoice.app.automix.MixPointStore.init(context)
    com.ketvoice.app.automix.MixPresetStore.init(context)
    com.ketvoice.app.automix.WaveStore.init(context)
    // « Voix prêtes à l'avance »: downloads separated ahead while on mains power.
    com.ketvoice.app.stems.StemLibrary.start()
    // Windows 2.0 « Puissance de Ketmix »: large models, GPU, idle preparation, live mix view, « Ketmix apprend ».
    com.ketvoice.desktop.ketmix.KetmixPc.start(context, engine, scope)
    // Audio files opened with Ketvoice (file associations), from this launch and later ones.
    com.ketvoice.desktop.library.OpenFiles.start(scope, java.io.File(context.cacheDir, "local-covers")) { Player.playQueue(it, 0) }
    // Updates from the windows-v* releases (at most once per 6 h; Settings › À propos checks now).
    com.ketvoice.desktop.update.Updates.start(scope)
    DesktopPrefs.autostart.refresh()
    Startup.mark("behind")
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
            val license = org.bytedeco.ffmpeg.global.avutil.avutil_license().string
            say("license $license")
            // Shipped under the LGPL v3 notice (docs/licenses/FFmpeg-LGPL-3.0.txt): a GPL build must never pass.
            if (!license.startsWith("LGPL")) error("FFmpeg is not the LGPL build: $license")
            say("app version ${com.ketvoice.app.BuildConfig.VERSION_NAME}")
            say(com.ketvoice.desktop.audio.AudioSmoke.run())
            say("os ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, java ${System.getProperty("java.version")}")
            if (!com.ketvoice.desktop.platform.PlatformSmoke.check(::say)) error("SMTC check failed")
            if (!com.ketvoice.desktop.audio.WasapiNative.smoke(::say)) error("WASAPI check failed")
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
