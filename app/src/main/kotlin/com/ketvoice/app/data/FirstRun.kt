package com.ketvoice.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * The welcome of a new install (6.2): language, playlists, Ketmix and voice, shown once to a listener who has just
 * installed Ketvoice, never to one who updates.
 *
 * The decision is taken once, the first time this install reads its settings ([SettingsStore.load], from the app or
 * from the playback service), before anything is written: an install is new when it has no settings yet, no
 * listening history and nothing downloaded. It is stored at once in the non-backed-up `ketvoice-editions`
 * preferences, so a welcome left halfway comes back at the next launch, and an update never sees it (its settings
 * already exist, written at least by the first `onStop`). The app's data is never backed up (`allowBackup` false).
 */
object FirstRun {
    private const val PREFS = "ketvoice-editions"
    private const val KEY = "onboarding"
    const val PENDING = "pending"
    const val DONE = "done"

    /** True while the welcome is to be shown (read by the activity). */
    val pending = mutableStateOf(false)

    /** A new install: nothing stored by an earlier run of Ketvoice. */
    fun isFresh(settingsEntries: Int, historyEntries: Int, downloads: Int): Boolean =
        settingsEntries == 0 && historyEntries == 0 && downloads == 0

    /** What to store: an earlier decision is kept; otherwise a new install starts the welcome. */
    fun decide(stored: String?, fresh: Boolean): String = when (stored) {
        PENDING, DONE -> stored
        else -> if (fresh) PENDING else DONE
    }

    /** Called with the settings before they are read; decides once and publishes [pending]. */
    fun init(context: Context, settings: SharedPreferences) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        val state = if (stored == PENDING || stored == DONE) stored else {
            val history = runCatching { app.getSharedPreferences("listening-history", Context.MODE_PRIVATE).all.size }.getOrDefault(1)
            val downloads = runCatching { File(app.filesDir, "downloads").list()?.size ?: 0 }.getOrDefault(1)
            decide(null, isFresh(settings.all.size, history, downloads)).also { prefs.edit().putString(KEY, it).commit() }
        }
        pending.value = state == PENDING
    }

    /** The welcome was finished or skipped: it never comes back. */
    fun finish(context: Context) {
        pending.value = false
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, DONE).apply()
    }
}
