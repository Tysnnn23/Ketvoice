package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.launch

/**
 * How the cover playing colours the whole interface. Each part follows its own setting, as the
 * listener chose it: pages with « Fond selon la pochette » (`coverBackground`), buttons, links and
 * every accent with « Couleur de la pochette » (`coverAccent`), the glass with « Verre teinté par
 * la pochette » (`coverTintedGlass`, see `glass/GlassTint.kt`).
 *
 * [tone] and [accent] are the cover's colours (from [CoverColors]); [toneAmount] and [accentAmount]
 * (0..1) are animated by the root so that colours glide in when a title starts, from one title to
 * the next, and back to the theme's own when it stops or the setting is turned off.
 *
 * Legibility comes first: whatever the cover, light or dark theme, labels keep at least
 * [CoverLegibility.TEXT] (WCAG AA) on the pages and cards, and so do the accent's links and the
 * label of a button filled with the accent.
 */
data class CoverLook(
    val tone: Color? = null,
    val toneAmount: Float = 0f,
    val accent: Color? = null,
    val accentAmount: Float = 0f,
) {
    fun apply(chosen: KetTheme, black: Boolean = false): KetTheme {
        val toned = tone?.takeIf { amount(toneAmount) > 0f }?.let { color ->
            if (black && chosen.isDark) {
                // Noir: the page stays pure black; only the cards take a hint of the cover, held as dim as a night page.
                val card = lerp(chosen.card, com.ketvoice.app.ui.sub.PageTint.nightCap(lerp(chosen.card, color, .22f)), amount(toneAmount))
                return@let chosen.copy(card = card, secondary = CoverLegibility.readableSecondary(chosen.secondary, chosen.background, card))
            }
            val full = com.ketvoice.app.ui.sub.artistPageTint(chosen.background, color, chosen.isDark)
            val page = lerp(chosen.background, full, amount(toneAmount))
            val card = lerp(page, chosen.onBackground, if (chosen.isDark) .08f else .05f)
            chosen.copy(background = page, card = card,
                secondary = CoverLegibility.readableSecondary(chosen.secondary, page, card))
        } ?: chosen
        val coverAccent = accent?.takeIf { amount(accentAmount) > 0f } ?: return toned
        val mixed = lerp(chosen.accent, coverAccent, amount(accentAmount))
        return toned.copy(accent = CoverLegibility.readableAccent(mixed, toned.background, toned.card))
    }

    private fun amount(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}

/**
 * A cover colour that glides: the first one fades in over the theme's own colour, the next ones
 * glide from the colour shown, and when there is none any more (the music stopped, the setting
 * was turned off) it fades back out. Between two titles the next cover's colour is not known for
 * a moment: the last one is held for [CoverGlide.GAP_MS] instead of dipping to the theme's colour.
 * Returns the colour (null before the first) and its presence, 0..1. Snaps under reduced motion.
 */
@androidx.compose.runtime.Composable
fun rememberCoverGlide(target: Color?, millis: Int): Pair<Color?, Float> {
    val color = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<androidx.compose.animation.core.Animatable<Color, androidx.compose.animation.core.AnimationVector4D>?>(null)
    }
    val presence = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(target) {
        val duration = com.ketvoice.app.ui.motion.Motion.duration(millis)
        if (target == null) {
            if (presence.value > 0f) kotlinx.coroutines.delay(CoverGlide.GAP_MS)
            presence.animateTo(0f, androidx.compose.animation.core.tween(duration))
            return@LaunchedEffect
        }
        val current = color.value
        if (current == null || presence.value == 0f) color.value = androidx.compose.animation.Animatable(target)
        else launch { current.animateTo(target, androidx.compose.animation.core.tween(duration)) }
        presence.animateTo(1f, androidx.compose.animation.core.tween(duration))
    }
    return color.value?.value to presence.value
}

object CoverGlide {
    /** How long the last cover colour is held while the next title's colour is read. */
    const val GAP_MS = 650L
}

/**
 * True inside a page that wears its cover's accent (7.3, album and artist pages): the Lossless mark and
 * the secondary capsule buttons take [KetTheme.accent] there instead of the neutral inks.
 */
val LocalCoverAccentMarks = androidx.compose.runtime.staticCompositionLocalOf { false }

/** What a page wearing its cover's [accent] provides: the marks above and the text selection in that colour. */
fun coverAccentLocals(accent: Color): Array<androidx.compose.runtime.ProvidedValue<*>> = arrayOf(
    LocalCoverAccentMarks provides true,
    androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
        androidx.compose.foundation.text.selection.TextSelectionColors(handleColor = accent, backgroundColor = accent.copy(alpha = .32f)))
