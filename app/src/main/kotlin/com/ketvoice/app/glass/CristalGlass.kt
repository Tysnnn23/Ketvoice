package com.ketvoice.app.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.LocalTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * The look of the glass (8.4, owner 2026-11-22: « fais un mode liquid glass nouveau que tu nommes comme tu veux »):
 * [LIQUID_27] stays the default and is not changed in any way; [CRISTAL] is a second, lighter optical glass with its
 * own material ([Cristal]) and its own motion (the tab bar, its selection, the mini-player, the menus).
 */
object GlassLook {
    const val LIQUID_27 = 0
    const val CRISTAL = 1
    const val MAX = CRISTAL

    /** The names shown in Settings (French source strings, translated). */
    val labels = listOf("Liquid 27", "Cristal")

    /** Whether the listener picked Cristal; read where it is used (composition or drawing). */
    val cristal: Boolean get() = AppSettings.glassLook.value == CRISTAL
}

/**
 * Cristal's material, in one place. Every surface samples the page behind it at a third of its size, saturates it,
 * blurs it a little and bends it at the rim, then lays a 40 % tint of near-black (dark theme) or near-white (light)
 * over it, with the backdrop library's default rim light and shadow. Menus and sheets are frost instead: a stronger
 * saturation, a wider blur on a quarter-size copy and a denser fill.
 */
internal object Cristal {
    // ---- Controls and navigation.
    const val SATURATION = 1.5f
    const val BLUR_DP = 8f
    const val LENS_HEIGHT_DP = 24f
    const val LENS_AMOUNT_DP = 24f
    /** Each side of the sampled page, as a fraction (about 11 % of the pixels). */
    const val SCALE = .33f
    /** Performance mode (and the automatic economy mode): no lens, so the blur alone breaks the page's text up. */
    const val FROST_BLUR_DP = 16f
    const val FROST_SCALE = .25f
    const val TINT_ALPHA = .4f
    val TINT_DARK = Color(0xFF121212)
    val TINT_LIGHT = Color(0xFFFAFAFA)
    /** The hairline that stands in for the rim wherever the glass itself is not drawn. */
    val EDGE_WIDTH: Dp = .5.dp
    val EDGE = Color.White.copy(alpha = .10f)

    // ---- Menus, sheets and alerts.
    const val MENU_SATURATION = 1.8f
    const val MENU_BLUR_DP = 24f
    const val MENU_SCALE = .25f
    val MENU_FILL_DARK = Color(0xFF1C1C1E)
    val MENU_FILL_LIGHT = Color(0xFFF6F6F7)
    const val MENU_FILL_ALPHA_DARK = .60f
    const val MENU_FILL_ALPHA_LIGHT = .72f
    val MENU_RIM_DARK = Color.White.copy(alpha = .10f)
    val MENU_RIM_LIGHT = Color.Black.copy(alpha = .06f)
    val MENU_SHADOW: Dp = 24.dp
    /** The page under a held menu. */
    const val VEIL_BLUR_DP = 20f
    const val PLATE_ALPHA = .5f
    const val PLATE_ALPHA_SOLID = .85f
    const val MENU_SHADOW_ALPHA = .30f

