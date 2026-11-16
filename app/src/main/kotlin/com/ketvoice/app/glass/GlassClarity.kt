package com.ketvoice.app.glass

import com.ketvoice.app.data.AppSettings

/**
 * Transparency of the glass, like the Liquid Glass slider of iOS 27: from very clear glass that
 * shows almost all of what lies behind it to tinted glass that is milkier and easier to read
 * through. It is one stepped value ([AppSettings.glassTintLevel]) and every glass surface of the
 * app reads it from here: the single and fused Liquid 27 passes, the blurred fallback below
 * Android 13 and in the performance mode, and the static glass.
 *
 * [DEFAULT] is the look Ketvoice had before the setting existed (a frost of 0); the two levels
 * under it are clearer than that, the four above it lead to the most tinted glass the earlier
 * continuous setting offered (a frost of 1).
 */
object GlassClarity {
    const val MIN = 0
    const val MAX = 6
    const val DEFAULT = 2
    /** Number of steps on the slider's travel (one fewer than the levels). */
    const val STEPS = MAX - MIN

    fun sanitize(level: Int): Int = level.coerceIn(MIN, MAX)

    /** The level every glass surface draws with right now. */
    val level: Int get() = sanitize(AppSettings.glassTintLevel.value)

    /** 0..1: how far towards tinted glass; 0 at the default level and clearer ones. */
    fun frost(level: Int = this.level): Float {
        val l = sanitize(level)
        return if (l <= DEFAULT) 0f else (l - DEFAULT).toFloat() / (MAX - DEFAULT)
    }

    /** 0..1: how much clearer than the default; 0 at the default level and more tinted ones. */
    fun clarity(level: Int = this.level): Float {
        val l = sanitize(level)
        return if (l >= DEFAULT) 0f else (DEFAULT - l).toFloat() / (DEFAULT - MIN)
    }

    /**
     * The level of a continuous tint of an earlier version (`glassTint`, 0 untouched .. 1 tinted):
     * 0 stays the default look, the rest falls on the nearest tinted step.
     */
    fun fromLegacyTint(tint: Float): Int {
        val t = GlassTuning.sanitize(tint)
        return sanitize(DEFAULT + Math.round(t * (MAX - DEFAULT)))
    }

    /** Blur under the Liquid 27 pass never goes below this; clearer levels lower the floor. */
    fun minimumBlurPx(level: Int = this.level): Float = Ket27Glass.MIN_BLUR_PX - (Ket27Glass.MIN_BLUR_PX - CLEAREST_BLUR_PX) * clarity(level)

    /** Strength of the veil of the blurred fallback (below Android 13, performance mode). */
    fun fallbackVeil(dark: Boolean, level: Int = this.level): Float =
        GlassTuning.fallbackTint(frost(level), dark) * (1f - FALLBACK_CLEARING * clarity(level))

    /** Opacity lost by the static glass at clearer levels (added back to its base by [frost]). */
    fun staticClearing(level: Int = this.level): Float = STATIC_CLEARING * clarity(level)

    /** The French name of a level, as the slider announces it. */
    fun label(level: Int): String = when (sanitize(level)) {
        0 -> "Très transparent"
        1 -> "Transparent"
        DEFAULT -> "Standard"
        3 -> "Légèrement teinté"
        4 -> "Teinté"
        5 -> "Très teinté"
        else -> "Le plus lisible"
    }

    const val CLEAREST_BLUR_PX = 5f
    private const val FALLBACK_CLEARING = .45f
    private const val STATIC_CLEARING = .16f
}
