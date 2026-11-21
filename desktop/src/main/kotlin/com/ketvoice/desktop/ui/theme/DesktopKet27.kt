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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.theme.DesignTokens

/**
 * Ket 27 on the desktop. The app's `theme/Ket27.kt` loads Inter through an Android font resource
 * (`R.font.inter_variable`), which Compose Desktop has no form of, so its tokens are repeated here
 * unchanged (colours, the San Francisco text styles set for Inter, the 4 dp spacing grid, the radii);
 * Inter itself is the same file, read from the classpath. Keep both in step.
 */
object Ket27Desk {
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val font: FontFamily by lazy {
        val bytes = Ket27Desk::class.java.classLoader.getResourceAsStream("font/inter_variable.ttf")?.use { it.readBytes() }
            ?: return@lazy FontFamily.SansSerif
        FontFamily(listOf(300, 400, 500, 600, 700, 800).map { w ->
            Font("Inter-$w", bytes, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        })
    }

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

    object Radius {
        val thumb = 10.dp; val artwork = 14.dp; val card = 24.dp; val group = 26.dp; val sheet = 34.dp
        /** A desktop menu or tooltip (smaller than a phone sheet). */
        val menu = 12.dp
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
) {
    companion object {
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

val LocalKet = staticCompositionLocalOf { KetColors.of(dark = true) }

/** Ket 27 for the window: Inter everywhere, the system's light or dark look unless [dark] is given. */
@Composable
fun KetDesktopTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = KetColors.of(dark)
    val scheme = if (dark) darkColorScheme(primary = colors.accent, background = colors.ground, surface = colors.card, onSurface = colors.label)
    else lightColorScheme(primary = colors.accent, background = colors.ground, surface = colors.card, onSurface = colors.label)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalKet provides colors,
            LocalContentColor provides colors.label,
            LocalTextStyle provides Ket27Desk.Type.body.copy(fontFamily = Ket27Desk.font, color = colors.label),
            content = content,
        )
    }
}
