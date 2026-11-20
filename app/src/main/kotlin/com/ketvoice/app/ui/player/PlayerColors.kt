package com.ketvoice.app.ui.player

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * How the full player takes its colours from the cover (7.4). The background colour keeps the cover's hue
 * at a dim, steady level whatever the cover (a white sleeve no longer gives a muddy mid grey, a black one no
 * dead black), and the veil over the blurred artwork follows how light the cover is, so white controls read
 * on a very light cover and a dark cover is not buried under black.
 */
internal object PlayerColors {
    /** Every colour change of the player glides over this time: background, accent, mesh and colour fields. */
    const val GLIDE_MS = 700

    /** Relative luminance band of the background colour. */
    const val AMBIENT_MIN = .014f
    const val AMBIENT_MAX = .075f

    /** The veil over the blurred artwork, for the darkest and the lightest covers. */
    const val VEIL_DARK = .50f
    const val VEIL_LIGHT = .80f

    /** The background colour of a cover whose main colour is [dominant]: its hue, dim. */
    fun ambient(dominant: Int): Color {
        val color = Color(dominant or (255 shl 24))
        val luminance = color.luminance()
        if (luminance < .0005f) return Color(0xFF16161A)
        val target = (luminance * .35f).coerceIn(AMBIENT_MIN, AMBIENT_MAX)
        // Luminance follows the components roughly to the power 2.2: scale them by its root.
        val factor = (target / luminance).toDouble().pow(1 / 2.2).toFloat()
        return Color((color.red * factor).coerceAtMost(1f), (color.green * factor).coerceAtMost(1f),
            (color.blue * factor).coerceAtMost(1f))
    }

    /**
     * The veil for a cover of mean relative luminance [mean]. Flowing colours take a lighter veil (they are
     * the show), except on light covers, where the veil is what keeps the white text readable.
     */
    fun veil(mean: Float, flowing: Boolean): Float {
        val m = mean.coerceIn(0f, 1f)
        val base = VEIL_DARK + (VEIL_LIGHT - VEIL_DARK) * m
        return if (flowing) base - .18f * (1f - m) else base
    }

    /** The colour of the veil: black, warmed a little by the cover's own colour so the scrim never reads grey. */
    fun scrim(ambient: Color): Color = lerp(Color.Black, ambient, .35f)

    /** Mean relative luminance of ARGB [pixels], transparent ones left out; null without any. */
    fun meanLuminance(pixels: IntArray): Float? {
        var sum = 0.0; var n = 0
        for (p in pixels) {
            if (p ushr 24 < 128) continue
            sum += AlbumColors.luminance(p); n++
        }
        return if (n == 0) null else (sum / n).toFloat()
    }

    /** The player's colours for ARGB [pixels] of a cover; null when the cover carries no usable colour. */
    fun ambience(pixels: IntArray): CoverAmbience? {
        val dominant = AlbumColors.dominant(pixels) ?: return null
        val mean = meanLuminance(pixels) ?: return null
        return CoverAmbience(ambient(dominant), veil = veil(mean, flowing = false),
            accent = Color(AlbumColors.readableAccent(dominant)), mean = mean)
    }

    private val cache = object : LinkedHashMap<String, CoverAmbience>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverAmbience>?) = size > 48
    }

    fun cached(url: String): CoverAmbience? = synchronized(cache) { cache[url] }

    /** The colours of the cover at [url], from memory or read once (off the main thread); null when unreadable. */
    suspend fun load(context: Context, url: String): CoverAmbience? {
        cached(url)?.let { return it }
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build())
            as? SuccessResult ?: return null
        val ambience = withContext(Dispatchers.Default) {
            val bitmap = result.drawable.toBitmap(48, 48)
            val pixels = IntArray(24 * 24)
            var i = 0
            for (y in 0 until 48 step 2) for (x in 0 until 48 step 2) pixels[i++] = bitmap.getPixel(x, y)
            ambience(pixels)
        } ?: return null
        synchronized(cache) { cache[url] = ambience }
        return ambience
    }

    /** Reads the colours of a cover before the player needs them, so it opens, or changes title, already in them. */
    suspend fun warm(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { load(context, url) }
        runCatching { ArtworkFlow.load(context, url) }
    }
}
