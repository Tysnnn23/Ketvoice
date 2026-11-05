package com.ketvoice.app.theme

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Dynamic accent extracted from album artwork.
 *
 * The whole point of a "premium" music app is that the UI breathes with what is
 * playing. We sample a small (downscaled) bitmap, quantise it, and pick the most
 * vivid colours that ALSO stay legible as an accent — then hand back a tuned
 * [KetTheme] palette. Everything is computed off the main thread and cached by
 * artwork URL so switching tracks never stutters.
 */
object CoverPalette {

    /** In-memory cache: artwork URL -> extracted accent pair (primary, secondary). */
    private val cache = object : LinkedHashMap<String, Pair<Color, Color>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Color, Color>>?) = size > 32
    }

    fun cached(url: String?): Pair<Color, Color>? = url?.let { synchronized(cache) { cache[it] } }

    /**
     * Extract a vivid-but-readable (primary, secondary) accent pair from a bitmap.
     * Returns null when the image carries no usable colour (e.g. pure greyscale)
     * so callers can keep the user's chosen accent.
     */
    suspend fun extract(bitmap: Bitmap): Pair<Color, Color>? = withContext(Dispatchers.Default) {
        // Downscale to ~40px for speed; colour structure survives fine.
        val w = 40
        val h = (bitmap.height.toFloat() / bitmap.width * w).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)

        // Bucket colours into a 4x4x4 grid and track weighted "vividness" per bucket.
        data class Bucket(val r: Int, val g: Int, val b: Int, var weight: Float, var count: Int)
        val buckets = HashMap<Int, Bucket>()
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        for (c in px) {
            val a = (c ushr 24) and 0xFF
            if (a < 128) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            // Skip near-black / near-white, favour mid-lightness with saturation.
            if (lum < 0.10f || lum > 0.95f) continue
            if (sat < 0.12f) continue
            val key = (r / 68 shl 16) or (g / 68 shl 8) or (b / 68)
            val bucket = buckets.getOrPut(key) { Bucket(0, 0, 0, 0f, 0) }
            // Weight = saturation * (1 - distance from pleasant mid luminance) * area
            val midPenalty = 1f - abs(lum - 0.55f)
            val wgt = sat * midPenalty + 0.04f
            bucket.r += r
            bucket.g += g
            bucket.b += b
            bucket.weight += wgt
            bucket.count += 1
        }
        if (buckets.isEmpty()) {
            small.recycle()
            return@withContext null
        }
        // Average colours inside each bucket, sort by total weight.
        val cols = buckets.values
            .filter { it.count > 0 }
            .map { bk ->
                val c = Color(bk.r / bk.count, bk.g / bk.count, bk.b / bk.count)
                bk.weight / bk.count to c
            }
            .sortedByDescending { it.first }
        small.recycle()
        if (cols.isEmpty()) return@withContext null

        val primary = tune(cols[0].second)
        // Secondary: first colour that is a decent distance from primary.
        val secondary = cols.drop(1).firstOrNull { colorDistance(primary, it.second) > 0.22f }?.second
            ?.let { tune(it) } ?: shiftHue(primary, 0.12f)
        primary to secondary
    }

    /** Clamp a raw sampled colour into the legible-accent band (not too dark/bright). */
    private fun tune(raw: Color): Color {
        val lum = raw.luminance()
        return when {
            lum < 0.28f -> raw.lighten(0.34f - lum)
            lum > 0.80f -> raw.darken((lum - 0.80f) * 0.8f)
            else -> raw
        }
    }

    private fun Color.lighten(amount: Float): Color {
        val a = amount.coerceIn(0f, 1f)
        return Color(red + (1f - red) * a, green + (1f - green) * a, blue + (1f - blue) * a, alpha)
    }

    private fun Color.darken(amount: Float): Color {
        val a = amount.coerceIn(0f, 1f)
        return Color(red * (1f - a), green * (1f - a), blue * (1f - a), alpha)
    }

    private fun shiftHue(c: Color, delta: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
        hsv[0] = (hsv[0] + delta * 360f) % 360f
        val rgb = android.graphics.Color.HSVToColor(hsv)
        return Color(rgb)
    }

    private fun colorDistance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    /** Persist a freshly-extracted pair in the URL-keyed cache. */
    fun store(url: String, pair: Pair<Color, Color>) {
        synchronized(cache) { cache[url] = pair }
    }
}

/**
 * Compose-friendly hook: given a Coil [ImageRequest] result or a bitmap, produce
 * the dynamic (primary, secondary) accent pair, cached per URL. Returns null until
 * extraction completes or when the image has no usable colour.
 */
@Composable
fun rememberCoverAccent(url: String?): Pair<Color, Color>? {
    if (url.isNullOrBlank()) return null
    var accent by remember(url) { mutableStateOf(CoverPalette.cached(url)) }
    if (accent != null) return accent
    val ctx = LocalContext.current
    LaunchedEffect(url) {
        val request = ImageRequest.Builder(ctx).data(url).allowHardware(false).size(120).build()
        val loader = coil.Coil.imageLoader(ctx)
        val result = runCatching { loader.execute(request) }.getOrNull()
        val drawable = result?.drawable
        val bmp = (drawable as? BitmapDrawable)?.bitmap
        if (bmp != null) {
            val pair = CoverPalette.extract(bmp)
            if (pair != null) {
                CoverPalette.store(url, pair)
                accent = pair
            }
        }
    }
    return accent
}
