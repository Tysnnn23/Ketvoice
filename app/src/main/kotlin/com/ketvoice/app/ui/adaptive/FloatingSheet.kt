package com.ketvoice.app.ui.adaptive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.launch

/**
 * A sheet on a large window: instead of rising from the bottom edge across the whole width, it floats
 * as a panel — centred for a form, or hanging from where it was asked for (a popover) for a menu — and
 * grows out of that place on a spring. Touching beside it closes it; with reduced motion it simply
 * appears. Same contract as [com.ketvoice.app.ui.common.SpringSheet]: [content] is told the tallest it
 * may be, and an owner that refuses to close (a save is running) sees its sheet come back.
 */
@Composable
internal fun FloatingSheet(onDismiss: () -> Unit, modifier: Modifier, scrim: Float, style: SheetStyle, insets: Modifier,
                           content: @Composable (maxHeight: Dp) -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val reduced = Motion.reduced
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Where it was asked for, in window px; read once, when the sheet opens.
    val anchor = remember { if (style == SheetStyle.Anchored) com.ketvoice.app.ui.AppUi.lastTouch else null }
    LaunchedEffect(Unit) { if (!reduced) appear.animateTo(1f, spring(dampingRatio = .8f, stiffness = 420f)) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            if (!Motion.reduced) appear.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f))
            dismiss()
            kotlinx.coroutines.delay(com.ketvoice.app.ui.common.SheetDetents.REFUSED_AFTER_MS)
            closing = false
            if (Motion.reduced) appear.snapTo(1f) else appear.animateTo(1f, spring(dampingRatio = .8f, stiffness = 420f))
        }
    }
    val closeLabel = com.ketvoice.app.data.AppText.t("Fermer")
    Box(modifier.fillMaxSize()) {
        if (scrim > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = scrim)))
        Box(Modifier.fillMaxSize().semantics { contentDescription = closeLabel }
            .clickable(remember { MutableInteractionSource() }, null) { close() })
        BoxWithConstraints(Modifier.fillMaxSize().then(insets)) {
            val density = LocalDensity.current
            val width = LargeScreen.sheetWidth(maxWidth.value).dp
            val room = minOf(maxHeight * .88f, 820.dp)
            val windowW = constraints.maxWidth.toFloat()
            val windowH = constraints.maxHeight.toFloat()
            val margin = with(density) { 24.dp.toPx() }
            var size by remember { mutableStateOf(IntSize.Zero) }
            val place = LargeScreen.floatingPlacement(anchor?.x, anchor?.y, size.width.toFloat(), size.height.toFloat(),
                windowW, windowH, margin, top = 0f, bottom = 0f)
            val shape = RoundedCornerShape(30.dp)
            Box(Modifier.offset { IntOffset(place.first.toInt(), place.second.toInt()) }
                .width(width).heightIn(max = room)
                .onSizeChanged { size = it }
                .graphicsLayer {
                    val p = appear.value
                    alpha = p.coerceIn(0f, 1f)
                    val scale = .88f + .12f * p
                    scaleX = scale; scaleY = scale
                    transformOrigin = if (anchor != null && size.width > 0 && size.height > 0) TransformOrigin(
                        ((anchor.x - place.first) / size.width).coerceIn(0f, 1f), ((anchor.y - place.second) / size.height).coerceIn(0f, 1f))
                        else TransformOrigin.Center
                }
                .shadow(28.dp, shape, ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f))
                .clip(shape)
                // A touch on the panel itself never closes it.
                .pointerInput(Unit) { detectTapGestures {} }
                .testTag("floating-sheet")) {
                Box(Modifier.testTag("spring-sheet")) {
                    CompositionLocalProvider(com.ketvoice.app.ui.common.LocalSheetMaxHeight provides room) { content(room) }
                }
            }
        }
    }
}
