package com.ketvoice.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.theme.Ket27
import androidx.compose.animation.core.spring
import androidx.compose.ui.composed
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned

/** One timeline owns reveal, drag and dismissal. No duplicate flying cover. */
internal val LocalPlayerDismissOffset = staticCompositionLocalOf<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>?> { null }

/**
 * The same timeline, readable from draw-phase lambdas: 0 = mini-player, 1 = full player.
 * Shared elements (the artwork, the controls) derive their transform from it, so they
 * can never drift from the reveal mask or from an interactive dismissal.
 */
internal val LocalPlayerReveal = staticCompositionLocalOf<() -> Float> { { 1f } }

internal data class CoverMorph(val scale: Float, val translationX: Float, val translationY: Float)

/**
 * Shared-element transform of the player artwork: at progress 0 its centre sits on the
 * mini-player cover and it is as wide as that cover; at 1 it is at rest. The artwork
 * frame is laid out at the top-left of the player, [restWidth] × [restHeight] px.
 */
internal fun sharedCoverMorph(mini: Rect?, restWidth: Float, restHeight: Float, progress: Float): CoverMorph {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    if (mini == null || mini.width <= 0f || mini.height <= 0f || restWidth <= 0f || restHeight <= 0f || p >= 1f) {
        return CoverMorph(1f, 0f, 0f)
    }
    val start = (mini.width / restWidth).coerceIn(.02f, 1f)
    return CoverMorph(
        scale = start + (1f - start) * p,
        translationX = (mini.center.x - restWidth / 2f) * (1f - p),
        translationY = (mini.center.y - restHeight / 2f) * (1f - p))
}

/** How far a held back gesture pulls the player toward the mini-player (at most 22 % of the height). */
internal fun predictiveBackTravel(progress: Float, height: Float): Float =
    (if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f) * height.coerceAtLeast(0f) * .22f

/** Controls rise into place slightly after the artwork: 0 while p < .2, 1 from p = .8. */
internal fun controlsReveal(progress: Float): Float = ((progress - .2f) / .6f).coerceIn(0f, 1f)

internal fun presentationBounds(origin: Rect, width: Float, height: Float, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(origin.left * (1f - p), origin.top * (1f - p),
        origin.right + (width - origin.right) * p, origin.bottom + (height - origin.bottom) * p)
}

/**
 * The one number that places everything between the mini-player (0) and the full player (1): the
 * reveal, held back by how far the player is being dragged down ([drag] px of a [height] px screen).
 */
internal fun presentationProgress(reveal: Float, drag: Float, height: Float, max: Float = 1f): Float {
    if (!reveal.isFinite() || !drag.isFinite() || height <= 0f) return 1f
    return (reveal * (1f - drag / height).coerceIn(0f, 1f)).coerceIn(0f, max)
}

/** Closing the player by dragging it down, and the spring that opens and closes it (Ket 27). */
internal object PlayerDismiss {
    /** Dragged further than this share of the screen, the player closes when released. */
    const val DISTANCE = .16f
    /** A release faster than this (dp per second) decides by its direction alone. */
    const val FLING_DP = 900f
    const val OPEN_DAMPING = .84f
    const val OPEN_STIFFNESS = 230f
    const val CLOSE_STIFFNESS = 300f
    /** How far past their place the shared elements may go while the spring settles. */
    const val OVERSHOOT = 1.06f

    @Volatile private var thrown = 0f
    /** The speed (px per second, downwards) the player was released at; read once by the closing spring. */
    fun throwAt(velocity: Float) { thrown = if (velocity.isFinite()) velocity.coerceAtLeast(0f) else 0f }
    fun takeVelocity(): Float { val v = thrown; thrown = 0f; return v }

    /**
     * Whether a released drag closes the player. The classic interface looks only at the distance.
     * With [velocityAware] (Ket 27) a flick down closes it from anywhere and a flick up always keeps it.
     */
    fun closes(offset: Float, velocity: Float, height: Float, fling: Float, velocityAware: Boolean): Boolean {
        if (!offset.isFinite() || height <= 0f) return false
        val far = offset > height * DISTANCE
        if (!velocityAware || !velocity.isFinite()) return far
        return when {
            velocity > fling -> offset > 0f
            velocity < -fling -> false
            else -> far
        }
    }
}

/** Where the title and the buttons of the mini-player are on screen, for the elements that travel from them. Not state: read while drawing. */
internal object MiniPlayerAnchors {
    @Volatile var title: Rect? = null
    @Volatile var play: Rect? = null
    @Volatile var next: Rect? = null
}

internal data class ElementMorph(val scale: Float, val translationX: Float, val translationY: Float)

