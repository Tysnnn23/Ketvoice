package com.ketvoice.desktop.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.common.KetIcons

/**
 * The few symbols the desktop bar needs that KetIcons does not draw (the app takes them from Material
 * icons, too large a jar to add here), on KetIcons' 24 grid, stroke and rounded joins; transport
 * glyphs are solid, as KetIcons' Play and Pause.
 */
object DeskGlyphs {
    private fun glyph(name: String, solid: Boolean = false, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = "Desk.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            if (solid) path(fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 1.2f, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
            else path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = KetIcons.STROKE, strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    val Next: ImageVector by lazy { glyph("Next", solid = true) {
        moveTo(3.5f, 6.2f); lineTo(11.2f, 12f); lineTo(3.5f, 17.8f); close()
        moveTo(11.8f, 6.2f); lineTo(19.5f, 12f); lineTo(11.8f, 17.8f); close()
        moveTo(20.2f, 6.2f); lineTo(21.2f, 6.2f); lineTo(21.2f, 17.8f); lineTo(20.2f, 17.8f); close()
    } }
    val Previous: ImageVector by lazy { glyph("Previous", solid = true) {
        moveTo(20.5f, 6.2f); lineTo(12.8f, 12f); lineTo(20.5f, 17.8f); close()
        moveTo(12.2f, 6.2f); lineTo(4.5f, 12f); lineTo(12.2f, 17.8f); close()
        moveTo(3.8f, 6.2f); lineTo(2.8f, 6.2f); lineTo(2.8f, 17.8f); lineTo(3.8f, 17.8f); close()
    } }
    val Repeat: ImageVector by lazy { glyph("Repeat") {
        moveTo(4f, 11f); lineTo(4f, 9.5f); curveTo(4f, 8.1f, 5.1f, 7f, 6.5f, 7f); lineTo(19f, 7f)
        moveTo(16f, 4f); lineTo(19f, 7f); lineTo(16f, 10f)
        moveTo(20f, 13f); lineTo(20f, 14.5f); curveTo(20f, 15.9f, 18.9f, 17f, 17.5f, 17f); lineTo(5f, 17f)
        moveTo(8f, 14f); lineTo(5f, 17f); lineTo(8f, 20f)
    } }
    val RepeatOne: ImageVector by lazy { glyph("RepeatOne") {
        moveTo(4f, 11f); lineTo(4f, 9.5f); curveTo(4f, 8.1f, 5.1f, 7f, 6.5f, 7f); lineTo(19f, 7f)
        moveTo(16f, 4f); lineTo(19f, 7f); lineTo(16f, 10f)
        moveTo(20f, 13f); lineTo(20f, 14.5f); curveTo(20f, 15.9f, 18.9f, 17f, 17.5f, 17f); lineTo(5f, 17f)
        moveTo(8f, 14f); lineTo(5f, 17f); lineTo(8f, 20f)
        moveTo(11.2f, 10.6f); lineTo(12.4f, 9.8f); lineTo(12.4f, 14.2f)
    } }
    val Volume: ImageVector by lazy { glyph("Volume") {
        moveTo(4f, 9.5f); lineTo(7f, 9.5f); lineTo(11f, 6f); lineTo(11f, 18f); lineTo(7f, 14.5f); lineTo(4f, 14.5f); close()
        moveTo(14.5f, 9f); curveTo(15.5f, 10f, 15.5f, 14f, 14.5f, 15f)
        moveTo(17.5f, 6.5f); curveTo(20f, 9f, 20f, 15f, 17.5f, 17.5f)
    } }
    val Mute: ImageVector by lazy { glyph("Mute") {
        moveTo(4f, 9.5f); lineTo(7f, 9.5f); lineTo(11f, 6f); lineTo(11f, 18f); lineTo(7f, 14.5f); lineTo(4f, 14.5f); close()
        moveTo(15f, 9.5f); lineTo(20f, 14.5f); moveTo(20f, 9.5f); lineTo(15f, 14.5f)
    } }
    val Queue: ImageVector by lazy { glyph("Queue") {
        moveTo(4f, 6.5f); lineTo(16f, 6.5f); moveTo(4f, 11.5f); lineTo(16f, 11.5f); moveTo(4f, 16.5f); lineTo(11f, 16.5f)
        moveTo(14.5f, 14f); lineTo(14.5f, 19.5f); lineTo(19.5f, 16.75f); close()
    } }
    /** Audio output: a speaker cone in a rounded box. */
    val Output: ImageVector by lazy { glyph("Output") {
        moveTo(7f, 3.5f); lineTo(17f, 3.5f); curveTo(18.1f, 3.5f, 19f, 4.4f, 19f, 5.5f); lineTo(19f, 18.5f)
        curveTo(19f, 19.6f, 18.1f, 20.5f, 17f, 20.5f); lineTo(7f, 20.5f); curveTo(5.9f, 20.5f, 5f, 19.6f, 5f, 18.5f)
        lineTo(5f, 5.5f); curveTo(5f, 4.4f, 5.9f, 3.5f, 7f, 3.5f); close()
        moveTo(15f, 14.5f); curveTo(15f, 16.2f, 13.7f, 17.5f, 12f, 17.5f); curveTo(10.3f, 17.5f, 9f, 16.2f, 9f, 14.5f)
        curveTo(9f, 12.8f, 10.3f, 11.5f, 12f, 11.5f); curveTo(13.7f, 11.5f, 15f, 12.8f, 15f, 14.5f); close()
        moveTo(12f, 7f); lineTo(12f, 7.2f)
    } }
    /** Phone control: a screen and a phone side by side. */
    val Devices: ImageVector by lazy { glyph("Devices") {
        moveTo(3f, 5f); lineTo(15f, 5f); lineTo(15f, 14f); lineTo(3f, 14f); close()
        moveTo(9f, 14f); lineTo(9f, 17.5f); moveTo(6f, 18f); lineTo(12f, 18f)
        moveTo(17.5f, 9f); lineTo(21f, 9f); lineTo(21f, 19f); lineTo(17.5f, 19f); close()
    } }
    val Phone: ImageVector by lazy { glyph("Phone") {
        moveTo(8f, 3f); lineTo(16f, 3f); lineTo(16f, 21f); lineTo(8f, 21f); close()
        moveTo(11f, 18f); lineTo(13f, 18f)
    } }
    val Computer: ImageVector by lazy { glyph("Computer") {
        moveTo(3f, 4.5f); lineTo(21f, 4.5f); lineTo(21f, 15.5f); lineTo(3f, 15.5f); close()
        moveTo(12f, 15.5f); lineTo(12f, 19f); moveTo(8.5f, 19.5f); lineTo(15.5f, 19.5f)
    } }
    /** Ketmix: two decks crossing. */
    val Ketmix: ImageVector by lazy { glyph("Ketmix") {
        moveTo(3.5f, 8f); curveTo(9f, 8f, 11f, 16f, 20.5f, 16f)
        moveTo(3.5f, 16f); curveTo(9f, 16f, 11f, 8f, 20.5f, 8f)
        moveTo(18f, 5.5f); lineTo(20.5f, 8f); lineTo(18f, 10.5f)
        moveTo(18f, 13.5f); lineTo(20.5f, 16f); lineTo(18f, 18.5f)
    } }
    val ChevronDown: ImageVector by lazy { glyph("ChevronDown") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }
    val ChevronUp: ImageVector by lazy { glyph("ChevronUp") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) } }
    val Stats: ImageVector by lazy { glyph("Stats") {
        moveTo(5f, 19f); lineTo(5f, 12f); moveTo(10f, 19f); lineTo(10f, 6f); moveTo(15f, 19f); lineTo(15f, 10f); moveTo(20f, 19f); lineTo(20f, 4f)
    } }
    val Grip: ImageVector by lazy { glyph("Grip") { moveTo(5f, 9f); lineTo(19f, 9f); moveTo(5f, 15f); lineTo(19f, 15f) } }
    val Mic: ImageVector by lazy { glyph("Lyrics") {
        moveTo(5f, 6.5f); curveTo(5f, 5.1f, 6.1f, 4f, 7.5f, 4f); lineTo(16.5f, 4f); curveTo(17.9f, 4f, 19f, 5.1f, 19f, 6.5f)
        lineTo(19f, 13.5f); curveTo(19f, 14.9f, 17.9f, 16f, 16.5f, 16f); lineTo(11f, 16f); lineTo(7f, 19.5f); lineTo(7f, 16f)
        curveTo(5.9f, 16f, 5f, 14.9f, 5f, 13.5f); close()
        moveTo(8.5f, 8.5f); lineTo(15.5f, 8.5f); moveTo(8.5f, 11.5f); lineTo(13f, 11.5f)
    } }
}
