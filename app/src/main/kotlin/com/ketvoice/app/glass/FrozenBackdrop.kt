package com.ketvoice.app.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ketvoice.app.data.AppSettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 8.3 « Verre plus propre »: a menu, a sheet or an alert that grows out of a button is glass over a still copy of
 * the page, taken as it opens, so the blur behind it does not swim while it grows (a list still gliding, a progress
 * bar, an animated symbol). Where what lies under the glass keeps moving on purpose — a playing Canvas, the
 * player's living background ([MovingBackdrops]) — the glass stays live. Android 11 keeps its live [SoftBlur].
 */
internal object MovingBackdrops {
    private val marked = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /** Canvas videos playing right now, anywhere in the app ([CanvasMotion]). */
    val videos = mutableIntStateOf(0)

    fun mark(backdrop: Any) { marked.add(backdrop) }

    /** Whether a glass over [backdrop] must stay live: it is marked, or a Canvas is playing. */
    fun moving(backdrop: Any): Boolean = backdrop in marked || videos.intValue > 0

    /** Whether a still copy may stand in for [backdrop] on this Android, with these settings. */
    fun mayFreeze(backdrop: Backdrop?, api: Int = GlassCompat.api, glass: Boolean = AppSettings.liquidGlass.value): Boolean =
        backdrop is LayerBackdrop && glass && api >= Build.VERSION_CODES.S && !moving(backdrop)
}

/** Counts a Canvas video while it plays, so that menus opened over it keep the live glass. */
@Composable
internal fun CanvasMotion(playing: Boolean) {
    if (!playing) return
    DisposableEffect(Unit) {
        MovingBackdrops.videos.intValue++
        onDispose { MovingBackdrops.videos.intValue = (MovingBackdrops.videos.intValue - 1).coerceAtLeast(0) }
    }
}

/**
 * The still copy: [image] is the page at [scale] of its size, taken where the page lay at [origin] (window pixels);
 * drawn back at full size under each glass, as the live backdrop would be. A software canvas (a View drawn into a
 * bitmap) cannot draw the copy, which is a hardware bitmap: there [live] is drawn instead.
 */
internal class SnapshotBackdrop(private val image: ImageBitmap, private val origin: Offset, private val scale: Float,
                                private val live: Backdrop) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
                                        layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) return with(live) { drawBackdrop(density, coordinates, layerBlock) }
        val at = coordinates?.takeIf { it.isAttached }?.positionInWindow() ?: return
        translate(origin.x - at.x, origin.y - at.y) {
            scale(1f / scale, 1f / scale, Offset.Zero) { drawImage(image) }
        }
    }
}

/** Takes the still copy of [source], laid out at [place]; null when this device or host cannot. */
internal suspend fun freezeBackdrop(source: LayerBackdrop, place: LayoutCoordinates, graphics: GraphicsContext,
                                    density: Density, scale: Float): SnapshotBackdrop? {
    val page = source.graphicsLayer
    if (page.size.width <= 0 || page.size.height <= 0 || !place.isAttached) return null
    val copy = graphics.createGraphicsLayer()
    return try {
        val size = GlassResolution.smallSize(page.size.width.toFloat(), page.size.height.toFloat(), scale)
        copy.record(density, LayoutDirection.Ltr, size) { scale(scale, scale, Offset.Zero) { drawLayer(page) } }
        val origin = place.positionInWindow()
        SnapshotBackdrop(copy.toImageBitmap(), origin, scale, source)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    } finally {
        graphics.releaseGraphicsLayer(copy)
    }
}

/**
 * Puts [content] (a growing menu, sheet or alert) on a still copy of the backdrop taken when it opens, when that is
 * allowed ([MovingBackdrops.mayFreeze]); until the copy is ready, and wherever it is not allowed, the live one.
 */
@Composable
internal fun FrozenBackdrop(content: @Composable () -> Unit) {
    val live = LocalBackdropLayer.current
    val source = live as? LayerBackdrop
    var still by remember(source) { mutableStateOf<Backdrop?>(null) }
    if (source != null && MovingBackdrops.mayFreeze(source)) {
        val graphics = LocalGraphicsContext.current
        val density = LocalDensity.current
        LaunchedEffect(source) {
            val place = BackdropPlaces.place(source) ?: return@LaunchedEffect
            still = freezeBackdrop(source, place, graphics, density, GlassResolution.NORMAL)
        }
    }
    val shown = still?.takeIf { source != null && !MovingBackdrops.moving(source) } ?: live
    CompositionLocalProvider(LocalBackdropLayer provides shown) { content() }
}
