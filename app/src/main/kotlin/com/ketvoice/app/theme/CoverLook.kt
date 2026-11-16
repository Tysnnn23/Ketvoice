package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
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
    fun apply(chosen: KetTheme): KetTheme {
        val toned = tone?.takeIf { amount(toneAmount) > 0f }?.let { color ->
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

/** Contrast guarantees of colours taken from a cover (WCAG 2 relative luminance and ratios). */
object CoverLegibility {
    /** WCAG AA for body text. */
    const val TEXT = 4.5f

    /** Contrast of [fg] (possibly translucent) drawn over the opaque [bg]. */
    fun contrast(fg: Color, bg: Color): Float {
        val ink = if (fg.alpha < 1f) fg.compositeOver(bg.copy(alpha = 1f)) else fg
        val a = ink.luminance() + .05f; val b = bg.copy(alpha = 1f).luminance() + .05f
        return if (a > b) a / b else b / a
    }

    /** Opacity of the accent behind the label of a tinted capsule button (`Ket27AlertButtons`). */
    const val CAPSULE_TINT = .16f

    /** A capsule tinted with [accent] at [CAPSULE_TINT] over [surface]. */
    fun tinted(accent: Color, surface: Color): Color = accent.copy(alpha = CAPSULE_TINT).compositeOver(surface.copy(alpha = 1f))

    /** Black or white, whichever reads better on [fill] (the label of a button filled with the accent). */
    fun onFill(fill: Color): Color = if (contrast(Color.Black, fill) >= contrast(Color.White, fill)) Color.Black else Color.White

    /**
     * The accent itself where it already reads on both [background] and [card]; otherwise the same
     * hue and saturation, darker on a light page or lighter on a dark one, just enough to reach
     * [TEXT] on both, and in a capsule tinted with itself. A cover colour can thus never leave a link or a slider fill unreadable.
     */
    fun readableAccent(accent: Color, background: Color, card: Color, minimum: Float = TEXT): Color {
        val opaque = accent.copy(alpha = 1f)
        // Also as the label of a capsule tinted with the accent itself (Ket 27 alert and page buttons).
        fun ok(c: Color) = contrast(c, background) >= minimum && contrast(c, card) >= minimum &&
            contrast(c, tinted(c, background)) >= minimum && contrast(c, tinted(c, card)) >= minimum
        if (ok(opaque)) return opaque
        val hsl = Hsl.of(opaque)
        val lighten = background.luminance() < .18f
        var l = hsl.l
        repeat(50) {
            l = (if (lighten) l + .02f else l - .02f).coerceIn(0f, 1f)
            val candidate = hsl.copy(l = l).toColor()
            if (ok(candidate)) return candidate
        }
        // Not reachable for real pages (they stay near white or near black), kept as a guarantee.
        return if (lighten) Color.White else Color.Black
    }

    /**
     * Secondary text as the theme draws it where it reads at [TEXT] on the page and its cards;
     * on a tinted page where it would not, it moves towards the primary ink just enough.
     */
    fun readableSecondary(secondary: Color, background: Color, card: Color, minimum: Float = TEXT): Color {
        fun ok(c: Color) = contrast(c, background) >= minimum && contrast(c, card) >= minimum
        if (ok(secondary)) return secondary
        val ink = if (background.luminance() < .18f) Color.White else Color.Black
        var t = 0f
        repeat(20) {
            t += .05f
            val candidate = lerp(secondary, ink, t)
            if (ok(candidate)) return candidate
        }
        return ink
    }

    /** Hue, saturation, lightness of a colour (all 0..1, hue in degrees). */
    internal data class Hsl(val h: Float, val s: Float, val l: Float) {
        fun toColor(): Color {
            val c = (1f - abs(2f * l - 1f)) * s
            val hp = (h / 60f) % 6f
            val x = c * (1f - abs(hp % 2f - 1f))
            val (r, g, b) = when {
                hp < 1f -> Triple(c, x, 0f)
                hp < 2f -> Triple(x, c, 0f)
                hp < 3f -> Triple(0f, c, x)
                hp < 4f -> Triple(0f, x, c)
                hp < 5f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = l - c / 2f
            return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
        }

        companion object {
            fun of(color: Color): Hsl {
                val r = color.red; val g = color.green; val b = color.blue
                val max = maxOf(r, g, b); val min = minOf(r, g, b)
                val l = (max + min) / 2f
                val chroma = max - min
                if (chroma <= 0f) return Hsl(0f, 0f, l)
                val s = chroma / (1f - abs(2f * l - 1f))
                return Hsl(CoverColors.hue(r, g, b, max, chroma), s.coerceIn(0f, 1f), l)
            }
        }
    }
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
