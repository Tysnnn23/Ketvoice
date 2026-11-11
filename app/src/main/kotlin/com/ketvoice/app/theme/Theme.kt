package com.ketvoice.app.theme

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

object T {
    val huge = DesignTokens.Typography.display
    val hugeTitle = DesignTokens.Typography.largeTitle
    val h2 = DesignTokens.Typography.title
    val section = DesignTokens.Typography.section
    val body = DesignTokens.Typography.body
    val bodyBold = DesignTokens.Typography.bodyBold
    val sub = DesignTokens.Typography.supporting
    val caption = DesignTokens.Typography.caption
    val chip = DesignTokens.Typography.label
    val lyrics = DesignTokens.Typography.lyrics
}

@Composable
fun KetvoiceTheme(
    useDark: Boolean? = null,
    dynamicAccent: Color? = null,
    content: @Composable () -> Unit
) {
    val sysDark = isSystemInDarkTheme()
    val isDark = useDark ?: sysDark
    val accent = dynamicAccent ?: Color(com.ketvoice.app.data.AppSettings.accentArgb.value.toInt())
    val base = (if (isDark) KetTheme.dark() else KetTheme.light()).copy(accent = accent)
    val th = if (com.ketvoice.app.data.DesignChoices.enabled("D1")) base.copy(
        background=if(isDark)Palette.BackgroundDark else Color(0xFFF7F7FA),
        card=if(isDark)Palette.CardDark else Color.White,
        secondary=if(isDark)Palette.SecondaryTextDark else Color(0xFF555A69)) else base
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTheme provides th,
        // Le repère visuel de l'app est la réaction du verre et du contenu,
        // pas la Ripple Material héritée d'Android.
        androidx.compose.foundation.LocalIndication provides com.ketvoice.app.theme.NoIndication
    ) {
        MaterialTheme(
            colorScheme = if (isDark) darkColorScheme(
                background = th.background,
                onBackground = Palette.White,
                surface = th.card,
                primary = accent,
                onPrimary = if (accent.luminance() > .179f) Color.Black else Color.White,
                surfaceContainerLowest = th.background,
                surfaceContainerLow = Color(0xFF13171C),
                surfaceContainer = th.card,
                surfaceContainerHigh = Color(0xFF242A32),
                surfaceContainerHighest = Color(0xFF303741),
                surfaceTint = Color.Transparent,
                outline = Color(0xFF717B89),
                outlineVariant = Color(0xFF353D48),
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
            typography = Material3Typography(),
            content = content
        )
    }
}

private fun Material3Typography() = androidx.compose.material3.Typography(
    headlineLarge = T.hugeTitle, headlineMedium = T.huge, titleLarge = T.h2,
    bodyLarge = T.body, bodyMedium = T.sub, labelLarge = T.chip
)

/** Aucun retour visuel Material : les interactions vivent dans le verre et le contenu. */
object NoIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource):
        androidx.compose.ui.node.DelegatableNode = EmptyNode()
    override fun hashCode(): Int = -1
    override fun equals(other: Any?): Boolean = other === this
}

private class EmptyNode : androidx.compose.ui.Modifier.Node()