    // ---- The tab bar's selection.
    val INDICATOR_LIGHT = Color.White.copy(alpha = .5f)
    val INDICATOR_DARK = Color.White.copy(alpha = .14f)
    const val PILL_LENS_HEIGHT_DP = 12f
    const val PILL_LENS_AMOUNT_DP = 14f
    val PILL_SHADOW = Shadow(radius = 12.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = .3f))

    fun tint(dark: Boolean): Color = (if (dark) TINT_DARK else TINT_LIGHT).copy(alpha = TINT_ALPHA)
    fun solid(dark: Boolean): Color = if (dark) TINT_DARK else TINT_LIGHT
    fun content(dark: Boolean): Color = if (dark) Color.White else Color.Black
    fun indicator(dark: Boolean): Color = if (dark) INDICATOR_DARK else INDICATOR_LIGHT
    fun menuFill(dark: Boolean, frosted: Boolean): Color =
        (if (dark) MENU_FILL_DARK else MENU_FILL_LIGHT).copy(alpha = if (!frosted) 1f else if (dark) MENU_FILL_ALPHA_DARK else MENU_FILL_ALPHA_LIGHT)
    fun menuRim(dark: Boolean): Color = if (dark) MENU_RIM_DARK else MENU_RIM_LIGHT

    /** The fraction a backdrop is sampled at: fixed, a quarter in [economy]; full size where nothing is blurred. */
    fun scale(blurPx: Float, economy: Boolean, api: Int = GlassCompat.api): Float =
        if (api < Build.VERSION_CODES.S || !blurPx.isFinite() || blurPx <= 0f) 1f else if (economy) FROST_SCALE else SCALE

    /** The menus' frost is always sampled at a quarter. */
    fun menuScale(blurPx: Float, api: Int = GlassCompat.api): Float = scale(blurPx, economy = true, api = api)
}

/** How a Cristal surface is drawn on this device with these settings. */
internal enum class CristalPath {
    /** « Réduire le flou »: an opaque fill and the hairline. */
    Solid,
    /** No live page behind it (glass off, or drawn inside the captured page): the tint and the hairline. */
    Tinted,
    /** Android 11 (Ketvoice Lite): the page blurred by [SoftBlur], saturated and tinted; no lens. */
    Soft,
    /** Android 12: blurred, saturated and tinted, rim light and shadow; no lens (it needs Android 13). */
    Blur,
    /** Android 13+: the whole material, lens included (dropped in the performance mode). */
    Optical,
}

internal fun cristalPath(api: Int, hasBackdrop: Boolean, enabled: Boolean, captured: Boolean, reduceBlur: Boolean): CristalPath = when {
    reduceBlur -> CristalPath.Solid
    !hasBackdrop || !enabled || captured -> CristalPath.Tinted
    api < Build.VERSION_CODES.S -> CristalPath.Soft
    api < Build.VERSION_CODES.TIRAMISU -> CristalPath.Blur
    else -> CristalPath.Optical
}

/** What the control glass does right now: its blur (dp), the fraction it samples at, and whether it bends. */
internal data class CristalSpec(val blurDp: Float, val economy: Boolean, val lens: Boolean)

/** [economy]: the performance mode (or the economy mode, or a phone with little memory). */
internal fun cristalSpec(path: CristalPath, economy: Boolean): CristalSpec = CristalSpec(
    blurDp = if (economy) Cristal.FROST_BLUR_DP else Cristal.BLUR_DP,
    economy = economy,
    lens = path == CristalPath.Optical && !economy)

/**
 * A Cristal control or navigation surface. [shadow] false for surfaces drawn clipped to their own outline (the tab
 * bar's), where an outer shadow would not show anyway. [export] records the glass as drawn, for a lens above it.
 */
@Composable
internal fun Modifier.cristalGlass(shape: Shape, dark: Boolean, enabled: Boolean = true, captured: Boolean = false,
                                   shadow: Boolean = true, export: LayerBackdrop? = null): Modifier {
    val backdrop = LocalBackdropLayer.current
    val path = cristalPath(GlassCompat.api, backdrop != null, enabled && AppSettings.liquidGlass.value, captured,
        AppSettings.reduceDynamicBlur.value)
    return when {
        path == CristalPath.Solid -> background(Cristal.solid(dark), shape).border(Cristal.EDGE_WIDTH, Cristal.EDGE, shape)
        path == CristalPath.Tinted || backdrop == null ->
            clip(shape).background(Cristal.tint(dark), shape).border(Cristal.EDGE_WIDTH, Cristal.EDGE, shape)
        else -> cristalLive(shape, dark, path, backdrop, shadow, export)
    }
}

