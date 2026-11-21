package com.ketvoice.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ketvoice.app.data.AppText
import com.ketvoice.desktop.ui.KetvoiceShell
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--smoke" in args) exitProcess(Smoke.run())
    // French is the source language; the others come from the app's translations.json on the classpath.
    AppText.init(android.content.Context.app)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Ketvoice",
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
        ) {
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(900, 600) }
            KetvoiceShell()
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
