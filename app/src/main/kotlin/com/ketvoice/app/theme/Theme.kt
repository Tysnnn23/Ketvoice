package com.ketvoice.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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

/** iOS-like text styles */
object T {
    val huge = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = 0.5.sp, lineHeight = 40.sp)
    val hugeTitle = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, letterSpacing = 0.sp, lineHeight = 44.sp)
    val h2 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
    val section = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp)
    val bodyBold = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
    val sub = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val chip = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    val lyrics = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 30.sp)
}

@Composable
fun KetvoiceTheme(useDark: Boolean? = null, content: @Composable () -> Unit) {
    val sysDark = isSystemInDarkTheme()
    val isDark = useDark ?: sysDark
    val accent = Color(com.ketvoice.app.data.AppSettings.accentArgb.value.toInt())
    val base = (if (isDark) KetTheme.dark() else KetTheme.light()).copy(accent = accent)
    val th = if (com.ketvoice.app.data.DesignChoices.enabled("D1")) base.copy(
        background=if(isDark)Color(0xFF0C0D12) else Color(0xFFF7F7FA),
        card=if(isDark)Color(0xFF1B1D26) else Color.White,
        secondary=if(isDark)Color(0xFFB6B9C6) else Color(0xFF555A69)) else base
    androidx.compose.runtime.CompositionLocalProvider(LocalTheme provides th) {
        MaterialTheme(
            colorScheme = if (isDark) darkColorScheme(
                background = th.background,
                onBackground = Palette.White,
                surface = th.card,
                primary = accent,
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
