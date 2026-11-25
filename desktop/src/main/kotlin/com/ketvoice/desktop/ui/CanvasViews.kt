package com.ketvoice.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.canvas.CanvasPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The shared `CanvasFit` rule (app `ui/player/CanvasFit.kt`, which needs Android's Bitmap and Media3 and is not
 * compiled here): a Canvas whose shape is close to its place fills it (at most 12 % cut off, as Apple Music does);
 * any other is shown whole, as large as the place allows, the rest of the place a soft extension of its own colours.
 */
internal object DeskCanvasFit {
    const val FILL_MAX_HIDDEN = .12f
    const val UPRIGHT = 9f / 16f

    data class Placement(val fill: Boolean, val width: Float, val height: Float)

    fun place(boxWidth: Float, boxHeight: Float, aspect: Float): Placement {
        if (boxWidth <= 0f || boxHeight <= 0f || !aspect.isFinite() || aspect <= 0f) return Placement(true, boxWidth, boxHeight)
        val box = boxWidth / boxHeight
        val hidden = 1f - minOf(box / aspect, aspect / box)
        if (hidden <= FILL_MAX_HIDDEN) return Placement(true, boxWidth, boxHeight)
        return if (aspect < box) Placement(false, boxHeight * aspect, boxHeight) else Placement(false, boxWidth, boxWidth / aspect)
    }

    /** The still shrunk to a few pixels, its shape kept: stretched with clamped edges, it is the extension. */
    fun extension(still: ImageBitmap): org.jetbrains.skia.Image? = runCatching {
        val wide = still.width >= still.height
        val w = if (wide) 10 else 6; val h = if (wide) 6 else 10
        val small = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(w, h, true) }
        org.jetbrains.skia.Canvas(small).use { c ->
            org.jetbrains.skia.Image.makeFromBitmap(still.asSkiaBitmap()).use { src ->
                c.drawImageRect(src, org.jetbrains.skia.Rect.makeWH(still.width.toFloat(), still.height.toFloat()),
                    org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), org.jetbrains.skia.SamplingMode.LINEAR, null, true)
            }
        }
        org.jetbrains.skia.Image.makeFromBitmap(small.apply { setImmutable() })
    }.getOrNull()
}

/** Whether Canvas may move now: the setting [wanted] on and motion not reduced (with it, the still stays). */
internal fun canvasMoves(): Boolean = !Motion.reduced

/** The Canvas to play for [track] in the player (resolved once wanted), or null. */
@Composable
internal fun rememberPlayerCanvas(track: Track, queue: List<Track>, index: Int): String? {
    val wanted = AppSettings.animatedArtwork.value
    val paths by CanvasRepo.paths.collectAsState()
    LaunchedEffect(track.id, wanted) {
        if (!wanted) return@LaunchedEffect
        CanvasRepo.request(track)
        (1..2).mapNotNull { queue.getOrNull(index + it) }.forEach { CanvasRepo.request(it, ahead = true) }
    }
    if (!wanted || MediaOverrides.canvasHiddenInPlayer(track) || MediaOverrides.canvasDisabled(track)) return null
    return paths[track.id]?.takeIf { !it.startsWith("https://") && File(it).isFile }
}

