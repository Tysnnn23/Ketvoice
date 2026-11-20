package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.ketvoice.app.ui.sub.PageTint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 7.5 (owner: « le color picker … les couleurs sont mal choisies des fois, comme sur The Party Never Ends »):
 * the picker on real covers. The fixtures (`src/test/resources/covers`) are 64 × 64 copies of public
 * catalogue artwork (The Party Never Ends 2.0 and others) and four drawn covers. Each is read as the app reads
 * it (32 px for the page colour, 40 px for the accent) and must give the colour a person would name.
 *
 * What 7.4 gave (raw, before legibility), and why it was wrong:
 *  - The Party Never Ends: page #926545, accent #946544 — Juice's skin; the cover reads purple (sky, title).
 *  - 1989: #AB7858 brown (the face) on a pale blue sky; the sky was below the grey threshold and thrown away.
 *  - Blonde: #87523D brown (skin) on a white sleeve.
 *  - DAMN.: page #992D36, from the red title, while the cover is wall and white T-shirt.
 *  - Random Access Memories: a gold page (#B79957) from a reflection on a black cover.
 *  - A black cover with a small green disc: a green page (#26CD54).
 *  - Navy and mustard two-tone: a mustard page (#DFAA29) although navy covers two thirds.
 *  - After Hours #724626, IGOR #EDACC0, Legends Never Die #C55A6B, pastel #DEBCE8: acceptable.
 *  - The Dark Side of the Moon and a white minimal sleeve: black / off-white with the app's accent: right.
 *
 * Also draws `build/host-ui-preview/cover-picker-sheet.png`: per cover, the cover, 7.4's page and accent,
 * 7.5's page and accent, then a light and a dark page made from 7.5's picks with its readable accent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverPickerFixturesTest {
    private data class Pick(val name: String, val cover: Bitmap, val result: CoverColors.Result, val accent: CoverColors.Result)

    private val old = mapOf(
        "1989" to (0xFFAB7858 to 0xFFAC7758), "after_hours" to (0xFF724626 to 0xFF774927),
        "blonde" to (0xFF87523D to 0xFF89523C), "damn" to (0xFF992D36 to 0xFFA02A33),
        "dark_side_of_the_moon" to (0xFF020202 to null), "igor" to (0xFFEDACC0 to 0xFFEFACC1),
        "legends_never_die" to (0xFFC55A6B to 0xFFC65A6B), "random_access_memories" to (0xFFB79957 to 0xFFC09F5A),
        "synthetic_black_green_speck" to (0xFF26CD54 to 0xFF26D055), "synthetic_pastel" to (0xFFDEBCE8 to 0xFFEFC2DB),
        "synthetic_two_tone" to (0xFFDFAA29 to 0xFFE1AB28), "synthetic_white_minimal" to (0xFFF4F2EE to null),
        "the_party_never_ends" to (0xFF926545 to 0xFF946544))

    /** As the app reads a cover: scaled down with filtering, then its pixels. */
    private fun pixels(image: Bitmap, side: Int): IntArray {
        val small = Bitmap.createScaledBitmap(image, side, side, true)
        return IntArray(side * side).also { small.getPixels(it, 0, side, 0, 0, side, side) }
    }

    private fun picks(): Map<String, Pick> {
        val dir = File(javaClass.classLoader!!.getResource("covers/the_party_never_ends.png")!!.toURI()).parentFile
        return dir.listFiles { f -> f.extension == "png" }!!.sortedBy { it.name }.associate { file ->
            val image = BitmapFactory.decodeFile(file.path)
            val name = file.nameWithoutExtension
            name to Pick(name, image, CoverColors.analyze(pixels(image, 32), 32)!!, CoverColors.analyze(pixels(image, 40), 40)!!)
        }
    }

    private fun lch(color: Int) = CoverColors.lch(color)
    private fun hueIn(color: Int, vararg ranges: ClosedFloatingPointRange<Float>) = lch(color).third.let { h -> ranges.any { h in it } }
    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)

    @Test fun eachCoverGivesTheColourAPersonWouldName() {
        val p = picks()
        p.values.forEach { println("${it.name.padEnd(30)} page ${hex(it.result.background)}  accent ${if (it.accent.colourful) hex(it.accent.base) else "app accent"}") }
        p.getValue("the_party_never_ends").let {
            assertTrue("purple page, not skin: ${hex(it.result.background)}", hueIn(it.result.background, 280f..360f) && lch(it.result.background).second >= .03f)
            assertTrue("purple accent: ${hex(it.accent.base)}", it.accent.colourful && hueIn(it.accent.base, 290f..345f) && lch(it.accent.base).second >= .08f)
        }
        p.getValue("1989").let {
            assertTrue("blue sky page: ${hex(it.result.background)}", hueIn(it.result.background, 200f..270f))
            assertTrue("blue accent: ${hex(it.accent.base)}", it.accent.colourful && hueIn(it.accent.base, 200f..270f))
        }
        p.getValue("blonde").let { assertTrue("white sleeve", lch(it.result.background).first > .85f && lch(it.result.background).second < .02f) }
        p.getValue("damn").let { assertTrue("red accent: ${hex(it.accent.base)}", it.accent.colourful && hueIn(it.accent.base, 0f..40f, 350f..360f)) }
        p.getValue("dark_side_of_the_moon").let { assertTrue(lch(it.result.background).first < .2f) }
        p.getValue("random_access_memories").let { assertTrue("black, not gold", lch(it.result.background).first < .25f) }
        p.getValue("synthetic_black_green_speck").let {
            assertTrue("black page", lch(it.result.background).first < .2f)
            assertTrue("green accent", it.accent.colourful && hueIn(it.accent.base, 125f..165f))
        }
        p.getValue("synthetic_white_minimal").let {
            assertTrue(lch(it.result.background).first > .9f)
            assertFalse("a speck of red is no accent", it.accent.colourful)
        }
        p.getValue("synthetic_two_tone").let { assertTrue("navy covers two thirds", hueIn(it.result.background, 245f..290f)) }
        p.getValue("igor").let { assertTrue("pink", hueIn(it.result.background, 330f..360f, 0f..15f) && lch(it.result.background).first > .7f) }
        p.getValue("legends_never_die").let { assertTrue("red sky", hueIn(it.result.background, 340f..360f, 0f..40f)) }
        p.getValue("synthetic_pastel").let { assertTrue(lch(it.result.background).first > .8f && hueIn(it.result.background, 300f..360f)) }
        p.getValue("after_hours").let { assertTrue("a dark page", lch(it.result.background).first < .45f) }
        sheet(p.values.toList())
    }

    private fun sheet(picks: List<Pick>) {
        val cell = 72; val gap = 6
        val image = Bitmap.createBitmap(gap + 7 * (cell + gap), gap + picks.size * (cell + gap), Bitmap.Config.ARGB_8888)
        val g = android.graphics.Canvas(image)
        g.drawColor(0xFF808080.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun box(col: Int, row: Int, argb: Int?, ink: Int? = null) {
            val x = (gap + col * (cell + gap)).toFloat(); val y = (gap + row * (cell + gap)).toFloat()
            paint.color = argb ?: 0xFF808080.toInt(); g.drawRect(x, y, x + cell, y + cell, paint)
            if (argb == null) { paint.color = -1; paint.strokeWidth = 2f; g.drawLine(x, y, x + cell, y + cell, paint) }
            if (ink != null) { paint.color = ink; g.drawRoundRect(x + 10, y + cell / 2f - 8, x + cell - 10, y + cell / 2f + 8, 8f, 8f, paint) }
        }
        picks.forEachIndexed { row, p ->
            val y = gap + row * (cell + gap)
            g.drawBitmap(p.cover, null, android.graphics.Rect(gap, y, gap + cell, y + cell), Paint(Paint.FILTER_BITMAP_FLAG))
            val (oldTone, oldAccent) = old.getValue(p.name)
            box(1, row, oldTone.toInt()); box(2, row, oldAccent?.toInt())
            box(3, row, p.result.background); box(4, row, if (p.accent.colourful) p.accent.base else null)
            val tone = Color(p.result.background)
            val app = Color(0xFFFA2D48)
            val light = PageTint.dayFloor(lerp(Color.White, tone, .38f), Color.White)
            val dark = PageTint.nightCap(lerp(Color.Black, tone, .30f))
            fun accentOn(page: Color) = CoverLegibility.pageAccent(if (p.accent.colourful) Color(p.accent.base) else null, app, page,
                lerp(page, if (page == light) Color.Black else Color.White, if (page == light) .05f else .08f))
            box(5, row, light.toArgb(), accentOn(light).toArgb())
            box(6, row, dark.toArgb(), accentOn(dark).toArgb())
        }
        val out = File("build/host-ui-preview").apply { mkdirs() }
        File(out, "cover-picker-sheet.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
