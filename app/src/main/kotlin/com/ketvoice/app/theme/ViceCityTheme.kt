package com.ketvoice.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp

/**
 * The look of the Vice City limited edition (6.1, until 10 December 2026; see `data/ViceCityEdition.kt`):
 * a deep night violet, a sunset in pink, magenta and orange at the top of the pages and behind the tab bar,
 * a touch of neon cyan, and a thin neon edge on the selected tab and the main buttons.
 *
 * Legibility first: every text colour reaches WCAG AA (4.5:1) on the page and on its cards, and the accent
 * reads as a link, as a tinted capsule's label and under its own black label when it fills a button
 * ([CoverLegibility], checked in ViceCityThemeTest). The movement is a slow breathing of the glow, stepped
 * a few times a second (it is too slow to need more), lighter in the performance mode (thus in Lite) and
 * still under reduced motion.
 */
object ViceCityTheme {
    object Colors {
        /** The page: a night violet, nearly black. */
        val night = Color(0xFF120A22)
        /** Cards and grouped lists, one step lighter. */
        val card = Color(0xFF1F1338)
        val secondary = Color(0xFFBFB0DD)
        val hairline = Color(0x33F3C4FF)
        /** The accent: the sunset's pink. */
        val accent = Color(0xFFFF5FA8)
        val magenta = Color(0xFFFF3D9A)
        val orange = Color(0xFFFF8A4C)
        val cyan = Color(0xFF4FE8FF)
        val violet = Color(0xFF7B3FE4)
        /** Labels on a button filled with the sunset. */
        val ink = Color(0xFF140A22)
        /** The chrome of a large title (6.3): a pink mist above its horizon, a light pink and a peach under it. */
        val chromeMist = Color(0xFFFFE3F1)
        val chromePink = Color(0xFFFFA3CE)
        val chromePeach = Color(0xFFFFA36B)
    }

    /**
     * The 80s chrome of the large titles of Home, Explorer and the Library (6.3), from the top of each line to its
     * bottom: white, a pink mist down to the horizon, then light pink turning peach. Every stop reads at AA on the
     * night and on the brightest of the glow (ViceCityThemeTest).
     */
    val chromeStops: List<Pair<Float, Color>> = listOf(0f to Color.White, .50f to Colors.chromeMist,
        .56f to Colors.chromePink, 1f to Colors.chromePeach)

    /** The player's elapsed time (light pink) and remaining time and Ketmix's sign (cyan), over the darkened cover. */
    val elapsedTime: Color get() = Colors.chromePink
    val remainingTime: Color get() = Colors.cyan

    /** The colour of a held note's small glow in the lyrics: neon pink turning cyan as the note is held (0..1). */
    fun heldNoteGlow(progress: Float): Color =
        androidx.compose.ui.graphics.lerp(Colors.accent, Colors.cyan, if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f)

    const val ID = "vicecity"

    /** The edition's look in the seasonal registry (6.2): the glows and edge below, with its palms and K. */
    val look: SeasonLook = object : SeasonLook(ID, SeasonPalette(
        night = Colors.night, card = Colors.card, secondary = Colors.secondary, hairline = Colors.hairline,
        accent = Colors.accent, core = Colors.magenta, mid = Colors.orange, far = Colors.violet, cool = Colors.cyan,
        fill = listOf(Colors.magenta, Colors.accent, Colors.orange), ink = Colors.ink,
        edge = listOf(Colors.magenta, Colors.orange, Colors.cyan))) {
        override val edgeTint: Color get() = Colors.magenta
        @Composable override fun HomeDecor(modifier: Modifier, fade: () -> Float) =
            com.ketvoice.app.ui.vicecity.ViceCityPalms(modifier, fade)
        @Composable override fun BottomDecor(modifier: Modifier) = com.ketvoice.app.ui.vicecity.ViceCityFloor(modifier)
        @Composable override fun Emblem(size: Dp, modifier: Modifier) = com.ketvoice.app.ui.vicecity.ViceCityLogo(size, modifier)
        @Composable override fun Preview(modifier: Modifier, height: Dp) = com.ketvoice.app.ui.vicecity.ViceCityPreview(modifier, height)
    }

    fun theme(): KetTheme = look.theme()

    /**
     * The theme is on around a surface that keeps its own colours (the player, 6.3): there only its neon tints apply —
     * the play button's ring, the times, the progress, a held note's glow — never its night or its layout.
     */
    val LocalNeon = androidx.compose.runtime.staticCompositionLocalOf { false }

    /** Whether the neon tints apply here: on a page of the theme, or inside the player while the theme is on. */
    @Composable
    fun neonHere(): Boolean = LocalTheme.current.viceCity || LocalNeon.current

    /** The sunset of a filled button, left to right. */
    val sunset: Brush get() = look.fill

    /** Where the glow is in its slow cycle, 0..1; set by [Pulse] at the root, read in the draw phase only. */
    val phase get() = SeasonPulse.phase

    /** One breath of the glow. */
    const val CYCLE_MS = SeasonPulse.CYCLE_MS
    /** How often the glow moves: often enough to look continuous at its slow pace. */
    fun stepMs(performance: Boolean): Long = SeasonPulse.stepMs(performance)

    /** Moves the glow while the theme is on and frames are drawn (it pauses with the app); still under reduced motion. */
    @Composable
    fun Pulse(reducedMotion: Boolean, performance: Boolean) = SeasonPulse.Pulse(reducedMotion, performance)

    /** The glow at the top of a page (see [pageBackground]). */
    val glow: Modifier get() = look.glow

    /** The sunset behind the top of a page: a magenta core turning orange, from just above the screen. */
    fun DrawScope.topGlow(phase: Float) = with(look) { topGlow(phase) }

    /** The glow the glass tab bar floats over, rising from the bottom edge. */
    fun DrawScope.bottomGlow(phase: Float) = with(look) { bottomGlow(phase) }

    /** The edge of a selected tab or a main button, as a thin neon tube with a soft halo (the tube alone in the performance mode). */
    fun Modifier.viceCityNeon(shape: Shape, on: Boolean): Modifier = with(look) { seasonEdge(shape, on) }
}