/** The Canvas an album page's header plays: its first title's, else what the page showed last time. */
@Composable
internal fun rememberHeaderCanvas(first: Track?, pageId: String?): String? {
    val wanted = AppSettings.albumHeroCanvas.value
    val paths by CanvasRepo.paths.collectAsState()
    LaunchedEffect(first?.id, wanted) { if (wanted && first != null) CanvasRepo.request(first) }
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
 * The video at [path], decoded by FFmpeg at the size it is shown and drawn frame by frame, looping; cropped to fill
 * its box. While [playing] is false it holds its frame (the first one at the start).
 */
@Composable
internal fun CanvasVideo(path: String, playing: Boolean, modifier: Modifier = Modifier, onAspect: (Float) -> Unit = {}) {
    BoxWithConstraints(modifier.clipToBounds().testTag("canvas-video")) {
        val density = LocalDensity.current
        // Decoded at the shown size, in steps of 64 px so that a resize does not restart it at every pixel.
        val w = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(64).let { (it + 63) / 64 * 64 }
        val h = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(64).let { (it + 63) / 64 * 64 }
        val playback = remember(path, w, h) { CanvasPlayback(File(path), w, h).start() }
        DisposableEffect(playback) { onDispose { playback.close() } }
        playback.playing = playing
        var frame by remember(playback) { mutableStateOf<org.jetbrains.skia.Image?>(null) }
        DisposableEffect(playback) { onDispose { frame?.close(); frame = null } }
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
        Canvas(Modifier.fillMaxSize()) {
            val image = frame ?: return@Canvas
            // Crop to fill: the centre of the frame, as ContentScale.Crop.
            val scale = maxOf(size.width / image.width, size.height / image.height)
            val sw = size.width / scale; val sh = size.height / scale
            val src = org.jetbrains.skia.Rect.makeXYWH((image.width - sw) / 2f, (image.height - sh) / 2f, sw, sh)
            drawIntoCanvas { it.skiaCanvas.drawImageRect(image, src, org.jetbrains.skia.Rect.makeWH(size.width, size.height),
                org.jetbrains.skia.SamplingMode.LINEAR, null, true) }
        }
    }
}

/**
 * A Canvas placed by [DeskCanvasFit] inside [safe]: its still at once, the video over it, and when it does not fill,
 * the extension of its colours around it ([ambient], the cover's colour, until the still exists). [shade] darkens the
 * extension a little so words around the video stay legible.
 */
@Composable
internal fun FittedCanvas(path: String, playing: Boolean, ambient: Color, modifier: Modifier = Modifier,
                          safe: PaddingValues = PaddingValues(0.dp), shade: Float = .06f) {
    val poster = rememberCanvasPoster(path)
    var aspect by remember(path) { mutableFloatStateOf(0f) }
    val shape = if (aspect > 0f) aspect else poster?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: DeskCanvasFit.UPRIGHT
    val extension = remember(poster) { poster?.let(DeskCanvasFit::extension) }
    DisposableEffect(extension) { onDispose { extension?.close() } }
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier.clipToBounds().testTag("fitted-canvas")) {
        val areaW = maxWidth - safe.calculateStartPadding(direction) - safe.calculateEndPadding(direction)
        val areaH = maxHeight - safe.calculateTopPadding() - safe.calculateBottomPadding()
        val place = DeskCanvasFit.place(areaW.value, areaH.value, shape)
        val density = LocalDensity.current
        val video = with(density) {
            val vw = if (place.fill) areaW.toPx() else place.width.dp.toPx()
            val vh = if (place.fill) areaH.toPx() else place.height.dp.toPx()
            Rect(Offset(safe.calculateStartPadding(direction).toPx() + (areaW.toPx() - vw) / 2f,
                safe.calculateTopPadding().toPx() + (areaH.toPx() - vh) / 2f), Size(vw, vh))
        }
        Box(Modifier.fillMaxSize().background(ambient).drawBehind {
            val ext = extension ?: return@drawBehind
            val matrix = org.jetbrains.skia.Matrix33.makeTranslate(video.left, video.top)
                .makeConcat(org.jetbrains.skia.Matrix33.makeScale(video.width / ext.width, video.height / ext.height))
            val shader = ext.makeShader(org.jetbrains.skia.FilterTileMode.CLAMP, org.jetbrains.skia.FilterTileMode.CLAMP,
                org.jetbrains.skia.SamplingMode.LINEAR, matrix)
            org.jetbrains.skia.Paint().use { paint ->
                paint.shader = shader
                drawIntoCanvas { it.skiaCanvas.drawRect(org.jetbrains.skia.Rect.makeWH(size.width, size.height), paint) }
            }
            shader.close()
            drawRect(Color.Black.copy(alpha = shade))
        })
        Box(Modifier.fillMaxSize().padding(safe), contentAlignment = Alignment.Center) {
            Box((if (place.fill) Modifier.fillMaxSize() else Modifier.size(place.width.dp, place.height.dp))
                .testTag(if (place.fill) "canvas-fill" else "canvas-whole")) {
                if (poster != null) Image(poster, null, Modifier.fillMaxSize().testTag("canvas-poster"), contentScale = ContentScale.Crop)
                CanvasVideo(path, playing && canvasMoves(), Modifier.fillMaxSize(), onAspect = { if (it.isFinite() && it > 0f) aspect = it })
            }
        }
    }
}

/**
 * Apple Music's motion artwork on an album page: the Canvas from the top of the page, edge to edge (out of the
 * page's side margins), placed whole by [DeskCanvasFit] and dissolving into the page's [wash] at its foot, where the
 * words start. It plays while the page is shown; with reduced motion its still stays.
 */
@Composable
internal fun MotionHeader(path: String, wash: Color) {
    // Out of the page's margins: the shell's insets and the page's own (PageEdges), up to the window's top.
    val edges = PageEdges.padding()
    val left = edges.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    val right = edges.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    val top = edges.calculateTopPadding()
    BoxWithConstraints(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val l = left.roundToPx(); val r = right.roundToPx(); val t = top.roundToPx()
        val wide = constraints.maxWidth + l + r
        val p = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
        layout(constraints.maxWidth, (p.height - t).coerceAtLeast(0)) { p.place(-l, -t) }
    }.testTag("motion-header")) {
        val height = (maxWidth.value * .42f).coerceIn(300f, 520f).dp
        Box(Modifier.fillMaxWidth().height(height)) {
            FittedCanvas(path, playing = true, ambient = wash, modifier = Modifier.fillMaxSize(), safe = PaddingValues(bottom = 36.dp))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, wash))))
        }
    }
}
