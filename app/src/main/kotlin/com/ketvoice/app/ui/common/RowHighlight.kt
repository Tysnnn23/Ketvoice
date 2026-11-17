package com.ketvoice.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.launch

/**
 * The highlight of a list row on iOS: under the finger the row darkens at once, and lets go slowly
 * when the finger lifts. The app's default indication is none (the glass and the content answer the
 * finger), which left the rows of titles without any sign of a touch; this gives them iOS's.
 * Under reduced motion the shade appears and leaves at once.
 */
internal class RowHighlight(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color)
    override fun equals(other: Any?): Boolean = other is RowHighlight && other.color == color
    override fun hashCode(): Int = color.hashCode()

    companion object {
        /** How dark the row gets under the finger (a share of the ink colour). */
        const val SHADE = .09f
        const val IN_MS = 40
        const val OUT_MS = 320

        /** The shade for a number of fingers down on the row. */
        fun target(presses: Int): Float = if (presses > 0) SHADE else 0f

        fun spec(pressed: Boolean, reduced: Boolean): AnimationSpec<Float> =
            if (reduced) snap() else tween(if (pressed) IN_MS else OUT_MS)
    }

    private class Node(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
        private val shade = Animatable(0f)

        override fun onAttach() {
            coroutineScope.launch {
                var presses = 0
                source.interactions.collect { interaction ->
                    presses = when (interaction) {
                        is PressInteraction.Press -> presses + 1
                        is PressInteraction.Release, is PressInteraction.Cancel -> (presses - 1).coerceAtLeast(0)
                        else -> presses
                    }
                    val target = target(presses)
                    if (shade.targetValue != target) launch { shade.animateTo(target, spec(presses > 0, Motion.reduced)) }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            val alpha = shade.value
            if (alpha > 0f) drawRect(color.copy(alpha = color.alpha * alpha))
            drawContent()
        }
    }
}
