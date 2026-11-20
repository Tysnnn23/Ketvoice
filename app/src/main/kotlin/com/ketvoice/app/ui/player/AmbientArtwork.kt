package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import com.ketvoice.app.ui.motion.Motion
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The player's colours for a cover; [mean] is the cover's mean relative luminance. */
data class CoverAmbience(val color: Color = Color(0xFF20212A), val veil: Float = .62f, val accent: Color? = null,
                         val mean: Float = .2f)

/**
 * The colours of [url] for the player (7.4): kept from memory when already read, and the previous title's kept
 * until the new ones are known, so the background glides from one to the other instead of dipping to grey.
 * A cover that cannot be read gives the neutral colours, never the previous title's.
 */
@Composable
internal fun rememberCoverAmbience(url: String?): CoverAmbience {
    val context = LocalContext.current
    val state = remember { mutableStateOf(url?.let(PlayerColors::cached) ?: CoverAmbience()) }
    LaunchedEffect(url) {
        state.value = if (url == null) CoverAmbience()
            else runCatching { PlayerColors.load(context, url) }.getOrNull() ?: CoverAmbience()
    }
    return state.value
}

@Composable
internal fun AmbientArtwork(url: String?, ambience: CoverAmbience, playing: Boolean = false) {
    Box(Modifier.fillMaxSize().background(ambience.color)) {
        // "Fond animé" (5.9.0): the cover's colours flow as in Apple Music — three blurred copies turning
        // slowly. Off, the blurred artwork stands still (7.4: the same soft field, still, instead of a live blur
        // of the full picture: no seam at the edges, a crossfade between titles, and far cheaper).
        val flowing = url != null && com.ketvoice.app.data.AppSettings.meshBackground.value
        if (url != null) ArtworkFlowBackground(url, playing, still = !flowing)
        // The veil follows the cover's lightness, and glides with the colours.
        val veil by animateFloatAsState(PlayerColors.veil(ambience.mean, flowing),
            tween(Motion.duration(PlayerColors.GLIDE_MS)), label = "player-veil")
        val scrim = PlayerColors.scrim(ambience.color)
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(scrim, alpha = veil)
            // A little more at the top (the status bar) and at the bottom (the controls), none in the middle.
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .14f), .42f to Color.Transparent,
                .62f to Color.Transparent, 1f to Color.Black.copy(alpha = .26f)))
            // A faint grain breaks the 8-bit steps of the dark gradients (no banding on large, dim screens).
            drawRect(Dither.brush, alpha = Dither.ALPHA)
        })
    }
}

/** A tiled noise of one or two levels, laid over dim gradients so their 8-bit steps never show as bands. */
internal object Dither {
    const val ALPHA = .022f
    private const val SIDE = 64
    val brush: androidx.compose.ui.graphics.ShaderBrush by lazy {
        val random = java.util.Random(74)
        val pixels = IntArray(SIDE * SIDE) { val v = random.nextInt(256); (255 shl 24) or (v shl 16) or (v shl 8) or v }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, SIDE, SIDE, android.graphics.Bitmap.Config.ARGB_8888)
        androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(bitmap.asImageBitmap(),
            androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated))
    }
}
