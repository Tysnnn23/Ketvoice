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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Covers, decoded once per address and kept (bounded), with their 7.5 colours. */
object Covers {
    private const val MAX = 400
    private val images = object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX
    }
    private val colors = ConcurrentHashMap<String, CoverColors.Result>()
    private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    fun cached(url: String?): ImageBitmap? = url?.let { synchronized(images) { images[it] } }
    /** Tests and the decoder put images here. */
    fun put(url: String, image: ImageBitmap) { synchronized(images) { images[url] = image } }

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
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { put(url, it) }
            }.getOrNull()
        }
    }

    /** CoverColors.analyze (7.5) over a 48 × 48 reduction of the cover. */
    internal fun colorsOf(url: String?, image: ImageBitmap?): CoverColors.Result? {
        if (url == null || image == null) return null
        colors[url]?.let { return it }
        val side = 48
        val small = ImageBitmap(side, side)
        Canvas(small).drawImageRect(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset.Zero, IntSize(side, side), Paint())
        val pixels = IntArray(side * side)
        small.readPixels(pixels)
        return CoverColors.analyze(pixels, side)?.also { colors[url] = it }
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

