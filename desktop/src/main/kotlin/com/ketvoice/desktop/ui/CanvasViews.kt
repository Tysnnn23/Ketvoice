package com.ketvoice.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Frame
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.sub.framedPicture
import com.ketvoice.desktop.canvas.CanvasPlayback
import java.io.File

/**
 * How a Canvas is drawn on this screen. It always fills its place, edge to edge (never whole in a box with bars or on a
 * dark field, owner rule of 8.1): placed by the owner's framing when the Studio has one, else at its own shape across
 * the place from the top, the foot cut first ([topAnchoredCrop], the phone's `PlayerArtworkSize.cover`). Its video is
 * decoded at the place's real pixels (Windows' scale included) in a few steps, so a glide or a resize does not restart
 * it at every pixel, and never larger than the file itself; a picture shown larger than its file is drawn with a cubic
 * filter, which keeps its edges clean instead of the soft bilinear blur.
 */
internal object CanvasSizing {
    /** The decode steps, in pixels per side: 4K and 1440p screens get their own, a thumbnail stays small. */
    val STEPS = intArrayOf(360, 540, 720, 960, 1080, 1440, 1920, 2160, 2880, 3840)

    fun step(px: Int): Int = STEPS.firstOrNull { it >= px } ?: STEPS.last()

    /** Upscaling past this needs the cubic filter; below it, plain linear sampling is as sharp and cheaper. */
    const val CUBIC_FROM = 1.02f

    fun cubic(scale: Float): Boolean = scale > CUBIC_FROM

    /** The shape a Canvas is placed at: its video's once known, else its still's, else upright (a Canvas is 9:16). */
    fun shape(reported: Float?, poster: ImageBitmap?): Float = when {
        reported != null && reported.isFinite() && reported > 0f -> reported
        poster != null && poster.width > 0 && poster.height > 0 -> poster.width.toFloat() / poster.height
        else -> UPRIGHT
    }

    const val UPRIGHT = 9f / 16f
}

/** Whether Canvas may move now: the setting [wanted] on and motion not reduced (with it, the still stays). */
internal fun canvasMoves(): Boolean = !Motion.reduced

/** The Canvas to play for [track] in the player (resolved once wanted, again when the owner publishes), or null. */
@Composable
internal fun rememberPlayerCanvas(track: Track, queue: List<Track>, index: Int): String? {
    val wanted = AppSettings.animatedArtwork.value
    val paths by CanvasRepo.paths.collectAsState()
    val revision by MediaOverrides.revision.collectAsState()
    LaunchedEffect(track.id, wanted, revision) {
        if (!wanted) return@LaunchedEffect
        CanvasRepo.request(track)
        (1..2).mapNotNull { queue.getOrNull(index + it) }.forEach { CanvasRepo.request(it, ahead = true) }
    }
    // The phone's rule: the player's own say (« Canvas » on or off in the player) wins, else the album page's.
    if (!wanted || MediaOverrides.canvasHiddenInPlayer(track)) return null
    return paths[track.id]?.takeIf { !it.startsWith("https://") && File(it).isFile }
}

/** The Canvas an album page's header plays: its first title's, else what the page showed last time. */
@Composable
internal fun rememberHeaderCanvas(first: Track?, pageId: String?): String? {
    val wanted = AppSettings.albumHeroCanvas.value
    val paths by CanvasRepo.paths.collectAsState()
    val revision by MediaOverrides.revision.collectAsState()
    LaunchedEffect(first?.id, wanted, revision) { if (wanted && first != null) CanvasRepo.request(first) }
    val own = if (!wanted || first == null || MediaOverrides.canvasDisabled(first)) null else paths[first.id]
    LaunchedEffect(pageId, own) { if (pageId != null && own != null) CanvasRepo.rememberPage(pageId, own) }
    if (!wanted) return null
    return (own ?: pageId?.let { paths[CanvasRepo.pageKey(it)] })?.takeIf { !it.startsWith("https://") && File(it).isFile }
}

/** The still of the Canvas at [path], kept next to its file (null until it is written). */
@Composable
internal fun rememberCanvasPoster(path: String?): ImageBitmap? {
    val revision by CanvasRepo.posters.collectAsState()
    return remember(path, revision) { path?.let(CanvasPosters::get) }
}

/** Decoded Canvas stills, a few at once (each at most 960 px long, a small JPEG: read where it is shown, as on the phone). */
private object CanvasPosters {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 6
    }
    @Synchronized fun get(path: String): ImageBitmap? {
        val file = CanvasRepo.posterOf(path) ?: return null
        val key = file.absolutePath + ":" + file.lastModified()
        cache[key]?.let { return it }
        return runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).use { it.toComposeImageBitmap() } }.getOrNull()
            ?.also { cache[key] = it }
    }
}

/**
 * The video at [path], decoded by FFmpeg at the size it is shown ([CanvasSizing]) and drawn frame by frame, looping;
 * cropped to fill its box. It fades in over what is under it (the still, the cover) once its first frame is there.
 * While [playing] is false it holds its frame (the first one at the start).
 */
