package com.ketvoice.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class KetTheme(
    val isDark: Boolean,
    val background: Color,
    val onBackground: Color,
    val secondary: Color,
    val card: Color,
    val hairline: Color,
    val accent: Color,
    val glassDefault: Boolean = true,
    /** A seasonal theme is on (6.2, [SeasonLook]): its glows, edges and decor are drawn. */
    val season: SeasonLook? = null
) {
    companion object {
        fun light() = KetTheme(
            isDark = false, background = Palette.BackgroundLight, onBackground = Palette.Black,
            secondary = Palette.SecondaryTextLight, card = Palette.CardLight,
            hairline = Palette.HairlineLight, accent = Palette.Accent
        )
        fun dark() = KetTheme(
            isDark = true, background = Palette.BackgroundDark, onBackground = Palette.White,
            secondary = Palette.SecondaryTextDark, card = Palette.CardDark,
            hairline = Palette.HairlineDark, accent = Palette.Accent
        )
    }
}

val LocalTheme = staticCompositionLocalOf<KetTheme> { KetTheme.light() }

/**
 * The background of a page: the plain system background of Ket 27, light or dark; with a seasonal theme (6.2), its
 * night and the slow glow at the top ([SeasonLook.glow]).
 */
fun androidx.compose.ui.Modifier.pageBackground(theme: KetTheme): androidx.compose.ui.Modifier =
    theme.season?.let { background(theme.background).then(it.glow) } ?: background(theme.background)

/** The text styles of the app: each is the iOS text style that plays its part (Ket 27). */
object T {
    val huge get() = Ket27.Type.largeTitle
    val hugeTitle get() = Ket27.Type.largeTitle
    val h2 get() = Ket27.Type.title2
    val section get() = Ket27.Type.title3
    val body get() = Ket27.Type.body
    val bodyBold get() = Ket27.Type.headline
    val sub get() = Ket27.Type.footnote
    val caption get() = Ket27.Type.caption
    val chip get() = Ket27.Type.subheadline.copy(fontWeight = FontWeight.Medium)
    val lyrics get() = DesignTokens.Typography.lyrics
}

@Composable
fun KetvoiceTheme(
    useDark: Boolean? = null,
    dynamicAccent: Color? = null,
    /**
     * The colours of the cover playing (« Fond selon la pochette », « Couleur de la pochette »), already
     * animated by the root; [CoverLook.apply] keeps every label readable on them.
     */
    cover: CoverLook = CoverLook(),
    /** A seasonal theme (6.2): its own dark look and accent over the listener's choices. */
    season: SeasonLook? = null,
    content: @Composable () -> Unit
) {
    val sysDark = isSystemInDarkTheme()
    val isDark = season != null || (useDark ?: sysDark)
    val chosenAccent = dynamicAccent ?: Color(com.ketvoice.app.data.AppSettings.accentArgb.value.toInt())
    // Like an album page, the whole app takes the colour of what plays: the same tint, held dim at night.
    // Noir (7.1): pure black pages in dark mode, whatever the cover; the cards stay apart from them.
    val black = isDark && com.ketvoice.app.data.AppSettings.trueBlack.value
    val th = season?.theme() ?: cover.apply(Ket27.theme(isDark, chosenAccent)
        .let { if (black) it.copy(background = Color.Black) else it }, black)
    val accent = th.accent
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTheme provides th,
        // Le repère visuel de l'app est la réaction du verre et du contenu,
        // pas la Ripple Material héritée d'Android.
        androidx.compose.foundation.LocalIndication provides com.ketvoice.app.theme.NoIndication
    ) {
      // Lists bounce at their ends as on iOS (the platform's stretch with reduced motion).
      androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides
          (if (com.ketvoice.app.ui.motion.Motion.reduced) androidx.compose.foundation.LocalOverscrollFactory.current
           else com.ketvoice.app.ui.motion.BounceOverscrollFactory)) {
        val ketFont = Ket27.font
        MaterialTheme(
            colorScheme = if (isDark) darkColorScheme(
                background = th.background,
                onBackground = Palette.White,
                surface = th.card,
                primary = accent,
                onPrimary = if (accent.luminance() > .179f) Color.Black else Color.White,
                surfaceContainerLowest = th.background,
                surfaceContainerLow = DesignTokens.Color.sunkenDark,
                surfaceContainer = th.card,
                surfaceContainerHigh = DesignTokens.Color.raisedDark,
                surfaceContainerHighest = DesignTokens.Color.highestDark,
                surfaceTint = Color.Transparent,
                outline = DesignTokens.Color.outlineDark,
                outlineVariant = DesignTokens.Color.outlineSoftDark,
                onSurface = Palette.White,
                onSurfaceVariant = Palette.SecondaryTextDark
            ) else lightColorScheme(
                background = th.background,
                onBackground = Palette.Black,
                surface = th.card,
                primary = accent,
                onSurface = Palette.Black,
                onSurfaceVariant = Palette.SecondaryTextLight
            ),
            typography = Material3Typography(ketFont),
            content = {
                val inherited = androidx.compose.material3.LocalTextStyle.current
                androidx.compose.material3.ProvideTextStyle(inherited.copy(fontFamily = ketFont), content)
            }
        )
      }
    }
}

private fun Material3Typography(family: androidx.compose.ui.text.font.FontFamily) =androidx.compose.material3.Typography(
    headlineLarge = T.hugeTitle.copy(fontFamily = family), headlineMedium = T.huge.copy(fontFamily = family), titleLarge = T.h2.copy(fontFamily = family),
    bodyLarge = T.body.copy(fontFamily = family), bodyMedium = T.sub.copy(fontFamily = family), labelLarge = T.chip.copy(fontFamily = family)
)

/** Aucun retour visuel Material : les interactions vivent dans le verre et le contenu. */
object NoIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource):
        androidx.compose.ui.node.DelegatableNode = EmptyNode()
    override fun hashCode(): Int = -1
    override fun equals(other: Any?): Boolean = other === this
}

private class EmptyNode : androidx.compose.ui.Modifier.Node()
