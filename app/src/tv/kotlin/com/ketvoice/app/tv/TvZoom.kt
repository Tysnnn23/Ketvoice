package com.ketvoice.app.tv

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * « Zoom carte → page » (1.1.0): opening an album, a playlist or an artist from a card, its picture grows from the
 * card into the page's header (the cover of a collection, the full-screen photo of an artist) and shrinks back into
 * the card on Back. The picture flies over the pages ([TvZoomOverlay]) between two rectangles measured in the root:
 * the card's when it is chosen, the header's once the page has placed it. The header stays hidden until the picture
 * lands on it, the card while the picture comes back. It moves in the layout and draw phases only; under reduced
 * motion nothing flies.
 */
class TvZoom(private val nav: TvNavigator) {
    /** One trip: the card's picture, its rectangle and decoded size, the page it opened and the header's rectangle there. */
    data class Trip(val cardKey: String, val image: String?, val round: Boolean, val card: Rect, val px: Int, val from: TvRoute,
                    val to: TvRoute, val header: Rect? = null)

    var trip by mutableStateOf<Trip?>(null)
        private set
    /** True while the picture goes from the card to the header, false on its way back. */
    var forward by mutableStateOf(true)
        private set
    var flying by mutableStateOf(false)
        private set
    /** 0..1 along the current flight. */
    val progress = mutableFloatStateOf(0f)

    /** A card was chosen: where its picture is (grown as the focus draws it), before its page opens. */
    fun takeOff(item: TvItem, coordinates: LayoutCoordinates?, scale: Float) {
        val route = item.route
        val bounds = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
        if (Motion.reduced || bounds == null || (route !is TvRoute.Collection && route !is TvRoute.Artist)) { trip = null; flying = false; return }
        val px = ((bounds.width + 7f) / 8f).toInt().coerceAtLeast(1) * 8
        trip = Trip(item.key, item.image, item.kind == TvItemKind.Artist, inflate(bounds, scale), px, nav.current, route)
        home = null
        forward = true
        flying = false
    }

    /** The page placed its header: the picture flies to it (once per trip). */
    internal fun landed(routeKey: String, bounds: Rect) {
        val t = trip ?: return
        if (t.to.key != routeKey || t.header != null) return
        progress.floatValue = 0f
        trip = t.copy(header = bounds)
        forward = true
        flying = true
    }

    /** The header's opacity: hidden while the picture is on its way, showing through as it lands. */
    internal fun headerAlpha(routeKey: String): Float {
        val t = trip ?: return 1f
        if (t.to.key != routeKey) return 1f
        if (t.header == null) return 0f
        return if (flying && forward) headerAlpha(progress.floatValue) else 1f
    }

    /** Whether the card [key] is hidden: its page is back and the picture is (about to be) on its way back to it. */
    internal fun hidesCard(key: String): Boolean = trip?.let { it.cardKey == key && it.header != null && nav.current == it.from } == true

    /** The card of the trip, laid out again on its page: the picture flies back to where it is now (the row may have moved). */
    private var home: LayoutCoordinates? = null

    internal fun placed(key: String, coordinates: LayoutCoordinates) { if (trip?.cardKey == key) home = coordinates }

    /** Where the picture lands on its way back: the card as it lies now (grown by its focus), else where it was. */
    internal fun cardNow(fallback: Rect): Rect = home?.takeIf { it.isAttached }?.boundsInRoot()?.let { inflate(it, TvDesign.FOCUS_SCALE) } ?: fallback

    /** The stack changed: Back from the zoomed page to its card's page flies the picture home; anything else ends the trip. */
    internal fun onRoute(previous: TvRoute?, current: TvRoute) {
        val t = trip ?: return
        when {
            previous == t.to && current == t.from && t.header != null && !Motion.reduced -> {
                progress.floatValue = 0f; forward = false; flying = true
            }
            current != t.to -> { trip = null; flying = false }
        }
    }

    internal fun finished() {
        flying = false
        if (!forward) trip = null
    }

    /** A page that did not place its header in time gives the trip up (its header then simply shows). */
    internal fun abandon(routeKey: String) { if (trip?.to?.key == routeKey && trip?.header == null) trip = null }

