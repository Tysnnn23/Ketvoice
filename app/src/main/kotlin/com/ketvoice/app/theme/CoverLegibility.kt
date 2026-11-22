package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

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

    /**
     * The accent of an album or artist page (7.3): its cover's colour made readable on the page and its
     * cards (see [readableAccent]), or [fallback], the app's accent, when the cover gives none.
     */
    fun pageAccent(cover: Color?, fallback: Color, background: Color, card: Color): Color =
        cover?.let { readableAccent(it, background, card) } ?: fallback

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
