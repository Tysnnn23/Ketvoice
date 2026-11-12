package com.ketvoice.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** One optical weight and rounded geometry; vectors are built once, never during drawing. */
object KetIcons {
    private fun glyph(name: String, mirrored: Boolean = false, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = "Ket.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = mirrored).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    private fun PathBuilder.circle(x: Float, y: Float, r: Float) {
        val k = r * .552285f
        moveTo(x + r, y)
        curveTo(x + r, y + k, x + k, y + r, x, y + r)
        curveTo(x - k, y + r, x - r, y + k, x - r, y)
        curveTo(x - r, y - k, x - k, y - r, x, y - r)
        curveTo(x + k, y - r, x + r, y - k, x + r, y)
        close()
    }

    val ArrowLeft: ImageVector by lazy { glyph("ArrowLeft", mirrored = true) {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    } }
    val ChevronRight: ImageVector by lazy { glyph("ChevronRight", mirrored = true) {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    } }
    val Plus: ImageVector by lazy { glyph("Plus") {
        moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f)
    } }
    val Search: ImageVector by lazy { glyph("Search") {
        circle(10.5f, 10.5f, 6.5f); moveTo(15.2f, 15.2f); lineTo(20f, 20f)
    } }
    val More: ImageVector by lazy { glyph("More") {
        circle(5f, 12f, .65f); circle(12f, 12f, .65f); circle(19f, 12f, .65f)
    } }
    val Close: ImageVector by lazy { glyph("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f)
    } }
    val Play: ImageVector by lazy { glyph("Play") {
        moveTo(8f, 5f); curveTo(8f, 4.4f, 8.5f, 4.2f, 9f, 4.5f)
        lineTo(19f, 11.3f); curveTo(19.6f, 11.7f, 19.6f, 12.3f, 19f, 12.7f)
        lineTo(9f, 19.5f); curveTo(8.5f, 19.8f, 8f, 19.6f, 8f, 19f); close()
    } }
    val Pause: ImageVector by lazy { glyph("Pause") {
        moveTo(8f, 5f); lineTo(8f, 19f); moveTo(16f, 5f); lineTo(16f, 19f)
    } }
    val Shuffle: ImageVector by lazy { glyph("Shuffle") {
        moveTo(3f, 6f); lineTo(5f, 6f); curveTo(10f, 6f, 11f, 18f, 16f, 18f); lineTo(21f, 18f)
        moveTo(18f, 15f); lineTo(21f, 18f); lineTo(18f, 21f)
        moveTo(3f, 18f); lineTo(5f, 18f); curveTo(7f, 18f, 8f, 16f, 9f, 14f)
        moveTo(15f, 7f); curveTo(16f, 6f, 17f, 6f, 18f, 6f); lineTo(21f, 6f)
        moveTo(18f, 3f); lineTo(21f, 6f); lineTo(18f, 9f)
    } }
    val Download: ImageVector by lazy { glyph("Download") {
        moveTo(12f, 3f); lineTo(12f, 15f)
        moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
        moveTo(4f, 16f); lineTo(4f, 19f); curveTo(4f, 20.1f, 4.9f, 21f, 6f, 21f)
        lineTo(18f, 21f); curveTo(19.1f, 21f, 20f, 20.1f, 20f, 19f); lineTo(20f, 16f)
    } }
    val Library: ImageVector by lazy { glyph("Library") {
        moveTo(4f, 4f); lineTo(4f, 20f); moveTo(9f, 4f); lineTo(9f, 20f)
        moveTo(14f, 5f); lineTo(18f, 4f); lineTo(22f, 19f); lineTo(18f, 20f); close()
    } }
    val Playlist: ImageVector by lazy { glyph("Playlist") {
        moveTo(4f, 5f); lineTo(20f, 5f); moveTo(4f, 10f); lineTo(14f, 10f)
        moveTo(4f, 15f); lineTo(11f, 15f); moveTo(18f, 10f); lineTo(18f, 18f)
        curveTo(18f, 21f, 13f, 21f, 13f, 18f); curveTo(13f, 16f, 16f, 15.5f, 18f, 17f)
        moveTo(18f, 10f); lineTo(21f, 11f)
    } }
    val Album: ImageVector by lazy { glyph("Album") {
        circle(12f, 12f, 9f); circle(12f, 12f, 2.5f)
        moveTo(6f, 10f); curveTo(6.5f, 8f, 8f, 6.5f, 10f, 6f)
        moveTo(14f, 18f); curveTo(16f, 17.5f, 17.5f, 16f, 18f, 14f)
    } }
    val Music: ImageVector by lazy { glyph("Music") {
        moveTo(9f, 17f); lineTo(9f, 5f); lineTo(20f, 3f); lineTo(20f, 15f)
        moveTo(9f, 9f); lineTo(20f, 7f); circle(6f, 18f, 3f); circle(17f, 16f, 3f)
    } }
    val Heart: ImageVector by lazy { glyph("Heart") {
        moveTo(12f, 20f); lineTo(4.8f, 13f)
        curveTo(-.2f, 8.2f, 6.8f, 1.3f, 12f, 7f)
        curveTo(17.2f, 1.3f, 24.2f, 8.2f, 19.2f, 13f); close()
    } }
    val Clock: ImageVector by lazy { glyph("Clock") {
        circle(12f, 12f, 9f); moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
    } }
    val Folder: ImageVector by lazy { glyph("Folder") {
        moveTo(3f, 8f); lineTo(3f, 18f); curveTo(3f, 19.1f, 3.9f, 20f, 5f, 20f)
        lineTo(19f, 20f); curveTo(20.1f, 20f, 21f, 19.1f, 21f, 18f); lineTo(21f, 8f)
        curveTo(21f, 6.9f, 20.1f, 6f, 19f, 6f); lineTo(11f, 6f); lineTo(9f, 4f)
        lineTo(5f, 4f); curveTo(3.9f, 4f, 3f, 4.9f, 3f, 6f); close()
    } }
    val Check: ImageVector by lazy { glyph("Check") {
        moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f)
    } }
    val Trash: ImageVector by lazy { glyph("Trash") {
        moveTo(3f, 6f); lineTo(21f, 6f); moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
        moveTo(5f, 6f); lineTo(6f, 19f); curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
        lineTo(16f, 21f); curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f); lineTo(19f, 6f)
        moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f)
    } }
    val Refresh: ImageVector by lazy { glyph("Refresh") {
        moveTo(20f, 9f); curveTo(18f, 3f, 10f, 1f, 5f, 7f); lineTo(3f, 10f)
        moveTo(3f, 5f); lineTo(3f, 10f); lineTo(8f, 10f)
        moveTo(4f, 15f); curveTo(6f, 21f, 14f, 23f, 19f, 17f); lineTo(21f, 14f)
        moveTo(16f, 14f); lineTo(21f, 14f); lineTo(21f, 19f)
    } }
    val Settings: ImageVector by lazy { glyph("Settings") {
        moveTo(9.5f, 3f); lineTo(14.5f, 3f); lineTo(15f, 6f); lineTo(17f, 7.2f)
        lineTo(20f, 6.2f); lineTo(22f, 10.5f); lineTo(19.5f, 12f); lineTo(19.5f, 14f)
        lineTo(21.5f, 16f); lineTo(19f, 20f); lineTo(16f, 19f); lineTo(14.5f, 20f)
        lineTo(14f, 22f); lineTo(10f, 22f); lineTo(9.5f, 20f); lineTo(8f, 19f)
        lineTo(5f, 20f); lineTo(2.5f, 16f); lineTo(4.5f, 14f); lineTo(4.5f, 12f)
        lineTo(2f, 10.5f); lineTo(4f, 6.2f); lineTo(7f, 7.2f); lineTo(9f, 6f)
        close(); circle(12f, 12f, 3.3f)
    } }
    val Share: ImageVector by lazy { glyph("Share") {
        circle(18f, 5f, 3f); circle(6f, 12f, 3f); circle(18f, 19f, 3f)
        moveTo(8.6f, 10.5f); lineTo(15.4f, 6.5f); moveTo(8.6f, 13.5f); lineTo(15.4f, 17.5f)
    } }
    val Appearance: ImageVector by lazy { glyph("Appearance") {
        circle(12f, 12f, 9f); moveTo(12f, 3f); lineTo(12f, 21f)
        moveTo(15.5f, 6f); lineTo(15.5f, 18f); moveTo(18.5f, 8f); lineTo(18.5f, 16f)
    } }
    val Audio: ImageVector by lazy { glyph("Audio") {
        moveTo(4f, 14f); lineTo(4f, 11f); curveTo(4f, .5f, 20f, .5f, 20f, 11f); lineTo(20f, 14f)
        moveTo(4f, 12f); lineTo(6f, 12f); curveTo(7f, 12f, 7f, 13f, 7f, 14f)
        lineTo(7f, 18f); curveTo(7f, 19f, 6f, 20f, 5f, 20f); curveTo(3f, 20f, 3f, 18f, 3f, 16f); lineTo(3f, 14f); curveTo(3f, 13f, 3f, 12f, 4f, 12f)
        moveTo(20f, 12f); lineTo(18f, 12f); curveTo(17f, 12f, 17f, 13f, 17f, 14f)
        lineTo(17f, 18f); curveTo(17f, 19f, 18f, 20f, 19f, 20f); curveTo(21f, 20f, 21f, 18f, 21f, 16f); lineTo(21f, 14f); curveTo(21f, 13f, 21f, 12f, 20f, 12f)
    } }
    val Lyrics: ImageVector by lazy { glyph("Lyrics") {
        moveTo(5f, 3f); lineTo(19f, 3f); curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
        lineTo(21f, 17f); curveTo(21f, 18.1f, 20.1f, 19f, 19f, 19f)
        lineTo(8f, 19f); lineTo(3f, 22f); lineTo(3f, 5f); curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); close()
        moveTo(7f, 7f); lineTo(17f, 7f); moveTo(7f, 11f); lineTo(17f, 11f); moveTo(7f, 15f); lineTo(13f, 15f)
    } }
    val Canvas: ImageVector by lazy { glyph("Canvas") {
        moveTo(5f, 4f); lineTo(19f, 4f); curveTo(20.1f, 4f, 21f, 4.9f, 21f, 6f)
        lineTo(21f, 18f); curveTo(21f, 19.1f, 20.1f, 20f, 19f, 20f)
        lineTo(5f, 20f); curveTo(3.9f, 20f, 3f, 19.1f, 3f, 18f); lineTo(3f, 6f); curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f); close()
        moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close()
    } }
    val Globe: ImageVector by lazy { glyph("Globe") {
        circle(12f, 12f, 9f); moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f); curveTo(6f, 8f, 6f, 16f, 12f, 21f)
        curveTo(18f, 16f, 18f, 8f, 12f, 3f)
    } }
    val Storage: ImageVector by lazy { glyph("Storage") {
        moveTo(5f, 3f); lineTo(19f, 3f); curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
        lineTo(21f, 19f); curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
        lineTo(5f, 21f); curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f); lineTo(3f, 5f); curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); close()
        moveTo(3f, 9f); lineTo(21f, 9f); moveTo(3f, 15f); lineTo(21f, 15f)
        moveTo(7f, 6f); lineTo(7.1f, 6f); moveTo(7f, 12f); lineTo(7.1f, 12f); moveTo(7f, 18f); lineTo(7.1f, 18f)
    } }
    val Shield: ImageVector by lazy { glyph("Shield") {
        moveTo(12f, 3f); lineTo(21f, 6f); lineTo(21f, 11f)
        curveTo(21f, 16f, 17f, 20f, 12f, 22f); curveTo(7f, 20f, 3f, 16f, 3f, 11f)
        lineTo(3f, 6f); close(); moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    } }
    val Info: ImageVector by lazy { glyph("Info") {
        circle(12f, 12f, 9f); moveTo(12f, 11f); lineTo(12f, 17f)
        moveTo(12f, 7f); lineTo(12f, 7.1f)
    } }
    val Sliders: ImageVector by lazy { glyph("Sliders") {
        moveTo(5f, 3f); lineTo(5f, 7f); circle(5f, 10f, 3f); moveTo(5f, 13f); lineTo(5f, 21f)
        moveTo(12f, 3f); lineTo(12f, 12f); circle(12f, 15f, 3f); moveTo(12f, 18f); lineTo(12f, 21f)
        moveTo(19f, 3f); lineTo(19f, 5f); circle(19f, 8f, 3f); moveTo(19f, 11f); lineTo(19f, 21f)
    } }
    val Wifi: ImageVector by lazy { glyph("Wifi") {
        moveTo(2f, 8f); curveTo(7.5f, 2.5f, 16.5f, 2.5f, 22f, 8f)
        moveTo(5.5f, 11.5f); curveTo(9f, 8f, 15f, 8f, 18.5f, 11.5f)
        moveTo(9f, 15f); curveTo(10.5f, 13.5f, 13.5f, 13.5f, 15f, 15f)
        circle(12f, 19f, .65f)
    } }
    val Bell: ImageVector by lazy { glyph("Bell") {
        moveTo(5f, 16f); lineTo(6f, 14f); lineTo(6f, 9f); curveTo(6f, 1f, 18f, 1f, 18f, 9f)
        lineTo(18f, 14f); lineTo(19f, 16f); lineTo(19f, 18f); lineTo(5f, 18f); close()
        moveTo(9f, 21f); curveTo(10f, 23f, 14f, 23f, 15f, 21f)
    } }
    val Account: ImageVector by lazy { glyph("Account") {
        circle(12f, 8f, 4f); moveTo(4f, 21f); lineTo(4f, 19f)
        curveTo(4f, 12f, 20f, 12f, 20f, 19f); lineTo(20f, 21f)
    } }
    val Link: ImageVector by lazy { glyph("Link") {
        moveTo(10f, 14f); lineTo(14f, 10f)
        moveTo(8f, 10f); lineTo(5f, 13f); curveTo(0f, 18f, 6f, 24f, 11f, 19f); lineTo(14f, 16f)
        moveTo(10f, 8f); lineTo(13f, 5f); curveTo(18f, 0f, 24f, 6f, 19f, 11f); lineTo(16f, 14f)
    } }
    val Sun: ImageVector by lazy { glyph("Sun") {
        circle(12f, 12f, 4f)
        moveTo(12f, 2f); lineTo(12f, 4f); moveTo(12f, 20f); lineTo(12f, 22f)
        moveTo(2f, 12f); lineTo(4f, 12f); moveTo(20f, 12f); lineTo(22f, 12f)
        moveTo(5f, 5f); lineTo(6.5f, 6.5f); moveTo(17.5f, 17.5f); lineTo(19f, 19f)
        moveTo(5f, 19f); lineTo(6.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(19f, 5f)
    } }
    val Moon: ImageVector by lazy { glyph("Moon") {
        moveTo(20.5f, 13.5f); curveTo(17f, 21.5f, 6.5f, 22f, 3.5f, 14f)
        curveTo(1f, 7.5f, 6f, 2f, 11f, 3f); curveTo(5f, 10f, 13f, 18f, 20.5f, 13.5f); close()
    } }
}
