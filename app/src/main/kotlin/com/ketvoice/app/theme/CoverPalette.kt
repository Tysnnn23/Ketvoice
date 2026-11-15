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
import androidx.compose.ui.graphics.luminance
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
        data class Bucket(var r: Int, var g: Int, var b: Int, var weight: Float, var count: Int)
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

/**
 * The same accent pair taken from a Canvas: a frame of the video, a little after its start,
 * read off the main thread and remembered per file. Null until it is known, when [path] is
 * null, or when the frame carries no usable colour.
 */
@Composable
fun rememberCanvasAccent(path: String?): Pair<Color, Color>? {
    if (path.isNullOrBlank()) return null
    val key = "canvas:$path"
    var accent by remember(key) { mutableStateOf(CoverPalette.cached(key)) }
    if (accent != null) return accent
    LaunchedEffect(key) {
        val frame = withContext(Dispatchers.IO) {
            val reader = android.media.MediaMetadataRetriever()
            try {
                if (path.startsWith("https://")) reader.setDataSource(path, emptyMap()) else reader.setDataSource(path)
                // Small is enough for colours, and keeps the read light.
                reader.getScaledFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 170)
            } catch (_: Exception) { null } finally { runCatching { reader.release() } }
        }
        if (frame != null) {
            val pair = CoverPalette.extract(frame)
            frame.recycle()
            if (pair != null) { CoverPalette.store(key, pair); accent = pair }
        }
    }
    return accent
}

/**
 * The colour a picture is mostly made of, the way the eye sums it up: the largest group of
 * similar pixels, desaturated ones included. A black-and-white photo with a small gold detail
 * is grey, not gold. Pages are tinted with it; the vivid accent stays for buttons and links.
 */
object CoverTone {
    private val cache = object : LinkedHashMap<String, Color>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 48
    }
    fun cached(key: String): Color? = synchronized(cache) { cache[key] }
    fun store(key: String, color: Color) { synchronized(cache) { cache[key] = color } }

    /** Pixels as 0xRRGGBB (alpha ignored below 128); the average of the most populated group. */
    fun dominant(pixels: IntArray): Color? {
        val counts = HashMap<Int, IntArray>()
        for (c in pixels) {
            if ((c ushr 24) and 0xFF < 128) continue
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val key = (r / 43 shl 16) or (g / 43 shl 8) or (b / 43)
            val bucket = counts.getOrPut(key) { IntArray(4) }
            bucket[0] += r; bucket[1] += g; bucket[2] += b; bucket[3]++
        }
        val top = counts.values.maxByOrNull { it[3] } ?: return null
        return Color(top[0] / top[3], top[1] / top[3], top[2] / top[3])
    }

    suspend fun of(bitmap: Bitmap): Color? = withContext(Dispatchers.Default) {
        val w = 32
        val h = (bitmap.height.toFloat() / bitmap.width * w).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        dominant(px)
    }
}

/** [CoverTone] of a cover, cached per address; null until it is known. */
@Composable
fun rememberCoverTone(url: String?): Color? {
    if (url.isNullOrBlank()) return null
    var tone by remember(url) { mutableStateOf(CoverTone.cached(url)) }
    if (tone != null) return tone
    val ctx = LocalContext.current
    LaunchedEffect(url) {
        val result = runCatching { coil.Coil.imageLoader(ctx).execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).size(96).build()) }.getOrNull()
        val bmp = (result?.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        CoverTone.of(bmp)?.let { CoverTone.store(url, it); tone = it }
    }
    return tone
}

/** [CoverTone] of a Canvas, from a frame a little after its start; null until it is known. */
@Composable
fun rememberCanvasTone(path: String?): Color? {
    if (path.isNullOrBlank()) return null
    val key = "canvas:$path"
    var tone by remember(key) { mutableStateOf(CoverTone.cached(key)) }
    if (tone != null) return tone
    LaunchedEffect(key) {
        val frame = withContext(Dispatchers.IO) {
            val reader = android.media.MediaMetadataRetriever()
            try {
                if (path.startsWith("https://")) reader.setDataSource(path, emptyMap()) else reader.setDataSource(path)
                reader.getScaledFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 170)
            } catch (_: Exception) { null } finally { runCatching { reader.release() } }
        } ?: return@LaunchedEffect
        val found = CoverTone.of(frame)
        frame.recycle()
        found?.let { CoverTone.store(key, it); tone = it }
    }
    return tone
}
