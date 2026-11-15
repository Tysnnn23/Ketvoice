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

/** How strong the glow of a dark page is at its brightest, as a share of the accent. */
internal object PageGlow {
    const val MAIN = .16f
    const val SIDE = .07f
    /** The colour of the second, smaller glow: the accent a third of the way round the wheel, so the page is not one colour. */
    fun companion(accent: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (accent.red * 255).toInt(), (accent.green * 255).toInt(),
            (accent.blue * 255).toInt()), hsv)
        hsv[0] = (hsv[0] + 250f) % 360f
        hsv[1] = (hsv[1] * .85f).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}

/**
 * The background of a page. Light, it is the plain page colour. Dark, the page is lit from
 * above by two soft glows of the accent that stay in place while the content scrolls: depth
 * without a single line, and the colour of the app (or of the cover being played) on every
 * screen. Two gradients drawn behind the content; nothing is animated.
 */
fun androidx.compose.ui.Modifier.pageBackground(theme: KetTheme): androidx.compose.ui.Modifier =
    // Ket 27: the plain system background, light or dark.
    if (!theme.isDark || Ket27.on) background(theme.background) else drawBehind {
        drawRect(theme.background)
        val reach = maxOf(size.width, size.height * .5f)
        drawRect(Brush.radialGradient(
            0f to theme.accent.copy(alpha = PageGlow.MAIN), .45f to theme.accent.copy(alpha = PageGlow.MAIN * .3f), 1f to Color.Transparent,
            center = Offset(size.width * .12f, -size.height * .04f), radius = reach * 1.05f))
        drawRect(Brush.radialGradient(
            0f to PageGlow.companion(theme.accent).copy(alpha = PageGlow.SIDE), 1f to Color.Transparent,
            center = Offset(size.width * 1.02f, size.height * .10f), radius = reach * .8f))
    }

object T {
    // Ket 27 swaps each style for the iOS text style that plays its part.
    val huge get() = if (Ket27.on) Ket27.Type.largeTitle else DesignTokens.Typography.display
    val hugeTitle get() = if (Ket27.on) Ket27.Type.largeTitle else DesignTokens.Typography.largeTitle
    val h2 get() = if (Ket27.on) Ket27.Type.title2 else DesignTokens.Typography.title
    val section get() = if (Ket27.on) Ket27.Type.title3 else DesignTokens.Typography.section
    val body get() = if (Ket27.on) Ket27.Type.body else DesignTokens.Typography.body
    val bodyBold get() = if (Ket27.on) Ket27.Type.headline else DesignTokens.Typography.bodyBold
    val sub get() = if (Ket27.on) Ket27.Type.footnote else DesignTokens.Typography.supporting
    val caption get() = if (Ket27.on) Ket27.Type.caption else DesignTokens.Typography.caption
    val chip get() = if (Ket27.on) Ket27.Type.subheadline.copy(fontWeight = FontWeight.Medium) else DesignTokens.Typography.label
    val lyrics get() = DesignTokens.Typography.lyrics
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
    val th = if (Ket27.on) Ket27.theme(isDark, accent) else if (com.ketvoice.app.data.DesignChoices.enabled("D1")) base.copy(
        background=if(isDark)Palette.BackgroundDark else Color(0xFFF7F7FA),
        card=if(isDark)Palette.CardDark else Color.White,
        secondary=if(isDark)Palette.SecondaryTextDark else Color(0xFF555A69)) else base
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
        val ketFont = if (Ket27.on) Ket27.font else null
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
                // Always through the same call, so that switching Ket 27 on does not rebuild the page and lose where one is.
                val inherited = androidx.compose.material3.LocalTextStyle.current
                androidx.compose.material3.ProvideTextStyle(inherited.copy(fontFamily = ketFont ?: inherited.fontFamily), content)
            }
        )
      }
    }
}

private fun Material3Typography(family: androidx.compose.ui.text.font.FontFamily? = null) = androidx.compose.material3.Typography(
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
