package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CoverAmbience(val color: Color = Color(0xFF20212A), val veil: Float = .62f, val accent: Color? = null)

@Composable
internal fun rememberCoverAmbience(url: String?): CoverAmbience {
    val context = LocalContext.current
    return produceState(CoverAmbience(), url) {
        value = CoverAmbience()
        if (url == null) return@produceState
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build()) as? SuccessResult
            ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val bitmap = result.drawable.toBitmap(48, 48)
            val pixels = mutableListOf<Int>()
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                pixels += bitmap.getPixel(x, y)
            }
            val dominant = AlbumColors.dominant(pixels.toIntArray()) ?: return@withContext CoverAmbience()
            val sampled = Color(dominant)
            CoverAmbience(Color(sampled.red * .55f, sampled.green * .55f, sampled.blue * .55f),
                accent = Color(AlbumColors.readableAccent(dominant)))
        }
    }.value
}

@Composable
internal fun AmbientArtwork(url: String?, ambience: CoverAmbience) {
    Box(Modifier.fillMaxSize().background(ambience.color)) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize().blur(90.dp), contentScale = ContentScale.Crop, alpha = .88f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = ambience.veil),
            ambience.color.copy(alpha = .40f),
            Color.Black.copy(alpha = ambience.veil + .08f)
        ))))
    }
}
