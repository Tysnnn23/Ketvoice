package com.ketvoice.app.ui.vicecity

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.SeasonPulse
import com.ketvoice.app.theme.ViceCityTheme
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion

/*
 * The 80s neon night of the Vice City theme (6.3): a synthwave floor behind the tab bar, chrome large titles with
 * a neon underline that draws in once, and the KETVOICE sign that flickers on once per session. Everything here is
 * drawn only while the theme is on; it is still under reduced motion and lighter in the performance mode.
 */

/** The synthwave floor's geometry and pace, apart so it can be checked without a screen. */
internal object NeonFloor {
    /** Its height above the navigation bar: the tab bar and its margin, never the content above them. */
    const val HEIGHT_DP = 92
    /** Cross lines between the horizon and the bottom edge (fewer in the performance mode). */
    const val ROWS = 7
    const val ROWS_LIGHT = 5
    /** Lines running to the horizon, either side of the middle one (fewer in the performance mode). */
    const val RAYS = 6
    const val RAYS_LIGHT = 4
    /** Cross lines that come towards the viewer in one breath of the glow ([SeasonPulse.CYCLE_MS]): one every 2 s. */
    const val PASSES_PER_CYCLE = 8
    /** Where the lines rest when the floor holds still (performance mode, reduced motion). */
    const val STILL = .35f
    /** The strongest a line gets, at the bottom edge: a faint glow, the page stays the page. */
    const val MAX_ALPHA = .30f

    /** Where the cross lines are in their travel, 0..1; fixed in the performance mode. */
    fun scroll(phase: Float, performance: Boolean): Float =
        if (performance || !phase.isFinite()) STILL else (phase * PASSES_PER_CYCLE).mod(1f)

    /** The depth of each cross line, 0 at the horizon to 1 at the bottom edge, spreading out towards the viewer. */
    fun rows(scroll: Float, count: Int): List<Float> = (0 until count).map { i -> ((i + scroll) / count).let { it * it } }

    /** How much of the floor shows at depth [t]: nothing at the horizon, fading in to the bottom edge. */
    fun presence(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
}

/**
 * The perspective neon grid of a synthwave poster at the very bottom of the pages, behind the tab bar: it fades in
 * from nothing at its horizon and its cross lines slowly come towards the viewer, stepped with the theme's pulse and
 * read in the draw phase only. Still in the performance mode (with fewer lines) and under reduced motion (the pulse
 * does not move). Decorative: no semantics, no touch.
 */
@Composable
fun ViceCityFloor(modifier: Modifier = Modifier) {
    val performance = AppSettings.highPerformanceMode.value
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Canvas(modifier.fillMaxWidth().height(NeonFloor.HEIGHT_DP.dp + inset).testTag("vicecity-floor")) {
        val scroll = if (performance) NeonFloor.STILL else NeonFloor.scroll(SeasonPulse.phase.floatValue, false)
        drawNeonFloor(scroll, performance)
    }
}

/** Draws the floor in the whole of this scope: its horizon at the top edge, its vanishing point above it. */
internal fun DrawScope.drawNeonFloor(scroll: Float, performance: Boolean) {
    val w = size.width
    val h = size.height
    val line = 1.dp.toPx()
    val rays = if (performance) NeonFloor.RAYS_LIGHT else NeonFloor.RAYS
    val vanish = Offset(w / 2f, -h * .55f)
    val spacing = w / (rays * 1.15f)
    val ray = Brush.verticalGradient(0f to Color.Transparent, .35f to ViceCityTheme.Colors.violet.copy(alpha = .10f),
        1f to ViceCityTheme.Colors.cyan.copy(alpha = NeonFloor.MAX_ALPHA * .75f), startY = 0f, endY = h)
    for (i in -rays..rays) {
        val bottom = Offset(w / 2f + i * spacing * 1.9f, h)
        val top = Offset(vanish.x + (bottom.x - vanish.x) * (-vanish.y / (h - vanish.y)), 0f)
        drawLine(ray, top, bottom, line)
    }
    NeonFloor.rows(scroll, if (performance) NeonFloor.ROWS_LIGHT else NeonFloor.ROWS).forEach { depth ->
        val y = depth * h
        drawLine(ViceCityTheme.Colors.magenta.copy(alpha = NeonFloor.MAX_ALPHA * NeonFloor.presence(depth)),
            Offset(0f, y), Offset(w, y), line)
    }
}

/** The large titles' chrome and their neon underline (6.3). */
internal object NeonTitle {
    /** The underline's length once drawn in, and how long it takes. */
    const val UNDERLINE_DP = 46f
    const val DRAW_MS = 700
    const val DELAY_MS = 140

