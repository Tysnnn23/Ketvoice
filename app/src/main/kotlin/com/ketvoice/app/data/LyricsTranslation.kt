package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object LyricsTranslation {
    /** The translation the lyrics screen shows, line by line, while it is turned on (6.1): the lock screen shows it too. */
    data class Live(val trackId: String, val lines: Map<Int, String> = emptyMap())
    private val _live = kotlinx.coroutines.flow.MutableStateFlow<Live?>(null)
    val live: kotlinx.coroutines.flow.StateFlow<Live?> = _live

    /** The lyrics screen turned the translation of [trackId] on (an empty map until lines arrive) or off. */
    fun showing(trackId: String, on: Boolean) {
        if (on) _live.value = Live(trackId) else if (_live.value?.trackId == trackId) _live.value = null
    }

    /** A translated line arrived on the lyrics screen. */
    fun translated(trackId: String, index: Int, text: String) {
        val current = _live.value ?: return
        if (current.trackId != trackId || current.lines[index] == text) return
        _live.value = current.copy(lines = current.lines + (index to text))
    }

    suspend fun stream(lines: List<MusicApi.LyricLine>, target: String, active: Int,
                       onLine: (Int, String) -> Unit) = coroutineScope {
        val semaphore = Semaphore(3)
        val source = AppSettings.translationSource.value
        val ordered = lines.indices.sortedBy { kotlin.math.abs(it - active.coerceAtLeast(0)) }
        ordered.chunked(3).forEach { indices -> launch(Dispatchers.IO) {
            semaphore.withPermit {
                val missing = mutableListOf<Int>()
                for (i in indices) {
                    val native = lines[i].translations[target.lowercase()] ?: lines[i].translations.entries
                        .firstOrNull { it.key.startsWith("${target.lowercase()}-") }?.value
                    val cached = native ?: LyricsDiskCache.read("subtitle|$source|$target|${lines[i].text}")?.second?.firstOrNull()?.text
                    if (cached != null) withContext(Dispatchers.Main) { onLine(i, cached) } else missing += i
                }
                if (missing.isNotEmpty()) {
                    val result = MusicApi.translate(missing.joinToString("\n") { lines[it].text }, target)
                        ?.split('\n')?.takeIf { it.size == missing.size }
                    result?.forEachIndexed { offset, text ->
                        val i = missing[offset]
                        LyricsDiskCache.write("subtitle|$source|$target|${lines[i].text}", "Traduction" to listOf(MusicApi.LyricLine(-1, text)))
                        withContext(Dispatchers.Main) { onLine(i, text) }
                    }
                }
            }
        } }
    }
}
