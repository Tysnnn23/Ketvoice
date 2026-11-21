package com.ketvoice.desktop.platform

import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Start-up (Windows 1.1): the GPU render API, the class-data archive folder and the time to the first frame.
 *
 * - Rendering: Direct3D with vsync on Windows. If Direct3D cannot start, skiko itself falls back to OpenGL, then
 *   software; [firstFrame] logs which one draws. `SKIKO_RENDER_API` (environment) or `-Dskiko.renderApi` still win.
 * - Class data: the packaged launcher runs with `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=<data>\cds\ketvoice.jsa`
 *   (build.gradle.kts). The JVM writes the archive when the app quits and maps it on the next start; it only needs
 *   the folder to exist, which [prepareArchive] makes.
 * - `--startup-probe`: a normal start that quits after the first frame and writes `startup.log` (Windows CI times it).
 */
object Startup {
    const val PROBE = "--startup-probe"
    private const val ARCHIVE_PROPERTY = "ketvoice.cdsArchive"

    /** The render API asked for (null: skiko's default), set before any window exists. */
    fun configureRendering(os: String = System.getProperty("os.name").orEmpty(), env: (String) -> String? = System::getenv): String? {
        if (System.getProperty("skiko.vsync.enabled") == null) System.setProperty("skiko.vsync.enabled", "true")
        env("SKIKO_RENDER_API")?.takeIf { it.isNotBlank() }?.let { return it.uppercase() }
        System.getProperty("skiko.renderApi")?.takeIf { it.isNotBlank() }?.let { return it.uppercase() }
        if (!os.startsWith("Windows")) return null
        System.setProperty("skiko.renderApi", "DIRECT3D")
        return "DIRECT3D"
    }

    /** The archive the launcher names, its folder made; null when the app was not started by the packaged launcher. */
    fun prepareArchive(): File? {
        val path = System.getProperty(ARCHIVE_PROPERTY)?.takeIf { it.isNotBlank() && '$' !in it } ?: return null
        return File(path).also { runCatching { it.parentFile?.mkdirs() } }
    }

    /** Milliseconds since the process started (the launcher, the JVM's own start included). */
    fun msSinceStart(now: Instant = Instant.now()): Long {
        val start = ProcessHandle.current().info().startInstant().orElse(null) ?: return -1
        return Duration.between(start, now).toMillis()
    }

    private var archiveAtStart: Long = -1

    /** Called first thing in main: what the archive was before this run could rewrite it. */
    fun begin() { archiveAtStart = prepareArchive()?.takeIf { it.isFile }?.length() ?: -1 }

    /** The line logged when the main window has drawn its first frame (and written to `startup.log` by the probe). */
    fun firstFrame(drawnWith: String, wanted: String?, probe: Boolean, dataDir: File): String {
        val ms = msSinceStart()
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) shr 20
        val sharing = System.getProperty("java.vm.info").orEmpty().contains("sharing")
        val cds = when {
            !sharing -> "off"
            archiveAtStart > 0 -> "app archive ${archiveAtStart shr 20} MB"
            prepareArchive() != null -> "base only (app archive written at exit)"
            else -> "base only"
        }
        val line = "first frame $ms ms, render $drawnWith, vsync ${System.getProperty("skiko.vsync.enabled")}, " +
            "heap $heapMb MB of ${rt.maxMemory() shr 20} MB, cds $cds"
        AppLog.i("start", line)
        if (wanted != null && !drawnWith.equals(wanted, ignoreCase = true)) AppLog.w("render", "$wanted could not start; drawing with $drawnWith")
        if (probe) runCatching { File(dataDir, "startup.log").writeText(line + "\n") }
        return line
    }
}
