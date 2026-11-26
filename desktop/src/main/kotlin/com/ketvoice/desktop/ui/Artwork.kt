package com.ketvoice.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.CoverColors
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Covers, decoded once per address and kept with their 7.5 colours. The cache is bounded by pixels, not by count
 * ([BUDGET_BYTES], least recently used first out). Each cover is decoded at the size the window shows it (a step of
 * [CoverUrls.STEPS] at or above the place times the screen's scale, so 1440p and 4K get sharp pictures and a row's
 * thumbnail stays small), fetched at that size from its service ([CoverUrls]), kept on disk ([CoverDisk]), and a
 * failed load falls back to the address as given and is tried again a few times ([rememberCover]).
 */
object Covers {
    /** What a decode keeps on its long side when no size is asked. */
    const val MAX_SIDE = 1280
    const val BUDGET_BYTES = 192L shl 20
    private var bytes = 0L
    /** A decoded cover and the step it was asked at ([Int.MAX_VALUE]: as large as it will ever be). */
    private class Entry(val image: ImageBitmap, val step: Int)
    // Access order; several may have to go at once when a big one comes in, so put trims, not removeEldestEntry.
    private val images = LinkedHashMap<String, Entry>(64, .75f, true)
    private val colors = object : LinkedHashMap<String, CoverColors.Result>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverColors.Result>?) = size > 2000
    }
    /** Sized addresses a service answered « not found » for, and when: the next load goes straight to the fallback. */
    private val missing = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MISSING_FOR_MS = 30 * 60_000L
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }
    /** The network, replaced by the tests: the bytes at an address, or null; [NotFound] for a definite miss. */
    internal var fetchBytes: (String) -> ByteArray? = ::download

    internal class NotFound : Exception()

    /** The cover at [url] as decoded so far, whatever its size. */
    fun cached(url: String?): ImageBitmap? = url?.let { synchronized(images) { images[it]?.image } }

    /** The cover at [url] when it was decoded at least for [px]. */
    fun cachedFor(url: String?, px: Int): ImageBitmap? {
        val step = CoverUrls.step(px)
        return url?.let { synchronized(images) { images[it]?.takeIf { e -> e.step >= step }?.image } }
    }

    /** Tests and the decoder put images here; without a step, the image is as large as it will be. */
    fun put(url: String, image: ImageBitmap, step: Int = Int.MAX_VALUE) {
        synchronized(images) {
            // A smaller decode finishing late never replaces a larger one.
            images[url]?.let { held -> if (held.step > step && held.image !== image) return }
            images.put(url, Entry(image, step))?.let { bytes -= sizeOf(it.image) }
            bytes += sizeOf(image)
            val it = images.entries.iterator()
            while (bytes > BUDGET_BYTES && images.size > 1 && it.hasNext()) {
                val e = it.next()
                if (e.key == url) continue
                val n = sizeOf(e.value.image); bytes -= n; it.remove(); evictedBytes(n)
            }
        }
    }
    /** Decoded bytes held now (at most [BUDGET_BYTES], or one image if a single one is bigger). */
    fun heldBytes(): Long = synchronized(images) { bytes }
    fun count(): Int = synchronized(images) { images.size }
    /** Everything dropped (the window went to the tray): a cover decodes again the next time it is shown. */
    fun trim() { synchronized(images) { images.clear(); bytes = 0 } }
    internal fun forgetMisses() = missing.clear()

    private fun sizeOf(image: ImageBitmap) = image.width.toLong() * image.height * 4

    // An evicted cover keeps its pixels (Skia memory, outside the Java heap) until the Java GC collects its small
    // wrapper, which a quiet heap may not do for minutes: after every 64 MB evicted, a concurrent collection is asked for
    // (-XX:+ExplicitGCInvokesConcurrent in the launcher), on its own thread.
    private var evicted = 0L
    private fun evictedBytes(n: Long) {
        evicted += n
        if (evicted < 64L shl 20) return
        evicted = 0
        Thread({ System.gc() }, "covers-release").apply { isDaemon = true }.start()
    }

    /** The cover at [url] for a place of [px] pixels: from memory, else from disk or its service, or null. */
    suspend fun load(url: String?, px: Int = CoverUrls.DEFAULT_PX): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        val step = CoverUrls.step(px)
        cachedFor(url, step)?.let { return it }
        return withContext(Dispatchers.IO) { fetch(url, step) }
    }

    /** Each candidate in turn: the sized address first, the given one last. */
    private fun fetch(url: String, step: Int): ImageBitmap? {
        val now = System.currentTimeMillis()
        for (candidate in CoverUrls.candidates(url, step)) {
            if (candidate != url && missing[candidate]?.let { now - it < MISSING_FOR_MS } == true) continue
            val data = try { bytesOf(candidate) } catch (_: NotFound) { missing[candidate] = now; null } ?: continue
            val image = runCatching { decode(data, step) }.getOrNull()
            if (image == null) { CoverDisk.drop(candidate); continue }
            put(url, image, step)
            return image
        }
        return null
    }

    private fun bytesOf(address: String): ByteArray? = when {
        address.startsWith("http") -> CoverDisk.read(address) ?: fetchBytes(address)?.also { CoverDisk.write(address, it) }
        address.startsWith("file:") -> runCatching { File(java.net.URI(address)).readBytes() }.getOrNull()
        else -> File(address).takeIf { it.isFile }?.readBytes()
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Ketvoice")
            .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8").build()
        return runCatching { client.newCall(request).execute() }.getOrNull()?.use { r ->
            if (r.code == 404 || r.code == 410 || r.code == 403) throw NotFound()
            if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size > 64 } else null
        }
    }

    /** An encoded image, at most [maxSide] px on its long side (mipmapped, so a big reduction does not alias). */
    internal fun decode(bytes: ByteArray, maxSide: Int = MAX_SIDE): ImageBitmap? {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val long = maxOf(image.width, image.height)
        if (long <= maxSide) return image.use { it.toComposeImageBitmap() }
        val w = (image.width.toLong() * maxSide / long).toInt().coerceAtLeast(1)
        val h = (image.height.toLong() * maxSide / long).toInt().coerceAtLeast(1)
        return image.use { src ->
            org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { surface ->
                surface.canvas.drawImageRect(src, org.jetbrains.skia.Rect.makeWH(src.width.toFloat(), src.height.toFloat()),
                    org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
                    org.jetbrains.skia.FilterMipmap(org.jetbrains.skia.FilterMode.LINEAR, org.jetbrains.skia.MipmapMode.LINEAR), null, true)
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }

    /** CoverColors.analyze (7.5) over a 48 × 48 reduction of the cover. */
    internal fun colorsOf(url: String?, image: ImageBitmap?): CoverColors.Result? {
        if (url == null || image == null) return null
        synchronized(colors) { colors[url] }?.let { return it }
        val side = 48
        val small = ImageBitmap(side, side)
        Canvas(small).drawImageRect(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset.Zero, IntSize(side, side), Paint())
        val pixels = IntArray(side * side)
        small.readPixels(pixels)
        return CoverColors.analyze(pixels, side)?.also { synchronized(colors) { colors[url] = it } }
    }
}

/** How long a cover that could not load waits before each new try (the network back, a slow service). */
internal val COVER_RETRY_MS = longArrayOf(0L, 2_000L, 6_000L, 20_000L, 60_000L)

/**
 * The cover at [url] for a place of [px] pixels (the place's size times the screen's scale): what is decoded already
 * shows at once, at any size, and the sharp one replaces it; a failed load is tried again ([COVER_RETRY_MS]).
 */
@Composable
fun rememberCover(url: String?, px: Int = CoverUrls.DEFAULT_PX): ImageBitmap? {
    val step = CoverUrls.step(px)
    val image by produceState(Covers.cached(url), url, step) {
        value = Covers.cached(url)
        if (url.isNullOrBlank()) return@produceState
        for (wait in COVER_RETRY_MS) {
            if (wait > 0) delay(wait)
            Covers.load(url, step)?.let { value = it; return@produceState }
        }
    }
    return image
}

/** The pixels a place of [side] takes on this screen (Windows' scale included). */
@Composable
internal fun pixelsOf(side: Dp): Int = with(LocalDensity.current) { side.roundToPx() }

/** The page and accent colours of a cover, or null while it loads. */
@Composable
fun rememberCoverColors(url: String?): Pair<Color, Color>? {
    val image = rememberCover(url, 256)
    val result = Covers.colorsOf(url, image) ?: return null
    return Color(result.background) to Color(result.base)
}

/** A cover: rounded, a soft shadow when [shadow] is set, a quiet placeholder while it loads. */
@Composable
fun Artwork(url: String?, size: Dp?, modifier: Modifier = Modifier, radius: Dp = Ket27Desk.Radius.thumb,
            circle: Boolean = false, shadow: Dp = 0.dp) {
    val ket = LocalKet.current
    val shape: Shape = if (circle) CircleShape else RoundedCornerShape(radius)
    val sized = if (size != null) modifier.size(size) else modifier.fillMaxWidth().aspectRatio(1f)
    BoxWithConstraints(
        (if (shadow > 0.dp) sized.shadow(shadow, shape, ambientColor = Color.Black.copy(alpha = .25f), spotColor = Color.Black.copy(alpha = .3f)) else sized)
            .clip(shape).background(ket.card),
        contentAlignment = Alignment.Center,
    ) {
        val image = rememberCover(url, pixelsOf(size ?: maxOf(maxWidth, maxHeight)))
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        else com.ketvoice.app.ui.common.Icon(KetIcons.Music, null, tint = ket.tertiary,
            modifier = Modifier.size(((size ?: 120.dp) * .34f).coerceIn(14.dp, 64.dp)))
    }
}
