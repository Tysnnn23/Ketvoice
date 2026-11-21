package com.ketvoice.app.ui.motion

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp

/**
 * 8.0 « Menus qui sortent du bouton »: a menu, a sheet or an alert grows out of the control that was
 * touched — a drop of glass that stretches from the button's rectangle (and its corner radius) to its
 * own, on a spring, its content fading and scaling in after [CONTENT_FROM] of the travel — and folds
 * back into it when it closes (into its own centre when the button has left the screen).
 *
 * Everything is in screen pixels, so a container in a popup or a dialog window grows from a button of
 * the main window. The numbers are pure so they can be checked without a screen; the modifiers read
 * the progress in the draw phase only (no recomposition per frame). With reduced motion it is a quick fade.
 */
internal object GrowFromSource {
    /** The share of the travel after which the content starts to show. */
    const val CONTENT_FROM = .4f
    /** The content's scale when it starts to show. */
    const val CONTENT_SCALE = .9f
    /** Reduced motion: the container is where it ends and only fades, this fast. */
    const val REDUCED_FADE_MS = 120
    /** A source this long ago no longer counts: what opens later was not opened by that touch. */
    const val FRESH_MS = 650L

    /** Tests freeze every growth at this progress (0..1) to render it half-way (state, so the layers redraw). */
    private val frozenState = mutableStateOf<Float?>(null)
    @get:VisibleForTesting @set:VisibleForTesting
    internal var frozen: Float?
        get() = frozenState.value
        set(value) { frozenState.value = value }

    /** One frame of the growth: where the container shows, its corner radius, how much of its content shows. */
    data class Frame(val rect: Rect, val radius: Float, val contentAlpha: Float, val contentScale: Float,
                     val alignX: Float, val alignY: Float)

    /** Where the growth starts: [source] while some of it is on [screen], otherwise a small drop in the middle of [target]. */
    fun resolve(source: Rect?, target: Rect, screen: Rect?): Rect {
        val usable = source?.takeIf { it.width > 0f && it.height > 0f && it.left.isFinite() && it.top.isFinite() &&
            (screen == null || it.overlaps(screen)) }
        return usable ?: Rect(target.center, minOf(target.width, target.height) * .1f)
    }

    fun frame(source: Rect, target: Rect, sourceRadius: Float, targetRadius: Float, progress: Float): Frame {
        // A spring may pass its end a little: the drop stretches slightly past its size, never far.
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1.08f) else 1f
        val left = lerp(source.left, target.left, p)
        val top = lerp(source.top, target.top, p)
        val width = lerp(source.width, target.width, p).coerceAtLeast(1f)
        val height = lerp(source.height, target.height, p).coerceAtLeast(1f)
        val radius = lerp(sourceRadius, targetRadius, p.coerceIn(0f, 1f)).coerceIn(0f, minOf(width, height) / 2f)
        val shown = ((p - CONTENT_FROM) / (1f - CONTENT_FROM)).coerceIn(0f, 1f)
        val alignX = if (target.width > 0f) ((source.center.x - target.left) / target.width).coerceIn(0f, 1f) else .5f
        val alignY = if (target.height > 0f) ((source.center.y - target.top) / target.height).coerceIn(0f, 1f) else .5f
        return Frame(Rect(left, top, left + width, top + height), radius, shown,
            lerp(CONTENT_SCALE, 1f, shown), alignX, alignY)
    }
}

/** The control a menu or a sheet grows out of: its bounds on screen and its corner radius, in px. */
internal data class GrowSource(val rect: Rect, val radius: Float)

/**
 * The last control pressed and the last touch, noted on the way down (never consumed). A control that
 * carries [growSource] gives its own bounds; any other touch of the app gives a 44 dp drop around the finger.
 */
internal object GrowSources {
    private class Noted(val source: GrowSource, val at: Long)
    @Volatile private var control: Noted? = null
    @Volatile private var touch: Noted? = null

    fun noteControl(rect: Rect, radius: Float, at: Long = SystemClock.uptimeMillis()) { control = Noted(GrowSource(rect, radius), at) }
    fun noteTouch(point: Offset, radius: Float, at: Long = SystemClock.uptimeMillis()) {
        touch = Noted(GrowSource(Rect(point, radius), radius), at)
    }
    fun clear() { control = null; touch = null }

    /** Where something opening at [now] comes from: the control just pressed, else the touch, else nowhere. */
    fun current(now: Long = SystemClock.uptimeMillis()): GrowSource? {
        val c = control?.takeIf { now - it.at in 0..GrowFromSource.FRESH_MS }
        val t = touch?.takeIf { now - it.at in 0..GrowFromSource.FRESH_MS }
        return when {
            c != null && (t == null || c.at >= t.at - 50L) -> c.source
            else -> t?.source
        }
    }
}

/** A growth in progress: one per container that opens. [progress] 0 = the source, 1 = the container's place. */
@Stable
internal class GrowTransition(private val sourceOf: () -> GrowSource?, private val screen: Rect?, val reduced: Boolean) {
    val progress = Animatable(0f)
    /** The container's own place on screen, measured once it is laid out. */
    var target: Rect? by mutableStateOf(null)
        internal set

    /** Whether there is a control to grow from (a sheet without one keeps its own rise). */
    val hasSource: Boolean get() = sourceOf() != null

    suspend fun open() {
        if (reduced) progress.animateTo(1f, tween(GrowFromSource.REDUCED_FADE_MS))
        else progress.animateTo(1f, Motion.Springs.GROW.spec())
    }

