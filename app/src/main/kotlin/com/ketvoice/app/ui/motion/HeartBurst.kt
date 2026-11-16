package com.ketvoice.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A title made a favourite: small hearts and sparks burst out of the heart and fade. Drawn over
 * the button, never taking touches; nothing with reduced motion.
 */
@Composable
internal fun HeartBurst(trigger: Int, color: Color, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    if (Motion.reduced || trigger <= 0) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.snapTo(0f); progress.animateTo(1f, tween(BurstShape.DURATION_MS, easing = LinearOutSlowInEasing)) }
    // The burst takes no room (5.9.2): measured at its own size but laid out as a point at the centre of
    // the button, so a like never pushes the title and the buttons around it.
    Box(modifier.layout { measurable, _ ->
        val side = size.roundToPx()
        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(side, side))
        layout(0, 0) { placeable.place(-side / 2, -side / 2) }
    }.testTag("heart-burst"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val t = progress.value
            if (t >= 1f) return@Canvas
            for (p in BurstShape.particles) {
                val at = BurstShape.position(p, t, this.size.minDimension / 2f)
                val alpha = BurstShape.alpha(t)
                val r = p.size.toPx() * (1f - .35f * t)
                val tint = if (p.spark) Color.White else color
                if (p.spark) drawCircle(tint.copy(alpha = alpha * .9f), r * .45f, center + at)
                else rotate(p.angleDegrees + 90f, center + at) { heart(center + at, r, tint.copy(alpha = alpha)) }
            }
        }
    }
}

internal object BurstShape {
    const val DURATION_MS = 620
    class Particle(val angleDegrees: Float, val reach: Float, val size: Dp, val spark: Boolean)
    /** Eight hearts and six sparks around the button, at uneven distances so the burst does not look drawn with a compass. */
    val particles: List<Particle> = (0 until 14).map { i ->
        val spark = i % 7 == 3 || i % 7 == 6 || i % 5 == 4
        Particle(i * (360f / 14f) + (if (i % 2 == 0) 6f else -4f), .62f + (i * 37 % 10) / 28f, if (spark) 4.dp else 5.5.dp, spark)
    }
    /** Where a particle is at [t] (0..1), for a burst of [radius] px: fast out, slowing down. */
    fun position(p: Particle, t: Float, radius: Float): Offset {
        val travel = 1f - (1f - t) * (1f - t)
        val a = p.angleDegrees * PI.toFloat() / 180f
        val d = radius * p.reach * (.25f + .75f * travel)
        return Offset(cos(a) * d, sin(a) * d)
    }
    /** Fully there for the first third, then fading. */
    fun alpha(t: Float): Float = if (t < .35f) 1f else ((1f - t) / .65f).coerceIn(0f, 1f)
}

private fun DrawScope.heart(at: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(at.x, at.y + r * .85f)
        cubicTo(at.x - r * 1.25f, at.y + r * .05f, at.x - r * .75f, at.y - r * 1.05f, at.x, at.y - r * .35f)
        cubicTo(at.x + r * .75f, at.y - r * 1.05f, at.x + r * 1.25f, at.y + r * .05f, at.x, at.y + r * .85f)
        close()
    }
    drawPath(path, color)
}
