package com.ketvoice.app.glass

import com.ketvoice.app.data.AppSettings

/**
 * The intensity of the glass (7.5, « Curseur d'intensité du verre »), like the Liquid Glass slider of
 * iOS 27: one continuous value ([AppSettings.glassIntensity], 0..1) from very clear glass that shows
 * almost all of what lies behind it to opaque glass that reads over anything. It replaces both the
 * stepped transparency of 6.0 (`glassTintLevel`, 0..6) and the Clair / Teinté switch of 7.1
 * (`glassTinted`), which overlapped: the old clear levels lie on the first part of the travel, Teinté
 * near its opaque end ([fromLevel]). Every navigation glass surface reads it from here: the single and
 * fused Liquid 27 passes, the blurred fallback below Android 13 and in the performance mode, and the
 * static glass.
 *
 * The travel has three parts: up to [DEFAULT] the glass clears ([clarity]); from there to [FROSTED] it
 * frosts ([frost]); past [FROSTED] it keeps more and more milk whatever the backdrop ([opacity]).
 */
object GlassClarity {
    const val MIN = 0f
    const val MAX = 1f
    /** The look Ketvoice always had (the old level 2, « Clair »). */
    const val DEFAULT = .2f
    /** Fully frosted (the old level 6); past it the glass turns opaque. */
    const val FROSTED = .6f
    /** Where « Teinté » of 7.1 lands, and the least Android's high-contrast text allows. */
    const val TINTED = .9f
    /** Ticks felt along the slider's travel. */
    const val TICKS = 20

    fun sanitize(position: Float): Float = if (position.isFinite()) position.coerceIn(MIN, MAX) else DEFAULT

    /** The intensity every glass surface draws with right now: high-contrast text lifts it to [TINTED]. */
    val position: Float get() = sanitize(AppSettings.glassIntensity.value).let { if (HighContrastText.on.value) maxOf(it, TINTED) else it }

    /** The position of a setting of 6.0 to 7.4: a level of 0..6, and whether « Teinté » was on. */
    fun fromLevel(level: Int, tinted: Boolean): Float {
        val clear = level.coerceIn(0, 6) * (FROSTED / 6f)
        return if (tinted) TINTED + (MAX - TINTED) * frost(clear) else clear
    }

    /** The keys of earlier glass settings among [keys], when they come without an intensity (a backup or a device of 7.4 or older). */
    fun legacyKeys(keys: Collection<String>): List<String>? =
        if ("glassIntensity" in keys) null else listOf("glassTint", "glassTintLevel", "glassTinted").filter { it in keys }.takeIf { it.isNotEmpty() }

    /** 0..1: how far towards frosted glass; 0 at the default and clearer. */
    fun frost(position: Float = this.position): Float = ((sanitize(position) - DEFAULT) / (FROSTED - DEFAULT)).coerceIn(0f, 1f)

    /** 0..1: how much clearer than the default; 0 at the default and more opaque. */
    fun clarity(position: Float = this.position): Float = ((DEFAULT - sanitize(position)) / DEFAULT).coerceIn(0f, 1f)

    /** 0..1: how far into the opaque part of the travel. */
    fun opacity(position: Float = this.position): Float = ((sanitize(position) - FROSTED) / (MAX - FROSTED)).coerceIn(0f, 1f)

    /**
     * The least share of milk a surface keeps over what lies behind it, whatever the backdrop. Menus,
     * sheets and dialogs ([GlassRole.Sheet]) always keep far more than the navigation (which keeps none
     * until the opaque part of the travel), so their text reads over any artwork.
     */
    fun body(role: GlassRole, position: Float = this.position): Float {
        val o = opacity(position)
        return if (role == GlassRole.Sheet) SHEET_BODY + (SHEET_OPAQUE_BODY - SHEET_BODY) * o
            else OPAQUE_BODY * kotlin.math.sqrt(o)
    }

    /**
     * The position of a continuous tint of 5.x (`glassTint`, 0 untouched .. 1 tinted): 0 stays the
     * default look, the rest falls between the default and fully frosted.
     */
    fun fromLegacyTint(tint: Float): Float = DEFAULT + (FROSTED - DEFAULT) * GlassTuning.sanitize(tint)

    /** Blur under the Liquid 27 pass never goes below this; clearer glass lowers the floor. */
    fun minimumBlurPx(position: Float = this.position): Float =
        Ket27Glass.MIN_BLUR_PX - (Ket27Glass.MIN_BLUR_PX - CLEAREST_BLUR_PX) * clarity(position)

    /** Strength of the veil of the blurred fallback (below Android 13, performance mode). */
    fun fallbackVeil(dark: Boolean, position: Float = this.position, role: GlassRole = GlassRole.Control): Float =
        maxOf(GlassTuning.fallbackTint(frost(position), dark) * (1f - FALLBACK_CLEARING * clarity(position)), body(role, position))

    /** Opacity lost by the static glass on clearer glass, and gained on opaque glass. */
    fun staticShift(position: Float = this.position): Float =
        frost(position) * .08f - STATIC_CLEARING * clarity(position) + OPAQUE_STATIC_LIFT * opacity(position)

    /** The French name of a position, as the slider announces it. */
    fun label(position: Float): String {
        val p = sanitize(position)
        return when {
            p < .05f -> "Très transparent"
            p < .14f -> "Transparent"
            p < .28f -> "Standard"
            p < .42f -> "Légèrement teinté"
            p < .62f -> "Teinté"
            p < .84f -> "Très teinté"
            else -> "Le plus lisible"
        }
    }

    const val CLEAREST_BLUR_PX = 5f
    const val OPAQUE_BODY = .72f
    const val SHEET_BODY = .84f
    const val SHEET_OPAQUE_BODY = .93f
    private const val FALLBACK_CLEARING = .45f
    private const val STATIC_CLEARING = .16f
    private const val OPAQUE_STATIC_LIFT = .12f
}