    /** Folds back into the source (or into the middle). Interrupting [open] continues from where it is, with its speed. */
    suspend fun close() {
        if (reduced) progress.animateTo(0f, tween(GrowFromSource.REDUCED_FADE_MS))
        else progress.animateTo(0f, Motion.Springs.DISMISS.spec())
    }

    internal fun shownProgress(): Float = GrowFromSource.frozen ?: progress.value

    /** The frame drawn now for a container whose final corner radius is [targetRadius] px; null before it is laid out. */
    fun frame(targetRadius: Float): GrowFromSource.Frame? {
        val t = target ?: return null
        val s = sourceOf()
        val from = GrowFromSource.resolve(s?.rect, t, screen)
        val radius = if (s != null && from == s.rect) s.radius else minOf(from.width, from.height) / 2f
        return GrowFromSource.frame(from, t, radius, targetRadius, shownProgress())
    }
}

/** The main window's origin on screen, to put root coordinates (a row anchor) on screen. */
@Composable
internal fun rootOnScreen(): Offset {
    val view = LocalView.current
    val at = IntArray(2)
    view.getLocationOnScreen(at)
    return Offset(at[0].toFloat(), at[1].toFloat())
}

/**
 * Remembers the growth of a container that opens now. [source] is read on every frame (screen px), so a
 * container closing after its button scrolled away folds into its own middle; by default it is the control
 * or the touch that has just been pressed.
 */
@Composable
internal fun rememberGrowTransition(key: Any? = Unit, source: (() -> GrowSource?)? = null): GrowTransition {
    val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    return remember(key) {
        val fixed = if (source == null) GrowSources.current() else null
        GrowTransition(source ?: { fixed }, Rect(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat()), Motion.reduced)
    }
}

/** A rounded rectangle placed anywhere in the layer (the part of the container shown so far). */
private class PlacedRoundRect(val rect: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * Put on the container (outside its own surface): it is laid out where it ends, and drawn moved and
 * clipped to the frame of the growth. [radius] is its corner radius once open.
 */
internal fun Modifier.growContainer(transition: GrowTransition, radius: Dp): Modifier = this
    .onPlaced { transition.target = Rect(it.positionOnScreen(), it.size.toSize()) }
    .graphicsLayer {
        val target = transition.target
        if (target == null) { alpha = 0f; return@graphicsLayer }
        if (transition.reduced) { alpha = transition.shownProgress().coerceIn(0f, 1f); return@graphicsLayer }
        val f = transition.frame(radius.toPx()) ?: return@graphicsLayer
        val clipLeft = f.alignX * (target.width - f.rect.width)
        val clipTop = f.alignY * (target.height - f.rect.height)
        translationX = f.rect.left - target.left - clipLeft
        translationY = f.rect.top - target.top - clipTop
        alpha = (transition.shownProgress() * 4f).coerceIn(0f, 1f)
        // Settled, the container is no longer clipped: its own shape (a sheet's square bottom) shows whole.
        if (transition.shownProgress() < .999f) {
            shape = PlacedRoundRect(Rect(clipLeft, clipTop, clipLeft + f.rect.width, clipTop + f.rect.height), f.radius)
            clip = true
        }
    }

/** Put on the container's content: it fades and scales in once the container has travelled [GrowFromSource.CONTENT_FROM]. */
internal fun Modifier.growContent(transition: GrowTransition): Modifier = graphicsLayer {
    if (transition.reduced) return@graphicsLayer
    val f = transition.frame(0f) ?: run { alpha = 0f; return@graphicsLayer }
    alpha = f.contentAlpha
    scaleX = f.contentScale; scaleY = f.contentScale
    transformOrigin = TransformOrigin(f.alignX, f.alignY)
}

/**
 * Notes this control's bounds (and corner radius, a capsule by default) when it is pressed, so that a
 * menu, a sheet or an alert it opens grows out of it. Costs nothing while it scrolls: the coordinates are
 * only kept, and read on the way down of a touch.
 */
internal fun Modifier.growSource(radius: Dp? = null): Modifier = this then GrowSourceElement(radius, false)

/** For the root of the app: notes every touch, never consumed, as the fallback source. */
internal fun Modifier.growTouches(): Modifier = this then GrowSourceElement(null, true)

private data class GrowSourceElement(val radius: Dp?, val point: Boolean) : ModifierNodeElement<GrowSourceNode>() {
    override fun create() = GrowSourceNode(radius, point)
    override fun update(node: GrowSourceNode) { node.radius = radius; node.point = point }
}

private class GrowSourceNode(var radius: Dp?, var point: Boolean) : Modifier.Node(), LayoutAwareModifierNode,
    PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private var coordinates: LayoutCoordinates? = null
    override fun onPlaced(coordinates: LayoutCoordinates) { this.coordinates = coordinates }
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val down = pointerEvent.changes.firstOrNull { it.pressed && !it.previousPressed } ?: return
        val c = coordinates?.takeIf { it.isAttached } ?: return
        val origin = runCatching { c.positionOnScreen() }.getOrNull() ?: return
        val density = currentValueOf(LocalDensity)
        if (point) GrowSources.noteTouch(origin + down.position, with(density) { 22.dp.toPx() })
        else {
            val size = c.size.toSize()
            val r = radius?.let { with(density) { it.toPx() } } ?: (minOf(size.width, size.height) / 2f)
            GrowSources.noteControl(Rect(origin, size), r)
        }
    }
    override fun onCancelPointerInput() {}
}
