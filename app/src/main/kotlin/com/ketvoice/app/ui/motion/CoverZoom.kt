package com.ketvoice.app.ui.motion

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.ui.AppUi
import kotlin.math.roundToInt

/** The page being drawn by the navigation host: a cover flying into it knows where to land. */
internal val LocalNavigationPageKey = staticCompositionLocalOf<String?> { null }

/**
 * Zoom from a cover into its page (6.0.0, as on iOS): a touched album, playlist or artist cover lifts off
 * its card and grows continuously into the artwork at the top of the page it opens, while the page itself
 * grows out of the card underneath; back (button, gesture or predictive back) shrinks it into the card
 * again. The flying cover is drawn once, above the pages, by [CoverZoomLayer]; the card and the page's
 * artwork are hidden while it flies, so there is only ever one cover. Nothing flies with reduced motion.
 */
internal object CoverZoom {
    /** How long after a touch the page's artwork may still take the flight (a page that loads late). */
    const val ARM_WINDOW_MS = 900L
    /** The cover hands over to the page's artwork over the last part of an opening. */
    const val HANDOVER_FROM = .82f

    /** A touched cover, waiting for its page's artwork to be placed. */
    internal data class Armed(val art: ZoomArt, val origin: Rect, val originRadiusPx: Float, val atMs: Long)

    /** What a page remembers of the cover it was opened from, to shrink back into it. */
    internal data class Record(val art: ZoomArt, val origin: Rect, val originRadiusPx: Float)

    internal data class Flight(
        val id: Long,
        val page: String,
        val art: ZoomArt,
        val origin: Rect,
        val originRadiusPx: Float,
        val heroRadiusPx: Float,
        /** Where the page's artwork was when the flight started, used once it has left the screen. */
        val lastHero: Rect,
        val opening: Boolean
    )

    private class Target(var coordinates: LayoutCoordinates?, var radiusPx: Float)

    @Volatile private var armed: Armed? = null
    private val targets = HashMap<String, Target>()
    private val records = LinkedHashMap<String, Record>()
    private var nextId = 0L

    /** The flight in progress, if any; [progress] runs from 0 to 1 along it. */
    val flight = mutableStateOf<Flight?>(null)
    val progress = Animatable(0f)

    /** A cover was touched: the next page artwork placed within [ARM_WINDOW_MS] grows out of it. */
    fun arm(art: ZoomArt, origin: Rect?, originRadiusPx: Float) {
        armed = if (origin == null || origin.isEmpty || Motion.reduced) null
            else Armed(art, origin, originRadiusPx, SystemClock.uptimeMillis())
    }

    /** Taken once, by the artwork of the page that has just opened. */
    internal fun takeArmed(now: Long = SystemClock.uptimeMillis()): Armed? {
        val taken = armed ?: return null
        armed = null
        return taken.takeIf { now - it.atMs in 0..ARM_WINDOW_MS && !Motion.reduced }
    }

    internal fun register(page: String, coordinates: LayoutCoordinates, radiusPx: Float) {
        val known = targets[page]
        if (known == null) targets[page] = Target(coordinates, radiusPx)
        else { known.coordinates = coordinates; known.radiusPx = radiusPx }
    }

    internal fun unregister(page: String, coordinates: LayoutCoordinates?) {
        val known = targets[page] ?: return
        if (coordinates == null || known.coordinates === coordinates) targets.remove(page)
    }

    /** The artwork of [page] has been placed for the first time after a touch on [from]: the cover flies in. */
    internal fun open(page: String, from: Armed, hero: Rect, heroRadiusPx: Float) {
        if (Motion.reduced) return
        records.remove(page)
        records[page] = Record(from.art, from.origin, from.originRadiusPx)
        while (records.size > MAX_RECORDS) records.remove(records.keys.first())
        flight.value = Flight(++nextId, page, from.art, from.origin, from.originRadiusPx, heroRadiusPx, hero, opening = true)
    }

    /**
     * The page [page] is closing (back): its artwork shrinks into the cover it came from, if it opened from one
     * and its artwork is still on screen (a page scrolled far down simply closes, without a flight).
     */
    fun close(page: String, viewportHeight: Float = Float.MAX_VALUE) {
        val record = records.remove(page) ?: return
        if (Motion.reduced) { flight.value = null; return }
        val target = targets[page] ?: return
        val hero = target.coordinates?.let(::unclippedBounds) ?: return
        if (hero.bottom <= 0f || hero.top >= viewportHeight || hero.isEmpty) return
        flight.value = Flight(++nextId, page, record.art, record.origin, record.originRadiusPx, target.radiusPx, hero, opening = false)
    }

    /** Jumps (a tab, the search page) forget every page: nothing flies back to them. */
    fun forget() { records.clear(); armed = null; flight.value = null }

