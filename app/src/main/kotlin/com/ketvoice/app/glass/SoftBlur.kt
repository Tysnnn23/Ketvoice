package com.ketvoice.app.glass

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Glass blur without RenderEffect (Ketvoice Lite on Android 11, owner 2026-11-18: « compatible Android 11 … qui
 * garde les mêmes effets »). RenderEffect, which the backdrop library blurs with, arrived in Android 12; below it
 * the library draws what is behind the glass sharp. Here the backdrop is blurred another way, so the glass still
 * shows the colours behind it, diffused:
 *
 *  - on the GPU (a phone): a pyramid of small offscreen layers. The backdrop is drawn at a quarter of its size,
 *    halved again a few times (each step averages 2×2 pixels through bilinear filtering) and enlarged back step by
 *    step. Every layer only refers to the backdrop's own layer, so the glass follows a list that scrolls behind it
 *    as the real blur does, and no pixel is read back by the processor;
 *  - in software (a view drawn into a bitmap: host renders, screenshots): the backdrop is drawn into a small bitmap
 *    and blurred there with three box passes, the classic approximation of a Gaussian.
 *
 * Android 12+ keeps RenderEffect: nothing changes for mobile (minSdk 31) and Quest.
 */
internal object SoftBlur {
    /** HWUI turns a RenderEffect blur radius into a Gaussian sigma this way (SkBlurMask::ConvertRadiusToSigma). */
    fun sigma(radiusPx: Float): Float = if (radiusPx <= 0f) 0f else .57735f * radiusPx + .5f

    /** Whether this Android has no RenderEffect, so the glass and soft images are blurred here. */
    fun needed(api: Int = GlassCompat.api): Boolean = api < Build.VERSION_CODES.S

    /**
     * How much the GPU pyramid shrinks the backdrop in all (a power of two): averaging down to 1/F and enlarging
     * back gives a blur of about 0.6 F pixels of sigma.
     */
    fun pyramidFactor(sigma: Float): Int = powerOfTwo(sigma / .6f).coerceIn(2, 32)

    /** The first step of the pyramid: the backdrop is drawn directly at a quarter of its size (or less for a soft blur). */
    fun firstStep(factor: Int): Int = minOf(factor, 4)

    /** Shrink factor of the software copy: what remains of the blur at that size is 2 to 4 pixels, cheap to compute. */
    fun softwareFactor(sigma: Float): Int = if (sigma < 4f) 1 else Integer.highestOneBit((sigma / 2.5f).toInt()).coerceIn(1, 16)

    /** Room taken around the glass so its edges blur with what surrounds them (a multiple of [step]). */
    fun margin(sigma: Float, step: Int): Int {
        val m = ceil(2f * sigma).toInt()
        return ((m + step - 1) / step) * step
    }

    /** The nearest power of two to [value] (1 at least). */
    fun powerOfTwo(value: Float): Int {
        if (value <= 1f) return 1
        val low = Integer.highestOneBit(value.toInt().coerceAtLeast(1))
        return if (value - low < low * 2 - value) low else low * 2
    }

    /**
     * Radii of three box blurs whose sequence approximates a Gaussian of [sigma] (W. Wells, "Efficient synthesis of
     * Gaussian filters by cascaded uniform filters", 1986): boxes of widths wl or wl + 2.
     */
    fun boxRadii(sigma: Float, passes: Int = 3): IntArray {
        if (sigma <= 0f) return IntArray(passes)
        val ideal = sqrt(12.0 * sigma * sigma / passes + 1)
        var wl = floor(ideal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - passes * wl * wl - 4.0 * passes * wl - 3.0 * passes) / (-4.0 * wl - 4.0)
        val m = mIdeal.roundToInt()
        return IntArray(passes) { i -> ((if (i < m) wl else wu) - 1) / 2 }
    }

