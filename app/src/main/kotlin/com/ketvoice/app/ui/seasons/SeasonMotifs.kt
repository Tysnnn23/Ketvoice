package com.ketvoice.app.ui.seasons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.SeasonPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * The decor of the seasonal themes (6.2), drawn in code over Home's header. Each moves with the slow seasonal
 * phase (0..1, see SeasonPulse), always by whole cycles, so nothing jumps when the phase starts again; with reduced
 * motion the phase holds and the decor stands still. [k] scales every length (1 on Home, ½ in a preview). Every
 * function stays small: the release build's methods must stay under ART's register limit (6.1.2).
 */

private const val TAU = 2f * PI.toFloat()

/** A stable pseudo-random number in 0..1 for the [i]-th element and a [salt]. */
internal fun seeded(i: Int, salt: Float): Float {
    val v = sin(i * 12.9898f + salt * 78.233f) * 43758.547f
    return v - floor(v)
}

internal fun DrawScope.drawMotif(motif: Motif, p: SeasonPalette, phase: Float, k: Float) = when (motif) {
    Motif.Snow -> { garland(p, phase, k); snowfall(phase, k) }
    Motif.Fireworks -> { bursts(p, phase, k); sparkles(p, phase, k) }
    Motif.Hearts -> hearts(p, phase, k)
    Motif.Blossom -> { branch(p, k); petals(p, phase, k) }
    Motif.Sun -> { sun(p, phase, k); waves(p, phase, k) }
    Motif.Night -> { web(p, k); moon(k); bats(phase, k) }
}

// ------------------------------------------------------------------ Noël

/** Two swags of lights on the right, clear of the greeting. */
private fun DrawScope.garland(p: SeasonPalette, phase: Float, k: Float) {
    swag(p, phase, k, Offset(size.width * .44f, 0f), Offset(size.width * .63f, 62.dp.toPx() * k), Offset(size.width * .80f, 8.dp.toPx() * k), 0)
    swag(p, phase, k, Offset(size.width * .80f, 8.dp.toPx() * k), Offset(size.width * .93f, 50.dp.toPx() * k), Offset(size.width * 1.04f, 0f), 7)
}

private fun DrawScope.swag(p: SeasonPalette, phase: Float, k: Float, from: Offset, bend: Offset, to: Offset, first: Int) {
    val wire = Path().apply { moveTo(from.x, from.y); quadraticTo(bend.x, bend.y, to.x, to.y) }
    drawPath(wire, Color.White.copy(alpha = .18f), style = Stroke(1.2.dp.toPx() * k))
    val colours = listOf(p.core, p.mid, p.cool, p.fill.first())
    for (i in 1 until 7) bulb(quadratic(from, bend, to, i / 7f), colours[(first + i) % colours.size], twinkle(phase, first + i), k)
}

private fun quadratic(a: Offset, b: Offset, c: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(u * u * a.x + 2 * u * t * b.x + t * t * c.x, u * u * a.y + 2 * u * t * b.y + t * t * c.y)
}

private fun twinkle(phase: Float, i: Int): Float = .6f + .4f * sin(TAU * (phase * 2f + i * .37f))

private fun DrawScope.bulb(at: Offset, colour: Color, light: Float, k: Float) {
    val glow = 11.dp.toPx() * k
    drawCircle(Brush.radialGradient(listOf(colour.copy(alpha = .42f * light), Color.Transparent), at, glow), glow, at)
    drawCircle(colour.copy(alpha = .55f + .45f * light), 3.dp.toPx() * k, at.copy(y = at.y + 3.dp.toPx() * k))
}

private fun DrawScope.snowfall(phase: Float, k: Float) {
    val band = size.height
    for (i in 0 until 16) {
        val x = seeded(i, 1f) * size.width
        val y = (seeded(i, 2f) * band + phase * band) % band
        val r = (2.5f + seeded(i, 3f) * 5f).dp.toPx() * k
        val alpha = (.25f + seeded(i, 4f) * .4f) * (1f - y / band)
        if (r > 5.dp.toPx() * k) flake(Offset(x, y), r, Color.White.copy(alpha = alpha))
        else drawCircle(Color.White.copy(alpha = alpha), r * .45f, Offset(x, y))
    }
}

