package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared design vocabulary. Existing Palette, T and Motion names delegate here. */
object DesignTokens {
    object Color {
        val white = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        val black = androidx.compose.ui.graphics.Color(0xFF000000)
        val backgroundLight = white
        val backgroundDark = androidx.compose.ui.graphics.Color(0xFF0B0D10)
        val surfaceLight = androidx.compose.ui.graphics.Color(0xFFF2F2F7)
        val surfaceDark = androidx.compose.ui.graphics.Color(0xFF191D23)
        val secondaryLight = androidx.compose.ui.graphics.Color(0xFF8E8E92)
        val secondaryDark = androidx.compose.ui.graphics.Color(0xFFB4BAC4)
        val separatorLight = androidx.compose.ui.graphics.Color(0x14000000)
        val separatorDark = androidx.compose.ui.graphics.Color(0x24FFFFFF)
        val accent = androidx.compose.ui.graphics.Color(0xFFFA2D48)
    }

    object Typography {
        val display = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = .5.sp, lineHeight = 40.sp)
        val largeTitle = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp)
        val title = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
        val section = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
        val body = TextStyle(fontSize = 16.sp)
        val bodyBold = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
        val supporting = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
        val caption = TextStyle(fontSize = 12.sp)
        val label = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        val lyrics = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 30.sp)
    }

    object Space {
        val hairline = .5.dp
        val small = 8.dp
        val item = 12.dp
        val page = 18.dp
        val section = 24.dp
        val touch = 48.dp
    }

    object Radius {
        val row = 12.dp
        val card = 18.dp
        val sheet = 28.dp
        val capsule = 50
    }

    object Glass {
        const val CONTROL_BLUR_PX = 8f
        const val CIRCLE_BLUR_PX = 6f
        const val NAVIGATION_BLUR_PX = 7f
        const val LOW_COST_BLUR_PX = 3f
        const val EDGE_HEIGHT_DP = 3.5f
        const val EDGE_AMOUNT_DP = 6f
        const val NAVIGATION_EDGE_HEIGHT_DP = 4f
        const val NAVIGATION_EDGE_AMOUNT_DP = 7f
        const val FALLBACK_LIGHT_TINT = .72f
        const val FALLBACK_DARK_TINT = .62f
        const val FALLBACK_HIGHLIGHT = .22f
        const val DISPERSION = .006f
    }

    object Motion {
        const val FAST_MS = 150
        const val NORMAL_MS = 250
        const val MEDIUM_MS = 350
        const val SLOW_MS = 500
        const val PRESS_DAMPING = .9f
        const val RELEASE_DAMPING = .5f
        const val PRESS_STIFFNESS = 900f
        const val RELEASE_STIFFNESS = 420f
        const val CHROME_DAMPING = .88f
        // 3 % slower than the original 460: a spring's duration scales with 1/sqrt(stiffness),
        // so 460 / 1.03² keeps the same movement and lets the droplet split be seen.
        const val CHROME_STIFFNESS = 433.6f
    }

    object Elevation {
        val flat = 0.dp
        val card = 1.dp
        val raised = 4.dp
        val floating = 8.dp
        val overlay = 16.dp
        val system = 24.dp
    }
}
