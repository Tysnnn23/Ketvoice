package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Legacy artwork scene: the video and still artwork share the same bottom dissolve.
 * [solid] (0..1, read in draw) fades the dissolve away while the artwork becomes the header's cover (8.0).
 */
@Composable
internal fun PlayerArtworkFrame(video: Boolean, ambience: Color, modifier: Modifier = Modifier,
                                solid: () -> Float = { 0f }, content: @Composable () -> Unit) {
    // 7.6: a Canvas is shown whole above the title (CanvasFit), so only the foot of its extension dissolves.
    val stops = if (video) arrayOf(0f to 1f, .90f to 1f, 1f to 0f)
        else arrayOf(0f to 1f, .36f to 1f, .52f to .98f, .66f to .82f, .79f to .48f, .90f to .16f, 1f to 0f)
    val coverFade = Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val s = solid().coerceIn(0f, 1f)
            if (s < .999f) drawRect(Brush.verticalGradient(*Array(stops.size) { i ->
                stops[i].first to Color.White.copy(alpha = stops[i].second + (1f - stops[i].second) * s)
            }), blendMode = BlendMode.DstIn)
        }
    Box(modifier.then(coverFade)) {
        content()
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - solid().coerceIn(0f, 1f) }.background(if (video) Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .16f), .14f to Color.Transparent, .90f to Color.Transparent, 1f to ambience.copy(alpha = .6f))
        else Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .16f), .52f to Color.Transparent,
            .83f to ambience.copy(alpha = .45f), 1f to ambience.copy(alpha = .85f))))
    }
}