@Composable
private fun Modifier.cristalLive(shape: Shape, dark: Boolean, path: CristalPath, backdrop: Backdrop, shadow: Boolean,
                                 export: LayerBackdrop?): Modifier {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val lowMemory = remember(context) { isLowRamDevice(context) }
    // Read while drawing: the performance mode switching never composes the glass again.
    val economy = remember(lowMemory) { { lowMemory || PerformanceMode() } }
    val blurPx = remember(density, economy) { { cristalSpec(path, economy()).blurDp * density } }
    val tint = Cristal.tint(dark)
    val soft = if (path == CristalPath.Soft) rememberBackdropBlur() else null
    val softFilter = remember(soft) { soft?.let { cristalSoftFilter(Cristal.SATURATION) } }
    val reduced = if (soft == null) rememberReducedBackdrop(backdrop, blurPx, economy, CristalScale) else null
    return then(if (soft != null) Modifier.onGloballyPositioned { soft.panel = it } else Modifier)
        .drawBackdrop(
            backdrop = reduced ?: backdrop,
            shape = { shape },
            exportedBackdrop = export,
            effects = {
                val spec = cristalSpec(path, economy())
                val radius = spec.blurDp * density
                if (soft == null) colorControls(saturation = Cristal.SATURATION)
                if (reduced?.blursItself(radius) != false && soft == null) blur(radius)
                if (spec.lens && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lens(refractionHeight = Cristal.LENS_HEIGHT_DP * density, refractionAmount = Cristal.LENS_AMOUNT_DP * density,
                        depthEffect = true, chromaticAberration = true)
                }
            },
            highlight = { Highlight.Default },
            shadow = { if (shadow) Shadow.Default else null },
            innerShadow = { null },
            onDrawBackdrop = { drawBackdrop ->
                if (soft == null) drawBackdrop() else with(soft) {
                    drawBlurred(drawBackdrop, blurPx(), softFilter, backdrop, BackdropPlaces.offset(backdrop, panel))
                }
            },
            onDrawSurface = { drawRect(tint) }
        )
}

/**
 * Cristal's frost, for menus, sheets and alerts: the page (a still copy of it while a menu opens, [FrozenBackdrop])
 * saturated 1.8 times and blurred by 24 dp on a quarter-size copy, under [Cristal.menuFill], with a hairline rim.
 * Opaque under « Réduire le flou » or with the glass off. [shadow] casts the panel's own 24 dp shadow.
 */
@Composable
internal fun Modifier.cristalFrost(shape: Shape, dark: Boolean, enabled: Boolean = true, shadow: Boolean = true): Modifier {
    val backdrop = LocalBackdropLayer.current
    val path = cristalPath(GlassCompat.api, backdrop != null, enabled && AppSettings.liquidGlass.value, false,
        AppSettings.reduceDynamicBlur.value)
    val frosted = backdrop != null && (path == CristalPath.Optical || path == CristalPath.Blur || path == CristalPath.Soft)
    val fill = Cristal.menuFill(dark, frosted)
    val shadowed = if (shadow) cristalMenuShadow(shape) else this
    val surface = if (!frosted || backdrop == null) shadowed.clip(shape).background(fill)
        else shadowed.cristalFrostLive(shape, backdrop, fill, path == CristalPath.Soft)
    return surface.clip(shape).border(Cristal.EDGE_WIDTH, Cristal.menuRim(dark), shape)
}

/** The shadow a Cristal menu casts: 24 dp, 30 % black. */
internal fun Modifier.cristalMenuShadow(shape: Shape): Modifier = shadow(Cristal.MENU_SHADOW, shape, clip = false,
    ambientColor = Color.Black.copy(alpha = Cristal.MENU_SHADOW_ALPHA), spotColor = Color.Black.copy(alpha = Cristal.MENU_SHADOW_ALPHA))

