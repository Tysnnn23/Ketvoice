package com.ketvoice.app.ui.common

import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.glass.GlassStyles
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics

/** True when controls should behave like Liquid 27 glass: the style is chosen and glass is on. */
@Composable
internal fun isLiquid27Glass(): Boolean =
    AppSettings.liquidGlass.value && AppSettings.glassStyle.value == GlassStyles.LIQUID_27

/**
 * How a glass control answers a press. Liquid 27 follows the documented behaviour of
 * interactive Liquid Glass: the material swells under the finger, evenly, and settles
 * with a small bounce. The other styles keep giving way.
 * Returns the horizontal and vertical scale for a pressure in 0..1.
 */
internal fun glassPressScale(pressure: Float, liquid27: Boolean): Pair<Float, Float> {
    val p = if (pressure.isFinite()) pressure.coerceIn(0f, 1.2f) else 0f
    return if (liquid27) (1f + p * GLASS_SWELL) to (1f + p * GLASS_SWELL)
    else (1f - p * .035f) to (1f - p * .018f)
}

internal const val GLASS_SWELL = .045f

/** Fast under the finger; on release Liquid 27 overshoots once before it rests. */
internal fun glassPressSpec(pressed: Boolean, liquid27: Boolean): FiniteAnimationSpec<Float> =
    if (liquid27 && !pressed && !Motion.reduced) spring(dampingRatio = .52f, stiffness = 420f)
    else Motion.pressSpring(pressed)

/** Text and glyph colour that stays readable on the accent colour. */
internal fun onAccent(accent: Color): Color = if (accent.luminance() > .179f) Color.Black else Color.White

/**
 * The two buttons of the system: a **prominent** one for the main action of a page
 * (tinted with the accent colour) and a plain glass one for the others. Both are
 * capsules of the same height, so a pair reads as one control group.
 */
@Composable
internal fun GlassCapsuleButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    tag: String? = null,
    minHeight: Dp = 56.dp
) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(999.dp)
    val liquid27 = isLiquid27Glass()
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val haptic = rememberHaptics()
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var reflection by remember { mutableFloatStateOf(45f) }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { event ->
            if (event is PressInteraction.Press) haptic(Haptics.Kind.Press)
            // The rim light turns towards the finger while it is down.
            reflection = if (event is PressInteraction.Press) {
                val x = bounds.width / 2f - event.pressPosition.x
                val y = bounds.height / 2f - event.pressPosition.y
                if (x * x + y * y < 1f) 45f else Math.toDegrees(kotlin.math.atan2(y, x).toDouble()).toFloat()
            } else 45f
        }
    }
    val light by animateFloatAsState(if (Motion.reduced) 45f else reflection, Motion.pressSpring(pressed), label = "capsule-light")
    val pressure by animateFloatAsState(if (pressed && !Motion.reduced) 1f else 0f,
        glassPressSpec(pressed, liquid27), label = "capsule-pressure")
    val (scaleWidth, scaleHeight) = glassPressScale(pressure, liquid27)
    val ink = when {
        prominent -> onAccent(theme.accent)
        else -> theme.onBackground
    }
    Box(modifier.heightIn(min = minHeight).onSizeChanged { bounds = it }
        .graphicsLayer {
            scaleX = scaleWidth; scaleY = scaleHeight
            alpha = if (enabled) 1f else .45f
        }) {
        GlassPanel(Modifier.matchParentSize(), shape, GlassParams.pill(dark = theme.isDark).copy(
            highlightAngle = light, opticalPressure = pressure), enabled = AppSettings.liquidGlass.value) {}
        // A plain glass button keeps a faint body, so it still reads as a button on a flat page.
        if (!prominent) Box(Modifier.matchParentSize().clip(shape).background(theme.onBackground.copy(alpha = .06f))
            .border(.8.dp, Brush.verticalGradient(listOf(theme.onBackground.copy(alpha = .16f),
                theme.onBackground.copy(alpha = .04f))), shape))
        if (prominent) {
            // Tinted glass: the accent fills the body, the rim and the top light stay glass.
            Box(Modifier.matchParentSize().clip(shape)
                .background(theme.accent.copy(alpha = if (liquid27) .88f else 1f))
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (liquid27) .22f else .08f),
                    .5f to Color.Transparent, 1f to Color.Black.copy(alpha = if (liquid27) .10f else .04f)))
                .then(if (liquid27) Modifier.border(.8.dp, Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = .55f), Color.White.copy(alpha = .08f))), shape) else Modifier))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape)
            .clickable(interactions, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Lets a background reach past the horizontal padding of its parent, so it has no visible edge. */
private fun Modifier.bleedHorizontally(extra: Dp): Modifier = if (extra <= 0.dp) this else layout { measurable, constraints ->
    val px = extra.roundToPx()
    val placeable = measurable.measure(constraints.copy(
        minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

/**
 * The colours of a cover spread softly behind a collection header. The layer holds
 * only that image, so the glass buttons placed over it refract the artwork.
 */
@Composable
internal fun CollectionAmbient(cover: String?, modifier: Modifier = Modifier, strength: Float = 1f,
                               glow: Boolean = false, bleed: Dp = 0.dp,
                               content: @Composable BoxScope.() -> Unit) {
    val theme = LocalTheme.current
    val backdrop = rememberGlassBackdrop(theme.background)
    val showImage = cover != null && !AppSettings.highPerformanceMode.value
    Box(modifier) {
        BackdropCapture(backdrop, Modifier.matchParentSize().bleedHorizontally(bleed).testTag("collection-ambient")) {
            if (showImage) AsyncImage(cover, null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer {
                    // Larger than the header so the blur never shows a hard edge.
                    scaleX = 1.35f; scaleY = 1.35f
                    alpha = (if (theme.isDark) .62f else .46f) * strength.coerceIn(0f, 1f)
                }.blur(56.dp))
            else Box(Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(theme.accent.copy(alpha = .16f * strength.coerceIn(0f, 1f)), Color.Transparent))))
            // The page colour returns at the bottom: the list continues without a seam.
            // A glow also starts from the page colour, for a header that begins below other content.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to theme.background.copy(alpha = if (glow) 1f else .10f),
                .34f to theme.background.copy(alpha = if (glow) .42f else .38f),
                .58f to theme.background.copy(alpha = .55f),
                1f to theme.background)))
        }
        CompositionLocalProvider(LocalBackdropLayer provides backdrop) { content() }
    }
}