private fun DrawScope.flake(at: Offset, r: Float, colour: Color) {
    val stroke = (r / 6f).coerceAtLeast(1f)
    for (arm in 0 until 3) {
        val a = arm * PI.toFloat() / 3f
        val d = Offset(cos(a) * r, sin(a) * r)
        drawLine(colour, at - d, at + d, stroke, StrokeCap.Round)
    }
}

// ------------------------------------------------------------------ Nouvel An

private fun DrawScope.bursts(p: SeasonPalette, phase: Float, k: Float) {
    // Clear of the greeting on the left: high in the middle, and over the right side.
    val places = listOf(Triple(.56f, 30f, 32f), Triple(.92f, 150f, 20f), Triple(.70f, 92f, 20f))
    val colours = listOf(p.mid, p.cool, p.fill.last())
    places.forEachIndexed { i, (fx, y, r) ->
        val open = .82f + .18f * sin(TAU * (phase + i / 3f))
        burst(Offset(size.width * fx, y.dp.toPx() * k), r.dp.toPx() * k * open, colours[i], i * .3f, k)
    }
}

private fun DrawScope.burst(at: Offset, r: Float, colour: Color, turn: Float, k: Float) {
    for (j in 0 until 14) {
        val a = turn + j * TAU / 14f
        val dir = Offset(cos(a), sin(a))
        val start = at + dir * (r * .32f)
        val end = at + dir * r
        drawLine(Brush.linearGradient(listOf(colour.copy(alpha = 0f), colour.copy(alpha = .5f)), start, end),
            start, end, 1.6.dp.toPx() * k, StrokeCap.Round)
        drawCircle(colour.copy(alpha = .7f), 1.6.dp.toPx() * k, end)
    }
}

private fun DrawScope.sparkles(p: SeasonPalette, phase: Float, k: Float) {
    for (i in 0 until 10) {
        val at = Offset(seeded(i, 5f) * size.width, seeded(i, 6f) * size.height * .8f)
        val r = (3f + seeded(i, 7f) * 4f).dp.toPx() * k * twinkle(phase, i)
        star(at, r, p.cool.copy(alpha = .55f))
    }
}

private fun DrawScope.star(at: Offset, r: Float, colour: Color) {
    val path = Path().apply {
        moveTo(at.x, at.y - r); quadraticTo(at.x, at.y, at.x + r, at.y); quadraticTo(at.x, at.y, at.x, at.y + r)
        quadraticTo(at.x, at.y, at.x - r, at.y); quadraticTo(at.x, at.y, at.x, at.y - r); close()
    }
    drawPath(path, colour)
}

// ------------------------------------------------------------------ Saint-Valentin

private fun DrawScope.hearts(p: SeasonPalette, phase: Float, k: Float) {
    val band = size.height
    for (i in 0 until 10) {
        val y = (seeded(i, 8f) * band - phase * band + band) % band
        val sway = sin(TAU * (phase + i * .2f)) * 6.dp.toPx() * k
        val at = Offset((.42f + seeded(i, 9f) * .58f) * size.width + sway, y)
        val s = (10f + seeded(i, 10f) * 16f).dp.toPx() * k
        val fade = (y / band).coerceIn(0f, 1f)
        val path = heart(at, s)
        if (i % 3 == 0) drawPath(path, p.cool.copy(alpha = .45f * fade), style = Stroke(1.4.dp.toPx() * k))
        else drawPath(path, (if (i % 2 == 0) p.core else p.mid).copy(alpha = .38f * fade))
    }
}

/** A heart of width [s] centred on [at]. */
internal fun heart(at: Offset, s: Float): Path = Path().apply {
    val h = s * .62f
    moveTo(at.x, at.y + h * .9f)
    cubicTo(at.x - s * .9f, at.y + h * .1f, at.x - s * .55f, at.y - h * 1.1f, at.x, at.y - h * .45f)
    cubicTo(at.x + s * .55f, at.y - h * 1.1f, at.x + s * .9f, at.y + h * .1f, at.x, at.y + h * .9f)
    close()
}

