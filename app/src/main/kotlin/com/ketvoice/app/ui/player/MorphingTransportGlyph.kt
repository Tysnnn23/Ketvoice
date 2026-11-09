package com.ketvoice.app.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.motion.Motion

/** Two matching polygon halves become pause bars; never crossfade two icons. */
@Composable
fun MorphingTransportGlyph(playing: Boolean, modifier: Modifier = Modifier, tint: Color = Color.White) {
    val morph by animateFloatAsState(if (playing) 1f else 0f, tween(Motion.duration(220)), label = "transport-morph")
    Canvas(modifier.semantics { contentDescription = AppText.t(if (playing) "Pause" else "Lecture") }) {
        val from = listOf(
            floatArrayOf(.20f,.08f, .50f,.27f, .50f,.73f, .20f,.92f),
            floatArrayOf(.50f,.27f, .88f,.50f, .88f,.50f, .50f,.73f))
        val to = listOf(
            floatArrayOf(.18f,.08f, .41f,.08f, .41f,.92f, .18f,.92f),
            floatArrayOf(.59f,.08f, .82f,.08f, .82f,.92f, .59f,.92f))
        for (part in 0..1) {
            val p = Path()
            for (i in 0..3) {
                val x = (from[part][i*2] + (to[part][i*2] - from[part][i*2]) * morph) * size.width
                val y = (from[part][i*2+1] + (to[part][i*2+1] - from[part][i*2+1]) * morph) * size.height
                if (i == 0) p.moveTo(x,y) else p.lineTo(x,y)
            }
            p.close(); drawPath(p, tint)
        }
    }
}
