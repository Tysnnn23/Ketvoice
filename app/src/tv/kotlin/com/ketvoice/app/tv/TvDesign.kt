package com.ketvoice.app.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.theme.Ket27

/**
 * Ketvoice TV's 10-foot design (Ket 27 seen from the sofa). A 1080p TV lays the app out on 960 × 540 dp (4K TVs too:
 * Android draws their interface at the same size, twice as sharp). Everything stays inside the overscan-safe frame
 * ([Safe]); text is never smaller than 13 sp; a focused element grows and lights its edge.
 */
object TvDesign {
    /** Google's overscan-safe margins for TV: 5 % of each side. */
    object Safe {
        val horizontal = 48.dp
        val vertical = 27.dp
    }

    object Size {
        /** The side menu, closed (icons) and open (icons and names). */
        val railClosed = 72.dp
        val railOpen = 216.dp
        val card = 148.dp
        val wideCardWidth = 264.dp
        val wideCardHeight = 148.dp
        val artist = 128.dp
        val rowGap = 18.dp
        val rowSpacing = 30.dp
        val hero = 220.dp
        val trackRow = 56.dp
        val button = 48.dp
    }

    object Radius {
        val card = 14.dp
        val panel = 22.dp
        val pill = 999.dp
    }

    /** How much a focused card grows (Google TV uses about 1.1). */
    const val FOCUS_SCALE = 1.08f
    const val PRESS_SCALE = .97f

    object Type {
        private val font get() = Ket27.font
        /** An artist's name over their photo, a focused card's title over Home's poster (1.1.0). */
        val hero get() = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 60.sp, lineHeight = 64.sp, letterSpacing = (-1.2).sp)
        val display get() = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-.6).sp)
        /** The artist under Now Playing's title. */
        val lead get() = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)
        val title get() = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-.4).sp)
        val row get() = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp)
        val card get() = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
        val cardSub get() = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp)
        val body get() = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
        val label get() = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
        val caption get() = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
    }

    object Ink {
        val primary = Color.White
        val secondary = Color.White.copy(alpha = .66f)
        val tertiary = Color.White.copy(alpha = .42f)
        /** The cheap glass of the TV: a tint over an already soft background, no live blur. */
        val glass = Color(0xFF1C1C22).copy(alpha = .62f)
        val glassEdge = Color.White.copy(alpha = .10f)
        val focusFill = Color.White
        val onFocusFill = Color(0xFF0B0B0F)
        val page = Color(0xFF07070A)
    }
}

/** The tier of the TV the screens are drawn for (host renders set it). */
val LocalTvTier = staticCompositionLocalOf { TvTier.Balanced }

@Composable
@ReadOnlyComposable
fun tvTier(): TvTier = LocalTvTier.current

/**
 * A page's background reaches under the closed side menu (the page itself starts beside it): no hard edge between the
 * menu and a page drawn in its picture's colours.
 */
fun androidx.compose.ui.Modifier.underRail(): androidx.compose.ui.Modifier = this.then(
    androidx.compose.ui.Modifier.layout { measurable, constraints ->
        val extra = TvDesign.Size.railClosed.roundToPx()
        val wide = constraints.copy(minWidth = constraints.minWidth + extra,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth)
        val placeable = measurable.measure(wide)
        layout(constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: placeable.width, placeable.height) { placeable.place(-extra, 0) }
    })
