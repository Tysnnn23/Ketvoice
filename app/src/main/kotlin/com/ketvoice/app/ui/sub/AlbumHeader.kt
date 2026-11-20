package com.ketvoice.app.ui.sub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.launch

/**
 * How an album or playlist page opens (7.3, owner: « Cover centrée, ombre douce … mais aussi … Canvas qui prend jusqu'au
 * haut de l'écran »). 7.5 motion75 (owner: « je voulais quelque chose comme Apple Music »): the Canvas is the header
 * itself, edge to edge; « Pochette et Canvas » (a blurred Canvas behind a floating cover) is gone.
 */
internal enum class HeaderStyle {
    /** The cover centred, floating on a soft shadow of its own colour. */
    Cover,
    /** Apple Music's motion artwork: the Canvas from the top of the screen, the words on its fade. */
    Canvas
}

internal object AlbumHeader {
    /** The settings choices, in the order of `AppSettings.albumHeader` (French, translated at display). */
    val LABELS = listOf("Pochette", "Canvas animé")

    /** A stored choice as it is now: « Pochette et Canvas » (2, until 7.4) became the animated Canvas. */
    fun normalize(choice: Int): Int = if (choice == 2) 1 else choice.coerceIn(0, 1)

    /** What a page shows for the listener's [choice]: a Canvas style only when a Canvas can play there. */
    fun style(choice: Int, hasCanvas: Boolean): HeaderStyle =
        if (hasCanvas && normalize(choice) == 1) HeaderStyle.Canvas else HeaderStyle.Cover

    /** Whether the listener wants a Canvas in headers at all: Canvas on pages on, the Canvas style chosen, motion allowed. */
    fun wantsCanvas(pagesCanvas: Boolean, choice: Int, reduced: Boolean): Boolean = pagesCanvas && normalize(choice) == 1 && !reduced

    /** Height of the motion artwork on a phone: about 72 % of the window, as Apple Music, at most 1.8 × its width. */
    fun motionHeight(widthDp: Float, windowHeightDp: Float): Float =
        (windowHeightDp * .72f).coerceIn(minOf(320f, widthDp * 1.8f), widthDp * 1.8f)

    /** How far the artwork moves down while the page scrolls by [scrollPx]: a slight parallax, none with reduced motion. */
    fun parallax(scrollPx: Float, reduced: Boolean): Float = if (reduced || !scrollPx.isFinite()) 0f else scrollPx.coerceAtLeast(0f) * .3f

