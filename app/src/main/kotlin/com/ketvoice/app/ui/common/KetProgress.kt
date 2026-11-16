package com.ketvoice.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.motion.Motion

/**
 * The activity indicator of iOS 27 (Ket 27): eight rounded spokes, the brightest one going round
 * a step at a time. It replaces Material's spinning arc. With reduced motion it still turns (it
 * says that something is happening), but at half the pace.
 */
@Composable
fun ActivitySpinner(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = LocalTheme.current.secondary) {
    val turn = rememberInfiniteTransition(label = "activity")
    val step by turn.animateFloat(0f, Spinner.SPOKES.toFloat(),
        infiniteRepeatable(tween(if (Motion.reduced) Spinner.PERIOD_MS * 2 else Spinner.PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "activity-step")
    Canvas(modifier.size(size).testTag("activity-spinner").semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val lead = step.toInt() % Spinner.SPOKES
        val width = this.size.minDimension * .095f
        val outer = this.size.minDimension / 2f - width / 2f
        val inner = outer * .5f
        for (i in 0 until Spinner.SPOKES) {
            rotate(i * 360f / Spinner.SPOKES) {
                drawLine(color.copy(alpha = color.alpha * Spinner.alpha(i, lead)), Offset(center.x, center.y - inner),
                    Offset(center.x, center.y - outer), width, StrokeCap.Round)
            }
        }
    }
}

internal object Spinner {
    const val SPOKES = 8
    const val PERIOD_MS = 800
    /** Spoke [index] is brightest when it leads, and fades over the four spokes behind it. */
    fun alpha(index: Int, lead: Int): Float {
        val behind = Math.floorMod(lead - index, SPOKES)
        return if (behind >= 4) .25f else 1f - behind * .1875f
    }
}

/**
 * The progress bar of iOS 27 (Ket 27): a 4 dp capsule, filled in the app's colour. [progress]
 * returns 0..1, or null while the amount is unknown: then a short segment glides along the track
 * (it holds still in the middle with reduced motion).
 */
@Composable
fun KetProgressBar(progress: (() -> Float)?, modifier: Modifier = Modifier, color: Color = LocalTheme.current.accent,
                   track: Color = LocalTheme.current.onBackground.copy(alpha = .12f), height: Dp = 4.dp) {
    val glide = if (progress == null && !Motion.reduced) {
        val transition = rememberInfiniteTransition(label = "progress")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_300, easing = LinearEasing), RepeatMode.Restart),
            label = "progress-glide").value
    } else .5f
    Canvas(modifier.fillMaxWidth().height(height).testTag("ket-progress").semantics {
        progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it().coerceIn(0f, 1f), 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
    }) {
        val y = this.size.height / 2f
        val stroke = this.size.height
        val start = stroke / 2f
        val end = this.size.width - stroke / 2f
        drawLine(track, Offset(start, y), Offset(end, y), stroke, StrokeCap.Round)
        if (progress != null) {
            val fraction = progress().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            if (fraction > 0f) drawLine(color, Offset(start, y), Offset(start + (end - start) * fraction, y), stroke, StrokeCap.Round)
        } else {
            val span = (end - start) * .3f
            val left = start - span + (end - start + span) * glide
            drawLine(color, Offset(left.coerceAtLeast(start), y), Offset((left + span).coerceAtMost(end), y), stroke, StrokeCap.Round)
        }
    }
}
