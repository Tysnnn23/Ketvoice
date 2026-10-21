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

data class CoverAmbience(val color: Color = Color(0xFF20212A), val veil: Float = .25f)

@Composable
internal fun rememberCoverAmbience(url: String?): CoverAmbience {
    val context = LocalContext.current
    return produceState(CoverAmbience(), url) {
        if (url == null) return@produceState
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build()) as? SuccessResult
            ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val bitmap = result.drawable.toBitmap(48, 48)
            var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0; var light = 0.0
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                val cr = ((pixel shr 16) and 255) / 255.0
                val cg = ((pixel shr 8) and 255) / 255.0
                val cb = (pixel and 255) / 255.0
                val chroma = maxOf(cr, cg, cb) - minOf(cr, cg, cb)
                val w = .15 + chroma
                r += cr * w; g += cg * w; b += cb * w; weight += w
                light += .2126 * cr + .7152 * cg + .0722 * cb
            }
            val meanLight = light / ((bitmap.width + 1) / 2 * ((bitmap.height + 1) / 2))
            CoverAmbience(Color((r / weight).toFloat(), (g / weight).toFloat(), (b / weight).toFloat()),
                (.16 + meanLight * .45).toFloat().coerceIn(.18f, .58f))
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