    /**
     * The bar's title over the motion artwork: nothing until the large title (at [titlePx] below the bar's foot when
     * the page is at its top) reaches the bar, then fully in over [spanPx] of scrolling.
     */
    fun titleCollapse(heroOffsetPx: Int?, titlePx: Float, spanPx: Float, firstVisibleIndex: Int): Float {
        if (heroOffsetPx == null) return if (firstVisibleIndex > 0) 1f else 0f
        return ((-heroOffsetPx - titlePx) / spanPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** The zoom of the artwork pulled [pullPx] past the top of a header [heightPx] tall: a little, at most 1.25. */
    fun stretch(pullPx: Float, heightPx: Float): Float =
        if (pullPx <= 0f || heightPx <= 0f) 1f else (1f + pullPx / heightPx).coerceAtMost(1.25f)
}

/**
 * The Canvas a header may play for [track] (an album's or a playlist's first title), asked for once wanted; null
 * when the listener does not want one, the owner turned it off for that title, or none exists. With [pageId] an
 * album page remembers what it showed, and shows it again at once before its titles have arrived (7.5 motion75).
 */
@Composable
internal fun rememberHeaderCanvas(track: Track?, pageId: String? = null): String? {
    val wanted = AlbumHeader.wantsCanvas(AppSettings.albumHeroCanvas.value, AppSettings.albumHeader.value, Motion.reduced)
    val paths by CanvasRepo.paths.collectAsState()
    val revision by MediaOverrides.revision.collectAsState()
    LaunchedEffect(track?.id, wanted, revision) { if (wanted && track != null) CanvasRepo.resolve(track) }
    val own = if (!wanted || track == null || MediaOverrides.canvasDisabled(track)) null else paths[track.id]
    LaunchedEffect(pageId, own) { if (pageId != null && own != null) CanvasRepo.rememberPage(pageId, own) }
    if (!wanted) return null
    return own ?: if (track == null && pageId != null) paths[CanvasRepo.pageKey(pageId)] else null
}

/** Decoded Canvas stills, a few at once (each at most 960 px long). */
private object CanvasPosters {
    private val cache = android.util.LruCache<String, ImageBitmap>(6)
    fun get(path: String?): ImageBitmap? {
        val file = CanvasRepo.posterOf(path) ?: return null
        cache.get(file.path)?.let { return it }
        return runCatching { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
            ?.also { cache.put(file.path, it) }
    }
}

/** The still of the Canvas at [path], kept next to its file, or null until one is written. */
@Composable
internal fun rememberCanvasPoster(path: String?): ImageBitmap? {
    val revision by CanvasRepo.posters.collectAsState()
    return remember(path, revision) { CanvasPosters.get(path) }
}

/**
 * A Canvas filling its box (cover-fill, or the owner's [frame]): its still shows at once and the video starts over
 * it. Without a still yet (the very first opening), the [cover] waits under the video.
 */
@Composable
internal fun MotionArtwork(path: String, playing: Boolean, modifier: Modifier = Modifier, frame: com.ketvoice.app.data.Frame? = null,
                           cover: String? = null, onAspect: ((Float) -> Unit)? = null) {
    val poster = rememberCanvasPoster(path)
    var aspect by remember(path) { mutableFloatStateOf(0f) }
    val shape = if (aspect > 0f) aspect else poster?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (9f / 16f)
    val placed = if (frame == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().framedPicture(frame, shape)
    Box(modifier.clipToBounds().testTag("motion-artwork")) {
        if (poster != null) Image(poster, null, placed.testTag("canvas-poster"), contentScale = ContentScale.Crop)
        else if (cover != null) coil.compose.AsyncImage(cover, null, placed, contentScale = ContentScale.Crop)
        Box(placed) {
            com.ketvoice.app.ui.player.CanvasVideo(path, playing, cover = null, modifier = Modifier.fillMaxSize(),
                onAspect = { value -> if (value.isFinite() && value > 0f) aspect = value; onAspect?.invoke(value) })
        }
    }
}

/**
 * Pulling a page down past its top zooms its motion artwork a little and lets go with a spring (7.5 motion75, as
 * Apple Music). Off with reduced motion: the page then keeps its usual overscroll.
 */
internal class HeaderPull(private val scope: kotlinx.coroutines.CoroutineScope) : NestedScrollConnection {
    private val amount = Animatable(0f)
    val value: Float get() = amount.value
    var enabled = true

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || amount.value <= 0f || available.y >= 0f) return Offset.Zero
        val used = maxOf(available.y, -amount.value)
        scope.launch { amount.snapTo((amount.value + used).coerceAtLeast(0f)) }
        return Offset(0f, used)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
        scope.launch { amount.snapTo(amount.value + available.y * .5f) }
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (amount.value > 0f) amount.animateTo(0f, spring(dampingRatio = .8f, stiffness = 400f))
        return Velocity.Zero
    }
}

@Composable
internal fun rememberHeaderPull(): HeaderPull {
    val scope = rememberCoroutineScope()
    return remember(scope) { HeaderPull(scope) }.also { it.enabled = !Motion.reduced }
}

/**
 * Apple Music's motion artwork (7.5 motion75): the Canvas spans the width from the top of the screen (reaching
 * [extendUp] above its box when the page starts below the top bar), moves a little slower than the page ([scroll],
 * the page's scroll in px) and zooms a little when pulled ([pull], px). Its foot dissolves into the page colour,
 * where the page's words sit.
 */
@Composable
internal fun MotionHeaderArtwork(path: String, height: Dp, playing: Boolean, scroll: () -> Float, pull: () -> Float,
                                 modifier: Modifier = Modifier, frame: com.ketvoice.app.data.Frame? = null, cover: String? = null,
                                 extendUp: Dp = 0.dp) {
    val theme = LocalTheme.current
    val reduced = Motion.reduced
    Box(modifier.fillMaxWidth().height(height).testTag("header-canvas")) {
        Box(Modifier.matchParentSize().extendUp(extendUp).clipToBounds()) {
            MotionArtwork(path, playing, Modifier.fillMaxSize().graphicsLayer {
                translationY = AlbumHeader.parallax(scroll(), reduced)
                val zoom = AlbumHeader.stretch(pull(), size.height)
                scaleX = zoom; scaleY = zoom
                transformOrigin = TransformOrigin(.5f, 0f)
            }, frame, cover)
            // The floating buttons stay readable over a bright Canvas.
            Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = .26f), Color.Transparent))))
        }
        // A long, smooth dissolve into the page colour: the title, artist and details sit on it.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height * .46f).background(Brush.verticalGradient(
            0f to Color.Transparent,
            .30f to theme.background.copy(alpha = .20f),
            .58f to theme.background.copy(alpha = .62f),
            .82f to theme.background.copy(alpha = .92f),
            1f to theme.background)))
    }
}

/** While a catalogue album's tile is on screen, its Canvas is fetched ahead on Wi-Fi (data/AlbumCanvasPrefetch). */
@Composable
internal fun PrefetchAlbumCanvas(request: com.ketvoice.app.data.CatalogRequest) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // A tile only flicked past is left alone.
    LaunchedEffect(request.id) { kotlinx.coroutines.delay(800); com.ketvoice.app.data.AlbumCanvasPrefetch.album(context, request) }
}

/** While a library album's tile is on screen, its first title's Canvas is fetched ahead on Wi-Fi. */
@Composable
internal fun PrefetchAlbumCanvas(first: Track) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(first.id) { kotlinx.coroutines.delay(800); com.ketvoice.app.data.AlbumCanvasPrefetch.album(context, first) }
}

/** Draws [by] higher than the box it is given, keeping that box's size for the layout around it. */
internal fun Modifier.extendUp(by: Dp): Modifier = if (by <= 0.dp) this else layout { measurable, constraints ->
    val extra = by.roundToPx()
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height + extra))
    layout(constraints.maxWidth, height) { placeable.place(0, -extra) }
}
