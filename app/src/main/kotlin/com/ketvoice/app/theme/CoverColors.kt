package com.ketvoice.app.theme

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The colours of a cover, shared by album and artist pages, the player, the widgets and the app-wide tint.
 *
 * 7.5 (owner: « les couleurs sont mal choisies des fois, comme sur The Party Never Ends »): until 7.4 the
 * hue holding the most saturated, mid-light pixels won, for the page as for the accent. Skin, a gold
 * reflection or a small vivid sticker thus tinted whole pages (a brown page for The Party Never Ends'
 * purple sky, brown for 1989's blue, green for a black cover with a green dot), and pale colours below
 * the grey threshold were thrown away. Now, as Apple Music does:
 *  - pixels are compared in OKLab, where distances follow the eye, and grouped into clusters;
 *  - fine detail (text, a busy crowd) counts less than flat areas, and a uniform frame is left out;
 *  - clusters of one hue form a region (a sky in several lightnesses);
 *  - the page colour ([Result.background]) is the region that covers the most, a little favoured when
 *    it carries colour; near-black and near-white count only when the cover is mostly made of them;
 *  - the accent ([Result.base]) is the most prominent vivid region, or the page's own hue when that
 *    covers most of the cover, so text and buttons stay in the cover's family; a speck is never one.
 * Legibility (WCAG) and the night caps are applied afterwards by [CoverLegibility] and the pages.
 */
internal object CoverColors {
    /** Two pixels closer than this in OKLab belong to one cluster. */
    private const val CLUSTER_DISTANCE = .09f
    /** Below this OKLab chroma a cluster is a grey. */
    const val NEUTRAL_CHROMA = .018f
    /** Clusters whose hues are this close (degrees) form one region of the cover. */
    private const val REGION_HUE = 30f
    /** Smallest share of the cover that can colour a page; smaller is a speck. */
    private const val GROUND_SHARE = .04f
    /** Smallest share of the cover, and chroma, of an accent. */
    private const val ACCENT_SHARE = .02f
    private const val ACCENT_CHROMA = .06f
    /** A coloured page region covering at least this much also gives the accent. */
    private const val LEADING_REGION = .4f

    data class Result(
        /** The accent: a colour of the most prominent vivid region (the page colour when there is none). */
        val base: Int,
        /** Another vivid region's colour, far enough in hue from the first, or null. */
        val second: Int?,
        /** False when no region is vivid or wide enough to give an accent. */
        val colourful: Boolean,
        /** Hue (HSL degrees) of [base]. */
        val hue: Float,
        /** The colour of the region the cover is mostly made of: what a page around it takes. */
        val background: Int = base,
    )

    private class Cluster(var weight: Float, var l: Float, var a: Float, var b: Float, var r: Float, var g: Float, var bl: Float) {
        var share = 0f
        val ml get() = l / weight
        val ma get() = a / weight
        val mb get() = b / weight
        val chroma get() = hypot(ma, mb)
        val hue get() = ((Math.toDegrees(atan2(mb.toDouble(), ma.toDouble())) + 360) % 360).toFloat()
        fun rgb() = rgb(r / weight, g / weight, bl / weight)
    }

    private class Region(val members: MutableList<Cluster>, val neutral: Boolean) {
        val hue = members.first().hue
        val share get() = members.sumOf { it.share.toDouble() }.toFloat()
        val chroma get() = members.sumOf { (it.chroma * it.share).toDouble() }.toFloat() / share
        val lightness get() = members.sumOf { (it.ml * it.share).toDouble() }.toFloat() / share
        /** The region's colour, its richer clusters counting more: a sky reads as its blue, not as its haze. */
        fun rgb(): Int {
            fun weightOf(c: Cluster) = maxOf(c.chroma, .01f).toDouble()
            val w = members.sumOf { weightOf(it) * it.weight }
            return rgb(members.sumOf { weightOf(it) * it.r } / w, members.sumOf { weightOf(it) * it.g } / w,
                members.sumOf { weightOf(it) * it.bl } / w)
        }
        /** Its most telling colour: rich, and not a sliver of it. */
        fun accent(): Int = (members.filter { it.share >= .01f && it.ml in 0.3f..0.9f }.ifEmpty { members })
            .maxBy { it.chroma * sqrt(it.share) }.rgb()
    }

    /** [pixels] in ARGB, row by row; [width] lets fine detail and frames be recognised (a square is guessed). */
    fun analyze(pixels: IntArray, width: Int = 0): Result? {
        val n = pixels.size
        val l = FloatArray(n); val a = FloatArray(n); val b = FloatArray(n); val weight = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            if ((p ushr 24) < 128) continue
            weight[i] = 1f
            oklab(p, i, l, a, b)
        }
        if (weight.none { it > 0f }) return null
        val w = if (width > 0 && n % width == 0) width else sqrt(n.toDouble()).toInt().takeIf { it * it == n } ?: 0
        if (w >= 8 && n / w >= 8) {
            softenDetail(w, n / w, l, a, b, weight)
            dropFrame(w, n / w, l, a, b, weight)
        }
        val clusters = cluster(pixels, l, a, b, weight)
        val total = clusters.sumOf { it.weight.toDouble() }.toFloat()
        if (total <= 0f) return null
        clusters.forEach { it.share = it.weight / total }
        clusters.sortByDescending { it.share }
        val regions = regions(clusters)

        fun extreme(r: Region) = r.lightness < .25f || (r.lightness > .92f && r.chroma < .03f)
        fun groundScore(r: Region): Float {
            if (r.share < GROUND_SHARE) return 0f
            // A grey counts by its area; a coloured region more the more colour it carries (a purple sky over a
            // brown crowd of the same size wins, as the eye sums it up).
            val s = r.share * (if (r.neutral) .35f else .1f + 8f * minOf(r.chroma, .16f))
            return if (extreme(r) && r.share < .45f) s * .4f else s
        }
        val ground = regions.maxBy(::groundScore).takeIf { groundScore(it) > 0f } ?: regions.maxBy { it.share }

        fun vivid(r: Region) = !r.neutral && r.share >= ACCENT_SHARE && r.members.any { it.share >= .01f && it.chroma >= ACCENT_CHROMA }
        fun accentScore(r: Region) = r.share * minOf(r.chroma, .2f).pow(2)
        val accent = if (!ground.neutral && ground.share >= LEADING_REGION) ground
            else regions.filter(::vivid).maxByOrNull(::accentScore)
        val second = accent?.let { first ->
            regions.filter { it !== first && vivid(it) && hueDistance(it.hue, first.hue) >= 45f }.maxByOrNull(::accentScore)
        }
        val background = ground.rgb()
        val base = accent?.accent() ?: background
        return Result(base, second?.accent(), accent != null, hslHue(base), background)
    }

    /** Text and busy detail: a pixel unlike most of its neighbours says less about the cover's colour. */
    private fun softenDetail(w: Int, h: Int, l: FloatArray, a: FloatArray, b: FloatArray, weight: FloatArray) {
        val soft = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (weight[i] == 0f) continue
            var far = 0
            if (x > 0 && distance(i, i - 1, l, a, b) > .12f) far++
            if (x < w - 1 && distance(i, i + 1, l, a, b) > .12f) far++
            if (y > 0 && distance(i, i - w, l, a, b) > .12f) far++
            if (y < h - 1 && distance(i, i + w, l, a, b) > .12f) far++
            soft[i] = far >= 3
        }
        for (i in soft.indices) if (soft[i]) weight[i] *= .35f
    }

    /** A uniform frame (a Polaroid's white edge, a printed border) unlike the picture inside is not its colour. */
    private fun dropFrame(w: Int, h: Int, l: FloatArray, a: FloatArray, b: FloatArray, weight: FloatArray) {
        val band = maxOf(1, w / 20)
        val ring = ArrayList<Int>(); val inner = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (weight[i] == 0f) continue
            if (minOf(minOf(x, y), minOf(w - 1 - x, h - 1 - y)) < band) ring += i else inner += i
        }
        if (ring.isEmpty() || inner.isEmpty()) return
        fun median(values: FloatArray) = ring.map { values[it] }.sorted()[ring.size / 2]
        val ml = median(l); val ma = median(a); val mb = median(b)
        fun near(i: Int) = hypot(hypot(l[i] - ml, a[i] - ma), b[i] - mb) < .05f
        val framed = ring.filter(::near)
        if (framed.size < ring.size * .8f || inner.count(::near) >= inner.size * .5f) return
        framed.forEach { weight[it] = 0f }
    }

    private fun cluster(pixels: IntArray, l: FloatArray, a: FloatArray, b: FloatArray, weight: FloatArray): MutableList<Cluster> {
        val clusters = ArrayList<Cluster>()
        for (i in pixels.indices) {
            val wi = weight[i]
            if (wi == 0f) continue
            var best: Cluster? = null; var bestDistance = CLUSTER_DISTANCE
            for (c in clusters) {
                val d = hypot(hypot(l[i] - c.ml, a[i] - c.ma), b[i] - c.mb)
                if (d < bestDistance) { bestDistance = d; best = c }
            }
            val p = pixels[i]
            val r = (p shr 16 and 255) / 255f; val g = (p shr 8 and 255) / 255f; val bl = (p and 255) / 255f
            if (best == null) clusters += Cluster(wi, l[i] * wi, a[i] * wi, b[i] * wi, r * wi, g * wi, bl * wi)
            else best.apply {
                this.weight += wi; this.l += l[i] * wi; this.a += a[i] * wi; this.b += b[i] * wi
                this.r += r * wi; this.g += g * wi; this.bl += bl * wi
            }
        }
        return clusters
    }

    /** Clusters, largest first, gathered by hue: greys stay apart, each in its own lightness. */
    private fun regions(clusters: List<Cluster>): List<Region> {
        val regions = ArrayList<Region>()
        for (c in clusters) {
            if (c.chroma < NEUTRAL_CHROMA) { regions += Region(mutableListOf(c), neutral = true); continue }
            val region = regions.firstOrNull { !it.neutral && hueDistance(it.hue, c.hue) <= REGION_HUE }
            if (region == null) regions += Region(mutableListOf(c), neutral = false) else region.members += c
        }
        return regions
    }

    private fun hueDistance(x: Float, y: Float): Float { val d = abs(x - y) % 360f; return minOf(d, 360f - d) }

    private fun distance(i: Int, j: Int, l: FloatArray, a: FloatArray, b: FloatArray) = hypot(hypot(l[i] - l[j], a[i] - a[j]), b[i] - b[j])

    /** OKLab (Björn Ottosson) of an sRGB pixel, written at [i]. */
    private fun oklab(p: Int, i: Int, lOut: FloatArray, aOut: FloatArray, bOut: FloatArray) {
        fun linear(v: Int): Double { val c = v / 255.0; return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4) }
        val r = linear(p shr 16 and 255); val g = linear(p shr 8 and 255); val b = linear(p and 255)
        val l = cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
        val m = cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
        val s = cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
        lOut[i] = (.2104542553 * l + .7936177850 * m - .0040720468 * s).toFloat()
        aOut[i] = (1.9779984951 * l - 2.4285922050 * m + .4505937099 * s).toFloat()
        bOut[i] = (.0259040371 * l + .7827717662 * m - .8086757660 * s).toFloat()
    }

    /** OKLab lightness, chroma and hue (degrees) of an ARGB colour. */
    fun lch(color: Int): Triple<Float, Float, Float> {
        val l = FloatArray(1); val a = FloatArray(1); val b = FloatArray(1)
        oklab(color, 0, l, a, b)
        return Triple(l[0], hypot(a[0], b[0]), ((Math.toDegrees(atan2(b[0].toDouble(), a[0].toDouble())) + 360) % 360).toFloat())
    }

    private fun hslHue(color: Int): Float {
        val r = (color shr 16 and 255) / 255f; val g = (color shr 8 and 255) / 255f; val b = (color and 255) / 255f
        val max = maxOf(r, g, b); val chroma = max - minOf(r, g, b)
        return if (chroma <= 0f) 0f else hue(r, g, b, max, chroma)
    }

    fun hue(r: Float, g: Float, b: Float, max: Float, chroma: Float): Float {
        val h = when (max) {
            r -> ((g - b) / chroma) % 6f
            g -> (b - r) / chroma + 2f
            else -> (r - g) / chroma + 4f
        } * 60f
        return if (h < 0) h + 360f else h
    }

    private fun rgb(r: Double, g: Double, b: Double): Int {
        fun c(v: Double) = Math.round(v * 255).toInt().coerceIn(0, 255)
        return (255 shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun rgb(r: Float, g: Float, b: Float) = rgb(r.toDouble(), g.toDouble(), b.toDouble())

    /** The saturation (HSL) of an ARGB colour, 0..1. */
    fun saturation(color: Int): Float {
        val r = (color shr 16 and 255) / 255f; val g = (color shr 8 and 255) / 255f; val b = (color and 255) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val l = (max + min) / 2f
        return if (max == min) 0f else (max - min) / (1f - abs(2f * l - 1f))
    }
}
