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
    AppSettings.liquidGlass.value && GlassStyles.current == GlassStyles.LIQUID_27

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
    if (liquid27 && !pressed && !Motion.reduced) spring(dampingRatio = com.ketvoice.app.glass.GlassMotion.RELEASE_DAMPING, stiffness = 420f)
    else Motion.pressSpring(pressed)

/** Text and glyph colour that stays readable on the accent colour. */
internal fun onAccent(accent: Color): Color = if (accent.luminance() > .179f) Color.Black else Color.White

/**
 * The fill of a button that is not glass (7.1, « verre que pour la navigation »): a button in a page's
 * content, or one sitting on a glass panel, takes iOS's secondary system fill instead of a second layer
 * of glass. Translucent, so it keeps the hue of what it sits on (a cover's ambient, a card).
 */
internal fun plainFill(dark: Boolean): Color = Color(0xFF787880).copy(alpha = if (dark) .32f else .16f)

/**
 * The two buttons of the system: a **prominent** one for the main action of a page
 * (filled with the accent colour) and a plain one for the others. Both are capsules of
 * the same height, so a pair reads as one control group. They belong to a page's content,
 * so they are solid, not glass (glass stays on the navigation layer, 7.1).
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
    minHeight: Dp = 56.dp,
    interactions: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(999.dp)
    val liquid27 = isLiquid27Glass()
    val pressed by interactions.collectIsPressedAsState()
    val haptic = rememberHaptics()
    LaunchedEffect(interactions) {
        interactions.interactions.collect { event -> if (event is PressInteraction.Press) haptic(Haptics.Kind.Press) }
    }
    val pressure by animateFloatAsState(if (pressed && !Motion.reduced) 1f else 0f,
        glassPressSpec(pressed, liquid27), label = "capsule-pressure")
    val (scaleWidth, scaleHeight) = glassPressScale(pressure, liquid27)
    val ink = when {
        prominent -> onAccent(theme.accent)
        else -> theme.onBackground
    }
    Box(modifier.heightIn(min = minHeight)
        .graphicsLayer {
            scaleX = scaleWidth; scaleY = scaleHeight
            alpha = if (enabled) 1f else .45f
        }
        // A seasonal theme (6.2): the main button wears a thin edge of its colours.
        .then(theme.season?.let { look -> with(look) { Modifier.seasonEdge(shape, prominent) } } ?: Modifier)) {
        if (!prominent) Box(Modifier.matchParentSize().clip(shape).background(plainFill(theme.isDark)))
        // The main action: filled with the accent (a seasonal theme: its gradient, which its dark label reads on).
        else Box(Modifier.matchParentSize().clip(shape)
            .then(if (theme.season != null) Modifier.background(theme.season.fill) else Modifier.background(theme.accent)))
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
    val context = androidx.compose.ui.platform.LocalContext.current
    // A few dozen pixels stretched over the header: soft by construction and cheap to decode.
    val request = remember(context, cover) {
        coil.request.ImageRequest.Builder(context).data(cover).size(40).crossfade(false).build()
    }
    Box(modifier) {
        BackdropCapture(backdrop, Modifier.matchParentSize().bleedHorizontally(bleed).testTag("collection-ambient")) {
            if (showImage) com.ketvoice.app.glass.SoftBlurredImage(request, 56.dp,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                modifier = Modifier.matchParentSize().graphicsLayer {
                    // Larger than the header so the blur never shows a hard edge.
                    scaleX = 1.35f; scaleY = 1.35f
                    alpha = (if (theme.isDark) .62f else .40f) * strength.coerceIn(0f, 1f)
                })
            else Box(Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(theme.accent.copy(alpha = .16f * strength.coerceIn(0f, 1f)), Color.Transparent))))
            // The page colour returns at the bottom: the list continues without a seam.
            // A glow also starts from the page colour, for a header that begins below other content.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to theme.background.copy(alpha = if (glow) 1f else .10f),
                .34f to theme.background.copy(alpha = if (glow) .42f else .40f),
                .58f to theme.background.copy(alpha = .62f),
                1f to theme.background)))
        }
        CompositionLocalProvider(LocalBackdropLayer provides backdrop) { content() }
    }
}

/** One of the icons of a [GlassButtonPad]. */
internal class PadAction(val icon: ImageVector, val label: String, val tag: String? = null, val enabled: Boolean = true,
                         val onClick: () -> Unit)

/**
 * Several actions in one capsule, side by side with nothing between them: one object to look
 * at instead of a row of separate bubbles. Each icon keeps a full touch target. It sits in a
 * page's content, so it is a plain fill, not glass (7.1); [captured] is kept for its callers.
 */
@Composable
internal fun GlassButtonPad(actions: List<PadAction>, modifier: Modifier = Modifier, height: Dp = 48.dp, captured: Boolean = false,
                            tag: String? = null) {
    if (actions.isEmpty()) return
    val theme = LocalTheme.current
    val haptic = rememberHaptics()
    Box(modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier).clip(RoundedCornerShape(999.dp))
        .background(plainFill(theme.isDark))) {
        Row(Modifier.padding(horizontal = if (actions.size > 1) 3.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { action ->
                Box(Modifier.size(width = if (actions.size > 1) 44.dp else height, height = height).clip(RoundedCornerShape(999.dp))
                    .clickable(enabled = action.enabled, role = Role.Button) { haptic(Haptics.Kind.Press); action.onClick() }
                    .then(if (action.tag != null) Modifier.testTag(action.tag) else Modifier), contentAlignment = Alignment.Center) {
                    Icon(action.icon, action.label, tint = theme.onBackground.copy(alpha = if (action.enabled) 1f else .38f),
                        modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
