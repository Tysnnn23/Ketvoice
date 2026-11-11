package com.ketvoice.app.glass

import com.ketvoice.app.theme.DesignTokens

/** Tint affects glass controls/sheets only; contrast safeguards remain independent. */
object GlassTuning {
    fun sanitize(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    fun opticalTint(base: Float, amount: Float, dark: Boolean): Float =
        (base + sanitize(amount) * if (dark) .16f else .14f).coerceIn(0f, .45f)

    fun fallbackTint(amount: Float, dark: Boolean): Float {
        val safe = if (dark) DesignTokens.Glass.FALLBACK_DARK_TINT else DesignTokens.Glass.FALLBACK_LIGHT_TINT
        return safe + sanitize(amount) * .12f
    }
}
