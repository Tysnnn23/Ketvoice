package com.ketvoice.app.ui.sub

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class ArtistHeroLayout(
    val height: Dp,
    val photoHeight: Dp,
    val fadeStart: Float,
    val fadeEnd: Float
)

internal fun artistHeroLayout(width: Dp, viewportHeight: Dp, aspectRatio: Float): ArtistHeroLayout {
    val ratio = aspectRatio.coerceAtLeast(.6f)
    val height = if (width > 700.dp) (viewportHeight * .88f).coerceAtLeast(450.dp)
        else if (ratio > 1.2f) minOf(viewportHeight * .9f, width / ratio + 310.dp)
            .coerceAtLeast(420.dp)
        else (viewportHeight * .96f).coerceIn(530.dp, 850.dp)
    val photoHeight = minOf(width / ratio, height * .72f)
    val fadeStart = (photoHeight.value / height.value * .72f).coerceIn(.12f, .55f)
    val fadeEnd = ((photoHeight + 130.dp).value / height.value)
        .coerceIn(fadeStart + .12f, .82f)
    return ArtistHeroLayout(height, photoHeight, fadeStart, fadeEnd)
}
