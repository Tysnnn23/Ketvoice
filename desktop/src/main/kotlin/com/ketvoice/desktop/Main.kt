package com.ketvoice.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.platform.AppLog
import com.ketvoice.desktop.platform.Autostart
import com.ketvoice.desktop.platform.CrashHandler
import com.ketvoice.desktop.platform.DesktopPlatform
import com.ketvoice.desktop.platform.DesktopPrefs
import com.ketvoice.desktop.platform.InstanceMessage
import com.ketvoice.desktop.platform.MediaCommands
import com.ketvoice.desktop.platform.MediaControls
import com.ketvoice.desktop.platform.PlatformText
import com.ketvoice.desktop.platform.SingleInstance
import com.ketvoice.desktop.platform.WindowChrome
import com.ketvoice.desktop.platform.WindowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.awt.EventQueue
import kotlin.system.exitProcess

/** Ket 27's dark tokens (theme/Ket27.kt in the app), for the desktop shell. */
private object Ket {
    val ground = Color(0xFF000000)
    val sidebar = Color(0xFF1C1C1E)
    val label = Color(0xFFFFFFFF)
    val secondary = Color(0xB3EBEBF5)
    val separator = Color(0x2BFFFFFF)
    val accent = Color(0xFFFF2D55)
    val selected = Color(0x1FFFFFFF)
}

/** French is the source language; other languages come from the app's own translations.json. */
private object Strings {
    private val table: Map<String, String> by lazy {
        val lang = java.util.Locale.getDefault().language
        if (lang == "fr") return@lazy emptyMap()
        runCatching {
            val all = JSONObject(Strings::class.java.classLoader.getResourceAsStream("assets/translations.json")!!.reader().readText())
            val row = all.optJSONObject(lang) ?: return@lazy emptyMap()
            row.keySet().associateWith { row.getString(it) }
        }.getOrDefault(emptyMap())
    }
    fun t(french: String): String = table[french] ?: french
}

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
    // Tray, media keys and the Windows media controls drive the player.
    DesktopPlatform.bridge = object : com.ketvoice.desktop.platform.PlatformBridge {
        override fun toggle() = com.ketvoice.app.player.Player.toggle()
        override fun play() = com.ketvoice.app.player.Player.play()
        override fun pause() = com.ketvoice.app.player.Player.pause()
        override fun next() = com.ketvoice.app.player.Player.next()
        override fun previous() = com.ketvoice.app.player.Player.previous()
        override fun seekTo(ms: Long) = com.ketvoice.app.player.Player.seekTo(ms)
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
            instance?.close()
            exitApplication()
        }

        if (icon != null) Tray(
            icon = icon,
            tooltip = "Ketvoice",
            onAction = ::show,
            menu = {
                Item(PlatformText.t(if (playing) "Pause" else "Lire"), onClick = { DesktopPlatform.bridge.toggle() })
                Item(PlatformText.t("Suivant"), onClick = { DesktopPlatform.bridge.next() })
                Item(PlatformText.t("Précédent"), onClick = { DesktopPlatform.bridge.previous() })
                Separator()
                Item(PlatformText.t("Ouvrir"), onClick = ::show)
                Item(PlatformText.t("Quitter"), onClick = ::quit)
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
            MaterialTheme(colorScheme = darkColorScheme(primary = Ket.accent, background = Ket.ground, surface = Ket.sidebar)) {
                Shell()
            }
        }
    }
}

@Composable
private fun Shell() {
    val sections = listOf("Accueil", "Rechercher", "Bibliothèque", "Playlists", "Téléchargements")
    var current by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize().background(Ket.ground)) {
        Column(
            Modifier.width(232.dp).fillMaxHeight().background(Ket.sidebar).padding(horizontal = 12.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Ketvoice", color = Ket.label, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, bottom = 16.dp))
            sections.forEachIndexed { i, label ->
                val on = i == current
                Row(
                    Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) Ket.selected else Color.Transparent)
                        .clickable { current = i }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (on) Ket.accent else Color.Transparent))
                    Spacer(Modifier.width(10.dp))
                    Text(Strings.t(label), color = if (on) Ket.label else Ket.secondary, fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Ket.separator))
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp)) {
            Text(Strings.t(sections[current]), color = Ket.label, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ket.separator))
        }
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
