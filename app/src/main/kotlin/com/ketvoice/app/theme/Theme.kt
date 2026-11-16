package com.ketvoice.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
    val glassDefault: Boolean = true
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

/** The background of a page: the plain system background of Ket 27, light or dark. */
fun androidx.compose.ui.Modifier.pageBackground(theme: KetTheme): androidx.compose.ui.Modifier = background(theme.background)

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
    /** The colour the whole app's pages take (5.9.1, "Fond selon la pochette"), or null for the theme's own. */
    dynamicTone: Color? = null,
    content: @Composable () -> Unit
) {
    val sysDark = isSystemInDarkTheme()
    val isDark = useDark ?: sysDark
    val accent = dynamicAccent ?: Color(com.ketvoice.app.data.AppSettings.accentArgb.value.toInt())
    val chosen = Ket27.theme(isDark, accent)
    // Like an album page, the whole app takes the colour of what plays: the same tint, held dim at night.
    val th = if (dynamicTone == null) chosen else {
        val page = com.ketvoice.app.ui.sub.artistPageTint(chosen.background, dynamicTone, chosen.isDark)
        chosen.copy(background = page, card = androidx.compose.ui.graphics.lerp(page, chosen.onBackground, if (chosen.isDark) .08f else .05f))
    }
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
