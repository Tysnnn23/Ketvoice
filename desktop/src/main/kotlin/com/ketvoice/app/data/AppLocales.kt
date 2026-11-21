package com.ketvoice.app.data

import android.content.Context

/** The interface language on the desktop: the system's (Java's default locale) or the listener's choice. */
object AppLocales {
    private const val PREFS = "ketvoice_settings"

    fun systemLanguage(): AppLanguage = AppLanguage.systemDefault(java.util.Locale.getDefault())

    /** Stored choice, or null while the app follows the system language (same keys as on Android). */
    fun chosenLanguage(base: Context): AppLanguage? {
        val prefs = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("languageChosen", false)) return null
        return prefs.getString("language", null)?.let(AppLanguage::fromLabel)
    }
}
