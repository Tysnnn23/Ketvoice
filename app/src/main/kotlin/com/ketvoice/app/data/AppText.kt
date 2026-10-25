package com.ketvoice.app.data

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object AppText {

    private val dictionaries = ConcurrentHashMap<String, UiTextDictionary>()
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching {
                val raw = context.applicationContext.assets
                    .open("translations.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val root = JSONObject(raw)
                AppLanguage.all.forEach { language ->
                    if (language == AppLanguage.FRENCH) return@forEach
                    val obj = root.optJSONObject(language.code) ?: return@forEach
                    val map = HashMap<String, String>(obj.length())
                    obj.keys().forEach { key ->
                        val value = obj.optString(key)
                        if (value.isNotBlank()) map[key] = value
                    }
                    if (map.isNotEmpty()) dictionaries[language.code] = UiTextDictionary(map)
                }
            }.onFailure { android.util.Log.e("KetvoiceText", "Could not load bundled translations", it) }
            initialized = true
        }
    }

    fun language(): AppLanguage = AppLanguage.fromLabel(AppSettings.language.value)

    fun t(text: String): String {
        if (text.isBlank()) return text
        val language = language()
        if (language == AppLanguage.FRENCH) return text
        return dictionaries[language.code]?.translate(text) ?: text
    }

    fun hasLanguage(code: String): Boolean = dictionaries.containsKey(code)

    fun clearForTests() {
        dictionaries.clear()
        initialized = false
    }

}
