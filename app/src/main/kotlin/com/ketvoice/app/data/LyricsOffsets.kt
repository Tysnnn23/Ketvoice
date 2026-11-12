package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/** User adjustment only: provider timings remain unchanged. */
object LyricsOffsets {
    private const val LIMIT_MS = 10_000
    private var prefs: android.content.SharedPreferences? = null
    private val _values = MutableStateFlow<Map<String, Int>>(emptyMap())
    val values: StateFlow<Map<String, Int>> = _values

    fun key(trackId: String): String = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Synchronized fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("lyrics-offsets-v1", Context.MODE_PRIVATE)
        prefs = p
        _values.value = p.all.mapNotNull { (key, value) ->
            (value as? Int)?.let { key to it.coerceIn(-LIMIT_MS, LIMIT_MS) }
        }.toMap()
    }

    @Synchronized fun set(trackId: String, offsetMs: Int) {
        if (trackId.isBlank()) return
        val key = key(trackId)
        val offset = offsetMs.coerceIn(-LIMIT_MS, LIMIT_MS)
        _values.value = if (offset == 0) _values.value - key else _values.value + (key to offset)
        prefs?.edit()?.let { if (offset == 0) it.remove(key) else it.putInt(key, offset) }?.apply()
    }

    @Synchronized internal fun mergeBackup(offsets: Map<String, Int>) {
        val p = prefs ?: error("Lyrics offsets unavailable")
        val additions = offsets.filter { (key, value) ->
            key.matches(Regex("[0-9a-f]{64}")) && value in -LIMIT_MS..LIMIT_MS && !p.contains(key)
        }
        val editor = p.edit()
        additions.forEach { (key, value) -> editor.putInt(key, value) }
        check(editor.commit())
        _values.value = _values.value + additions
    }

    fun adjustedPosition(positionMs: Long, offsetMs: Int): Long = (positionMs + offsetMs).coerceAtLeast(0L)
    fun seekPosition(lineMs: Long, offsetMs: Int): Long = (lineMs - offsetMs).coerceAtLeast(0L)
}
