package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Automatic contrast helpers.
 *
 * The Liquid Glass surface samples whatever is behind it — album art, a bright
 * photo, a dark page — so labels can silently lose legibility. These helpers
 * compute, at runtime, the tint / veil / foreground that keeps content readable
 * on ANY background, in light and dark themes alike.
 */
object Contrast {

    /** Relative luminance (WCAG) of a colour. */
    fun luminanceOf(c: Color): Float = c.luminance()

    /** Whether the background reads as "light" (needs dark ink on top). */
    fun isLight(background: Color): Boolean = background.luminance() > 0.42f

    /**
     * A readable foreground for text/icons over `background`.
     * Returns high-opacity black on light surfaces and near-white on dark ones,
     * so the result clears ~WCAG AA on typical artwork backdrops.
     */
    fun inkOn(background: Color): Color =
        if (isLight(background)) Color.Black.copy(alpha = 0.90f)
        else Color.White.copy(alpha = 0.94f)

    /**
     * Legibility veil to mix over the sampled backdrop *under* the label.
     * On an already-readable backdrop returns a near-zero veil; when the backdrop
     * luminance is close to the ink luminance it lifts/darkens proportionally.
     */
    fun veilAlpha(background: Color, darkInk: Boolean): Float {
        val lum = background.luminance()
        return if (darkInk) {
            // Dark ink on (possibly) dark art → lift toward white as lum falls.
            (1f - smoothstep(0.30f, 0.85f, lum)) * 0.34f
        } else {
            // Light ink on (possibly) light art → deepen toward black as lum rises.
            smoothstep(0.18f, 0.72f, lum) * 0.30f
        }
    }

    /** WCAG contrast ratio between two opaque colours (1..21). */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /** True when `fg` over `bg` meets the WCAG AA normal-text threshold (4.5:1). */
    fun meetsAA(fg: Color, bg: Color): Boolean = contrastRatio(fg, bg) >= 4.5f

    /**
     * Blend `overlay` (e.g. a sampled album colour) at [strength] over [base]
     * while preserving [base]'s alpha — used for tinted surfaces that must stay
     * on-brand but readable. Strength is clamped to 0..1.
     */
    fun blend(base: Color, overlay: Color, strength: Float): Color {
        val s = strength.coerceIn(0f, 1f)
        return Color(
            red = base.red + (overlay.red - base.red) * s,
            green = base.green + (overlay.green - base.green) * s,
            blue = base.blue + (overlay.blue - base.blue) * s,
            alpha = base.alpha
        )
    }

    /**
     * Pick a glass surface tone appropriate for the backdrop. In dark material,
     * a bright backdrop is darkened slightly and vice-versa, so the glass always
     * separates from what is behind it instead of dissolving into it.
     */
    fun adaptSurface(base: Color, backdrop: Color, dark: Boolean): Color {
        val lum = backdrop.luminance()
        val shift = if (dark) {
            // Dark glass over bright art: deepen a touch to keep separation.
            (lum - 0.5f).coerceAtLeast(0f) * 0.10f
        } else {
            // Light glass over dark art: cool toward white to keep separation.
            (0.5f - lum).coerceAtLeast(0f) * 0.08f
        }
        return if (dark) base.darken(shift) else base.lighten(shift)
    }

    // ── colour maths ──
    private fun Color.lighten(amount: Float): Color {
        val a = amount.coerceIn(0f, 1f)
        return Color(
            red = min(1f, red + (1f - red) * a),
            green = min(1f, green + (1f - green) * a),
            blue = min(1f, blue + (1f - blue) * a),
            alpha = alpha
        )
    }

    private fun Color.darken(amount: Float): Color {
        val a = amount.coerceIn(0f, 1f)
        return Color(red = max(0f, red * (1f - a)), green = max(0f, green * (1f - a)),
            blue = max(0f, blue * (1f - a)), alpha = alpha)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // keep pow referenced for future gamma work without a warning
    @Suppress("unused")
    private fun gamma(x: Float) = x.coerceIn(0f, 1f).pow(2.2f)
}
