package com.ketvoice.app.ui.sub

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.ketvoice.app.data.ArtistSignature
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion

@Composable
internal fun ArtistIdentity(name: String, signature: ArtistSignature?, startAligned: Boolean = false) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    var loaded by remember(signature?.url) { mutableStateOf(false) }
    val blend by animateFloatAsState(if (loaded) 1f else 0f, tween(Motion.duration(300)), label = "artist-signature")
    Column(horizontalAlignment = if (startAligned) Alignment.Start else Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().heightIn(min = if (signature != null) 112.dp else 0.dp),
            contentAlignment = if (startAligned) Alignment.CenterStart else Alignment.Center) {
            val titleSize = when { name.length > 40 -> 30; name.length > 24 -> 36; else -> 44 }
            Text(name, localize = false, color = theme.onBackground, fontSize = titleSize.sp,
                lineHeight = (titleSize + 4).sp, fontWeight = FontWeight.Black, letterSpacing = (-1.0).sp,
                textAlign = if (startAligned) TextAlign.Start else TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - blend })
            if (signature != null) {
                val request = remember(signature) {
                    ImageRequest.Builder(context).data(signature.url).crossfade(false)
                        .setHeader("User-Agent", "Ketvoice/${com.ketvoice.app.BuildConfig.VERSION_NAME} (https://github.com/Tysnnn23/Ketvoice)")
                        .apply {
                            if (signature.monochrome) transformations(SignatureInk)
                        }.build()
                }
                AsyncImage(request, name, contentScale = ContentScale.Fit,
                    // Preserve multi-tone branded logos; a uniform tint can
                    // turn outlined/filled lettering into a solid rectangle.
                    colorFilter = if (signature.monochrome) ColorFilter.tint(theme.onBackground) else null,
                    onState = { loaded = it is AsyncImagePainter.State.Success },
                    modifier = Modifier.fillMaxWidth().height(112.dp).graphicsLayer { alpha = blend })
            }
        }
        if (loaded && signature != null) Text(com.ketvoice.app.data.AppText.t("Visuel d'artiste") + " · ${signature.attribution}", localize = false,
            color = theme.secondary, fontSize = 10.sp,
            modifier = Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(signature.source))) }
            }.padding(top = 3.dp, bottom = 3.dp))
    }
}

/** White paper in a raster asset must not become an opaque white rectangle. */
internal object SignatureInk : Transformation {
    override val cacheKey = "ketvoice-signature-ink-v1"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        val transparent = pixels.count { it ushr 24 < 16 } * 10L >= pixels.size
        pixels.indices.forEach { i ->
            val color = pixels[i]
            val light = ((color shr 16 and 255) * 54 + (color shr 8 and 255) * 183 + (color and 255) * 19) / 256
            // Already-transparent signatures may have white ink. Preserve that
            // mask instead of accidentally erasing every visible stroke.
            val alpha = if (transparent) color ushr 24 else (color ushr 24) * (255 - light) / 255
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }
        output.setPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        return output
    }
}
