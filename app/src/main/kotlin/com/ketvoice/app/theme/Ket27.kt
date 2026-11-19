package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.R

/**
 * Ket 27: Ketvoice's interface (since 6.0.0 the only one), in the manner of iOS 27. The numbers
 * are those of Apple's iOS 27 design kit (1 pt = 1 dp): system colours, the text styles of San
 * Francisco, grouped lists with 26 dp corners and 52 dp rows, a 62 dp tab bar. San Francisco
 * itself cannot be shipped outside Apple's platforms; Inter (SIL Open Font License) is the open
 * typeface closest to it.
 */
object Ket27 {
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val font: FontFamily by lazy {
        FontFamily(listOf(100, 200, 300, 400, 500, 600, 700, 800, 900).map { weight ->
            Font(R.font.inter_variable, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
        })
    }

    /** The system colours of iOS 27, light and dark. */
    object Colors {
        val groupedLight = Color(0xFFF2F2F7); val cellLight = Color(0xFFFFFFFF)
        val groupedDark = Color(0xFF000000); val cellDark = Color(0xFF1C1C1E)
        // 7.0: Apple's 60 % grey read at 3.3:1 on the grouped background; 73 % reaches 4.6:1 (footnotes are small text).
        val secondaryLight = Color(0xBA3C3C43); val secondaryDark = Color(0xB3EBEBF5)
        val separatorLight = Color(0x1F000000); val separatorDark = Color(0x2BFFFFFF)
        /** The tiles behind the symbols of a settings list. */
        val tiles = listOf(Color(0xFF0088FF), Color(0xFFFF2D55), Color(0xFF34C759), Color(0xFFFF8D28), Color(0xFF6155F5), Color(0xFF8E8E93))
    }

    fun theme(dark: Boolean, accent: Color) = KetTheme(
        isDark = dark,
        background = if (dark) Colors.groupedDark else Colors.groupedLight,
        onBackground = if (dark) Color.White else Color.Black,
        secondary = if (dark) Colors.secondaryDark else Colors.secondaryLight,
        card = if (dark) Colors.cellDark else Colors.cellLight,
        hairline = if (dark) Colors.separatorDark else Colors.separatorLight,
        accent = accent)

    /**
     * San Francisco's text styles, set for Inter. 7.0: Inter is wider than SF Display, so the large sizes
     * are tracked tighter (34 sp: +.36 → -.5; 22 sp: -.23 → -.35), the way SF's own display cut tightens.
     * The text sizes keep nine tenths of Apple's tracking.
     */
    object Type {
        val largeTitle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-.5).sp)
        /** The title of a large card (Home's « Reprendre », « Mix du jour »). */
        val title1 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 33.sp, letterSpacing = (-.45).sp)
        val title2 = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-.35).sp)
        val title3 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-.4).sp)
        val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-.39).sp)
        val headline = body.copy(fontWeight = FontWeight.SemiBold)
        val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-.29).sp)
        val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-.21).sp)
        val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-.07).sp)
        val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
        /** The smallest label (a badge, the kind of a card). */
        val caption2 = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = .06.sp)
    }

    /** 7.0: one spacing rhythm on a 4 dp grid, with more room above a section's header than under it. */
    object Space {
        val xxs = 4.dp
        val xs = 8.dp
        val s = 12.dp
        val m = 16.dp
        val l = 20.dp
        val xl = 24.dp
        /** Above a section's header. */
        val sectionTop = 30.dp
        /** Between a section's header and what it heads. */
        val sectionBottom = 10.dp
    }

    /** 7.0: one ladder of corner radii (they were 10 to 32 dp, chosen screen by screen). */
    object Radius {
        /** The artwork of a row. */
        val thumb = 10.dp
        /** A cover in a shelf. */
        val artwork = 14.dp
        /** A large card (a mix, « Reprendre »). */
        val card = 24.dp
        /** A grouped list. */
        val group = 26.dp
        /** A sheet, an alert. */
        val sheet = 34.dp
    }

    /** Lists, bars and controls. */
    object Metrics {
        val margin = 16.dp
        val groupRadius = Radius.group
        val row = 52.dp
        val tile = 30.dp
        val tileRadius = 7.dp
        val tabBar = 62.dp
        val alertRadius = Radius.sheet
        val switchWidth = 63.dp
        val switchHeight = 28.dp
    }

    /** The colour of the tile behind the [index]-th symbol of a list. */
    fun tile(index: Int): Color = Colors.tiles[Math.floorMod(index, Colors.tiles.size)]
}
