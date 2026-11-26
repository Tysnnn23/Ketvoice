package com.ketvoice.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Ket 27's symbols, drawn for Ketvoice in the manner of Apple's: filled, rounded, heavy, on a 24
 * grid. They are original drawings (Apple's SF Symbols may not be shipped outside its platforms).
 */
object Ket27Glyphs {
    private fun filled(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = "Ket27.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = draw)
        }.build()

    private fun stroked(name: String, width: Float, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = "Ket27.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y); lineTo(x + w - r, y); quadTo(x + w, y, x + w, y + r)
        lineTo(x + w, y + h - r); quadTo(x + w, y + h, x + w - r, y + h)
        lineTo(x + r, y + h); quadTo(x, y + h, x, y + h - r)
        lineTo(x, y + r); quadTo(x, y, x + r, y); close()
    }

    /** A house with a soft roof and a door cut out. */
    val Home: ImageVector by lazy { filled("Home") {
        moveTo(12f, 2.6f); curveTo(11.6f, 2.6f, 11.2f, 2.75f, 10.9f, 3f)
        lineTo(3.2f, 9.6f); curveTo(2.6f, 10.1f, 2.95f, 11.1f, 3.75f, 11.1f); lineTo(4.6f, 11.1f)
        lineTo(4.6f, 19.2f); curveTo(4.6f, 20.4f, 5.4f, 21.2f, 6.6f, 21.2f); lineTo(17.4f, 21.2f)
        curveTo(18.6f, 21.2f, 19.4f, 20.4f, 19.4f, 19.2f); lineTo(19.4f, 11.1f); lineTo(20.25f, 11.1f)
        curveTo(21.05f, 11.1f, 21.4f, 10.1f, 20.8f, 9.6f); lineTo(13.1f, 3f); curveTo(12.8f, 2.75f, 12.4f, 2.6f, 12f, 2.6f); close()
        roundRect(10f, 14.6f, 4f, 6.6f, 1.2f)
    } }

    /** Four rounded tiles. */
    val Explore: ImageVector by lazy { filled("Explore") {
        roundRect(3f, 3f, 8f, 8f, 2.4f); roundRect(13f, 3f, 8f, 8f, 2.4f)
        roundRect(3f, 13f, 8f, 8f, 2.4f); roundRect(13f, 13f, 8f, 8f, 2.4f)
    } }

    /** A stack of records: two cases behind, a note on the front one. */
    val Library: ImageVector by lazy { filled("Library") {
        roundRect(6.5f, 2.6f, 11f, 1.8f, .9f)
        roundRect(4.6f, 5.4f, 14.8f, 1.8f, .9f)
        roundRect(2.8f, 8.3f, 18.4f, 13.1f, 3.2f)
        // The note, cut out of the front case.
        moveTo(13.6f, 10.8f); lineTo(13.6f, 16.2f); curveTo(13.1f, 15.95f, 12.5f, 15.9f, 11.9f, 16.1f)
        curveTo(10.8f, 16.5f, 10.3f, 17.6f, 10.8f, 18.4f); curveTo(11.3f, 19.2f, 12.6f, 19.3f, 13.6f, 18.7f)
        curveTo(14.4f, 18.2f, 14.8f, 17.5f, 14.8f, 16.7f); lineTo(14.8f, 12.9f); lineTo(16.6f, 12.4f)
        lineTo(16.6f, 10.8f); lineTo(13.6f, 10.8f); close()
    } }

    /** A heavy magnifying glass. */
    val Search: ImageVector by lazy { stroked("Search", 2.4f) {
        moveTo(17.2f, 10.6f); curveTo(17.2f, 14.25f, 14.25f, 17.2f, 10.6f, 17.2f)
        curveTo(6.95f, 17.2f, 4f, 14.25f, 4f, 10.6f); curveTo(4f, 6.95f, 6.95f, 4f, 10.6f, 4f)
        curveTo(14.25f, 4f, 17.2f, 6.95f, 17.2f, 10.6f); close()
        moveTo(15.4f, 15.4f); lineTo(20.2f, 20.2f)
    } }

    private fun PathBuilder.heartShape() {
        moveTo(12f, 20.6f); curveTo(11.7f, 20.6f, 11.4f, 20.5f, 11.15f, 20.3f)
        curveTo(6.2f, 16.6f, 2.6f, 13.4f, 2.6f, 9.1f); curveTo(2.6f, 6.1f, 4.9f, 3.8f, 7.7f, 3.8f)
        curveTo(9.5f, 3.8f, 11.1f, 4.75f, 12f, 6.2f); curveTo(12.9f, 4.75f, 14.5f, 3.8f, 16.3f, 3.8f)
        curveTo(19.1f, 3.8f, 21.4f, 6.1f, 21.4f, 9.1f); curveTo(21.4f, 13.4f, 17.8f, 16.6f, 12.85f, 20.3f)
        curveTo(12.6f, 20.5f, 12.3f, 20.6f, 12f, 20.6f); close()
    }
    /** 👍 and 👎 for « Ketmix apprend »: a thumb over its cuff, the second one turned upside down. */
    val ThumbUp: ImageVector by lazy { stroked("ThumbUp", 1.9f) { thumb { it } } }
    val ThumbDown: ImageVector by lazy { stroked("ThumbDown", 1.9f) { thumb { 24f - it } } }

    private fun PathBuilder.thumb(y: (Float) -> Float) {
        moveTo(3.5f, y(10.5f)); lineTo(6.5f, y(10.5f)); lineTo(6.5f, y(19.5f)); lineTo(3.5f, y(19.5f)); close()
        moveTo(6.5f, y(10.5f)); lineTo(10f, y(4.6f)); curveTo(11.4f, y(4.2f), 12.7f, y(5.3f), 12.3f, y(6.8f)); lineTo(11.7f, y(9.5f))
        lineTo(17.4f, y(9.5f)); curveTo(18.9f, y(9.5f), 20f, y(10.9f), 19.6f, y(12.3f)); lineTo(18.3f, y(17.9f))
        curveTo(18.1f, y(18.9f), 17.2f, y(19.5f), 16.2f, y(19.5f)); lineTo(6.5f, y(19.5f))
    }

    val HeartFilled: ImageVector by lazy { filled("HeartFilled") { heartShape() } }
    val Heart: ImageVector by lazy { stroked("Heart", 2f) { heartShape() } }
}
