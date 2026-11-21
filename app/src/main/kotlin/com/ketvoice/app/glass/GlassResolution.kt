package com.ketvoice.app.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.Backdrop
import kotlin.math.ceil

/**
 * 8.3 « Verre plus fluide »: what lies behind a glass is drawn and blurred at a fraction of its size, then enlarged
 * back under the material. A blur leaves no fine detail, so the enlargement does not show, and the costly part of the
 * glass (drawing the page again under it and blurring it) touches about a ninth of the pixels. The material itself
 * (the Liquid 27 shader: lens, rim light, milk) still runs at full size, so its edges stay sharp.
 *
 * The fraction is a third of each side (11 % of the pixels), a quarter (6 %) in the performance mode
 * (and so during the automatic economy mode). It is never smaller than what the blur can hide: the blur that is left
 * at the small size keeps at least [MIN_SMALL_SIGMA] pixels, so the clearest glass, which barely blurs, keeps more
 * of its resolution, and a glass that does not blur keeps all of it. Android 11 keeps [SoftBlur], which already
 * blurs a small copy; Android 12+ alone uses this.
 */
internal object GlassResolution {
    const val NORMAL = 1f / 3f
    const val ECONOMY = .25f
    /** The Gaussian sigma, in pixels of the small copy, that hides a bilinear enlargement. */
    const val MIN_SMALL_SIGMA = 1f
    /** Above this fraction a separate small pass is not worth it: the glass keeps its full size. */
    const val FULL_FROM = .8f

    /** The fraction of each side a glass blurring by [blurPx] (a RenderEffect radius) is drawn at; 1 = full size. */
    fun scale(blurPx: Float, economy: Boolean, api: Int = GlassCompat.api): Float {
        if (api < Build.VERSION_CODES.S || !blurPx.isFinite() || blurPx <= 0f) return 1f
        val sigma = SoftBlur.sigma(blurPx)
        val wanted = maxOf(if (economy) ECONOMY else NORMAL, MIN_SMALL_SIGMA / sigma)
        return if (wanted >= FULL_FROM) 1f else wanted
    }

    /**
     * The RenderEffect radius that blurs the copy drawn at [scale] as [blurPx] blurs the full size: the same sigma
     * once enlarged (a radius becomes the sigma 0.577 r + 0.5, see [SoftBlur.sigma]).
     */
    fun smallRadius(blurPx: Float, scale: Float): Float =
        ((SoftBlur.sigma(blurPx) * scale - .5f) / .57735f).coerceAtLeast(0f)

    /** The size of the small copy of a glass of [width] × [height] pixels. */
    fun smallSize(width: Float, height: Float, scale: Float): IntSize =
        IntSize(ceil(width * scale).toInt().coerceAtLeast(1), ceil(height * scale).toInt().coerceAtLeast(1))
}

/**
 * A [Backdrop] that hands the glass its [source] already blurred, drawn small ([GlassResolution]) into [layer] and
 * enlarged back. [blurPx] and [economy] are read while drawing (so the transparency slider and the performance mode
 * never compose the glass again); when [GlassResolution.scale] is 1 it draws [source] as it is, and the glass blurs
 * it itself ([blursItself]).
 */
internal class ReducedBackdrop(private val source: Backdrop, private val layer: GraphicsLayer,
                               private val blurPx: () -> Float, private val economy: () -> Boolean) : Backdrop {
    override val isCoordinatesDependent: Boolean get() = source.isCoordinatesDependent
    private var effectRadius = -1f

    /** Whether the glass must still blur what this hands it (the full-size path). */
    fun blursItself(radiusPx: Float = blurPx()): Boolean = GlassResolution.scale(radiusPx, economy()) >= 1f

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
                                        layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val radius = blurPx()
        val s = GlassResolution.scale(radius, economy())
        if (s >= 1f) return with(source) { drawBackdrop(density, coordinates, layerBlock) }
        val small = GlassResolution.smallRadius(radius, s)
        if (small != effectRadius) {
            effectRadius = small
            layer.renderEffect = if (small > 0f) BlurEffect(small, small, TileMode.Clamp) else null
        }
        layer.record(this, layoutDirection, GlassResolution.smallSize(size.width, size.height, s)) {
            scale(s, s, Offset.Zero) { with(source) { drawBackdrop(density, coordinates, layerBlock) } }
        }
        scale(1f / s, 1f / s, Offset.Zero) { drawLayer(layer) }
    }
}

/** One [ReducedBackdrop] per glass surface, its small layer released with it. */
@Composable
internal fun rememberReducedBackdrop(source: Backdrop, blurPx: () -> Float, economy: () -> Boolean): ReducedBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(source, layer, blurPx, economy) { ReducedBackdrop(source, layer, blurPx, economy) }
}
