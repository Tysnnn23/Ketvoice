package com.ketvoice.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture tout crash non géré dans un fichier texte des fichiers de l'app
 * (Android/data/com.ketvoice.app/files/logs/). Au lancement suivant,
 * MainActivity lit les rapports en attente et permet de les copier/partager
 * sans PC ni ADB.
 */
object Crash {

    private fun logsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }

    fun install(context: Context) {
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
                        appendLine("Thread : ${thread.name}")
                        appendLine()
                        appendLine(Log.getStackTraceString(throwable))
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Rapports en attente, du plus ancien au plus récent. */
    fun pending(context: Context): List<File> =
        runCatching {
            logsDir(context).listFiles { _, name -> name.endsWith(".txt") }?.sortedBy { it.name }?.toList()
        }.getOrNull() ?: emptyList()

    fun clearAll(context: Context) {
        pending(context).forEach { runCatching { it.delete() } }
    }
}
