package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

object SearchHistory {
    private var prefs: android.content.SharedPreferences? = null
    private val _queries = MutableStateFlow<List<String>>(emptyList())
    val queries: StateFlow<List<String>> = _queries
    fun init(context: Context) {
        prefs = context.getSharedPreferences("search-history-real", Context.MODE_PRIVATE)
        _queries.value = runCatching {
            val a = JSONArray(prefs!!.getString("queries", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }
    fun record(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        _queries.value = (listOf(q) + _queries.value.filterNot { it.equals(q, true) }).take(20)
        prefs?.edit()?.putString("queries", JSONArray(_queries.value).toString())?.apply()
    }
    fun clear() { _queries.value = emptyList(); prefs?.edit()?.remove("queries")?.apply() }
}
