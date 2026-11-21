package com.ketvoice.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Legacy artwork scene: the video and still artwork share the same bottom dissolve.
 * [solid] (0..1, read in draw) fades the dissolve away while the artwork becomes the header's cover (8.0).
 * [footFromPx] (8.3): where the title starts, from the top of the artwork; a Canvas that reaches under the title
 * dissolves from there, so the title always sits on its fade (0 = the last tenth only).
 */
@Composable
internal fun PlayerArtworkFrame(video: Boolean, ambience: Color, modifier: Modifier = Modifier,
                                solid: () -> Float = { 0f }, footFromPx: Float = 0f, content: @Composable () -> Unit) {
    val coverFade = Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val s = solid().coerceIn(0f, 1f)
            if (s < .999f) {
                val stops = ArtworkFade.mask(video, ArtworkFade.footStart(footFromPx, size.height))
                drawRect(Brush.verticalGradient(*Array(stops.size) { i ->
                    stops[i].first to Color.White.copy(alpha = stops[i].second + (1f - stops[i].second) * s)
                }), blendMode = BlendMode.DstIn)
            }
        }
    Box(modifier.then(coverFade)) {
        content()
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - solid().coerceIn(0f, 1f) }.drawBehind {
            drawRect(ArtworkFade.shade(video, ambience, ArtworkFade.footStart(footFromPx, size.height)))
        })
    }
}

/** The artwork's dissolve into the player's background, as fractions of the artwork's height. */
internal object ArtworkFade {
    /** Where a Canvas starts to dissolve: at the title when it lies over the artwork, never below 90 % of it. */
    fun footStart(footFromPx: Float, heightPx: Float): Float =
        if (footFromPx <= 0f || heightPx <= 0f) .90f else (footFromPx / heightPx).coerceIn(.6f, .90f)

    fun mask(video: Boolean, start: Float): Array<Pair<Float, Float>> =
        if (video) arrayOf(0f to 1f, start to 1f, 1f to 0f)
        else arrayOf(0f to 1f, .36f to 1f, .52f to .98f, .66f to .82f, .79f to .48f, .90f to .16f, 1f to 0f)

    fun shade(video: Boolean, ambience: Color, start: Float): Brush = if (video) Brush.verticalGradient(
        0f to Color.Black.copy(alpha = .16f), .14f to Color.Transparent, start to Color.Transparent, 1f to ambience.copy(alpha = .6f))
    else Brush.verticalGradient(
        0f to Color.Black.copy(alpha = .16f), .52f to Color.Transparent,
        .83f to ambience.copy(alpha = .45f), 1f to ambience.copy(alpha = .85f))
}
