package com.ketvoice.desktop.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What glass surfaces see through: the content of a [glassSource], recorded once per frame into a graphics layer
 * that every surface above it draws again (moved to its own place, then refracted or blurred).
 */
@Stable
class GlassBackdrop {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var coords: LayoutCoordinates? = null
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

/** Records this content as [backdrop] (and draws it as usual). Glass drawn above it, outside it, refracts it. */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier = composed {
    val layer = rememberGraphicsLayer()
    DisposableEffect(backdrop, layer) {
        backdrop.layer = layer
        onDispose { if (backdrop.layer === layer) backdrop.layer = null }
    }
    onPlaced { backdrop.coords = it }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/** How a surface bends and frosts. Lengths in dp. */
@Immutable
data class GlassStyle(
    val refraction: Dp = 20.dp,
    val bevel: Dp = 24.dp,
    val frost: Dp = 12.dp,
    val dispersion: Float = .35f,
    val specular: Float = .5f,
) {
    companion object {
        /** The sidebar and the side panel: a deep rim, a soft frost so their text reads. */
        val Panel = GlassStyle(refraction = 26.dp, bevel = 30.dp, frost = 16.dp, specular = .45f)
        /** The player island: a capsule, bent hard at its round ends. */
        val Island = GlassStyle(refraction = 22.dp, bevel = 26.dp, frost = 12.dp, specular = .6f)
        /** A small control (a round button, the lens drop): mostly rim. */
        val Drop = GlassStyle(refraction = 10.dp, bevel = 14.dp, frost = 3.dp, dispersion = .45f, specular = .7f)
        /** A card over a picture (Home's hero): clear, a light frost. */
        val Clear = GlassStyle(refraction = 18.dp, bevel = 22.dp, frost = 6.dp, specular = .55f)
    }
}

/** A magnifying disc inside a surface (the lens under the pointer), in the surface's own pixels. */
@Immutable
data class GlassLens(val center: Offset, val zoom: Float, val radius: Float)

/**
 * Draws this surface as glass over [backdrop]: [GlassMode.LIQUID] refracts it through [GlassShader],
 * [GlassMode.FROSTED] blurs it, [GlassMode.TINTED] only lays [tint] (as 1.2's Acrylic). [tint] goes over the
 * backdrop in every mode (stronger when it is flat), then a light rim, then the content.
 */
fun Modifier.glass(
    backdrop: GlassBackdrop?,
    radius: Dp,
    tint: Color,
    style: GlassStyle = GlassStyle.Panel,
    rim: Color = Color.White.copy(alpha = .16f),
    lens: (() -> GlassLens?)? = null,
): Modifier = composed {
    val mode = LocalGlassMode.current
    val layer = rememberGraphicsLayer()
    val me = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val path = remember { GlassPath() }
    onPlaced { me[0] = it }.drawWithContent {
        val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
        val clip = path.of(size, r)
        val drawn = mode != GlassMode.TINTED && backdrop != null && drawBackdrop(backdrop, me[0], layer, clip, r, style, mode, lens?.invoke())
        drawPath(clip, if (drawn) tint else GlassTint.flat(tint))
        // A 2 % grain, as WinUI's Acrylic: it keeps the frost from reading as plain transparency.
        drawPath(clip, com.ketvoice.desktop.ui.Acrylic.grain, alpha = .025f)
        drawRim(clip, r, rim, mode == GlassMode.LIQUID && drawn)
        drawContent()
    }
}

/** The flat colours of [GlassMode.TINTED], and how strong a tint is over a refracted or blurred backdrop. */
object GlassTint {
    /** A tint drawn alone covers what is behind as 1.2's material did. */
    fun flat(tint: Color): Color = tint.copy(alpha = (tint.alpha * 1.9f).coerceIn(.72f, .94f))
}

/** The backdrop moved under this surface, refracted ([GlassMode.LIQUID]) or blurred; false when there is none. */
private fun ContentDrawScope.drawBackdrop(backdrop: GlassBackdrop, mine: LayoutCoordinates?, layer: GraphicsLayer, clip: Path, r: Float,
                                          style: GlassStyle, mode: GlassMode, lens: GlassLens?): Boolean {
    val source = backdrop.layer ?: return false
    val origin = backdrop.coords?.takeIf { it.isAttached } ?: return false
    val self = mine?.takeIf { it.isAttached } ?: return false
    val at = runCatching { origin.localPositionOf(self, Offset.Zero) }.getOrNull() ?: return false
    layer.renderEffect = effectFor(mode, style, r, lens) ?: BlurEffect(style.frost.toPx() * 1.6f + 1f, style.frost.toPx() * 1.6f + 1f, TileMode.Clamp)
    layer.record { translate(-at.x, -at.y) { drawLayer(source) } }
    clipPath(clip) { drawLayer(layer) }
    return true
}

private fun DrawScope.effectFor(mode: GlassMode, style: GlassStyle, r: Float, lens: GlassLens?) =
    if (mode != GlassMode.LIQUID) null else GlassShader.effect(GlassShader.Params(
        size = Size(size.width, size.height), radius = r, refraction = style.refraction.toPx(), bevel = style.bevel.toPx(),
        frost = style.frost.toPx(), dispersion = style.dispersion, specular = style.specular,
        lens = lens?.center ?: Offset.Unspecified, zoom = lens?.zoom ?: 1f, lensRadius = lens?.radius ?: 0f, rimWidth = 1.6.dp.toPx(),
    ))

/** A hairline that catches the light at the top and fades at the bottom (the shader draws the rest of the rim). */
private fun DrawScope.drawRim(clip: Path, r: Float, rim: Color, liquid: Boolean) {
    if (rim.alpha <= 0f) return
    val w = 1.dp.toPx()
    val brush = Brush.verticalGradient(0f to rim, .45f to rim.copy(alpha = rim.alpha * (if (liquid) .25f else .45f)), 1f to rim.copy(alpha = rim.alpha * .6f))
    drawRoundRect(brush, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius((r - w / 2).coerceAtLeast(0f)), style = Stroke(w))
}

/** The rounded outline of a surface, made again only when its size or radius changes. */
private class GlassPath {
    private var key: Triple<Float, Float, Float>? = null
    private val path = Path()
    fun of(size: Size, r: Float): Path {
        val k = Triple(size.width, size.height, r)
        if (k != key) {
            path.reset()
            path.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
            key = k
        }
        return path
    }
}
