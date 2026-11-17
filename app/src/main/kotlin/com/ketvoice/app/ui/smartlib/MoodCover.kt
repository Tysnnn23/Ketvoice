package com.ketvoice.app.ui.smartlib

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.smartlib.Mood
import com.ketvoice.app.ui.common.Text

/** The two colours of an ambiance's cover, from its top-left to its bottom-right corner. */
internal fun moodColors(mood: Mood): Pair<Color, Color> = when (mood) {
    Mood.Calm -> Color(0xFF2F6FD0) to Color(0xFF7FD6C8)
    Mood.Energy -> Color(0xFFFF4B2B) to Color(0xFFF7A21B)
    Mood.Party -> Color(0xFF7B2FF7) to Color(0xFFFF3CAC)
    Mood.Focus -> Color(0xFF0F4C5C) to Color(0xFF6DBE8A)
    Mood.Melancholy -> Color(0xFF1F2A44) to Color(0xFF6A7FDB)
    Mood.Happy -> Color(0xFFFFC233) to Color(0xFFFF6A3D)
}

/**
 * A generated cover: the ambiance's gradient, a drawing of its own (waves, sparks, lights, rings,
 * rain, sun) and its name. Nothing is downloaded; it draws the same on every device.
 */
@Composable
internal fun MoodCover(mood: Mood, size: Dp, modifier: Modifier = Modifier, radius: Dp = 14.dp, showName: Boolean = true) {
    val (from, to) = moodColors(mood)
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).background(Brush.linearGradient(listOf(from, to)))
        .testTag("mood-cover-" + mood.name)) {
        Canvas(Modifier.fillMaxSize()) { motif(mood) }
        if (showName) Text(mood.label, color = Color.White, fontSize = (size.value / 8.5f).coerceIn(12f, 34f).sp,
            fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = (size.value / 7.5f).coerceIn(14f, 38f).sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(size * .08f))
    }
}

private fun DrawScope.motif(mood: Mood) = when (mood) {
    Mood.Calm -> waves()
    Mood.Energy -> sparks()
    Mood.Party -> lights()
    Mood.Focus -> rings()
    Mood.Melancholy -> rain()
    Mood.Happy -> sun()
}

private val ink = Color.White.copy(alpha = .22f)

private fun DrawScope.waves() {
    for (row in 0 until 4) {
        val y = size.height * (.18f + row * .13f)
        val path = Path().apply {
            moveTo(0f, y)
            var x = 0f
            val step = size.width / 4f
            while (x < size.width) { quadraticTo(x + step / 2f, y + (if ((x / step).toInt() % 2 == 0) -1 else 1) * size.height * .05f, x + step, y); x += step }
        }
        drawPath(path, ink, style = Stroke(size.minDimension * .025f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.sparks() {
    val w = size.width; val h = size.height
    for (i in 0 until 5) {
        val x = w * (.15f + i * .2f)
        drawLine(ink, Offset(x, h * .08f), Offset(x - w * .18f, h * .62f), strokeWidth = size.minDimension * .05f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.lights() {
    val spots = listOf(.2f to .2f, .55f to .14f, .82f to .3f, .35f to .42f, .7f to .55f, .12f to .55f)
    spots.forEachIndexed { i, (x, y) -> drawCircle(Color.White.copy(alpha = .14f + (i % 3) * .06f), size.minDimension * (.06f + (i % 3) * .035f), Offset(size.width * x, size.height * y)) }
}

private fun DrawScope.rings() {
    val center = Offset(size.width * .72f, size.height * .3f)
    for (i in 1..4) drawCircle(ink, size.minDimension * .09f * i, center, style = Stroke(size.minDimension * .018f))
}

private fun DrawScope.rain() {
    val w = size.width; val h = size.height
    for (i in 0 until 9) {
        val x = w * (.08f + i * .105f); val y = h * (.06f + (i * 37 % 5) * .08f)
        drawLine(ink, Offset(x, y), Offset(x - w * .03f, y + h * .16f), strokeWidth = size.minDimension * .018f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.sun() {
    val center = Offset(size.width * .7f, size.height * .3f)
    val r = size.minDimension * .14f
    drawCircle(Color.White.copy(alpha = .3f), r, center)
    for (i in 0 until 10) {
        val angle = Math.toRadians(i * 36.0)
        val dx = kotlin.math.cos(angle).toFloat(); val dy = kotlin.math.sin(angle).toFloat()
        drawLine(ink, Offset(center.x + dx * r * 1.35f, center.y + dy * r * 1.35f), Offset(center.x + dx * r * 1.9f, center.y + dy * r * 1.9f),
            strokeWidth = size.minDimension * .025f, cap = StrokeCap.Round)
    }
}
