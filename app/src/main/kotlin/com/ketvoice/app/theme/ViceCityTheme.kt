package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color

/**
 * The look of the Vice City limited edition (until 10 December 2026; see `data/ViceCityEdition.kt`), redrawn in 6.5
 * after the owner found the neon night ugly: the way a music app wears a limited event — one accent colour and one
 * illustration, everything else left as the listener set it (light or dark, glass, layout).
 *
 *  - The accent is a flamingo pink, deeper on a light page and lighter on a dark one, and always run through
 *    [CoverLegibility.readableAccent] so links, tinted capsules and filled buttons keep WCAG AA (ViceCityThemeTest).
 *  - The illustration is a flat Ocean Drive sunset (`ui/vicecity/ViceCityScene.kt`): sand-pink sky, a coral sun, a
 *    mint sea and two palm silhouettes, in the pastel colours of Miami Beach's restored Art Deco district; drawn
 *    once, on the Vice FM card at the top of Home, on the welcome screen and in Settings. No glow, no grid, no motion.
 */
object ViceCityTheme {
    const val ID = "vicecity"

    /** The accent on a light page and on a dark one, each at AA as a link and as a tinted capsule's label. */
    val accentLight = Color(0xFFAD2756)
    val accentDark = Color(0xFFFF7AA8)

    /** The colours of one version of the illustration (day for the light theme, dusk for the dark one). */
    class Scene(val skyHigh: Color, val skyLow: Color, val sun: Color, val sea: Color, val glint: Color, val palm: Color)

    val day = Scene(skyHigh = Color(0xFFFDEDE4), skyLow = Color(0xFFFAD6C6), sun = Color(0xFFFF8E72),
        sea = Color(0xFF9AD8CC), glint = Color(0xFFFFF6EF), palm = Color(0xFF2F5E62))
    val dusk = Scene(skyHigh = Color(0xFF4A3866), skyLow = Color(0xFF8C5470), sun = Color(0xFFFF8C7A),
        sea = Color(0xFF23565C), glint = Color(0xFFFFB3A1), palm = Color(0xFF100C17))

    fun scene(dark: Boolean): Scene = if (dark) dusk else day

    /** The listener's light or dark Ket 27 theme with the flamingo accent. */
    fun theme(dark: Boolean): KetTheme {
        val plain = Ket27.theme(dark, if (dark) accentDark else accentLight)
        return plain.copy(accent = CoverLegibility.readableAccent(plain.accent, plain.background, plain.card), viceCity = true)
    }
}
