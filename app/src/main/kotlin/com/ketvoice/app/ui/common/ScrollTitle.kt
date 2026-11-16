package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme

/**
 * The large title of a root page as iOS 27 hands it over while the page scrolls (Ket 27): the large
 * one shrinks and clears, a small one takes its place in the bar over the page's faded edge. Every
 * value is read in a layer, so scrolling never recomposes the page for it.
 */
internal object ScrollTitle {
    /** 0 while the large title is whole, 1 once the list has scrolled [handoverPx] (or past its first item). */
    fun collapse(firstIndex: Int, firstOffsetPx: Int, handoverPx: Float): Float = when {
        firstIndex > 0 -> 1f
        handoverPx <= 0f -> 0f
        else -> (firstOffsetPx / handoverPx).coerceIn(0f, 1f)
    }

    /** How small the large title gets just before it hands over: four fifths of its size. */
    const val SHRUNK = .8f
}

/** The large title shrinks toward its leading edge and clears as [collapse] goes from 0 to 1. */
internal fun Modifier.shrinkingTitle(collapse: () -> Float): Modifier = graphicsLayer {
    val c = collapse().coerceIn(0f, 1f)
    val scale = 1f - (1f - ScrollTitle.SHRUNK) * c
    scaleX = scale; scaleY = scale
    transformOrigin = TransformOrigin(0f, 1f)
    alpha = 1f - c
}

/**
 * The small title of the bar, shown as the large one leaves, over the page's top edge fading out
 * as iOS 26 draws its scroll edge. It takes no touch: what is under it stays reachable.
 */
@Composable
internal fun ScrollEdgeTitle(title: String, collapse: () -> Float, modifier: Modifier = Modifier, tag: String = "scroll-edge-title",
                             localize: Boolean = true) {
    val theme = LocalTheme.current
    val shown by remember { derivedStateOf { collapse() > .01f } }
    if (!shown) return
    val whole by remember { derivedStateOf { collapse() > .98f } }
    Box(modifier.fillMaxWidth().graphicsLayer { alpha = collapse() }
        .background(Brush.verticalGradient(0f to theme.background, .58f to theme.background.copy(alpha = .95f),
            .80f to theme.background.copy(alpha = .70f), 1f to theme.background.copy(alpha = 0f)))
        .testTag(tag)) {
        Text(title, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, localize = localize,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 28.dp).height(48.dp)
                .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 112.dp)
                .then(if (whole) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}))
    }
}
