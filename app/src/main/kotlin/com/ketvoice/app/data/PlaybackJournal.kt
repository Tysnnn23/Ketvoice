package com.ketvoice.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What happened to playback, kept on the device so that a cut behind other apps can be explained
 * (5.10.0): the playback service starting and stopping, why Android ended the app's previous
 * process, audio focus taken by another app, errors and network losses. Shown and shared from
 * Settings → Playback; never sent anywhere by itself.
 */
object PlaybackJournal {
    private const val LIMIT = 300
    private val lines = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ketvoice-journal").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "playback-journal.txt")
        file = f
        writer.execute {
            runCatching { if (f.isFile) synchronized(lines) { f.readLines().takeLast(LIMIT).forEach(lines::addLast) } }
            recordExit(context.applicationContext)
        }
    }

    fun add(event: String) {
        val line = "${synchronized(clock) { clock.format(Date()) }}  $event"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > LIMIT) lines.removeFirst()
        }
        val f = file ?: return
        writer.execute { runCatching { f.writeText(snapshot().joinToString("\n")) } }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        val f = file ?: return
        writer.execute { f.delete() }
    }

    /** Why Android ended the previous process of the app, once per ending. */
    private fun recordExit(context: Context) {
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences("playback-journal", Context.MODE_PRIVATE)
            val seen = prefs.getLong("lastExit", 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > seen && it.processName == context.packageName }
                .sortedBy { it.timestamp }
            for (e in exits) {
                val at = synchronized(clock) { clock.format(Date(e.timestamp)) }
                add("previous process ended at $at: ${reason(e.reason)}${e.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}" +
                    " · importance ${e.importance}")
            }
            exits.lastOrNull()?.let { last -> prefs.edit { putLong("lastExit", last.timestamp) } }
        }
    }

    internal fun reason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage (battery manager)"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by the system (often a manufacturer battery manager)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        ApplicationExitInfo.REASON_FREEZER -> "frozen by Android"
        ApplicationExitInfo.REASON_OTHER -> "other system reason"
        else -> "reason $code"
    }
}
