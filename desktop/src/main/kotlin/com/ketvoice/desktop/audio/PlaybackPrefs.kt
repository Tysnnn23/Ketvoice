package com.ketvoice.desktop.audio

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * Windows 2.0's playback settings, kept on this PC only (the `desktop` preferences; they depend on its disk and sound
 * card, so they never follow the account): « Taille du cache » and « Préparer les favoris », « Lecture sans blanc »
 * (true gapless, off by default) and « Mode exclusif » (WASAPI bit-perfect, off by default) with its device.
 */
object PlaybackPrefs {
    private const val CACHE_GB = "audioCacheGb"
    private const val WARM = "audioCacheWarm"
    private const val GAPLESS = "trueGapless"
    private const val EXCLUSIVE = "exclusiveAudio"

    private val prefs by lazy { Context.app.getSharedPreferences("desktop", Context.MODE_PRIVATE) }
    private val _cacheGb = mutableStateOf(StreamCache.DEFAULT_GB)
    private val _warm = mutableStateOf(true)
    private val _gapless = mutableStateOf(false)
    private val _exclusive = mutableStateOf(false)
    private var loaded = false

    val cacheGb: State<Int> get() = load()._cacheGb
    val warm: State<Boolean> get() = load()._warm
    val trueGapless: State<Boolean> get() = load()._gapless
    val exclusive: State<Boolean> get() = load()._exclusive

    fun setCacheGb(gb: Int) {
        val v = gb.coerceIn(1, 200)
        load()._cacheGb.value = v
        StreamCache.limitBytes = v.toLong() shl 30
        runCatching { prefs.edit().putInt(CACHE_GB, v).apply() }
        Thread { StreamCache.trim() }.apply { isDaemon = true; start() }
    }

    fun setWarm(on: Boolean) { load()._warm.value = on; runCatching { prefs.edit().putBoolean(WARM, on).apply() } }
    fun setTrueGapless(on: Boolean) { load()._gapless.value = on; runCatching { prefs.edit().putBoolean(GAPLESS, on).apply() } }
    fun setExclusive(on: Boolean) { load()._exclusive.value = on; runCatching { prefs.edit().putBoolean(EXCLUSIVE, on).apply() } }

    @Synchronized private fun load(): PlaybackPrefs {
        if (loaded) return this
        loaded = true
        runCatching {
            _cacheGb.value = prefs.getInt(CACHE_GB, StreamCache.DEFAULT_GB).coerceIn(1, 200)
            _warm.value = prefs.getBoolean(WARM, true)
            _gapless.value = prefs.getBoolean(GAPLESS, false)
            _exclusive.value = prefs.getBoolean(EXCLUSIVE, false)
        }
        StreamCache.limitBytes = _cacheGb.value.toLong() shl 30
        return this
    }

    /** Reads the stored values (Main, at start) so the cache limit holds before the first stream opens. */
    fun init() { load() }

    /** Tests. */
    internal fun resetForTest() { loaded = true; _cacheGb.value = StreamCache.DEFAULT_GB; _warm.value = true; _gapless.value = false; _exclusive.value = false }
}
