package com.ketvoice.app.glass

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.ketvoice.app.LiteTuning
import kotlin.math.roundToInt

/**
 * A picture shown blurred behind a page: a cover in a header, the player's background. Android 12+ blurs it live
 * with `Modifier.blur` (RenderEffect), as before. Android 11 has no RenderEffect (the picture would show sharp),
 * and on a modest phone in Ketvoice Lite a live blur of a full-size cover costs memory and every frame: there a
 * [SoftBlur.IMAGE_SIDE]-pixel copy is decoded, blurred once by [SoftBlurTransformation] and enlarged with bilinear
 * filtering. The same soft picture, made once and cached by Coil.
 */
@Composable
internal fun SoftBlurredImage(
    model: Any?,
    blur: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    edge: BlurredEdgeTreatment = BlurredEdgeTreatment.Rectangle,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
) {
    if (!SoftImage.soft()) {
        AsyncImage(model, null, modifier.blur(blur, edge), contentScale = contentScale, alpha = alpha, filterQuality = filterQuality)
        return
    }
    val context = LocalContext.current
    val span = LocalConfiguration.current.screenWidthDp
    val request = remember(context, model, blur, span) { SoftImage.request(context, model, SoftImage.sigmaFraction(blur.value, span)) }
    AsyncImage(request, null, modifier, contentScale = contentScale, alpha = alpha, filterQuality = FilterQuality.Low)
}

internal object SoftImage {
    /**
     * Android 11, or a modest phone (Lite and, since 6.6, the mobile edition): blurred pictures are decoded small
     * and blurred once — the same soft picture as a live blur.
     */
    fun soft(api: Int = GlassCompat.api, modest: Boolean = LiteTuning.modest): Boolean =
        SoftBlur.needed(api) || modest

    /** The blur's sigma as a share of the picture's width: the picture spans about the window's width ([spanDp]). */
    fun sigmaFraction(blurDp: Float, spanDp: Int): Float = .57735f * blurDp / spanDp.coerceAtLeast(1)

    fun request(context: Context, model: Any?, fraction: Float): ImageRequest {
        val builder = (model as? ImageRequest)?.newBuilder(context) ?: ImageRequest.Builder(context).data(model)
        return builder.size(SoftBlur.IMAGE_SIDE).allowHardware(false).crossfade(false)
            .transformations(SoftBlurTransformation(fraction)).build()
    }
}

/** Blurs a small decoded picture once, by a sigma of [fraction] × its longer side. */
internal class SoftBlurTransformation(private val fraction: Float) : Transformation {
    override val cacheKey: String = "ketvoice-soft-blur-${(fraction * 1000).roundToInt()}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        val out = if (input.config == Bitmap.Config.ARGB_8888 && input.isMutable) input
            else input.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        SoftBlur.blur(pixels, w, h, fraction * maxOf(w, h))
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    override fun equals(other: Any?): Boolean = other is SoftBlurTransformation && other.fraction == fraction
    override fun hashCode(): Int = fraction.hashCode()
}