    /** The chrome, repeated on every line of the title ([lineHeightPx] apart), white above its horizon. */
    fun chrome(lineHeightPx: Float): Brush = Brush.verticalGradient(*ViceCityTheme.chromeStops.toTypedArray(),
        startY = 0f, endY = lineHeightPx.coerceAtLeast(1f), tileMode = TileMode.Repeated)
}

/**
 * A large title in the Vice City theme: the 80s chrome on its letters, and a short neon underline that draws in once
 * when the page opens (at once under reduced motion). The underline is drawn just under the title's box, so the page
 * keeps its layout.
 */
@Composable
internal fun ViceCityPageTitle(text: String, modifier: Modifier, style: TextStyle, maxLines: Int) {
    val reveal = remember { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(NeonTitle.DRAW_MS, NeonTitle.DELAY_MS, Motion.EASE_DECELERATE))
    }
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toPx() }
    val chrome = remember(lineHeight) { NeonTitle.chrome(lineHeight) }
    val written = remember(text, chrome) { buildAnnotatedString { withStyle(SpanStyle(brush = chrome)) { append(text) } } }
    Text(written, modifier.neonUnderline { reveal.value }, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** The underline under a title, [progress] (0..1, read in the draw phase only) of its length; no halo in the performance mode. */
internal fun Modifier.neonUnderline(progress: () -> Float): Modifier = drawWithContent {
    drawContent()
    val shown = progress().coerceIn(0f, 1f)
    if (shown <= 0f) return@drawWithContent
    val length = NeonTitle.UNDERLINE_DP.dp.toPx()
    val y = size.height + 1.5.dp.toPx()
    val rtl = layoutDirection == LayoutDirection.Rtl
    val from = if (rtl) size.width else 0f
    val to = if (rtl) from - length * shown else length * shown
    val tube = Brush.horizontalGradient(ViceCityTheme.look.palette.edge, startX = from, endX = if (rtl) from - length else length)
    if (!AppSettings.highPerformanceMode.value) drawLine(ViceCityTheme.Colors.magenta.copy(alpha = .16f),
        Offset(from, y), Offset(to, y), 6.dp.toPx(), StrokeCap.Round)
    drawLine(tube, Offset(from, y), Offset(to, y), 2.5.dp.toPx(), StrokeCap.Round)
}

/** The mini-player's underline in the Vice City theme: a faint neon tube under the sunset of the progress. */
internal object MiniNeon {
    val track: Brush by lazy { Brush.horizontalGradient(ViceCityTheme.look.palette.edge.map { it.copy(alpha = .30f) }) }
}

/** The KETVOICE sign of Home switching on like a neon tube: once per session, about 600 ms, then steady. */
internal object NeonFlicker {
    const val DURATION_MS = 600L
    /** Whether it has played in this session (this process). */
    @Volatile var played = false

    private val at = longArrayOf(0, 60, 110, 180, 240, 320, 370, 460, 520)
    private val level = floatArrayOf(.12f, 1f, .22f, .9f, .3f, 1f, .55f, 1f, .82f)

    /** The sign's brightness [ms] after it starts: a few stutters, then lit for good. */
    fun alpha(ms: Long): Float {
        if (ms < 0 || ms >= DURATION_MS) return 1f
        var i = at.size - 1
        while (i > 0 && at[i] > ms) i--
        return level[i]
    }

    /** Whether it plays now: not yet in this session, and never under reduced motion. */
    fun due(reduced: Boolean): Boolean = !played && !reduced
}

/** The word KETVOICE over Home's greeting in the theme's neon pink, with its once-per-session flicker. */
@Composable
internal fun ViceCityWordmark(modifier: Modifier = Modifier) {
    val glow = !AppSettings.highPerformanceMode.value
    val brightness = remember { mutableFloatStateOf(if (NeonFlicker.due(Motion.reduced)) NeonFlicker.alpha(0) else 1f) }
    LaunchedEffect(Unit) {
        if (!NeonFlicker.due(Motion.reduced)) return@LaunchedEffect
        NeonFlicker.played = true
        val start = withFrameMillis { it }
        var now = start
        while (now - start < NeonFlicker.DURATION_MS) {
            now = withFrameMillis { it }
            brightness.floatValue = NeonFlicker.alpha(now - start)
        }
        brightness.floatValue = 1f
    }
    Text("KETVOICE", localize = false, color = ViceCityTheme.Colors.chromePink, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 2.2.sp, style = TextStyle(shadow = if (glow) Shadow(ViceCityTheme.Colors.magenta.copy(alpha = .7f),
            blurRadius = 10f) else null),
        modifier = modifier.graphicsLayer { alpha = brightness.floatValue }.testTag("vicecity-wordmark"))
}
