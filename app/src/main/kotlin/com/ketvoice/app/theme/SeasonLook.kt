package com.ketvoice.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The colours of a seasonal theme (6.2). Every one is dark: the pages are a tinted night, the cards one step
 * lighter, the texts white and [secondary], all at WCAG AA (SeasonPaletteTest); the glow at the top of the pages
 * mixes [core], [mid], [far] and a touch of [cool]; buttons are filled with [fill] under an [ink] label; the
 * selected tab and the main buttons get a thin tube of [edge].
 */
@Immutable
class SeasonPalette(
    val night: Color, val card: Color, val secondary: Color, val hairline: Color, val accent: Color,
    val core: Color, val mid: Color, val far: Color, val cool: Color,
    val fill: List<Color>, val ink: Color, val edge: List<Color>,
)

/** The slow breath shared by every seasonal glow: one phase, set at the root, read in the draw phase only. */
object SeasonPulse {
    /** Where the glow is in its slow cycle, 0..1. */
    val phase = mutableFloatStateOf(.25f)

    /** One breath of the glow. */
    const val CYCLE_MS = 16_000L
    /** How often the glow moves: often enough to look continuous at its slow pace. */
    fun stepMs(performance: Boolean): Long = if (performance) 500L else 100L

    /** Moves the glow while a seasonal theme is on and frames are drawn; still under reduced motion. */
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
}

/**
 * The look of one seasonal theme: its [KetTheme], the glows of the pages and of the tab bar, the edge of the
 * selected tab and of the main buttons, the decor of Home's header, its emblem and a small preview for Settings.
 * The glows and the edge are the Vice City edition's (6.1), drawn with the season's [palette]; a season draws its
 * own decor ([HomeDecor]).
 */
abstract class SeasonLook(val id: String, val palette: SeasonPalette) {
    fun theme(): KetTheme = KetTheme(
        isDark = true, background = palette.night, onBackground = Color.White, secondary = palette.secondary,
        card = palette.card, hairline = palette.hairline, accent = palette.accent, season = this)

    /** A filled button, left to right. */
    val fill: Brush get() = Brush.horizontalGradient(palette.fill)

    /** The page's top edge keeps a little of the glow's colour. */
    open val edgeTint: Color get() = palette.core

    /** The glow at the top of a page (see [pageBackground]). */
    val glow: Modifier get() = Modifier.drawBehind { topGlow(SeasonPulse.phase.floatValue) }

    /** The glow behind the top of a page: a warm core turning to [SeasonPalette.mid], from just above the screen. */
    open fun DrawScope.topGlow(phase: Float) {
        val turn = phase * 2f * PI.toFloat()
        val drift = sin(turn)
        val breath = .5f + .5f * cos(turn)
        val w = size.width
        val r = max(w * .95f, 360.dp.toPx())
        val h = min(size.height, r)
        drawRect(Brush.radialGradient(
            0f to palette.core.copy(alpha = .40f + .06f * breath),
            .38f to palette.mid.copy(alpha = .15f + .04f * breath),
            .70f to palette.far.copy(alpha = .07f),
            1f to Color.Transparent,
            center = Offset(w * (.38f + .07f * drift), -r * .14f), radius = r), size = Size(w, h))
        drawRect(Brush.radialGradient(
            0f to palette.cool.copy(alpha = .12f + .03f * (1f - breath)), 1f to Color.Transparent,
            center = Offset(w * (.98f - .05f * drift), -r * .04f), radius = r * .40f), size = Size(w, h))
    }

    /** The glow the glass tab bar floats over, rising from the bottom edge. */
    open fun DrawScope.bottomGlow(phase: Float) {
        val turn = phase * 2f * PI.toFloat()
        val w = size.width
        val r = max(w * .75f, 300.dp.toPx())
        drawRect(Brush.radialGradient(
            0f to palette.core.copy(alpha = .30f + .05f * cos(turn)),
            .5f to palette.far.copy(alpha = .10f),
            1f to Color.Transparent,
            center = Offset(w * (.5f - .06f * sin(turn)), size.height + r * .35f), radius = r))
    }

    /** The edge of a selected tab or a main button: a thin tube with a soft halo (the tube alone in the performance mode). */
    fun Modifier.seasonEdge(shape: Shape, on: Boolean): Modifier = if (!on) this else drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val tube = Brush.linearGradient(palette.edge, Offset.Zero, Offset(size.width, size.height))
        val halo = if (AppSettings.highPerformanceMode.value) emptyList()
            else listOf(7.dp.toPx() to .05f, 4.dp.toPx() to .08f, 2.2.dp.toPx() to .14f)
        val thin = Stroke(1.dp.toPx())
        onDrawWithContent {
            drawContent()
            halo.forEach { (width, alpha) -> drawOutline(outline, palette.core.copy(alpha = alpha), style = Stroke(width)) }
            drawOutline(outline, tube, alpha = .9f, style = thin)
        }
    }

    /** The decor of Home's header, fading out with the large title ([fade], 0..1, read in the draw phase only). */
    @Composable abstract fun HomeDecor(modifier: Modifier, fade: () -> Float)

    /**
     * What a season lays at the very bottom of the pages, behind the tab bar, over the scroll edge (6.3): nothing by
     * default; Vice City's neon floor. Decorative only, never under text that must be read.
     */
    @Composable open fun BottomDecor(modifier: Modifier) {}

    /** The season's badge, in a rounded square. */
    @Composable abstract fun Emblem(size: Dp, modifier: Modifier)

    /** A small picture of the theme for Settings and the welcome card. */
    @Composable abstract fun Preview(modifier: Modifier, height: Dp)
}

/** Draws [look]'s top glow, for previews and tests. */
fun DrawScope.seasonTopGlow(look: SeasonLook, phase: Float) = with(look) { topGlow(phase) }
