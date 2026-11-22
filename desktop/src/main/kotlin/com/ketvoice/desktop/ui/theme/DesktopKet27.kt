package com.ketvoice.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.AppFonts
import com.ketvoice.app.theme.AppShape
import com.ketvoice.app.theme.CoverLegibility
import com.ketvoice.app.theme.DesignTokens
import com.ketvoice.app.theme.ThemeCodec
import com.ketvoice.app.theme.ThemeColors
import com.ketvoice.app.theme.ThemeGuard
import com.ketvoice.app.theme.ThemePresets

/**
 * Ket 27 on the desktop. The app's `theme/Ket27.kt` loads Inter through an Android font resource
 * (`R.font.inter_variable`), which Compose Desktop has no form of, so its tokens are repeated here
 * unchanged (colours, the San Francisco text styles set for Inter, the 4 dp spacing grid, the radii);
 * the fonts are the same files, read from the classpath (`AppFontFiles.kt`). Keep both in step.
 * « Personnalisation » (9.0): the font is the listener's ([AppFonts]), the radii follow « Arrondis » and the rows
 * « Densité des listes » ([AppShape]); every default is the look the desktop always had.
 */
object Ket27Desk {
    /** The interface's typeface: Inter, or the one picked in « Personnalisation ». Read in composition, it follows the setting. */
    val font: FontFamily get() = AppFonts.family(AppSettings.appFont.value)

    object Type {
        val largeTitle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-.5).sp)
        val title1 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 33.sp, letterSpacing = (-.45).sp)
        val title2 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-.35).sp)
        val title3 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-.4).sp)
        val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-.39).sp)
        val headline = body.copy(fontWeight = FontWeight.SemiBold)
        val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-.29).sp)
        val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-.21).sp)
        val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-.07).sp)
        val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
        val caption2 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = .06.sp)
    }

    object Space {
        val xxs = 4.dp; val xs = 8.dp; val s = 12.dp; val m = 16.dp; val l = 20.dp; val xl = 24.dp
        val sectionTop = 30.dp; val sectionBottom = 10.dp
        /** The page margin of the content area on a computer (Music for macOS: 30–40 pt). */
        val page = 32.dp
    }

    /** The radii of the Ket 27 ladder, scaled by « Arrondis » (0 % square, 200 % twice as round). */
    object Radius {
        val thumb: Dp get() = AppShape.radius(10.dp)
        val artwork: Dp get() = AppShape.radius(14.dp)
        val card: Dp get() = AppShape.radius(24.dp)
        val group: Dp get() = AppShape.radius(26.dp)
        val sheet: Dp get() = AppShape.radius(34.dp)
        /** A desktop menu or tooltip (smaller than a phone sheet). */
        val menu: Dp get() = AppShape.radius(12.dp)
        /** The hover shape of a title row. */
        val row: Dp get() = AppShape.radius(8.dp)
    }

    /** Row heights by « Densité des listes »: the album row (48 dp) and the compact row with artwork (56 dp). */
    object Metrics {
        val trackRow: Dp get() = when (AppSettings.listDensity.value) { AppShape.COMPACT -> 40.dp; AppShape.AIRY -> 56.dp; else -> 48.dp }
        val compactRow: Dp get() = when (AppSettings.listDensity.value) { AppShape.COMPACT -> 48.dp; AppShape.AIRY -> 64.dp; else -> 56.dp }
    }
}

/** The colours every desktop surface reads; light and dark follow Windows. */
@Immutable
data class KetColors(
    val dark: Boolean,
    val ground: Color,
    /** The sidebar's material, translucent over the blurred backdrop. */
    val material: Color,
    val card: Color,
    val raised: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val hairline: Color,
    val accent: Color,
    val hover: Color,
    val selected: Color,
    val fill: Color,
    /** Under the whole window: the ground, or transparent where Windows 11's Mica shows through ([glass]). */
    val window: Color = ground,
    /** The sidebar and top bar are Acrylic (tint and grain over the Mica) rather than a flat material. */
    val acrylic: Boolean = false,
) {
    companion object {
        /**
         * « Verre Windows 11 »: the window is Mica, the pages lie on WinUI's layer fill and the sidebar and the bar
         * are in-app Acrylic (its luminosity tint; the grain is drawn by `acrylic`). Cards and menus stay solid.
         */
        fun glass(dark: Boolean, accent: Color = DesignTokens.Color.accent) = of(dark, accent).let { solid ->
            if (dark) solid.copy(window = Color.Transparent, ground = Color(0x4C3A3A3A), material = Color(0x9E202020), acrylic = true)
            else solid.copy(window = Color.Transparent, ground = Color(0x80FFFFFF), material = Color(0xB8F3F3F3), acrylic = true)
        }

        fun of(dark: Boolean, accent: Color = DesignTokens.Color.accent) = if (dark) KetColors(
            dark = true, ground = Color(0xFF1C1C1E), material = Color(0xCC232326), card = Color(0xFF2C2C2E),
            raised = Color(0xFF2C2C2E), label = Color.White, secondary = Color(0xB3EBEBF5), tertiary = Color(0x4DEBEBF5),
            hairline = Color(0x2BFFFFFF), accent = accent, hover = Color(0x14FFFFFF), selected = Color(0x24FFFFFF),
            fill = Color(0x29787880),
        ) else KetColors(
            dark = false, ground = Color(0xFFFFFFFF), material = Color(0xD9F2F2F7), card = Color(0xFFF2F2F7),
            raised = Color(0xFFFFFFFF), label = Color.Black, secondary = Color(0xBA3C3C43), tertiary = Color(0x4D3C3C43),
            hairline = Color(0x1F000000), accent = accent, hover = Color(0x0A000000), selected = Color(0x14000000),
            fill = Color(0x1F787880),
        )
    }
}

