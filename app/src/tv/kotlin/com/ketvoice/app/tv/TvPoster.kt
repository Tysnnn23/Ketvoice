package com.ketvoice.app.tv

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.ketvoice.app.ui.motion.Motion

/**
 * A large still picture for a page's background (1.1.0: Home's poster, an artist's photo). It is decoded at the
 * screen's size, only for the picture shown (never kept in the image cache: one poster lives at a time, two for the
 * length of a cross-fade), and a new picture fades in over the previous one once it is decoded. [fill] covers the
 * whole box (an artist's photo, top aligned); otherwise the picture stands at the right edge at the box's height and
 * dissolves towards the left (Home). Light TVs decode two thirds of the size in 16 bits.
 */
@Composable
fun TvPoster(url: String?, modifier: Modifier = Modifier, fill: Boolean = false) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    val light = tvTier() == TvTier.Light
    val scale = if (light) .67f else 1f
    val w = (config.screenWidthDp * density * scale).toInt().coerceAtLeast(1)
    val h = (config.screenHeightDp * density * scale).toInt().coerceAtLeast(1)
    val layers = remember { PosterLayers() }
    LaunchedEffect(url, w, h) {
        if (url == null) { layers.show(null); return@LaunchedEffect }
        val bitmap = loadPoster(context, url, if (fill) w else h, h, light) ?: return@LaunchedEffect
        layers.show(bitmap)
    }
    Box(modifier.testTag("tv-poster")) {
        layers.back?.let { PosterImage(it, fill) { 1f } }
        layers.front?.let { PosterImage(it, fill) { layers.fade.value } }
    }
}

/** The poster shown and the one it fades in over. */
private class PosterLayers {
    var front by mutableStateOf<ImageBitmap?>(null)
    var back by mutableStateOf<ImageBitmap?>(null)
    val fade = Animatable(1f)

    suspend fun show(bitmap: ImageBitmap?) {
        if (bitmap == null) {
            // Nothing to show any more: the poster fades out over what lies under it.
            back = null
            fade.animateTo(0f, tween(Motion.duration(POSTER_FADE_MS)))
            front = null
            fade.snapTo(1f)
            return
        }
        back = front
        front = bitmap
        fade.snapTo(0f)
        fade.animateTo(1f, tween(Motion.duration(POSTER_FADE_MS)))
        back = null
    }
}

private const val POSTER_FADE_MS = 520

private suspend fun loadPoster(context: android.content.Context, url: String, w: Int, h: Int, light: Boolean): ImageBitmap? {
    val request = ImageRequest.Builder(context).data(url).size(w, h).scale(Scale.FILL).precision(Precision.INEXACT)
        .allowRgb565(light).memoryCachePolicy(CachePolicy.DISABLED).crossfade(false).build()
    val drawable = runCatching { coil.Coil.imageLoader(context).execute(request) }.getOrNull()?.drawable
    return (drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
}

@Composable
private fun PosterImage(bitmap: ImageBitmap, fill: Boolean, alpha: () -> Float) {
    val painter = remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.Medium) }
    if (fill) {
        Image(painter, null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }, contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter)
        return
    }
    val aspect = (bitmap.width.toFloat() / bitmap.height).coerceIn(.6f, 1.9f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Image(painter, null, Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true).graphicsLayer {
            this.alpha = alpha()
            compositingStrategy = CompositingStrategy.Offscreen
        }.dissolveLeft(), contentScale = ContentScale.Crop)
    }
}

/** The picture's left part and its bottom fade out (an alpha mask): it melts into the colour field around it. */
private fun Modifier.dissolveLeft(): Modifier = this.drawWithContent {
    drawContent()
    drawRect(Brush.horizontalGradient(0f to Color.Transparent, .5f to Color.Black, 1f to Color.Black), blendMode = BlendMode.DstIn)
    drawRect(Brush.verticalGradient(0f to Color.Black, .62f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
}
