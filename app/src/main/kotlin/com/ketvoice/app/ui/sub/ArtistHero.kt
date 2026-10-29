package com.ketvoice.app.ui.sub

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photo d'artiste façon pochette du lecteur : l'image est affichée ENTIÈREMENT
 * (fit, jamais zoomée au-delà du cadre) sur un prolongement flouté d'elle-même,
 * avec deux bandes de blur progressif — en haut et en bas — d'étendue limitée
 * et de fondu parfaitement lisse.
 *
 * Tout est calculé par un shader AGSL : un seul passage, deux échantillons par
 * pixel (net + flou pré-calculé), masque smoothstep — le flou s'estompe
 * continûment au lieu d'une frontière visible. Les mêmes bandes assombrissent
 * doucement le haut (barre de statut) et le bas (nom de l'artiste).
 * Repli pré-33 : photo recadrée + dégradés (ancien rendu).
 */
private const val PROGRESSIVE_BLUR_AGSL = """
uniform shader uSharp;
uniform shader uBlur;
uniform float2 uHero;
uniform float2 uImg;
uniform float uBandTop;
uniform float uBandBottom;
uniform float uTopDark;
uniform float uBottomDark;

float4 main(float2 coord) {
    // Prolongement flouté (style pochette du lecteur) : l'image couvre tout.
    float2 coverScale = float2(max(uHero.x / uImg.x, uHero.y / uImg.y) * 1.18);
    float2 coverSize = uImg * coverScale;
    float2 coverOrigin = (uHero - coverSize) * 0.5;
    float4 blurred = float4(uBlur.eval((coord - coverOrigin) / coverScale * uImg));

    // Photo nette en « fit » : entière, jamais zoomée au-delà du cadre.
    float2 fitScale = float2(min(uHero.x / uImg.x, uHero.y / uImg.y));
    float2 fitSize = uImg * fitScale;
    float2 fitOrigin = (uHero - fitSize) * float2(0.5, 0.40);
    float2 uv = (coord - fitOrigin) / fitSize;

    float4 color = blurred;
    if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
        float4 sharp = float4(uSharp.eval(uv * uImg));
        // Bandes d'étendue limitée : smoothstep = fondu parfaitement continu.
        float topBand = 1.0 - smoothstep(0.0, uBandTop, coord.y);
        float botBand = 1.0 - smoothstep(0.0, uBandBottom, uHero.y - coord.y);
        float band = max(topBand, botBand);
        // Fondu doux des bords gauche/droit de la photo contre le flou.
        float inset = smoothstep(0.0, 0.045, uv.x) * smoothstep(0.0, 0.045, 1.0 - uv.x);
        band = max(band, 1.0 - inset);
        color = mix(sharp, blurred, band);
    }

    // Assombrissement lissé des mêmes bandes (lisibilité statut + nom).
    float topDark = uTopDark * (1.0 - smoothstep(0.0, uBandTop, coord.y));
    float botDark = uBottomDark * (1.0 - smoothstep(0.0, uBandBottom, uHero.y - coord.y));
    color.rgb *= 1.0 - clamp(topDark + botDark, 0.0, 0.85);

    return half4(color);
}
"""

@Composable
internal fun ProgressiveBlurPhoto(
    photo: String?,
    modifier: Modifier = Modifier,
    bandTop: Dp = 120.dp,
    bandBottom: Dp = 210.dp,
    topDark: Float = 0.30f,
    bottomDark: Float = 0.50f
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val agsl = android.os.Build.VERSION.SDK_INT >= 33
    var bitmaps by remember(photo) { mutableStateOf<Pair<Bitmap, Bitmap>?>(null) }

    LaunchedEffect(photo) {
        bitmaps = null
        if (photo.isNullOrBlank()) return@LaunchedEffect
        val pair = withContext(Dispatchers.IO) {
            runCatching {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(photo).size(1080).allowHardware(false).build()
                ) as? SuccessResult ?: return@runCatching null
                val bmp = result.drawable.toBitmap()
                if (bmp.width < 8 || bmp.height < 8) return@runCatching null
                // Flou pré-calculé : double réduction bilinéaire — l'échantillonnage
                // bilinéaire du shader en fait un flou doux sans coût GPU.
                val w1 = (bmp.width / 5).coerceIn(8, 256)
                val h1 = (bmp.height / 5).coerceIn(8, 256)
                val small = Bitmap.createScaledBitmap(bmp, w1, h1, true)
                val w2 = (w1 / 4).coerceIn(4, 64)
                val h2 = (h1 / 4).coerceIn(4, 64)
                val smaller = Bitmap.createScaledBitmap(small, w2, h2, true)
                bmp to smaller
            }.getOrNull()
        }
        bitmaps = pair
    }

    if (agsl && bitmaps != null) {
        val pair = bitmaps ?: return
        val sharp = pair.first
        val blurred = pair.second
        val shader = remember { RuntimeShader(PROGRESSIVE_BLUR_AGSL) }
        val paint = remember { android.graphics.Paint() }
        val bandTopPx = with(density) { bandTop.toPx() }
        val bandBottomPx = with(density) { bandBottom.toPx() }
        Canvas(modifier) {
            val w = size.width
            val h = size.height
            shader.setFloatUniform("uHero", w, h)
            shader.setFloatUniform("uImg", sharp.width.toFloat(), sharp.height.toFloat())
            shader.setFloatUniform("uBandTop", bandTopPx)
            shader.setFloatUniform("uBandBottom", bandBottomPx)
            shader.setFloatUniform("uTopDark", topDark)
            shader.setFloatUniform("uBottomDark", bottomDark)
            shader.setInputBuffer("uSharp", android.graphics.BitmapShader(sharp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            shader.setInputBuffer("uBlur", android.graphics.BitmapShader(blurred, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
            paint.shader = shader
            drawContext.canvas.nativeCanvas.drawRect(0f, 0f, w, h, paint)
        }
    } else {
        // Repli : photo recadrée + dégradés de lisibilité (rendu historique).
        Box(modifier) {
            if (!photo.isNullOrBlank()) {
                AsyncImage(
                    model = photo, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()
                )
            }
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent,
                    Color.Black.copy(alpha = 0.72f))
            )))
        }
    }
}
