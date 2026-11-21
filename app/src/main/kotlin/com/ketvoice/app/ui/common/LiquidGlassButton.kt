package com.ketvoice.app.ui.common

import com.ketvoice.app.ui.motion.growSource
import com.ketvoice.app.ui.adaptive.hoverHighlight
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion

/**
 * Only the navigation layer (top bars, floating buttons) gets the optical material; content stays matte.
 * [plain]: a button of a page's content, or one already on glass (a sheet, a menu, the mini-player), takes
 * a plain fill instead of a second layer of glass (7.1).
 */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    plain: Boolean = LocalOnGlass.current,
    content: @Composable BoxScope.() -> Unit
) {
    val theme = LocalTheme.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var reflection by remember { mutableFloatStateOf(45f) }
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    LaunchedEffect(interactions) {
        interactions.interactions.collect { event ->
            if (event is PressInteraction.Press) haptic(com.ketvoice.app.ui.motion.Haptics.Kind.Press)
            reflection = if (event is PressInteraction.Press) {
                val x = bounds.width / 2f - event.pressPosition.x
                val y = bounds.height / 2f - event.pressPosition.y
                if (x * x + y * y < 1f) 45f else Math.toDegrees(kotlin.math.atan2(y, x).toDouble()).toFloat()
            } else 45f
        }
    }
    val light by animateFloatAsState(if (Motion.reduced) 45f else reflection,
        Motion.pressSpring(pressed), label = "glass-button-light")
    val liquid27 = isLiquid27Glass()
    val pressure by animateFloatAsState(if (pressed && !Motion.reduced) 1f else 0f,
        glassPressSpec(pressed, liquid27), label = "glass-button-pressure")
    val (scaleWidth, scaleHeight) = glassPressScale(pressure, liquid27)
    Box(modifier.onSizeChanged { bounds = it }) {
        if (plain) Box(Modifier.matchParentSize().graphicsLayer { scaleX = scaleWidth; scaleY = scaleHeight }
            .clip(shape).background(plainFill(theme.isDark)))
        else GlassPanel(Modifier.matchParentSize().graphicsLayer {
            scaleX = scaleWidth
            scaleY = scaleHeight
        }, shape, GlassParams.circle(dark = theme.isDark).copy(
            highlightAngle = light, opticalPressure = pressure), enabled = AppSettings.liquidGlass.value) {}
        // The 48dp hit target stays stable. In Liquid 27 the glyph rides the glass as it
        // swells; in the other styles only the surface gives way.
        Box(Modifier.fillMaxSize().clip(shape).graphicsLayer {
            alpha = if (enabled) 1f else .45f
            if (liquid27) { scaleX = scaleWidth; scaleY = scaleHeight }
        }
            .hoverHighlight(interactions, shape)
            // Inside the clip: a touch on the square corners around the circle still reaches what lies beneath.
            .growSource()
            .clickable(interactions, null, enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center, content = content)
    }
}

/**
 * The thumb samples a DIFFERENT layer containing only its track, never itself. As on iOS 26,
 * the thumb turns into a clear lens while the finger is on it: it widens towards the middle,
 * bends the track more, and settles back into its solid form when released.
 */
@Composable
internal fun LiquidGlassSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val track = rememberGlassBackdrop(theme.card)
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val lens by animateFloatAsState(if (pressed && !Motion.reduced) 1f else 0f,
        if (Motion.reduced) androidx.compose.animation.core.snap() else
            androidx.compose.animation.core.spring(dampingRatio = GlassMotion.RELEASE_DAMPING, stiffness = 520f), label = "glass-switch-lens")
    // Ket 27: the switch of iOS 27 — a 63 × 28 track, green when on, under a wide capsule thumb.
    val trackWidth = com.ketvoice.app.theme.Ket27.Metrics.switchWidth
    val trackHeight = com.ketvoice.app.theme.Ket27.Metrics.switchHeight
    val thumbWidth = 38.dp
    val thumbHeight = 24.dp
    val on = androidx.compose.ui.graphics.Color(if (theme.isDark) 0xFF30D158 else 0xFF34C759)
    val x by androidx.compose.animation.core.animateDpAsState(if (checked) trackWidth - thumbWidth - 2.dp else 2.dp,
        if (Motion.reduced) androidx.compose.animation.core.snap() else
            androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 650f), label = "glass-switch")
    val tick = com.ketvoice.app.ui.motion.rememberHaptics()
    Box(modifier.width(trackWidth).height(48.dp)
        .then(if (onCheckedChange != null) Modifier.toggleable(checked, interactions, null, role = Role.Switch,
            onValueChange = { tick(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); onCheckedChange(it) }) else Modifier),
        contentAlignment = Alignment.Center) {
        Box(Modifier.width(trackWidth).height(trackHeight)) {
            BackdropCapture(track, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(
                    Brush.horizontalGradient(if (checked) listOf(on, on)
                    else listOf(theme.onBackground.copy(alpha = .12f), theme.onBackground.copy(alpha = .22f)))))
            }
            CompositionLocalProvider(LocalBackdropLayer provides track) {
                val grow = LiquidSwitchLens.size(lens)
                GlassPanel(Modifier.offset { IntOffset((x - (if (checked) grow.width else 0.dp)).roundToPx(),
                    (2.dp - grow.height / 2).roundToPx()) }.size(thumbWidth + grow.width, thumbHeight + grow.height),
                    RoundedCornerShape(thumbHeight / 2 + grow.height / 2),
                    GlassParams.circle(dark = theme.isDark).copy(blurPx = 2f * (1f - lens),
                        refractionHeightDp = 6f + 4f * lens, refractionAmountDp = 8f + 8f * lens,
                        tintAlpha = LiquidSwitchLens.tint(lens), opticalPressure = lens, lens = lens,
                        shadow = false, innerShadow = false),
                    enabled = AppSettings.liquidGlass.value) {}
            }
        }
    }
}

/** The switch thumb as a lens: how much it grows and how clear it gets, for a press of 0..1. */
internal object LiquidSwitchLens {
    data class Growth(val width: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp)
    fun size(lens: Float): Growth { val l = lens.coerceIn(0f, 1.2f); return Growth((10f * l).dp, (6f * l).dp) }
    /** Solid enough to read as a thumb at rest, nearly clear while touched. */
    fun tint(lens: Float): Float = (.18f - .13f * lens.coerceIn(0f, 1f)).coerceAtLeast(.04f)
}
