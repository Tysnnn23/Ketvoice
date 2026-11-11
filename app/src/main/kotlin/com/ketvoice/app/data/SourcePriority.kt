package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Two persisted priorities — playback and download — over the same sources.
 * Each mode can be reordered and disabled independently, so "download lossless
 * first" no longer forces lossless playback (or the reverse).
 */
object SourcePriority {
    enum class Mode { PLAYBACK, DOWNLOAD }
    data class Entry(val id: String, val name: String, val enabled: Boolean)
    private val builtin = listOf("api:um", "api:last", "youtube")
    private var prefs: android.content.SharedPreferences? = null

    val playbackOrder = MutableStateFlow<List<String>>(emptyList())
    val downloadOrder = MutableStateFlow<List<String>>(emptyList())
    val playbackDisabled = MutableStateFlow<Set<String>>(emptySet())
    val downloadDisabled = MutableStateFlow<Set<String>>(emptySet())

    fun orderFlow(mode: Mode): StateFlow<List<String>> = if (mode == Mode.DOWNLOAD) downloadOrder else playbackOrder
    fun disabledFlow(mode: Mode): StateFlow<Set<String>> = if (mode == Mode.DOWNLOAD) downloadDisabled else playbackDisabled

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("ketvoice_source_priority", Context.MODE_PRIVATE)
        prefs = p
        playbackOrder.value = read(p, "order")
        downloadOrder.value = read(p, "download_order")
        playbackDisabled.value = p.getStringSet("disabled", emptySet()).orEmpty().toSet()
        downloadDisabled.value = p.getStringSet("download_disabled", emptySet()).orEmpty().toSet()
    }

    private fun read(p: android.content.SharedPreferences, key: String): List<String> =
        runCatching { val a = JSONArray(p.getString(key, "[]")); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())

    internal fun reconcile(saved: List<String>, available: List<String>): List<String> =
        (saved.filter { it in available } + available).distinct()

    internal fun promotePlaybackOnce(id: String) {
        val p = prefs ?: return
        if (p.getBoolean("lossless_promoted_v1", false)) return
        val next = listOf(id) + playbackOrder.value.filterNot { it == id }
        playbackOrder.value = next
        p.edit().putString("order", JSONArray(next).toString()).putBoolean("lossless_promoted_v1", true).apply()
    }

    fun entries(mode: Mode = Mode.PLAYBACK): List<Entry> {
        val custom = CustomSources.sources.value
        val available = builtin + custom.map { "cs:${it.id}" }
        val saved = orderFlow(mode).value
        val off = disabledFlow(mode).value
        return reconcile(saved, available).mapNotNull { id ->
            when (id) {
                "api:um" -> Entry(id, "Ketvoice API · MP3", id !in off)
                "api:last" -> Entry(id, "Ketvoice API · sans perte", id !in off)
                "youtube" -> Entry(id, "YouTube Music · audio adaptatif", id !in off)
                else -> custom.firstOrNull { "cs:${it.id}" == id }?.let { Entry(id, it.name, it.enabled && id !in off) }
            }
        }
    }

    fun move(id: String, delta: Int, mode: Mode = Mode.PLAYBACK) {
        val list = entries(mode).map { it.id }.toMutableList()
        val from = list.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        list.add(to, list.removeAt(from))
        if (mode == Mode.DOWNLOAD) downloadOrder.value = list else playbackOrder.value = list
        prefs?.edit()?.putString(if (mode == Mode.DOWNLOAD) "download_order" else "order", JSONArray(list).toString())?.apply()
    }

    fun setEnabled(id: String, enabled: Boolean, mode: Mode = Mode.PLAYBACK) {
        if (id.startsWith("cs:") && mode == Mode.PLAYBACK) {
            CustomSources.setEnabled(id.removePrefix("cs:"), enabled)
            return
        }
        val current = disabledFlow(mode).value
        val next = if (enabled) current - id else current + id
        if (mode == Mode.DOWNLOAD) downloadDisabled.value = next else playbackDisabled.value = next
        prefs?.edit()?.putStringSet(if (mode == Mode.DOWNLOAD) "download_disabled" else "disabled", next)?.apply()
    }
}