@Composable
internal fun CanvasVideo(path: String, playing: Boolean, modifier: Modifier = Modifier, onAspect: (Float) -> Unit = {}) {
    val shown = remember(path) { Animatable(0f) }
    BoxWithConstraints(modifier.clipToBounds().testTag("canvas-video")) {
        val density = LocalDensity.current
        val w = CanvasSizing.step(with(density) { maxWidth.toPx() }.toInt())
        val h = CanvasSizing.step(with(density) { maxHeight.toPx() }.toInt())
        val playback = remember(path, w, h) { CanvasPlayback(File(path), w, h).start() }
        DisposableEffect(playback) { onDispose { playback.close() } }
        playback.playing = playing
        var frame by remember(playback) { mutableStateOf<org.jetbrains.skia.Image?>(null) }
        DisposableEffect(playback) { onDispose { frame?.close(); frame = null } }
        LaunchedEffect(frame != null) {
            if (frame == null || shown.value >= 1f) return@LaunchedEffect
            if (Motion.reduced) shown.snapTo(1f) else shown.animateTo(1f, tween(Motion.duration(420)))
        }
        // Frames are asked for while it plays; paused, only until its frame is shown (then the window can rest).
        LaunchedEffect(playback, playing) {
            var told = false
            while ((playing || frame == null) && !playback.failed) withFrameNanos {
                val buffer = playback.take() ?: return@withFrameNanos
                val info = org.jetbrains.skia.ImageInfo(buffer.width, buffer.height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)
                val next = org.jetbrains.skia.Image.makeRaster(info, buffer.pixels, buffer.width * 4)
                playback.give(buffer)
                // Skia counts references: a frame still drawn elsewhere outlives this handle.
                frame?.close(); frame = next
                if (!told && playback.aspect > 0f) { told = true; onAspect(playback.aspect) }
            }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }) {
            val image = frame ?: return@Canvas
            // Crop to fill: the centre of the frame, as ContentScale.Crop.
            val scale = maxOf(size.width / image.width, size.height / image.height)
            val sw = size.width / scale; val sh = size.height / scale
            val src = org.jetbrains.skia.Rect.makeXYWH((image.width - sw) / 2f, (image.height - sh) / 2f, sw, sh)
            val sampling = if (CanvasSizing.cubic(scale)) org.jetbrains.skia.SamplingMode.MITCHELL else org.jetbrains.skia.SamplingMode.LINEAR
            drawIntoCanvas { it.skiaCanvas.drawImageRect(image, src, org.jetbrains.skia.Rect.makeWH(size.width, size.height), sampling, null, true) }
        }
    }
}

/**
 * Where a Canvas (and its still) goes in a place: the owner's [frame] (the Studio's player or album framing), else at
 * its own [shape] across the place from the top.
 */
internal fun Modifier.canvasPlacement(frame: Frame?, shape: Float): Modifier =
    if (frame != null) fillMaxSize().framedPicture(frame, shape) else fillMaxSize().topAnchoredCrop(shape)

/**
 * A Canvas filling its whole place, with what shows while it loads: the title's [cover] at once, the Canvas's still
 * fading in over it, then the video fading in over the still. [frame] is the owner's framing, if any.
 */
@Composable
internal fun FilledCanvas(path: String, playing: Boolean, cover: String?, modifier: Modifier = Modifier, frame: Frame? = null,
                          onAspect: (Float) -> Unit = {}) {
    var reported by remember(path) { mutableStateOf<Float?>(null) }
    val poster = rememberCanvasPoster(path)
    val shape = CanvasSizing.shape(reported, poster)
    val posterAlpha by animateFloatAsState(if (poster != null) 1f else 0f, tween(Motion.duration(260)), label = "canvas-still")
    BoxWithConstraints(modifier.clipToBounds().testTag("filled-canvas")) {
        val image = rememberCover(cover, pixelsOf(maxOf(maxWidth, maxHeight)))
        if (image != null) Image(image, null, Modifier.fillMaxSize().testTag("canvas-cover"), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        Box(Modifier.canvasPlacement(frame, shape).testTag("canvas-fill")) {
            if (poster != null) Image(poster, null, Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha }.testTag("canvas-poster"),
                contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            CanvasVideo(path, playing && canvasMoves(), Modifier.fillMaxSize(),
                onAspect = { if (it.isFinite() && it > 0f) { reported = it; onAspect(it) } })
        }
    }
}

/**
 * Apple Music's motion artwork on an album page: the Canvas from the top of the page, edge to edge (out of the
 * page's side margins), filling the header (placed by the owner's album framing when there is one) and dissolving
 * into the page's [wash] at its foot, where the words start. It plays while the page is shown; with reduced motion
 * its still stays.
 */
@Composable
internal fun MotionHeader(path: String, wash: Color, cover: String? = null, frame: Frame? = null) {
    // Out of the page's margins: the shell's insets and the page's own (PageEdges), up to the window's top.
    val edges = PageEdges.padding()
    val left = edges.calculateLeftPadding(LayoutDirection.Ltr)
    val right = edges.calculateRightPadding(LayoutDirection.Ltr)
    val top = edges.calculateTopPadding()
    BoxWithConstraints(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val l = left.roundToPx(); val r = right.roundToPx(); val t = top.roundToPx()
        val wide = constraints.maxWidth + l + r
        val p = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
        layout(constraints.maxWidth, (p.height - t).coerceAtLeast(0)) { p.place(-l, -t) }
    }.testTag("motion-header")) {
        val height = (maxWidth.value * .42f).coerceIn(300f, 520f).dp
        Box(Modifier.fillMaxWidth().height(height).background(wash)) {
            FilledCanvas(path, playing = true, cover = cover, modifier = Modifier.fillMaxSize(), frame = frame)
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, wash))))
        }
    }
}
