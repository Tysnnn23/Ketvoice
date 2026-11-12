package com.ketvoice.app.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

object AppLocales {
    private const val PREFS = "ketvoice_settings"

    /**
     * The device language, independent from a per-app locale that a previous
     * version or an explicit choice may have pinned.
     */
    fun systemLanguage(): AppLanguage {
        val system = runCatching {
            android.content.res.Resources.getSystem().configuration.locales[0]
        }.getOrNull()
        return AppLanguage.systemDefault(system ?: java.util.Locale.getDefault())
    }

    /** Stored choice, or null while the app follows the system language. */
    fun chosenLanguage(base: Context): AppLanguage? {
        val prefs = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("languageChosen", false)) return null
        return prefs.getString("language", null)?.let(AppLanguage::fromLabel)
    }

    fun wrap(base: Context): Context {
        val language = chosenLanguage(base) ?: systemLanguage()
        val locale = LocaleList.forLanguageTags(language.code)
        val config = Configuration(base.resources.configuration).apply { setLocales(locale) }
        return base.createConfigurationContext(config)
    }

    /**
     * Aligns the Android per-app locale with the stored choice. When the user
     * follows the system language, any pinned locale is cleared so a later
     * system-language change is picked up without reinstalling.
     */
    fun sync(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val chosen = chosenLanguage(context)
        val target = if (chosen == null) LocaleList.getEmptyLocaleList()
        else LocaleList.forLanguageTags(chosen.code)
        if (manager.applicationLocales != target) {
            runCatching { manager.applicationLocales = target }
        }
    }

    /** Pass null to follow the device language again. */
    fun apply(context: Context, language: AppLanguage?) {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val target = language?.let { LocaleList.forLanguageTags(it.code) }
                ?: LocaleList.getEmptyLocaleList()
            runCatching { manager?.applicationLocales = target }
        } else {
            var current: Context? = context
            while (current is ContextWrapper && current !is Activity) current = current.baseContext
            (current as? Activity)?.recreate()
        }
    }
}
