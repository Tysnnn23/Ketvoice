package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings

/**
 * « Police, taille et arrondis » (owner, 2026-11-22): the typefaces Ketvoice ships, all under the SIL Open Font
 * License (each with its licence in `docs/licenses`). Inter stays the default and the only one the interface was drawn for;
 * the others are variable fonts too, so every weight of the Ket 27 text styles exists. [Choice.file] is the font's
 * file in `res/font` (without `.ttf`); `AppFontFiles.kt` turns a choice into its family on each platform.
 */
object AppFonts {
    data class Choice(val id: String, val name: String, val file: String, val weights: IntRange)

    const val INTER = "inter"
    val all = listOf(
        Choice(INTER, "Inter", "inter_variable", 100..900),
        Choice("manrope", "Manrope", "manrope_variable", 200..800),
        Choice("atkinson", "Atkinson Hyperlegible", "atkinson_hyperlegible_next_variable", 200..800),
        Choice("nunito", "Nunito", "nunito_variable", 200..1000),
        Choice("lora", "Lora", "lora_variable", 400..700)
    )
    private val ids = all.map { it.id }.toSet()

    fun valid(id: String) = id in ids
    fun choice(id: String): Choice = all.firstOrNull { it.id == id } ?: all.first()

    private val families = HashMap<String, FontFamily>()

    fun family(id: String): FontFamily = synchronized(families) {
        val choice = choice(id)
        families.getOrPut(choice.id) { appFontFamily(choice) }
    }
}

/**
 * The interface's size and shape as the listener set them: text size (independent of Android's font scale, which
 * still applies on top), the corner radius of the Ket 27 ladder and how dense lists are. Every default reproduces
 * Ket 27 exactly (100 %, 100 %, normal).
 */
object AppShape {
    const val TEXT_MIN = 85; const val TEXT_MAX = 130; const val TEXT_DEFAULT = 100
    const val CORNER_MIN = 0; const val CORNER_MAX = 200; const val CORNER_DEFAULT = 100
    const val COMPACT = 0; const val NORMAL = 1; const val AIRY = 2

    fun textScale(percent: Int = AppSettings.textScale.value): Float = percent.coerceIn(TEXT_MIN, TEXT_MAX) / 100f
    fun cornerScale(percent: Int = AppSettings.cornerScale.value): Float = percent.coerceIn(CORNER_MIN, CORNER_MAX) / 100f

    /** A radius of the Ket 27 ladder, scaled: 0 % is square, 200 % twice as round. */
    fun radius(base: Dp): Dp = base * cornerScale()

    /** The height of a list row (52 dp in Ket 27) and the space above and under its content (10 dp). */
    fun row(density: Int = AppSettings.listDensity.value): Dp = when (density) { COMPACT -> 44.dp; AIRY -> 60.dp; else -> 52.dp }
    fun rowPadding(density: Int = AppSettings.listDensity.value): Dp = when (density) { COMPACT -> 6.dp; AIRY -> 14.dp; else -> 10.dp }

    val densityLabels = listOf("Compacte", "Normale", "Aérée")
}

/**
 * « Paroles à ton goût » (owner, 2026-11-22): the lyrics' font, size, alignment, the colour of the sung words, how
 * much distant lines blur and how much sung words lift and grow. Defaults are the 8.3 lyrics, unchanged.
 */
object LyricsLook {
    const val SIZE_MIN = 80; const val SIZE_MAX = 140; const val SIZE_DEFAULT = 100
    const val LEFT = 0; const val CENTRE = 1
    const val SUNG_WHITE = 0; const val SUNG_ACCENT = 1; const val SUNG_COVER = 2; const val SUNG_CUSTOM = 3
    val sungLabels = listOf("Blanc", "Accent", "Pochette", "Autre")

    /** The lyrics' own font: empty follows the interface's font. */
    fun font(): FontFamily = AppFonts.family(AppSettings.lyricsFont.value.takeIf(AppFonts::valid) ?: AppSettings.appFont.value)
    fun sizeScale(percent: Int = AppSettings.lyricsScale.value): Float = percent.coerceIn(SIZE_MIN, SIZE_MAX) / 100f
    val centred: Boolean get() = AppSettings.lyricsAlign.value == CENTRE

    /** How a line is aligned: centred for everyone when chosen, else start, and end for a duet's other singer. */
    fun align(end: Boolean): TextAlign? = when {
        centred -> TextAlign.Center
        end -> TextAlign.End
        else -> null
    }

    /** 0..1: 0 is no blur (as with « Flou des paroles » off), 1 the 8.3 distances. */
    fun blur(): Float = if (!AppSettings.lyricsBlur.value) 0f else AppSettings.lyricsBlurLevel.value.coerceIn(0, 100) / 100f
    /** 0..1: how much sung words lift, grow and swell; 0 keeps them flat (the fill still runs). */
    fun motion(): Float = AppSettings.lyricsMotion.value.coerceIn(0, 100) / 100f

    /**
     * The colour of the sung words over the player's dark ground: white, the accent, the cover's colour or the
     * listener's own, always lightened until it reads at AA on a dark ground (lyrics sit on a dark field).
     */
    fun sung(mode: Int, accent: Color, cover: Color?, custom: Long): Color = when (mode) {
        SUNG_ACCENT -> readableOnDark(accent)
        SUNG_COVER -> cover?.let(::readableOnDark) ?: Color.White
        SUNG_CUSTOM -> readableOnDark(Color(custom.toInt()))
        else -> Color.White
    }

    private val DARK_GROUND = Color(0xFF26262A)

    fun readableOnDark(color: Color): Color {
        val opaque = color.copy(alpha = 1f)
        if (CoverLegibility.contrast(opaque, DARK_GROUND) >= CoverLegibility.TEXT) return opaque
        for (step in 1..20) {
            val candidate = androidx.compose.ui.graphics.lerp(opaque, Color.White, step / 20f)
            if (CoverLegibility.contrast(candidate, DARK_GROUND) >= CoverLegibility.TEXT) return candidate
        }
        return Color.White
    }
}
