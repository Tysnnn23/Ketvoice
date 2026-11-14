package com.ketvoice.app.ui.common

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

/** Only interactive controls get the optical material; content stays matte. */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
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
        GlassPanel(Modifier.matchParentSize().graphicsLayer {
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
            .clickable(interactions, null, enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center, content = content)
    }
}

/** The thumb samples a DIFFERENT layer containing only its track, never itself. */
@Composable
internal fun LiquidGlassSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val track = rememberGlassBackdrop(theme.card)
    val x by androidx.compose.animation.core.animateDpAsState(if (checked) 23.dp else 3.dp,
        if (Motion.reduced) androidx.compose.animation.core.snap() else
            androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 650f), label = "glass-switch")
    Box(modifier.width(56.dp).height(48.dp)
        .then(if (onCheckedChange != null) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange) else Modifier),
        contentAlignment = Alignment.Center) {
        Box(Modifier.width(56.dp).height(32.dp)) {
            BackdropCapture(track, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(
                    Brush.horizontalGradient(if (checked) listOf(theme.accent.copy(alpha = .78f), theme.accent)
                    else listOf(theme.onBackground.copy(alpha = .12f), theme.onBackground.copy(alpha = .22f)))))
            }
            CompositionLocalProvider(LocalBackdropLayer provides track) {
                GlassPanel(Modifier.offset { IntOffset(x.roundToPx(), 2.dp.roundToPx()) }.size(30.dp, 28.dp), RoundedCornerShape(14.dp),
                    GlassParams.circle(dark = theme.isDark).copy(blurPx = 2f,
                        refractionHeightDp = 6f, refractionAmountDp = 8f, tintAlpha = .18f,
                        shadow = false, innerShadow = false),
                    enabled = AppSettings.liquidGlass.value) {}
            }
        }
    }
}
