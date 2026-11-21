package com.ketvoice.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.CoverColors
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Covers, decoded once per address and kept with their 7.5 colours. The cache is bounded by pixels, not by count
 * ([BUDGET_BYTES], least recently used first out), and a decode never keeps more than [MAX_SIDE] px on its long side:
 * a 3000 px store cover is 36 MB decoded, 6.5 MB here, which no window of the app shows larger.
 */
object Covers {
    const val MAX_SIDE = 1280
    const val BUDGET_BYTES = 160L shl 20
    private var bytes = 0L
    // Access order; several may have to go at once when a big one comes in, so put trims, not removeEldestEntry.
    private val images = LinkedHashMap<String, ImageBitmap>(64, .75f, true)
    private val colors = object : LinkedHashMap<String, CoverColors.Result>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverColors.Result>?) = size > 2000
    }
    private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    fun cached(url: String?): ImageBitmap? = url?.let { synchronized(images) { images[it] } }
    /** Tests and the decoder put images here. */
    fun put(url: String, image: ImageBitmap) {
        synchronized(images) {
            images.put(url, image)?.let { bytes -= sizeOf(it) }
            bytes += sizeOf(image)
            val it = images.entries.iterator()
            while (bytes > BUDGET_BYTES && images.size > 1 && it.hasNext()) {
                val e = it.next()
                if (e.key == url) continue
                val n = sizeOf(e.value); bytes -= n; it.remove(); evictedBytes(n)
            }
        }
    }
    /** Decoded bytes held now (at most [BUDGET_BYTES], or one image if a single one is bigger). */
    fun heldBytes(): Long = synchronized(images) { bytes }
    fun count(): Int = synchronized(images) { images.size }
    /** Everything dropped (the window went to the tray): a cover decodes again the next time it is shown. */
    fun trim() { synchronized(images) { images.clear(); bytes = 0 } }

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

    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        cached(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = when {
                    url.startsWith("http") -> client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                    url.startsWith("file:") -> File(java.net.URI(url)).readBytes()
                    else -> File(url).takeIf { it.isFile }?.readBytes()
                } ?: return@runCatching null
                decode(bytes)?.also { put(url, it) }
            }.getOrNull()
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

@Composable
fun rememberCover(url: String?): ImageBitmap? {
    val image by produceState(Covers.cached(url), url) { value = Covers.load(url) }
    return image
}

/** The page and accent colours of a cover, or null while it loads. */
@Composable
fun rememberCoverColors(url: String?): Pair<Color, Color>? {
    val image = rememberCover(url)
    val result = Covers.colorsOf(url, image) ?: return null
    return Color(result.background) to Color(result.base)
}

/** A cover: rounded, a soft shadow when [shadow] is set, a quiet placeholder while it loads. */
@Composable
fun Artwork(url: String?, size: Dp?, modifier: Modifier = Modifier, radius: Dp = Ket27Desk.Radius.thumb,
            circle: Boolean = false, shadow: Dp = 0.dp) {
    val ket = LocalKet.current
    val shape: Shape = if (circle) CircleShape else RoundedCornerShape(radius)
    val image = rememberCover(url)
    val sized = if (size != null) modifier.size(size) else modifier.fillMaxWidth().aspectRatio(1f)
    Box(
        (if (shadow > 0.dp) sized.shadow(shadow, shape, ambientColor = Color.Black.copy(alpha = .25f), spotColor = Color.Black.copy(alpha = .3f)) else sized)
            .clip(shape).background(ket.card),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else com.ketvoice.app.ui.common.Icon(KetIcons.Music, null, tint = ket.tertiary,
            modifier = Modifier.size(((size ?: 120.dp) * .34f).coerceIn(14.dp, 64.dp)))
    }
}

