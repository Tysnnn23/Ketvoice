package com.ketvoice.app.tv

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.motion.Motion

/**
 * The one focus look of Ketvoice TV: the focused element grows a little with a spring and its edge lights up in
 * white (with a soft shadow where the TV has room for it). Everything moves in the layer (draw phase): a card that
 * gains or loses the focus recomposes only itself. Under reduced motion the edge still lights, nothing grows.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvFocusable(
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(TvDesign.Radius.card),
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    scale: Float = TvDesign.FOCUS_SCALE,
    edge: Dp = 3.dp,
    tag: String? = null,
    role: Role = Role.Button,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shadow = tvTier().focusShadow
    val progress = focusProgress(focused)
    this
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .graphicsLayer {
            val p = progress.value
            val s = 1f + (scale - 1f) * p
            scaleX = s; scaleY = s
            if (shadow) { shadowElevation = 18.dp.toPx() * p; this.shape = shape; clip = false }
        }
        .drawWithContent {
            drawContent()
            val p = progress.value
            if (p > .01f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, Color.White.copy(alpha = p), style = Stroke(edge.toPx()))
            }
        }
        // The menu key opens a title's options, as a long press does on a phone.
        .onPreviewKeyEvent { event ->
            if (onLongClick != null && event.type == KeyEventType.KeyUp && event.key == Key.Menu) { onLongClick(); true } else false
        }
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
        .combinedClickable(interactionSource = interaction, indication = null, role = role,
            onLongClick = onLongClick, onClick = onClick)
}

/** 0 → 1 as the element takes the focus, on a soft spring; immediate under reduced motion. */
@Composable
private fun focusProgress(focused: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (focused) 1f else 0f,
    animationSpec = if (Motion.reduced) snap() else spring(dampingRatio = .72f, stiffness = Spring.StiffnessMediumLow),
    label = "tv-focus")

/**
 * A capsule button's focus look: white fill and dark ink when focused (Google TV's pills), glass otherwise. Returns
 * the ink to draw the content with; the fill is drawn behind.
 */
@Composable
fun rememberPillFocus(interaction: MutableInteractionSource): State<Boolean> = interaction.collectIsFocusedAsState()