    /** Live bounds of [page]'s artwork, wherever the page's own transition has carried it. */
    internal fun heroBounds(page: String): Rect? = targets[page]?.coordinates?.let(::unclippedBounds)

    private var lastSeen: Pair<Long, Rect>? = null

    /**
     * Where the flying cover's page end is now: the artwork's live bounds, or, once that page has left the
     * composition (the end of a closing), the last place it was seen, so the cover never jumps back.
     */
    internal fun heroFor(flight: Flight): Rect {
        val live = heroBounds(flight.page)
        if (live != null) { lastSeen = flight.id to live; return live }
        return lastSeen?.takeIf { it.first == flight.id }?.second ?: flight.lastHero
    }

    /** Opacity of the page's own artwork: hidden while its cover flies, then handed over at the end. */
    fun heroAlpha(page: String?): Float {
        val current = flight.value ?: return 1f
        if (page == null || current.page != page) return 1f
        return if (current.opening) handover(progress.value) else 0f
    }

    /**
     * Opacity of a card whose cover is in flight (it has lifted off): 0 while it flies. The card is known by
     * its place; a little slack covers the page underneath settling back after a predictive back gesture.
     */
    fun sourceAlpha(bounds: Rect?): Float {
        val current = flight.value ?: return 1f
        return if (bounds != null && sameRect(bounds, current.origin, 3f + current.origin.width * .1f)) 0f else 1f
    }

    /** The page's artwork appears over the last part of an opening, as the flying cover fades out. */
    fun handover(progress: Float): Float = ((progress - HANDOVER_FROM) / (1f - HANDOVER_FROM)).coerceIn(0f, 1f)

    /** The flying cover's rectangle and corner radius at [progress]. */
    internal fun frame(flight: Flight, hero: Rect, progress: Float): Pair<Rect, Float> {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1.05f) else 1f
        val (from, to) = if (flight.opening) flight.origin to hero else hero to flight.origin
        val (fromRadius, toRadius) = if (flight.opening) flight.originRadiusPx to flight.heroRadiusPx
            else flight.heroRadiusPx to flight.originRadiusPx
        fun mix(a: Float, b: Float) = a + (b - a) * p
        val rect = Rect(mix(from.left, to.left), mix(from.top, to.top), mix(from.right, to.right), mix(from.bottom, to.bottom))
        val radius = mix(fromRadius, toRadius).coerceIn(0f, minOf(rect.width, rect.height).coerceAtLeast(0f) / 2f)
        return rect to radius
    }

    internal fun sameRect(a: Rect, b: Rect, tolerance: Float = 1.5f): Boolean =
        kotlin.math.abs(a.left - b.left) <= tolerance && kotlin.math.abs(a.top - b.top) <= tolerance &&
            kotlin.math.abs(a.right - b.right) <= tolerance && kotlin.math.abs(a.bottom - b.bottom) <= tolerance

    internal fun resetForTests() { forget(); targets.clear(); nextId = 0 }

    private const val MAX_RECORDS = 16
}

/** Bounds in the window without the clipping of scrolling parents (a half-visible card keeps its true size). */
internal fun unclippedBounds(coordinates: LayoutCoordinates): Rect? {
    if (!coordinates.isAttached) return null
    val size = coordinates.size
    val a = coordinates.localToWindow(Offset.Zero)
    val b = coordinates.localToWindow(Offset(size.width.toFloat(), size.height.toFloat()))
    return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
}

/** A cover that can lift off its card into the page it opens. */
@Stable
class ZoomSource internal constructor(internal var art: ZoomArt, internal var radiusPx: Float) {
    internal var coordinates: LayoutCoordinates? = null
    /** Where the cover is now, in the window. */
    val bounds: Rect? get() = coordinates?.let(::unclippedBounds)

    /** Opens an album, a mix or another collection page from this cover. */
    fun openCatalog(request: CatalogRequest) { arm(); AppUi.openCatalog(request, bounds) }
    fun openArtist(id: String, name: String) { arm(); AppUi.openArtist(id, name, bounds) }
    fun openPlaylist(id: String) { arm(); AppUi.openPlaylist(id, bounds) }

    /** For a caller that opens its page itself: call just before, and pass [bounds] as the origin. */
    fun arm() = CoverZoom.arm(art, bounds, radiusPx)
}

/** What flies: the card's own artwork, drawn to fill the flying frame. */
typealias ZoomArt = @Composable (Modifier) -> Unit

/** [model] is what the card shows (the flying cover reuses it, already loaded); [round] for an artist's circle. */
@Composable
fun rememberZoomSource(model: Any?, radius: Dp = 12.dp, round: Boolean = false): ZoomSource =
    rememberZoomSource(radius, round) { place -> AsyncImage(model, null, place, contentScale = ContentScale.Crop) }

