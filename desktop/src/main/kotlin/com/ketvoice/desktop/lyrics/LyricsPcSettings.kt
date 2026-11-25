package com.ketvoice.desktop.lyrics

import androidx.compose.runtime.mutableStateOf

/**
 * The lyrics settings that belong to this PC (Windows 2.0, never synced: a laptop has no word sync by AI): the word
 * sync, the translation under every line, the floating desktop lyrics and their place. Stored in the
 * `ketvoice_lyrics_pc` preferences.
 */
object LyricsPcSettings {
    private const val NAME = "ketvoice_lyrics_pc"
    /** « Synchronisation des mots par IA »: on, once its model is downloaded (on a desktop PC in « Maximum »). */
    val aiSync = mutableStateOf(true)
    /** « Traduction sous chaque ligne » (off: under the sung line only, as before). */
    val translateEvery = mutableStateOf(false)
    /** « Paroles flottantes sur le bureau » (optional, off by default). */
    val floating = mutableStateOf(false)
    /** The floating band can be moved (unlocked) or lets clicks through to what is under it (locked). */
    val floatingLocked = mutableStateOf(true)
    /** Which monitor the band is on (index in the system's list; 0 the first). */
    val floatingMonitor = mutableStateOf(0)
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun load(context: android.content.Context = android.content.Context.app) {
        val p = context.getSharedPreferences(NAME, android.content.Context.MODE_PRIVATE)
        prefs = p
        aiSync.value = p.getBoolean("aiSync", true)
        translateEvery.value = p.getBoolean("translateEvery", false)
        floating.value = p.getBoolean("floating", false)
        floatingLocked.value = p.getBoolean("floatingLocked", true)
        floatingMonitor.value = p.getInt("floatingMonitor", 0).coerceIn(0, 15)
    }

    fun save() {
        val p = prefs ?: return
        p.edit().putBoolean("aiSync", aiSync.value).putBoolean("translateEvery", translateEvery.value)
            .putBoolean("floating", floating.value).putBoolean("floatingLocked", floatingLocked.value)
            .putInt("floatingMonitor", floatingMonitor.value).apply()
    }

    /** The band's place on its monitor, as a share of the monitor (0..1), or null for the default (bottom centre). */
    fun floatingPlace(): Pair<Float, Float>? = prefs?.let { p ->
        val x = p.getFloat("floatingX", Float.NaN); val y = p.getFloat("floatingY", Float.NaN)
        if (x.isNaN() || y.isNaN()) null else x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }

    fun saveFloatingPlace(x: Float, y: Float) { prefs?.edit()?.putFloat("floatingX", x)?.putFloat("floatingY", y)?.apply() }

    /** Free text values (the choices of lyrics per title) in the same file. */
    fun string(key: String): String? = prefs?.getString(key, null)
    fun putString(key: String, value: String?) { prefs?.edit()?.putString(key, value)?.apply() }
}