    /**
     * Blurs ARGB [pixels] ([width] × [height]) in place: a Gaussian of [sigma] pixels, edges clamped. [scratch] is
     * working room of at least the same size, which a caller that blurs again and again keeps.
     */
    fun blur(pixels: IntArray, width: Int, height: Int, sigma: Float, scratch: IntArray = IntArray(pixels.size)) {
        if (width <= 0 || height <= 0 || sigma <= 0f) return
        for (radius in boxRadii(sigma)) {
            if (radius <= 0) continue
            boxPass(pixels, scratch, width, height, radius, horizontal = true)
            boxPass(scratch, pixels, width, height, radius, horizontal = false)
        }
    }

    /** One running-sum box pass along rows ([horizontal]) or columns: O(pixels), whatever the radius. */
    private fun boxPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * width else line
            val stride = if (horizontal) 1 else width
            boxLine(src, dst, base, stride, length, radius, window)
        }
    }

    private fun boxLine(src: IntArray, dst: IntArray, base: Int, stride: Int, length: Int, radius: Int, window: Int) {
        fun at(i: Int) = src[base + i.coerceIn(0, length - 1) * stride]
        var a = 0; var r = 0; var g = 0; var b = 0
        for (k in -radius..radius) {
            val p = at(k)
            a += p ushr 24; r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
        }
        for (i in 0 until length) {
            dst[base + i * stride] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val out = at(i - radius); val inn = at(i + radius + 1)
            a += (inn ushr 24) - (out ushr 24)
            r += (inn shr 16 and 255) - (out shr 16 and 255)
            g += (inn shr 8 and 255) - (out shr 8 and 255)
            b += (inn and 255) - (out and 255)
        }
    }

    /**
     * What the library's colour effects do with RenderEffect (vibrancy 1.5× saturation, colorControls, exposure),
     * as one colour matrix applied when the blurred copy is drawn.
     */
    fun colorMatrix(saturation: Float, brightness: Float, contrast: Float, exposure: Float): ColorMatrix {
        val m = ColorMatrix().apply { setToSaturation(saturation) }
        val gain = Math.pow(2.0, exposure / 2.2).toFloat() * contrast
        val offset = (brightness + (1f - contrast) * .5f) * 255f
        val v = m.values
        for (row in 0 until 3) {
            for (col in 0 until 3) v[row * 5 + col] *= gain
            v[row * 5 + 4] += offset
        }
        return m
    }

    /** Soft images ([softImage]): the decoded copy is this small, then blurred and enlarged with bilinear filtering. */
    const val IMAGE_SIDE = 64
}

/** The API level the glass decides on: the device's, or one a host test sets to see Android 11's path. */
internal object GlassCompat {
    @Volatile var api: Int = Build.VERSION.SDK_INT

    /** Host tests only: the phone's pyramid on a software canvas, to count how often it is recorded. */
    @Volatile var pyramidInSoftware: Boolean = false
}

/**
 * Where each captured backdrop lies ([BackdropCapture]), so a glass on Android 11 can tell whether what is behind it
 * is still the same part of the same page, and a menu can take a still copy of it ([FrozenBackdrop]). Read and
 * written while laying out and drawing (main thread).
 */
internal object BackdropPlaces {
    private val captured = java.util.IdentityHashMap<Any, LayoutCoordinates>()

    fun put(backdrop: Any, coordinates: LayoutCoordinates) { captured[backdrop] = coordinates }

    fun forget(backdrop: Any) { captured.remove(backdrop) }

    /** Where [backdrop] is laid out, while it is. */
    fun place(backdrop: Any): LayoutCoordinates? = captured[backdrop]?.takeIf { it.isAttached }

    /**
     * What the backdrop's picture is moved by under a glass laid out at [panel] (the backdrop library computes the
     * same difference); unspecified while either is not laid out or the backdrop was not captured here.
     */
    fun offset(backdrop: Any?, panel: LayoutCoordinates?): Offset {
        val layer = captured[backdrop] ?: return Offset.Unspecified
        if (panel == null || !panel.isAttached || !layer.isAttached) return Offset.Unspecified
        return layer.positionInWindow() - panel.positionInWindow()
    }
}

