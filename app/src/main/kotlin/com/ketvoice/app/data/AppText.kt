package com.ketvoice.app.data

import android.content.Context
import android.content.res.AssetManager
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The interface's translations. French is the source text and needs no dictionary; another language's dictionary
 * is read the first time a text is asked in it, and only that one (6.7). Until 6.6 every start read the whole
 * 1.9 MB file on the main thread before the first screen, and kept all ten dictionaries (and their 1 600 template
 * patterns) in memory, whatever the language, French included.
 */
object AppText {

    private val dictionaries = ConcurrentHashMap<String, UiTextDictionary>()
    /** Languages whose dictionary could not be read: asked once, not at every text. */
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var assets: AssetManager? = null
    @Volatile private var known: Pair<String, AppLanguage>? = null

    /** Remembers where the dictionaries are; nothing is read here. */
    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    fun language(): AppLanguage {
        val label = AppSettings.language.value
        known?.let { if (it.first == label) return it.second }
        return AppLanguage.fromLabel(label).also { known = label to it }
    }

    fun t(text: String): String {
        if (text.isBlank()) return text
        val language = language()
        if (language == AppLanguage.FRENCH) return text
        return dictionary(language.code)?.translate(text) ?: text
    }

    fun hasLanguage(code: String): Boolean = dictionary(code) != null

    /** The languages read so far. */
    internal fun loaded(): Set<String> = dictionaries.keys.toSet()

    fun clearForTests() {
        dictionaries.clear()
        missing.clear()
        known = null
    }

    private fun dictionary(code: String): UiTextDictionary? {
        dictionaries[code]?.let { return it }
        if (code in missing) return null
        val from = assets ?: return null
        synchronized(this) {
            dictionaries[code]?.let { return it }
            if (code in missing) return null
            val read = runCatching { entries(from, code) }
                .onFailure { android.util.Log.e("KetvoiceText", "Could not load bundled translations ($code)", it) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let(::UiTextDictionary)
            if (read == null) missing += code else dictionaries[code] = read
            return read
        }
    }

    /**
     * The entries of one language. The large file is read as a stream: the other languages are passed over
     * without building anything for them.
     */
    internal fun entries(assets: AssetManager, code: String): Map<String, String> {
        val map = HashMap<String, String>()
        JsonReader(assets.open("translations.json").bufferedReader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != code || reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (reader.peek() != JsonToken.STRING) { reader.skipValue(); continue }
                    val value = reader.nextString()
                    if (value.isNotBlank()) map[key] = value
                }
                reader.endObject()
            }
        }
        val visual = JSONObject(assets.open("visual-refresh-translations.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val keys = visual.getJSONArray("keys")
        val extra = visual.getJSONObject("translations").getJSONArray(code)
        check(extra.length() == keys.length()) { "Incomplete visual refresh translations" }
        for (index in 0 until keys.length()) map[keys.getString(index)] = extra.getString(index)
        return map
    }
}