    companion object {
        const val FLIGHT_MS = 460

        fun inflate(r: Rect, scale: Float): Rect {
            if (scale == 1f) return r
            val dx = r.width * (scale - 1f) / 2f
            val dy = r.height * (scale - 1f) / 2f
            return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
        }

        /** The rectangle [t] of the way from [a] to [b]. Pure. */
        fun lerp(a: Rect, b: Rect, t: Float): Rect = Rect(
            a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)

        /** The flying picture is whole all the way, and dissolves into the header at the very end, once they overlap. */
        fun overlayAlpha(t: Float, forward: Boolean): Float = if (!forward) 1f else ((1f - t) / .04f).coerceIn(0f, 1f)

        /** The header shows through as the picture lands. */
        fun headerAlpha(t: Float): Float = ((t - .94f) / .06f).coerceIn(0f, 1f)
    }
}

val LocalTvZoom = staticCompositionLocalOf<TvZoom?> { null }

/** A card's picture: remembers where it lies (nothing is computed while a row scrolls) and hides while the picture flies back to it. */
fun Modifier.tvZoomSource(key: String, holder: Array<LayoutCoordinates?>): Modifier = composed {
    val zoom = LocalTvZoom.current
    this.onPlaced { holder[0] = it; zoom?.placed(key, it) }.then(if (zoom == null) Modifier else Modifier.graphicsLayer { alpha = if (zoom.hidesCard(key)) 0f else 1f })
}

/** A page's header picture, where a card's picture lands: it says where it lies, then shows through as the picture lands. */
fun Modifier.tvZoomTarget(route: TvRoute): Modifier = composed {
    val zoom = LocalTvZoom.current ?: return@composed this
    LaunchedEffect(route.key) { delay(ABANDON_MS); zoom.abandon(route.key) }
    this.onGloballyPositioned { if (zoom.trip?.let { it.to.key == route.key && it.header == null } == true) zoom.landed(route.key, it.boundsInRoot()) }
        .graphicsLayer { alpha = zoom.headerAlpha(route.key) }
}

private const val ABANDON_MS = 700L
private val FLIGHT_EASING = CubicBezierEasing(.2f, .8f, .2f, 1f)

/** The picture in flight, over every page. */
@Composable
fun TvZoomOverlay(zoom: TvZoom, nav: TvNavigator) {
    val previous = remember { arrayOfNulls<TvRoute>(1) }
    LaunchedEffect(nav.current) { zoom.onRoute(previous[0], nav.current); previous[0] = nav.current }
    val trip = zoom.trip ?: return
    val header = trip.header
    if (!zoom.flying || header == null) return
    val forward = zoom.forward
    LaunchedEffect(trip, forward) {
        animate(0f, 1f, animationSpec = tween(TvZoom.FLIGHT_MS, easing = FLIGHT_EASING)) { v, _ -> zoom.progress.floatValue = v }
        zoom.finished()
    }
    val context = LocalContext.current
    val request = remember(trip.image, trip.px) { trip.image?.let { ImageRequest.Builder(context).data(it).size(trip.px).crossfade(false).build() } }
    val density = LocalDensity.current.density
    val cardRadius = if (trip.round) min(trip.card.width, trip.card.height) / 2f else TvDesign.Radius.card.value * density
    val headerRadius = if (trip.round) 0f else TvDesign.Radius.panel.value * density
    Box(Modifier.fillMaxSize().testTag("tv-zoom")) {
        AsyncImage(request, null, Modifier.flight(zoom, if (forward) trip.card else header, if (forward) header else trip.card,
            if (forward) cardRadius else headerRadius, if (forward) headerRadius else cardRadius, forward), contentScale = ContentScale.Crop)
    }
}

/** Places the picture on its way, its corners (or an artist's circle) opening into the header's. */
private fun Modifier.flight(zoom: TvZoom, from: Rect, to: Rect, fromRadius: Float, toRadius: Float, forward: Boolean): Modifier = this
    .layout { measurable, constraints ->
        val r = TvZoom.lerp(from, if (forward) to else zoom.cardNow(to), zoom.progress.floatValue)
        val placeable = measurable.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
    }
    .graphicsLayer {
        val t = zoom.progress.floatValue
        shape = RoundedCornerShape(fromRadius + (toRadius - fromRadius) * t)
        clip = true
        alpha = TvZoom.overlayAlpha(t, forward)
    }
