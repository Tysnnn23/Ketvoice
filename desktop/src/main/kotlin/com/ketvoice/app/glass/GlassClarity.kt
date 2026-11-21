package com.ketvoice.app.glass

/**
 * The glass-intensity scale shared with the phone's setting (same values as the Android
 * `GlassClarity`, whose rendering half needs Android's glass and contrast APIs). The desktop
 * glass material of the UI phase reads these; until then the setting is only stored.
 */
object GlassClarity {
    const val MIN = 0f
    const val MAX = 1f
    const val DEFAULT = .2f
    const val FROSTED = .6f
    const val TINTED = .9f
    const val TICKS = 20

    fun sanitize(position: Float): Float = if (position.isFinite()) position.coerceIn(MIN, MAX) else DEFAULT

    /** As on Android: a device still on 7.4 synced its levels and Teinté, not an intensity. */
    fun legacyKeys(keys: Collection<String>): List<String>? =
        if ("glassIntensity" in keys) null else listOf("glassTint", "glassTintLevel", "glassTinted").filter { it in keys }.takeIf { it.isNotEmpty() }
}