/**
 * The window's colours from the settings: Ket 27's own (exactly [KetColors.of] / [KetColors.glass]) unless the listener
 * made a theme (« Thèmes complets », [themeColors] as stored) or picked an accent ([accentArgb]; the stored default
 * keeps the desktop's own red). A custom theme goes through the app's contrast guard ([ThemeGuard]): text, secondary
 * text and the accent keep WCAG AA on the page and on the cards.
 */
object DesktopTheme {
    fun colors(dark: Boolean, glass: Boolean, themeColors: String = AppSettings.themeColors.value,
               accentArgb: Long = AppSettings.accentArgb.value): KetColors {
        val custom = ThemeCodec.parse(themeColors)
        val chosen = if (accentArgb == ThemePresets.DEFAULT_ACCENT) null else Color(accentArgb.toInt()).copy(alpha = 1f)
        if (custom == null) {
            val base = if (glass) KetColors.glass(dark) else KetColors.of(dark)
            return chosen?.let { base.copy(accent = CoverLegibility.readableAccent(it, KetColors.of(dark).ground, base.card)) } ?: base
        }
        return custom(custom, dark, glass, chosen ?: DesignTokens.Color.accent)
    }

    /** A listener's theme, guarded, as the desktop's surfaces: page = ground, surfaces = cards and menus, text = labels. */
    fun custom(theme: ThemeColors, dark: Boolean, glass: Boolean, accent: Color): KetColors {
        val g = ThemeGuard.guarded(theme.side(dark), dark, accent)
        val ink = g.text
        val solid = KetColors(dark = g.isDark, ground = g.background, material = lerp(g.background, g.surface, .5f).copy(alpha = if (g.isDark) .8f else .85f),
            card = g.surface, raised = g.surface, label = ink, secondary = g.secondary, tertiary = ink.copy(alpha = .3f),
            hairline = g.hairline, accent = g.accent, hover = ink.copy(alpha = if (g.isDark) .08f else .04f),
            selected = ink.copy(alpha = if (g.isDark) .14f else .08f), fill = ink.copy(alpha = if (g.isDark) .14f else .10f))
        return if (!glass) solid else solid.copy(window = Color.Transparent, ground = g.background.copy(alpha = .6f),
            material = g.surface.copy(alpha = .72f), acrylic = true)
    }

    /** « Taille du texte » on top of the system's own scale; at 100 % the density is left as it is. */
    fun scaled(density: Density, percent: Int = AppSettings.textScale.value): Density {
        val k = AppShape.textScale(percent)
        return if (k == 1f) density else Density(density.density, density.fontScale * k)
    }
}

val LocalKet = staticCompositionLocalOf { KetColors.of(dark = true) }

/** Ket 27 for the window: the listener's font and colours, the system's light or dark look unless [dark] is given. */
@Composable
fun KetDesktopTheme(dark: Boolean = isSystemInDarkTheme(), glass: Boolean = false, content: @Composable () -> Unit) {
    val colors = DesktopTheme.colors(dark, glass)
    val scheme = if (dark) darkColorScheme(primary = colors.accent, background = colors.ground, surface = colors.card, onSurface = colors.label)
    else lightColorScheme(primary = colors.accent, background = colors.ground, surface = colors.card, onSurface = colors.label)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalDensity provides DesktopTheme.scaled(LocalDensity.current),
            LocalKet provides colors,
            LocalContentColor provides colors.label,
            LocalTextStyle provides Ket27Desk.Type.body.copy(fontFamily = Ket27Desk.font, color = colors.label),
            content = content,
        )
    }
}
