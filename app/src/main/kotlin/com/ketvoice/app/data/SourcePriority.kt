package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/** One persisted priority shared by streaming and downloads, including built-in sources. */
object SourcePriority {
    data class Entry(val id: String, val name: String, val enabled: Boolean)
    private val builtin = listOf("api:um", "api:last", "youtube")
    private var prefs: android.content.SharedPreferences? = null
    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order
    private val _disabled = MutableStateFlow<Set<String>>(emptySet())
    val disabled: StateFlow<Set<String>> = _disabled

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("ketvoice_source_priority", Context.MODE_PRIVATE)
        prefs = p
        _order.value = runCatching { val a = JSONArray(p.getString("order", "[]")); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
        _disabled.value = p.getStringSet("disabled", emptySet()).orEmpty().toSet()
    }
    internal fun reconcile(saved: List<String>, available: List<String>): List<String> =
        (saved.filter { it in available } + available).distinct()

    fun entries(): List<Entry> {
        val custom = CustomSources.sources.value
        return reconcile(_order.value, builtin + custom.map { "cs:${it.id}" }).mapNotNull { id ->
            when (id) {
                "api:um" -> Entry(id, "Ketvoice API · MP3", id !in _disabled.value)
                "api:last" -> Entry(id, "Ketvoice API · sans perte", id !in _disabled.value)
                "youtube" -> Entry(id, "YouTube Music · audio adaptatif", id !in _disabled.value)
                else -> custom.firstOrNull { "cs:${it.id}" == id }?.let { Entry(id, it.name, it.enabled) }
            }
        }
    }
    fun move(id: String, delta: Int) {
        val list = entries().map { it.id }.toMutableList()
        val from = list.indexOf(id); if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex); if (to == from) return
        list.add(to, list.removeAt(from)); _order.value = list
        prefs?.edit()?.putString("order", JSONArray(list).toString())?.apply()
    }
    fun setEnabled(id: String, enabled: Boolean) {
        if (id.startsWith("cs:")) { CustomSources.setEnabled(id.removePrefix("cs:"), enabled); return }
        if (id !in builtin) return
        _disabled.value = if (enabled) _disabled.value - id else _disabled.value + id
        prefs?.edit()?.putStringSet("disabled", _disabled.value)?.apply()
    }
}