/** The blurred backdrop of one glass panel; its layers and bitmap are kept from frame to frame. */
internal class BackdropBlur(private val graphics: GraphicsContext) {
    private val layers = ArrayList<GraphicsLayer>()
    private var bitmap: Bitmap? = null
    private var pixels: IntArray? = null
    private var scratch: IntArray? = null
    private val softwareScope = CanvasDrawScope()

    /** Where the glass is laid out, set by its panel ([BackdropPlaces.offset] reads it). */
    var panel: LayoutCoordinates? = null

    /** The backdrop's own drawing, as last handed over: the first layer always calls the newest one. */
    private var backdropDraw: (DrawScope.() -> Unit)? = null

    // What the pyramid was last recorded for. While all of it holds, the layers are drawn again as they are:
    // they refer to the backdrop's layer, so a page scrolling behind still shows through (the graphics chip
    // renders them again only then), and a glass drawn again for another reason costs no new blur.
    private var sizes: List<IntSize> = emptyList()
    private var keptSource: Any? = null
    private var keptPlace = Offset.Unspecified
    private var keptSize = Size.Unspecified
    private var keptSigma = 0f

    /**
     * Draws [drawBackdrop] blurred by a RenderEffect radius of [radiusPx] pixels, through [filter]. [source] is the
     * backdrop it draws and [place] where that backdrop lies under this glass (unspecified: not known, so the
     * blur is recorded at every call, as before 6.7).
     */
    fun DrawScope.drawBlurred(drawBackdrop: DrawScope.() -> Unit, radiusPx: Float, filter: ColorFilter?,
                              source: Any? = null, place: Offset = Offset.Unspecified) {
        val sigma = SoftBlur.sigma(radiusPx)
        if (sigma <= 0f || size.width < 1f || size.height < 1f) return drawBackdrop()
        val ok = runCatching {
            if (GlassCompat.pyramidInSoftware || drawContext.canvas.nativeCanvas.isHardwareAccelerated)
                drawPyramid(drawBackdrop, sigma, filter, source, place)
            else drawSoftware(drawBackdrop, sigma, filter)
        }.isSuccess
        if (!ok) { forgetPyramid(); drawBackdrop() }
    }

    private fun forgetPyramid() { keptSource = null; keptPlace = Offset.Unspecified }

    private fun layer(index: Int): GraphicsLayer {
        while (layers.size <= index) layers += graphics.createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
        return layers[index]
    }

    /** The GPU path: down to 1/F in steps of two, back up the same way, then drawn at full size. */
    private fun DrawScope.drawPyramid(drawBackdrop: DrawScope.() -> Unit, sigma: Float, filter: ColorFilter?,
                                      source: Any?, place: Offset) {
        val factor = SoftBlur.pyramidFactor(sigma)
        val first = SoftBlur.firstStep(factor)
        val margin = SoftBlur.margin(sigma, factor).toFloat()
        backdropDraw = drawBackdrop
        val sameShape = keptSize == size && keptSigma == sigma && sizes.isNotEmpty()
        val kept = sameShape && place.isSpecified && keptPlace == place && keptSource === source
        if (!kept) {
            forgetPyramid()
            if (!sameShape) sizes = pyramidSizes(size.width + 2 * margin, size.height + 2 * margin, first, factor)
            val levels = sizes.size
            recordFirst(layer(0), sizes[0], first, margin)
            for (i in 1 until levels) recordScaled(layer(i), sizes[i], .5f, layer(i - 1))
            // Back up: layer levels + k is level levels - 2 - k, drawn from the layer recorded just before it.
            for (k in 0 until levels - 1) recordScaled(layer(levels + k), sizes[levels - 2 - k], 2f, layer(levels + k - 1))
            keptSize = size; keptSigma = sigma; keptSource = source; keptPlace = place
            recordings++
        }
        // The last layer recorded: the one enlarged back to the first step, or the only one of a soft blur.
        val top = layer(2 * sizes.size - 2)
        top.colorFilter = filter
        translate(-margin, -margin) { scale(first.toFloat(), first.toFloat(), Offset.Zero) { drawLayer(top) } }
    }

