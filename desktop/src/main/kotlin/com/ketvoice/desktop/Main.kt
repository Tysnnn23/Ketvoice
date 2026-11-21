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
import org.json.JSONObject
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
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Ketvoice",
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
        ) {
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
            say(com.ketvoice.desktop.audio.AudioSmoke.run())
            say("os ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, java ${System.getProperty("java.version")}")
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