// ------------------------------------------------------------------ Printemps

private val Bark = Color(0xFF1E1214)

private fun DrawScope.branchPoint(t: Float, k: Float): Offset {
    // From the right edge towards the middle, above the avatar and clear of the greeting.
    val a = Offset(size.width + 12.dp.toPx() * k, 14.dp.toPx() * k)
    val d = Offset(size.width * .44f, 10.dp.toPx() * k)
    val u = 1f - t
    val b = Offset(size.width * .80f, 78.dp.toPx() * k)
    val c = Offset(size.width * .60f, -10.dp.toPx() * k)
    return a * (u * u * u) + b * (3 * u * u * t) + c * (3 * u * t * t) + d * (t * t * t)
}

private fun DrawScope.branch(p: SeasonPalette, k: Float) {
    val path = Path()
    for (i in 0..24) branchPoint(i / 24f, k).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) }
    drawPath(path, Bark.copy(alpha = .85f), style = Stroke(4.dp.toPx() * k, cap = StrokeCap.Round))
    for (i in 1..7) flower(branchPoint(i / 8f, k) + Offset(0f, (if (i % 2 == 0) -5f else 6f).dp.toPx() * k),
        (4f + seeded(i, 11f) * 2f).dp.toPx() * k, p)
}

private fun DrawScope.flower(at: Offset, r: Float, p: SeasonPalette) {
    for (petal in 0 until 5) {
        val a = petal * TAU / 5f - PI.toFloat() / 2f
        drawCircle(lighten(p.core, .35f).copy(alpha = .9f), r, at + Offset(cos(a), sin(a)) * r)
    }
    drawCircle(p.cool, r * .55f, at)
}

private fun lighten(c: Color, t: Float) = Color(c.red + (1 - c.red) * t, c.green + (1 - c.green) * t, c.blue + (1 - c.blue) * t)

private fun DrawScope.petals(p: SeasonPalette, phase: Float, k: Float) {
    val band = size.height
    for (i in 0 until 12) {
        val y = (seeded(i, 12f) * band + phase * band) % band
        val x = size.width * (.4f + (seeded(i, 13f) + phase) % 1f * .6f)
        val r = (2.5f + seeded(i, 14f) * 2.5f).dp.toPx() * k
        drawOval(lighten(p.core, .3f).copy(alpha = .75f * (1f - y / band)), Offset(x - r, y - r * .6f), Size(r * 2f, r * 1.2f))
    }
}

// ------------------------------------------------------------------ Été

private fun DrawScope.sun(p: SeasonPalette, phase: Float, k: Float) {
    val at = Offset(size.width * .68f, 26.dp.toPx() * k)
    val r = 32.dp.toPx() * k
    for (i in 0 until 12) {
        val a = i * TAU / 12f + phase * TAU / 12f
        val dir = Offset(cos(a), sin(a))
        drawLine(p.mid.copy(alpha = .45f), at + dir * (r * 1.25f), at + dir * (r * 1.7f), 2.5.dp.toPx() * k, StrokeCap.Round)
    }
    drawCircle(Brush.radialGradient(listOf(p.mid.copy(alpha = .95f), p.core.copy(alpha = .7f)), at, r), r, at)
}

private fun DrawScope.waves(p: SeasonPalette, phase: Float, k: Float) {
    val length = 90.dp.toPx() * k
    // A short swell under the sun, on the right, fading towards the greeting.
    val start = size.width * .46f
    listOf(112f to .34f, 128f to .24f, 144f to .14f).forEachIndexed { i, (y, alpha) ->
        val path = Path()
        val base = y.dp.toPx() * k
        var x = start
        while (x <= size.width) {
            val wy = base + sin(TAU * (x / length + phase + i * .25f)) * 5.dp.toPx() * k
            if (x == start) path.moveTo(x, wy) else path.lineTo(x, wy)
            x += 6.dp.toPx() * k
        }
        drawPath(path, Brush.horizontalGradient(listOf(Color.Transparent, p.cool.copy(alpha = alpha)), start, start + size.width * .25f),
            style = Stroke(1.6.dp.toPx() * k, cap = StrokeCap.Round))
    }
}

