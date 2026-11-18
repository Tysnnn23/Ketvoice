package com.ketvoice.app.ui.vicecity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.ViceCityTheme
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The Vice City edition's one illustration: a sunset on Ocean Drive, drawn flat in a few pastel colours — a sky in two
 * bands, a coral sun half set, a mint sea with three glints and two palm silhouettes — in its day version on a light
 * theme and its dusk version on a dark one. Still: no glow, no gradient, no motion, so reduced motion and the
 * performance mode have nothing to take away. Decorative only: never focusable, never in the way of a touch.
 *
 * [sunAt] places the sun across the width (0..1); the palms stand just to its right. Without [palms] (the small tile
 * of Settings) the scene is the sun over the sea alone.
 */
@Composable
fun ViceCityScene(modifier: Modifier = Modifier, sunAt: Float = .70f, palms: Boolean = true, tag: String = "vicecity-scene") {
    val scene = ViceCityTheme.scene(LocalTheme.current.isDark)
    Canvas(modifier.clipToBounds().clearAndSetSemantics { testTag = tag }) { drawScene(scene, sunAt, palms) }
}

/** The small square of the Settings row: the sun over the sea, in a rounded tile like the row's neighbours. */
@Composable
fun ViceCityTile(size: Dp, modifier: Modifier = Modifier) {
    ViceCityScene(modifier.size(size).clip(RoundedCornerShape(size * .23f)), sunAt = .5f, palms = false, tag = "vicecity-tile")
}

/** Where the scene's parts sit, as shares of its height, apart so it can be checked without a screen. */
internal object SceneLayout {
    /** The horizon, and the lower band of the sky above it, as shares of the height. */
    const val HORIZON = .70f
    const val LOW_SKY = .44f
    /**
     * The scene's unit: its height, but never more than this share of its width, so a tall picture (the welcome)
     * keeps the sun and the palms in proportion and lets the sky breathe above them.
     */
    const val UNIT_OF_WIDTH = .6f
    /** The sun's radius in units, and how far it has set under the horizon. */
    const val SUN = .27f
    const val SET = .18f
    /** The palms in units: the tall one and the short one, their feet just below the bottom edge. */
    const val TALL_PALM = .84f
    const val SHORT_PALM = .60f

    fun unit(width: Float, height: Float, palms: Boolean): Float = if (palms) min(height, width * UNIT_OF_WIDTH) else height
}

internal fun DrawScope.drawScene(scene: ViceCityTheme.Scene, sunAt: Float, palms: Boolean) {
    val w = size.width
    val h = size.height
    val horizon = h * SceneLayout.HORIZON
    drawRect(scene.skyHigh)
    drawRect(scene.skyLow, Offset(0f, h * SceneLayout.LOW_SKY), androidx.compose.ui.geometry.Size(w, horizon - h * SceneLayout.LOW_SKY))
    val u = SceneLayout.unit(w, h, palms)
    val r = min(u * SceneLayout.SUN, w * .36f)
    val sunX = w * sunAt.coerceIn(0f, 1f)
    clipRect(bottom = horizon) { drawCircle(scene.sun, r, Offset(sunX, horizon + r * SceneLayout.SET)) }
    drawRect(scene.sea, Offset(0f, horizon), androidx.compose.ui.geometry.Size(w, h - horizon))
    // The sun on the water: three short strokes, shorter towards the shore.
    val sea = h - horizon
    val line = (u * .018f).coerceAtLeast(1f)
    listOf(.28f to 1.10f, .54f to .66f, .80f to .30f).forEach { (at, length) ->
        val y = horizon + sea * at
        drawLine(scene.glint, Offset(sunX - r * length, y), Offset(sunX + r * length, y), line, StrokeCap.Round)
    }
    if (!palms) return
    drawPalm(scene, Offset(sunX + r * 2.35f, h + u * .02f), u * SceneLayout.SHORT_PALM, lean = .20f)
    drawPalm(scene, Offset(sunX + r * 1.55f, h + u * .04f), u * SceneLayout.TALL_PALM, lean = -.30f)
}

/**
 * One palm silhouette from its foot [base], [height] tall, its crown pushed sideways by [lean] (a share of the height,
 * negative to the left): a trunk that narrows and bends, and seven fronds that arch and droop.
 */
internal fun DrawScope.drawPalm(scene: ViceCityTheme.Scene, base: Offset, height: Float, lean: Float) {
    val crown = Offset(base.x + height * lean, base.y - height)
    val bend = Offset(base.x + height * lean * .15f, base.y - height * .55f)
    val foot = height * .030f
    val neck = height * .014f
    val trunk = Path().apply {
        moveTo(base.x - foot, base.y)
        quadraticTo(bend.x - foot * .8f, bend.y, crown.x - neck, crown.y)
        lineTo(crown.x + neck, crown.y)
        quadraticTo(bend.x + foot * .8f, bend.y, base.x + foot, base.y)
        close()
    }
    drawPath(trunk, scene.palm)
    // Angles in degrees from the crown (0 to the right, 90 down), and each frond's length as a share of the height.
    val fronds = listOf(-172f to .40f, -142f to .44f, -112f to .34f, -78f to .30f, -44f to .44f, -12f to .42f, 18f to .30f)
    for ((angle, share) in fronds) {
        val a = Math.toRadians(angle.toDouble())
        val length = height * share
        val droop = length * .38f * kotlin.math.abs(cos(a)).toFloat()
        val tip = Offset(crown.x + length * cos(a).toFloat(), crown.y + length * sin(a).toFloat() + droop)
        drawPath(frond(crown, tip, length), scene.palm)
    }
}

/** A frond from [from] to [tip]: a thin crescent arching above the straight line between them. */
private fun frond(from: Offset, tip: Offset, length: Float): Path {
    val dx = tip.x - from.x
    val dy = tip.y - from.y
    val d = hypot(dx, dy).coerceAtLeast(1f)
    // The side of the line that faces up, so every frond arches upwards before it droops.
    var nx = -dy / d
    var ny = dx / d
    if (ny > 0f) { nx = -nx; ny = -ny }
    val mid = Offset(from.x + dx * .5f, from.y + dy * .5f)
    val outer = Offset(mid.x + nx * length * .26f, mid.y + ny * length * .26f)
    val inner = Offset(mid.x + nx * length * .08f, mid.y + ny * length * .08f)
    return Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(outer.x, outer.y, tip.x, tip.y)
        quadraticTo(inner.x, inner.y, from.x, from.y)
        close()
    }
}