    private fun DrawScope.recordFirst(target: GraphicsLayer, size: IntSize, step: Int, margin: Float) {
        target.record(this, layoutDirection, size) {
            scale(1f / step, 1f / step, Offset.Zero) { translate(margin, margin) { backdropDraw?.invoke(this) } }
        }
    }

    private fun DrawScope.recordScaled(target: GraphicsLayer, size: IntSize, by: Float, source: GraphicsLayer) {
        target.colorFilter = null
        target.record(this, layoutDirection, size) { scale(by, by, Offset.Zero) { drawLayer(source) } }
    }

    /** The software path: one small bitmap, blurred on the processor, drawn enlarged with bilinear filtering. */
    private fun DrawScope.drawSoftware(drawBackdrop: DrawScope.() -> Unit, sigma: Float, filter: ColorFilter?) {
        val factor = SoftBlur.softwareFactor(sigma)
        val margin = SoftBlur.margin(sigma, factor).toFloat()
        val w = ceil((size.width + 2 * margin) / factor).toInt().coerceAtLeast(1)
        val h = ceil((size.height + 2 * margin) / factor).toInt().coerceAtLeast(1)
        val target = bitmapOf(w, h)
        val canvas = androidx.compose.ui.graphics.Canvas(target.asImageBitmap())
        softwareScope.draw(this, layoutDirection, canvas, Size(w.toFloat(), h.toFloat())) {
            scale(1f / factor, 1f / factor, Offset.Zero) { translate(margin, margin) { drawBackdrop() } }
        }
        val px = pixels?.takeIf { it.size == w * h } ?: IntArray(w * h).also { pixels = it }
        val room = scratch?.takeIf { it.size == w * h } ?: IntArray(w * h).also { scratch = it }
        target.getPixels(px, 0, w, 0, 0, w, h)
        SoftBlur.blur(px, w, h, sigma / factor, room)
        target.setPixels(px, 0, w, 0, 0, w, h)
        drawImage(target.asImageBitmap(), IntOffset.Zero, IntSize(w, h),
            dstOffset = IntOffset(-margin.roundToInt(), -margin.roundToInt()),
            dstSize = IntSize((w * factor), (h * factor)), colorFilter = filter, filterQuality = FilterQuality.Low)
    }

    private fun bitmapOf(w: Int, h: Int): Bitmap {
        val kept = bitmap
        if (kept != null && kept.width == w && kept.height == h) return kept.apply { eraseColor(0) }
        kept?.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }

    fun release() {
        layers.forEach { graphics.releaseGraphicsLayer(it) }
        layers.clear()
        sizes = emptyList(); forgetPyramid(); backdropDraw = null; panel = null
        bitmap?.recycle(); bitmap = null; pixels = null; scratch = null
    }

    companion object {
        /** How many times a pyramid was recorded since the start (host tests count it). */
        @Volatile var recordings = 0
            private set

        /** Sizes of the pyramid's levels: the first at 1/[first], then halved until 1/[factor]. */
        fun pyramidSizes(width: Float, height: Float, first: Int, factor: Int): List<IntSize> {
            val sizes = ArrayList<IntSize>()
            var step = first
            while (true) {
                sizes += IntSize(ceil(width / step).toInt().coerceAtLeast(1), ceil(height / step).toInt().coerceAtLeast(1))
                if (step >= factor) break
                step *= 2
            }
            return sizes
        }
    }
}

/** One [BackdropBlur] per glass panel, released with it. */
@Composable
internal fun rememberBackdropBlur(): BackdropBlur {
    val graphics = LocalGraphicsContext.current
    val blur = remember(graphics) { BackdropBlur(graphics) }
    DisposableEffect(blur) { onDispose { blur.release() } }
    return blur
}
