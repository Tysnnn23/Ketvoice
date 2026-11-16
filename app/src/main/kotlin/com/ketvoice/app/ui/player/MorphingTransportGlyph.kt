package com.ketvoice.app.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.SymbolEffects

/** The geometry of the play ⇄ pause symbol: two quadrilaterals, in the proportions of the glyph (0..1). */
internal object TransportMorph {
    private val play = arrayOf(
        floatArrayOf(.20f, .08f, .50f, .27f, .50f, .73f, .20f, .92f),
        floatArrayOf(.50f, .27f, .88f, .50f, .88f, .50f, .50f, .73f))
    private val pause = arrayOf(
        floatArrayOf(.18f, .08f, .41f, .08f, .41f, .92f, .18f, .92f),
        floatArrayOf(.59f, .08f, .82f, .08f, .82f, .92f, .59f, .92f))

    /**
     * The two halves at [progress]: 0 is the play triangle (its two halves touch), 1 the two pause bars.
     * A spring may pass a little beyond either end; the shape follows it without ever folding over.
     */
    fun polygons(progress: Float): Array<FloatArray> {
        val p = if (progress.isFinite()) progress.coerceIn(-.12f, 1.12f) else 0f
        return Array(2) { part -> FloatArray(8) { i -> play[part][i] + (pause[part][i] - play[part][i]) * p } }
    }

    /** The gap between the two halves, as a share of the width: none for play, the space between the bars for pause. */
    fun gap(progress: Float): Float = polygons(progress).let { it[1][0] - it[0][2] }
}

/** The symbol drawn at a given [progress] of the morph; [rounded] softens its corners (Ket 27). */
@Composable
internal fun TransportGlyphAt(progress: Float, modifier: Modifier = Modifier, tint: Color = Color.White, rounded: Boolean = false) {
    Canvas(modifier) {
        val parts = TransportMorph.polygons(progress)
        val corner = if (rounded) size.minDimension * .07f else 0f
        for (part in parts) {
            val p = Path()
            for (i in 0..3) {
                val x = part[i * 2] * size.width
                val y = part[i * 2 + 1] * size.height
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            if (corner > 0f) {
                // Filled, then stroked with round joins: the corners soften without the shape shrinking.
                drawPath(p, tint, style = Fill)
                drawPath(p, tint, style = Stroke(corner, join = StrokeJoin.Round, pathEffect = PathEffect.cornerPathEffect(corner)))
            } else drawPath(p, tint)
        }
    }
}

/** Two matching polygon halves become pause bars; never crossfade two icons. */
@Composable
fun MorphingTransportGlyph(playing: Boolean, modifier: Modifier = Modifier, tint: Color = Color.White) {
    val ket = Ket27.on
    // Ket 27: the symbol is replaced on a spring that overshoots once, as SF Symbols do, and it dips under the change.
    val morph by animateFloatAsState(if (playing) 1f else 0f,
        if (Motion.reduced) snap() else if (ket) spring(dampingRatio = SymbolEffects.MORPH_DAMPING, stiffness = SymbolEffects.MORPH_STIFFNESS)
        else tween(Motion.duration(220)), label = "transport-morph")
    val described = modifier.semantics { contentDescription = AppText.t(if (playing) "Pause" else "Lecture") }
    if (!ket) { TransportGlyphAt(morph, described, tint); return }
    TransportGlyphAt(morph, described.graphicsLayer {
        val dip = SymbolEffects.replaceDip(morph)
        scaleX = dip; scaleY = dip
    }, tint, rounded = true)
}
