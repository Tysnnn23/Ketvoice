package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion

/** How an album or playlist page opens (7.3, owner: « Cover centrée, ombre douce … mais aussi … Canvas qui prend jusqu'au haut de l'écran »). */
internal enum class HeaderStyle {
    /** The cover centred, floating on a soft shadow of its own colour. */
    Cover,
    /** The Canvas fills the top of the screen up to the status bar; the words sit on its fade. */
    Canvas,
    /** The Canvas full-bleed behind, the centred cover floating on it. */
    CoverOnCanvas
}

internal object AlbumHeader {
    /** The settings choices, in the order of `AppSettings.albumHeader` (French, translated at display). */
    val LABELS = listOf("Pochette", "Canvas plein écran", "Pochette et Canvas")

    /** What a page shows for the listener's [choice]: a Canvas style only when a Canvas can play there. */
    fun style(choice: Int, hasCanvas: Boolean): HeaderStyle = when {
        !hasCanvas -> HeaderStyle.Cover
        choice == 1 -> HeaderStyle.Canvas
        choice == 2 -> HeaderStyle.CoverOnCanvas
        else -> HeaderStyle.Cover
    }

    /** Whether the listener wants a Canvas in headers at all: Canvas on pages on, a Canvas style chosen, motion allowed. */
    fun wantsCanvas(pagesCanvas: Boolean, choice: Int, reduced: Boolean): Boolean = pagesCanvas && choice in 1..2 && !reduced
}

/**
 * The Canvas a header may play for [track] (an album's or a playlist's first title), asked for once wanted; null
 * when the listener does not want one, the owner turned it off for that title, or none exists.
 */
@Composable
internal fun rememberHeaderCanvas(track: Track?): String? {
    val wanted = AlbumHeader.wantsCanvas(AppSettings.albumHeroCanvas.value, AppSettings.albumHeader.value, Motion.reduced)
    val paths by CanvasRepo.paths.collectAsState()
    val revision by MediaOverrides.revision.collectAsState()
    LaunchedEffect(track?.id, wanted, revision) { if (wanted && track != null) CanvasRepo.resolve(track) }
    if (!wanted || track == null || MediaOverrides.canvasDisabled(track)) return null
    return paths[track.id]
}

/**
 * A Canvas filling the space behind a header, reaching [extendUp] higher than its box (up to the status bar when
 * the header starts below the top bar). Its top darkens a little for the floating buttons and its foot dissolves
 * into the page colour, so the list continues without a seam.
 */
@Composable
internal fun BoxScope.HeaderCanvasBackdrop(track: Track, canvas: String, extendUp: Dp = 0.dp, fadeFrom: Float = .45f) {
    val theme = LocalTheme.current
    Box(Modifier.matchParentSize().extendUp(extendUp).clipToBounds().testTag("header-canvas")) {
        com.ketvoice.app.ui.player.ArtworkCanvas(track, canvas, playing = true, forceCanvas = true, modifier = Modifier.fillMaxSize())
        // A light dim keeps the words and buttons readable over a bright Canvas.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f)))
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .30f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent,
            fadeFrom to Color.Transparent,
            (fadeFrom + (1f - fadeFrom) * .45f) to theme.background.copy(alpha = .55f),
            (fadeFrom + (1f - fadeFrom) * .78f) to theme.background.copy(alpha = .90f),
            1f to theme.background)).align(Alignment.BottomCenter))
    }
}

/** Draws [by] higher than the box it is given, keeping that box's size for the layout around it. */
internal fun Modifier.extendUp(by: Dp): Modifier = if (by <= 0.dp) this else layout { measurable, constraints ->
    val extra = by.roundToPx()
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height + extra))
    layout(constraints.maxWidth, height) { placeable.place(0, -extra) }
}
