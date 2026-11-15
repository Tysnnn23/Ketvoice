package com.ketvoice.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * The rubber band of iOS lists: pulled past its end, a list follows the finger less and less, then
 * springs back when let go; a fling that reaches the end carries on a little past it and bounces.
 * Given to every scrolling list of the app through LocalOverscrollFactory.
 */
internal object BounceOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = BounceOverscrollEffect()
    override fun hashCode(): Int = 0x6B76
    override fun equals(other: Any?): Boolean = other === this
}

internal object RubberBand {
    /** Apple's constant: how stiff the band is. */
    const val COEFFICIENT = .55f
    /** How far content may be shown past its end for a pull of [pull] px, in a list [dimension] px long. */
    fun displayed(pull: Float, dimension: Float): Float {
        if (pull == 0f || dimension <= 0f) return 0f
        val d = dimension
        return sign(pull) * (1f - 1f / (abs(pull) * COEFFICIENT / d + 1f)) * d
    }
    /** How much of a fling's leftover speed carries the list past its end (px/s → initial speed of the bounce). */
    const val FLING_SHARE = .22f
    const val STIFFNESS = Spring.StiffnessMediumLow
}

internal class BounceOverscrollEffect : OverscrollEffect {
    /** The raw pull on each axis; what shows is [RubberBand.displayed] of it. */
    private val pullX = mutableFloatStateOf(0f)
    private val pullY = mutableFloatStateOf(0f)
    private var width = 0f
    private var height = 0f
    private val shownX = Animatable(0f)
    private val shownY = Animatable(0f)
    private var animating = false

    private fun shown(): Offset = if (animating) Offset(shownX.value, shownY.value)
        else Offset(RubberBand.displayed(pullX.floatValue, width), RubberBand.displayed(pullY.floatValue, height))

    override val isInProgress: Boolean get() = pullX.floatValue != 0f || pullY.floatValue != 0f || animating

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        if (animating) return performScroll(delta)
        // Coming back from past the end gives the pull back first.
        var used = Offset.Zero
        var remaining = delta
        fun relax(pull: Float, d: Float): Float =
            if (pull != 0f && sign(d) != sign(pull)) (if (abs(d) >= abs(pull)) -pull else d) else 0f
        val backX = relax(pullX.floatValue, remaining.x)
        val backY = relax(pullY.floatValue, remaining.y)
        if (backX != 0f || backY != 0f) {
            pullX.floatValue += backX; pullY.floatValue += backY
            used = Offset(backX, backY); remaining -= used
        }
        val consumed = performScroll(remaining)
        val left = remaining - consumed
        // Only a finger pulls past the end; a fling's leftover is the bounce of applyToFling.
        if (source == NestedScrollSource.UserInput && (left.x != 0f || left.y != 0f)) {
            pullX.floatValue += left.x; pullY.floatValue += left.y
            return used + consumed + left
        }
        return used + consumed
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        val left = if (pullX.floatValue != 0f || pullY.floatValue != 0f) velocity else performFling(velocity)
        // Back to rest: from where the band is, with what the fling had left when it reached the end.
        val from = shown()
        pullX.floatValue = 0f; pullY.floatValue = 0f
        if (from == Offset.Zero && abs(left.x) < 50f && abs(left.y) < 50f) return
        animating = true
        try {
            shownX.snapTo(from.x); shownY.snapTo(from.y)
            val spec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = RubberBand.STIFFNESS)
            kotlinx.coroutines.coroutineScope {
                launch { shownX.animateTo(0f, spec, -left.x * RubberBand.FLING_SHARE) }
                launch { shownY.animateTo(0f, spec, -left.y * RubberBand.FLING_SHARE) }
            }
        } finally {
            animating = false
            shownX.snapTo(0f); shownY.snapTo(0f)
        }
    }

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            width = placeable.width.toFloat(); height = placeable.height.toFloat()
            return layout(placeable.width, placeable.height) {
                // Read here, so that a pull only moves the content, never measures it again.
                val o = shown()
                placeable.placeWithLayer(o.x.roundToInt(), o.y.roundToInt())
            }
        }
    }
}