/**
 * A shared element on its way from where it sits in the mini-player ([from]) to its place in the
 * full player ([rest]), both in window px: at 0 it covers [from] (same height, same centre — or same
 * left edge with [anchorStart], for text), at 1 it is at rest. Beyond 1 it carries on a little, for a spring.
 */
internal fun sharedElementMorph(from: Rect?, rest: Rect?, progress: Float, anchorStart: Boolean = false): ElementMorph {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1.2f) else 1f
    if (from == null || rest == null || from.height <= 0f || rest.height <= 0f || rest.width <= 0f || p == 1f) return ElementMorph(1f, 0f, 0f)
    val start = (from.height / rest.height).coerceIn(.2f, 2f)
    val startX = if (anchorStart) from.left - rest.left else from.center.x - rest.center.x
    return ElementMorph(start + (1f - start) * p, startX * (1f - p), (from.center.y - rest.center.y) * (1f - p))
}

private class RestPlace { var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null }

/**
 * Makes an element of the full player travel from its counterpart in the mini-player along the
 * reveal timeline. Its place at rest is read from the layout while drawing, so it stays right
 * whatever moves around it; nothing is recomposed during the journey.
 */
internal fun Modifier.travelFromMini(from: () -> Rect?, progress: () -> Float, anchorStart: Boolean = false): Modifier = composed {
    val rest = remember { RestPlace() }
    onGloballyPositioned { rest.coordinates = it }.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val p = progress()
                if (p != 1f) {
                    val morph = sharedElementMorph(from(), rest.coordinates?.takeIf { it.isAttached }?.let { it.findRootCoordinates().localBoundingBoxOf(it, false) }, p, anchorStart)
                    scaleX = morph.scale; scaleY = morph.scale
                    translationX = morph.translationX; translationY = morph.translationY
                    if (anchorStart) transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                }
            }
        }
    }
}

/** Clip a stable full-size player instead of stretching its text/video between aspect ratios. */
@Composable
internal fun PlayerPresentation(visible: Boolean, origin: Rect?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val reduced = Motion.reduced
    BoxWithConstraints(modifier) {
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        LaunchedEffect(visible, reduced) {
            if (visible) {
                if (reveal.value == 0f) {
                    anchor = origin?.takeIf { it.width > 0 && it.height > 0 && it.top < h }
                    drag.snapTo(0f)
                }
            } else {
                // Absorb the interactive fraction before animating the exact same curve back.
                reveal.snapTo(reveal.value * (1f - drag.value / h).coerceIn(0f, 1f))
                drag.snapTo(0f)
            }
            if (reduced) reveal.snapTo(if (visible) 1f else 0f)
            // Ket 27: one spring carries the mini-player into the full player and back; released from a
            // drag, it starts at the speed of the finger.
            else if (Ket27.on) {
                val thrown = PlayerDismiss.takeVelocity()
                reveal.animateTo(if (visible) 1f else 0f,
                    if (visible) spring(dampingRatio = PlayerDismiss.OPEN_DAMPING, stiffness = PlayerDismiss.OPEN_STIFFNESS)
                    else spring(dampingRatio = 1f, stiffness = PlayerDismiss.CLOSE_STIFFNESS),
                    initialVelocity = if (visible) 0f else -(thrown / h).coerceIn(0f, 12f))
            }
            else reveal.animateTo(if (visible) 1f else 0f,
                tween(if (visible) 420 else 320, easing = CubicBezierEasing(.22f, .8f, .2f, 1f)))
        }
        if (visible || reveal.value > 0f) {
            val p = presentationProgress(reveal.value, drag.value, h)
            val start = anchor ?: Rect(w * .08f, h * .89f, w * .92f, h * .96f)
            val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 27.dp.toPx() }
            val mask = remember(start, p, radius) {
                object : Shape {
                    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                        val rect = presentationBounds(start, size.width, size.height, p)
                        return Outline.Generic(Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius * (1f - p)))) })
                    }
                }
            }
            // Under Ket 27 the shared elements may pass their place a little before they settle (the spring's overshoot).
            val springy = Ket27.on && !reduced
            val progress = remember(h, springy) { { presentationProgress(reveal.value, drag.value, h, if (springy) PlayerDismiss.OVERSHOOT else 1f) } }
            CompositionLocalProvider(LocalPlayerDismissOffset provides drag, LocalPlayerReveal provides progress) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    shape = mask; clip = true
                    alpha = if (reduced) 1f else (p * 4f).coerceIn(0f, 1f)
                }) { content() }
            }
        }
    }
}

/** Scale of the player artwork for a pause amount in 0..1: full size playing, 88 % paused. */
internal fun pausedArtworkScale(paused: Float): Float = 1f - .12f * (if (paused.isFinite()) paused.coerceIn(-.2f, 1.2f) else 0f)
