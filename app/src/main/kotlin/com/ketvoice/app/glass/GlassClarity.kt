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

    /**
     * « Teinté » (7.1): the listener's Clair / Teinté choice ([AppSettings.glassTinted]), or Android's
     * high-contrast text ([HighContrastText]), which asks for the tinted glass whatever the choice.
     * Apple's Tinted mode of iOS 26.1 was found too close to Clear: here it is clearly more opaque.
     */
    val tinted: Boolean get() = AppSettings.glassTinted.value || HighContrastText.on.value

    /** The frost a surface draws with: the level's own, lifted to at least [TINTED_FROST] when [tinted]. */
    fun frostFor(level: Int, tinted: Boolean): Float = if (tinted) maxOf(frost(level), TINTED_FROST) else frost(level)

    /** Tinted glass is never clearer than the default. */
    fun clarityFor(level: Int, tinted: Boolean): Float = if (tinted) 0f else clarity(level)

    /**
     * The least share of milk a surface keeps over what lies behind it, whatever the backdrop. Menus,
     * sheets and dialogs ([GlassRole.Sheet]) always keep far more than the navigation (which keeps none
     * in Clair), so their text reads over any artwork; Teinté lifts both.
     */
    fun body(role: GlassRole, tinted: Boolean, level: Int = this.level): Float = when {
        role == GlassRole.Sheet -> if (tinted) SHEET_TINTED_BODY else SHEET_BODY
        tinted -> TINTED_BODY + (TINTED_BODY_MAX - TINTED_BODY) * frost(level)
        else -> 0f
    }

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
    fun fallbackVeil(dark: Boolean, level: Int = this.level, tinted: Boolean = false, role: GlassRole = GlassRole.Control): Float =
        maxOf(GlassTuning.fallbackTint(frostFor(level, tinted), dark) * (1f - FALLBACK_CLEARING * clarityFor(level, tinted)),
            body(role, tinted, level))

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
    const val TINTED_FROST = .75f
    const val TINTED_BODY = .62f
    const val TINTED_BODY_MAX = .72f
    const val SHEET_BODY = .84f
    const val SHEET_TINTED_BODY = .93f
    private const val FALLBACK_CLEARING = .45f
    private const val STATIC_CLEARING = .16f
}