/** A cover that is more than one picture (a playlist's mosaic): [art] draws it again in flight. */
@Composable
fun rememberZoomSource(radius: Dp = 12.dp, round: Boolean = false, art: ZoomArt): ZoomSource {
    val radiusPx = with(LocalDensity.current) { if (round) 999.dp.toPx() else radius.toPx() }
    val source = remember { ZoomSource(art, radiusPx) }
    source.art = art
    source.radiusPx = radiusPx
    return source
}

/** [zoomSource] as a plain modifier, for call sites that pass a modifier parameter. */
fun zoomSourceOf(source: ZoomSource): Modifier = Modifier.zoomSource(source)

/** Put on the cover itself (not the whole card): its place is noted, and it hides while it flies. */
fun Modifier.zoomSource(source: ZoomSource): Modifier =
    onGloballyPositioned { source.coordinates = it }
        .graphicsLayer { alpha = CoverZoom.sourceAlpha(source.bounds) }

/** The artwork at the top of a page, that a touched cover grows into. */
@Stable
class ZoomTarget internal constructor(internal val page: String?, internal val armed: CoverZoom.Armed?) {
    internal var launched = false
    internal var coordinates: LayoutCoordinates? = null
    internal var radiusPx = 0f
    internal var round = false
    /** True when this artwork receives a flying cover: its own entrance should stay still. */
    val flying: Boolean get() = armed != null
    /** For the artwork's graphicsLayer: hidden while the cover flies to it or from it. */
    fun alpha(): Float = CoverZoom.heroAlpha(page)
}

/** [radius] is the artwork's corner radius ([round] for a circle), so the flying cover's corners turn into it. */
@Composable
fun rememberZoomTarget(radius: Dp = 0.dp, round: Boolean = false): ZoomTarget {
    val page = LocalNavigationPageKey.current
    val target = remember(page) { ZoomTarget(page, if (page == null) null else CoverZoom.takeArmed()) }
    target.radiusPx = with(LocalDensity.current) { radius.toPx() }
    target.round = round
    DisposableEffect(target) {
        onDispose { target.page?.let { CoverZoom.unregister(it, target.coordinates) } }
    }
    return target
}

/** Put on the artwork's box: its place is followed every frame, and the first placement starts the flight. */
fun Modifier.zoomTarget(target: ZoomTarget): Modifier =
    onGloballyPositioned { coordinates ->
        val page = target.page ?: return@onGloballyPositioned
        val radiusPx = if (target.round) coordinates.size.width / 2f else target.radiusPx
        target.coordinates = coordinates
        CoverZoom.register(page, coordinates, radiusPx)
        val armed = target.armed
        if (armed != null && !target.launched) {
            target.launched = true
            unclippedBounds(coordinates)?.let { CoverZoom.open(page, armed, it, radiusPx) }
        }
    }

/**
 * Draws the cover in flight, above the pages and under the floating chrome. It runs the flight's clock
 * (the same critically damped spring as the page) and follows the page's artwork every frame, so the
 * cover lands exactly where the artwork is, even while that page is still growing or shrinking.
 */
@Composable
internal fun CoverZoomLayer(modifier: Modifier = Modifier) {
    val current = CoverZoom.flight.value
    LaunchedEffect(current?.id) {
        val flying = current ?: return@LaunchedEffect
        CoverZoom.progress.snapTo(0f)
        CoverZoom.progress.animateTo(1f, spring(dampingRatio = .92f, stiffness = 430f, visibilityThreshold = .002f))
        if (CoverZoom.flight.value?.id == flying.id) CoverZoom.flight.value = null
    }
    if (current == null) return
    val origin = remember { arrayOf<Offset>(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin[0] = it.localToWindow(Offset.Zero) }.clearAndSetSemantics { }) {
        Box(Modifier.testTag("cover-zoom")
            .layout { measurable, _ ->
                val hero = CoverZoom.heroFor(current)
                val (rect, _) = CoverZoom.frame(current, hero, CoverZoom.progress.value)
                val w = rect.width.roundToInt().coerceAtLeast(1)
                val h = rect.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(placeable.width, placeable.height) {
                    placeable.place((rect.left - origin[0].x).roundToInt(), (rect.top - origin[0].y).roundToInt())
                }
            }
            .graphicsLayer {
                val p = CoverZoom.progress.value
                val hero = CoverZoom.heroFor(current)
                val (_, radius) = CoverZoom.frame(current, hero, p)
                shape = RoundedCornerShape(radius)
                clip = true
                alpha = if (current.opening) 1f - CoverZoom.handover(p) else 1f
                shadowElevation = 18.dp.toPx() * (1f - kotlin.math.abs(p * 2f - 1f)).coerceIn(0f, 1f)
                ambientShadowColor = Color.Black.copy(alpha = .4f)
                spotShadowColor = Color.Black.copy(alpha = .5f)
            }) {
            current.art(Modifier.fillMaxSize())
        }
    }
}
