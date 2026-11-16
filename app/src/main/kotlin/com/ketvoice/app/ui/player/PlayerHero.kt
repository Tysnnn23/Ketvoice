package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams

/**
 * Ket 27, the full-screen player of iOS 26/27 on a phone held upright: the artwork, or its Canvas,
 * fills the window from its top edge and runs behind the controls; a gradient keeps the words
 * readable, and the controls rest on one glass platter.
 */
internal object PlayerHero {
    /** Side margin of the glass platter, and its distance from the bottom of the window. */
    const val PLATTER_MARGIN = 10f
    /** Corner radius of the platter: the corners of a phone's screen. */
    const val PLATTER_RADIUS = 38f

    /**
     * Height of a still cover laid full-bleed in a window [widthDp] wide whose usable height is
     * [safeHeightDp]: three fifths of the window, never less than a square (nothing letterboxed)
     * and never more than 1.4 times the width (the sides of a square cover are kept).
     */
    fun coverHeightDp(widthDp: Float, safeHeightDp: Float): Float {
        if (!widthDp.isFinite() || widthDp <= 0f) return 0f
        val wanted = if (safeHeightDp.isFinite() && safeHeightDp > 0f) safeHeightDp * .60f else widthDp
        return wanted.coerceIn(widthDp, widthDp * 1.4f)
    }
}

/**
 * The artwork laid edge to edge. A Canvas fills the window as it is, the gradient alone keeping the
 * controls readable; a still cover dissolves at its foot into the blurred copy of itself behind it.
 */
@Composable
internal fun PlayerHeroFrame(video: Boolean, ambience: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (video) {
        Box(modifier) { content() }
        return
    }
    val dissolve = Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(
                0f to Color.White, .62f to Color.White, .78f to Color.White.copy(alpha = .70f),
                .91f to Color.White.copy(alpha = .22f), 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }
    Box(modifier.then(dissolve)) {
        content()
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent, .70f to Color.Transparent, 1f to ambience.copy(alpha = .55f))))
    }
}

/** The shade over a full-bleed artwork: a little at the top for the handle, more at the foot for the controls. */
@Composable
internal fun PlayerLegibilityGradient(modifier: Modifier = Modifier) {
    Box(modifier.testTag("player-legibility-gradient").background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = .36f), .14f to Color.Black.copy(alpha = .04f), .36f to Color.Transparent,
        .58f to Color.Black.copy(alpha = .24f), .80f to Color.Black.copy(alpha = .50f), 1f to Color.Black.copy(alpha = .66f))))
}

/**
 * The glass the controls rest on. It takes the touches that land between two controls, so that a
 * drag there neither changes the title nor closes the player by surprise.
 */
@Composable
internal fun PlayerGlassPlatter(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PlayerHero.PLATTER_RADIUS.dp)
    GlassPanel(modifier.testTag("player-glass-platter").pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent() }
    }, shape, GlassParams.pill(blur = 46f, dark = true), enabled = AppSettings.liquidGlass.value) {
        // A breath of shade inside the glass: white words stay readable on the brightest covers.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)))
    }
}