@Composable
private fun Modifier.cristalFrostLive(shape: Shape, backdrop: Backdrop, fill: Color, softPath: Boolean): Modifier {
    val density = LocalDensity.current.density
    val blurPx = remember(density) { { Cristal.MENU_BLUR_DP * density } }
    val soft = if (softPath) rememberBackdropBlur() else null
    val softFilter = remember(soft) { soft?.let { cristalSoftFilter(Cristal.MENU_SATURATION) } }
    val reduced = if (soft == null) rememberReducedBackdrop(backdrop, blurPx, AlwaysEconomy, MenuScale) else null
    return then(if (soft != null) Modifier.onGloballyPositioned { soft.panel = it } else Modifier)
        .drawBackdrop(
            backdrop = reduced ?: backdrop,
            shape = { shape },
            effects = {
                if (soft == null) {
                    colorControls(saturation = Cristal.MENU_SATURATION)
                    if (reduced?.blursItself(blurPx()) != false) blur(blurPx())
                }
            },
            highlight = { null },
            shadow = { null },
            innerShadow = { null },
            onDrawBackdrop = { drawBackdrop ->
                if (soft == null) drawBackdrop() else with(soft) {
                    drawBlurred(drawBackdrop, blurPx(), softFilter, backdrop, BackdropPlaces.offset(backdrop, panel))
                }
            },
            onDrawSurface = { drawRect(fill) }
        )
}

/**
 * The page under a title's held menu, blurred (20 dp, on the quarter-size still copy of [FrozenBackdrop]) so the
 * translucent plate and menu read over it. Nothing where the glass is not live (the scrim alone then).
 */
@Composable
internal fun Modifier.cristalVeil(): Modifier {
    val backdrop = LocalBackdropLayer.current ?: return this
    val path = cristalPath(GlassCompat.api, true, AppSettings.liquidGlass.value, false, AppSettings.reduceDynamicBlur.value)
    if (path != CristalPath.Optical && path != CristalPath.Blur) return this
    val density = LocalDensity.current.density
    val blurPx = remember(density) { { Cristal.VEIL_BLUR_DP * density } }
    val reduced = rememberReducedBackdrop(backdrop, blurPx, AlwaysEconomy, MenuScale)
    return drawBackdrop(backdrop = reduced, shape = { RectangleShape },
        effects = { if (reduced.blursItself(blurPx())) blur(blurPx()) },
        highlight = { null }, shadow = { null }, innerShadow = { null })
}

private val CristalScale: (Float, Boolean) -> Float = { blurPx, economy -> Cristal.scale(blurPx, economy) }
private val MenuScale: (Float, Boolean) -> Float = { blurPx, _ -> Cristal.menuScale(blurPx) }
private val AlwaysEconomy: () -> Boolean = { true }

/** The saturation of the glass as a colour filter, for Android 11's [SoftBlur]. */
private fun cristalSoftFilter(saturation: Float): androidx.compose.ui.graphics.ColorFilter =
    androidx.compose.ui.graphics.ColorFilter.colorMatrix(SoftBlur.colorMatrix(saturation = saturation, brightness = 0f,
        contrast = 1f, exposure = 0f))

/**
 * [GlassPanel] in the Cristal look: controls get the optical glass, sheets the frost. The panel's content keeps the
 * theme of its material (a dark panel over a light page reads as dark).
 */
@Composable
internal fun CristalPanel(modifier: Modifier, shape: Shape, params: GlassParams, enabled: Boolean, captured: Boolean,
                          export: LayerBackdrop?, content: @Composable () -> Unit) {
    val theme = LocalTheme.current
    val panelTheme = if (params.dark && !theme.isDark) com.ketvoice.app.theme.KetTheme.dark().copy(accent = theme.accent) else theme
    CompositionLocalProvider(LocalTheme provides panelTheme, LocalOnGlass provides true) {
        val surface = if (params.role == GlassRole.Sheet) Modifier.cristalFrost(shape, params.dark, enabled, params.shadow)
            else Modifier.cristalGlass(shape, params.dark, enabled, captured, params.shadow, export)
        Box(modifier.then(surface)) { content() }
    }
}
