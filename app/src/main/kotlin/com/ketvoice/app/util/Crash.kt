package com.ketvoice.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Crash {

    private fun logsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }

    @Volatile private var installed = false

    /** Installed once per process, as early as possible (KetvoiceApp.onCreate), so the service's crashes are kept too. */
    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(logsDir(appCtx), "crash-${System.currentTimeMillis()}.txt").writeText(
                    buildString {
                        appendLine("Ketvoice — crash le $ts")
                        appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Android API : ${Build.VERSION.SDK_INT}")
                        appendLine("Ketvoice : ${com.ketvoice.app.BuildConfig.VERSION_NAME} (${com.ketvoice.app.BuildConfig.VERSION_CODE}, ${com.ketvoice.app.BuildConfig.FLAVOR})")
                        appendLine("Thread : ${thread.name}")
                        appendLine()
                        appendLine(Log.getStackTraceString(throwable))
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun pending(context: Context): List<File> =
        runCatching {
            logsDir(context).listFiles { _, name -> name.endsWith(".txt") }?.sortedBy { it.name }?.toList()
        }.getOrNull() ?: emptyList()

    fun clearAll(context: Context) {
        pending(context).forEach { runCatching { it.delete() } }
    }
}

/**
 * Crash loop guard (6.1.1, after 6.1.0 closed at once on the owner's phone): each launch of the screen is counted
 * as unsteady until it has lived [STEADY_MS]; when two launches in a row ended that way and a crash report is
 * there, the next launch opens [com.ketvoice.app.SafeModeActivity] instead of the app, with the report to share.
 */
object CrashLoop {
    private const val PREFS = "ketvoice-crash-loop"
    private const val UNSTEADY = "unsteadyLaunches"
    const val STEADY_MS = 10_000L
    const val LIMIT = 2

    private const val LAST_START = "lastLaunchAt"

    /**
     * Pure rule, host-tested: safe mode after [LIMIT] unsteady launches, and only when the newest crash report was
     * written after the previous launch began (an old report and a quick close are not a crash loop).
     */
    fun needsSafeMode(unsteadyLaunches: Int, newestReportAt: Long?, previousLaunchAt: Long): Boolean =
        unsteadyLaunches >= LIMIT && newestReportAt != null && newestReportAt >= previousLaunchAt

    /** The time a report was written, from its name `crash-<millis>.txt`. */
    fun reportTime(name: String): Long? = name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull()

    /** At the start of a launch: whether to open safe mode, then this launch counts as unsteady until it lasts. */
    fun beginLaunch(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val unsteady = prefs.getInt(UNSTEADY, 0)
        val newest = Crash.pending(context).mapNotNull { reportTime(it.name) }.maxOrNull()
        if (needsSafeMode(unsteady, newest, prefs.getLong(LAST_START, Long.MAX_VALUE))) return true
        prefs.edit().putInt(UNSTEADY, unsteady + 1).putLong(LAST_START, now).commit()
        return false
    }

    /** The app lived long enough: the count starts again. */
    fun steady(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(UNSTEADY, 0).commit()
    }

    /** « Relancer »: a fresh chance for the normal app. */
    fun reset(context: Context) = steady(context)
}
