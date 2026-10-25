package com.ketvoice.app.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

object AppLocales {
    fun wrap(base: Context): Context {
        val label=base.getSharedPreferences("ketvoice_settings",Context.MODE_PRIVATE).getString("language","Français")
        val locale=LocaleList.forLanguageTags(AppLanguage.fromLabel(label).code)
        val config=Configuration(base.resources.configuration).apply { setLocales(locale) }
        return base.createConfigurationContext(config)
    }
    fun apply(context: Context, language: AppLanguage) {
        if(Build.VERSION.SDK_INT>=33){
            context.getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags(language.code)
        } else {
            var current:Context?=context
            while(current is ContextWrapper && current !is Activity)current=current.baseContext
            (current as? Activity)?.recreate()
        }
    }
}
