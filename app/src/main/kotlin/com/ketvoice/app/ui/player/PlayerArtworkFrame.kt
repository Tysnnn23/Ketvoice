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

/** Cover fading must never erase the lower half of a fitted Canvas video. */
@Composable
internal fun PlayerArtworkFrame(video: Boolean, ambience: Color, modifier: Modifier = Modifier,
                                content: @Composable () -> Unit) {
    val coverFade = if (video) Modifier else Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(
                0f to Color.White, .36f to Color.White, .52f to Color.White.copy(alpha = .98f),
                .66f to Color.White.copy(alpha = .82f), .79f to Color.White.copy(alpha = .48f),
                .90f to Color.White.copy(alpha = .16f), 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    Box(modifier.then(coverFade)) {
        content()
        if (!video) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .16f), .52f to Color.Transparent,
            .83f to ambience.copy(alpha = .45f), 1f to ambience.copy(alpha = .85f))))
    }
}