// ------------------------------------------------------------------ Halloween

private val Moonlight = Color(0xFFFFE6B8)
private val BatInk = Color(0xFF050302)

private fun DrawScope.moon(k: Float) {
    val at = Offset(size.width * .82f, 46.dp.toPx() * k)
    val r = 26.dp.toPx() * k
    drawCircle(Brush.radialGradient(listOf(Moonlight.copy(alpha = .28f), Color.Transparent), at, r * 2.4f), r * 2.4f, at)
    val disc = Path().apply { addOval(Rect(at, r)) }
    val bite = Path().apply { addOval(Rect(at + Offset(r * .45f, -r * .25f), r * .92f)) }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), Moonlight.copy(alpha = .92f))
}

private fun DrawScope.bats(phase: Float, k: Float) {
    val places = listOf(Triple(.30f, 54f, 1f), Triple(.46f, 30f, .7f), Triple(.62f, 78f, .85f), Triple(.14f, 112f, .6f), Triple(.70f, 128f, .55f))
    places.forEachIndexed { i, (fx, y, s) ->
        val flap = sin(TAU * (phase * 4f + i * .3f))
        val drift = sin(TAU * (phase + i * .17f)) * 8.dp.toPx() * k
        bat(Offset(size.width * fx + drift, y.dp.toPx() * k), 22.dp.toPx() * k * s, flap)
    }
}

private fun DrawScope.bat(at: Offset, span: Float, flap: Float) {
    val lift = span * .22f * flap
    val path = Path().apply {
        moveTo(at.x, at.y)
        quadraticTo(at.x - span * .3f, at.y - span * .25f - lift, at.x - span * .5f, at.y - lift)
        quadraticTo(at.x - span * .32f, at.y - lift * .3f + span * .02f, at.x - span * .2f, at.y + span * .08f)
        quadraticTo(at.x - span * .1f, at.y + span * .02f, at.x, at.y + span * .12f)
        quadraticTo(at.x + span * .1f, at.y + span * .02f, at.x + span * .2f, at.y + span * .08f)
        quadraticTo(at.x + span * .32f, at.y - lift * .3f + span * .02f, at.x + span * .5f, at.y - lift)
        quadraticTo(at.x + span * .3f, at.y - span * .25f - lift, at.x, at.y)
        close()
    }
    drawPath(path, BatInk.copy(alpha = .82f))
}

private fun DrawScope.web(p: SeasonPalette, k: Float) {
    val reach = 120.dp.toPx() * k
    val colour = p.secondary.copy(alpha = .2f)
    val stroke = .9.dp.toPx() * k
    val spokes = (0..6).map { s -> (s / 6f) * PI.toFloat() / 2f }
    spokes.forEach { a -> drawLine(colour, Offset.Zero, Offset(cos(a), sin(a)) * reach, stroke) }
    for (ring in 1..4) {
        val r = reach * ring / 4.6f
        val path = Path()
        spokes.forEachIndexed { i, a -> Offset(cos(a), sin(a)).times(r).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
        drawPath(path, colour, style = Stroke(stroke))
    }
}

// ------------------------------------------------------------------ the emblems

internal fun DrawScope.drawEmblem(motif: Motif, p: SeasonPalette) {
    val at = center
    val r = size.minDimension * .3f
    when (motif) {
        Motif.Snow -> flake(at, r * 1.15f, Color.White)
        Motif.Fireworks -> { burst(at, r * 1.2f, p.mid, 0f, size.minDimension / 120.dp.toPx()); drawCircle(p.mid, r * .16f, at) }
        Motif.Hearts -> drawPath(heart(at, r * 1.9f), p.accent)
        Motif.Blossom -> flower(at, r * .5f, p)
        Motif.Sun -> drawCircle(Brush.radialGradient(listOf(p.mid, p.core), at, r), r, at)
        Motif.Night -> {
            val disc = Path().apply { addOval(Rect(at, r)) }
            val bite = Path().apply { addOval(Rect(at + Offset(r * .45f, -r * .25f), r * .92f)) }
            drawPath(Path.combine(PathOperation.Difference, disc, bite), Moonlight)
        }
    }
}
