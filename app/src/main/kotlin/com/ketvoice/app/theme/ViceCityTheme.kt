package com.ketvoice.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    }

    fun theme(): KetTheme = KetTheme(
        isDark = true, background = Colors.night, onBackground = Color.White, secondary = Colors.secondary,
        card = Colors.card, hairline = Colors.hairline, accent = Colors.accent, viceCity = true)

    /** The sunset of a filled button, left to right. */
    val sunset: Brush get() = Brush.horizontalGradient(listOf(Colors.magenta, Colors.accent, Colors.orange))

    /** Where the glow is in its slow cycle, 0..1; set by [Pulse] at the root, read in the draw phase only. */
    val phase = mutableFloatStateOf(.25f)

    /** One breath of the glow. */
    const val CYCLE_MS = 16_000L
    /** How often the glow moves: often enough to look continuous at its slow pace. */
    fun stepMs(performance: Boolean): Long = if (performance) 500L else 100L

    /**
     * Moves the glow while the theme is on and frames are drawn (it pauses with the app). Under reduced motion
     * it stays where it is.
     */
    @Composable
    fun Pulse(reducedMotion: Boolean, performance: Boolean) {
        LaunchedEffect(reducedMotion, performance) {
            if (reducedMotion) return@LaunchedEffect
            val step = stepMs(performance)
            while (true) {
                androidx.compose.runtime.withFrameMillis { now -> phase.floatValue = (now % CYCLE_MS) / CYCLE_MS.toFloat() }
                kotlinx.coroutines.delay(step)
            }
        }
    }

    /** The glow at the top of a page (see [pageBackground]). */
    val glow: Modifier = Modifier.drawBehind { topGlow(phase.floatValue) }

    /** The sunset behind the top of a page: a magenta core turning orange, from just above the screen. */
    fun DrawScope.topGlow(phase: Float) {
        val turn = phase * 2f * PI.toFloat()
        val drift = sin(turn)
        val breath = .5f + .5f * cos(turn)
        val w = size.width
        val r = max(w * .95f, 360.dp.toPx())
        val h = min(size.height, r)
        drawRect(Brush.radialGradient(
            0f to Colors.magenta.copy(alpha = .40f + .06f * breath),
            .38f to Colors.orange.copy(alpha = .15f + .04f * breath),
            .70f to Colors.violet.copy(alpha = .07f),
            1f to Color.Transparent,
            center = Offset(w * (.38f + .07f * drift), -r * .14f), radius = r), size = Size(w, h))
        drawRect(Brush.radialGradient(
            0f to Colors.cyan.copy(alpha = .12f + .03f * (1f - breath)), 1f to Color.Transparent,
            center = Offset(w * (.98f - .05f * drift), -r * .04f), radius = r * .40f), size = Size(w, h))
    }

    /** The glow the glass tab bar floats over, rising from the bottom edge. */
    fun DrawScope.bottomGlow(phase: Float) {
        val turn = phase * 2f * PI.toFloat()
        val w = size.width
        val r = max(w * .75f, 300.dp.toPx())
        drawRect(Brush.radialGradient(
            0f to Colors.magenta.copy(alpha = .30f + .05f * cos(turn)),
            .5f to Colors.violet.copy(alpha = .10f),
            1f to Color.Transparent,
            center = Offset(w * (.5f - .06f * sin(turn)), size.height + r * .35f), radius = r))
    }

    /** The edge of a selected tab or a main button, as a thin neon tube with a soft halo (the tube alone in the performance mode). */
    fun Modifier.viceCityNeon(shape: Shape, on: Boolean): Modifier = if (!on) this else drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val tube = Brush.linearGradient(listOf(Colors.magenta, Colors.orange, Colors.cyan), Offset.Zero, Offset(size.width, size.height))
        val halo = if (AppSettings.highPerformanceMode.value) emptyList()
            else listOf(7.dp.toPx() to .05f, 4.dp.toPx() to .08f, 2.2.dp.toPx() to .14f)
        val thin = Stroke(1.dp.toPx())
        onDrawWithContent {
            drawContent()
            halo.forEach { (width, alpha) -> drawOutline(outline, Colors.magenta.copy(alpha = alpha), style = Stroke(width)) }
            drawOutline(outline, tube, alpha = .9f, style = thin)
        }
    }
}
