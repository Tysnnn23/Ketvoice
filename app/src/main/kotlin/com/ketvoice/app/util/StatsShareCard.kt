package com.ketvoice.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.ketvoice.app.data.AppText
import java.io.File

/**
 * The statistics cards (8.0, « Replay en direct »): « Mon top 5 » and « Mes minutes », 1080×1920 like the title's
 * share card ([ShareCard]): the field made of the #1 cover's colours, white words, Ketvoice's mark at the foot.
 * Every word is passed in already translated.
 */
object StatsShareCard {
    private const val W = ShareCard.WIDTH
    private const val H = ShareCard.HEIGHT
    private val ink = 0xFFFFFFFF.toInt()

    data class Row(val title: String, val artist: String, val cover: Bitmap?)

    private fun paint(size: Float, bold: Boolean, alpha: Int = 255, align: Paint.Align = Paint.Align.LEFT) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; this.alpha = alpha; textSize = size; textAlign = align
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private fun fit(text: String, paint: TextPaint, width: Float) =
        android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END).toString()

    private fun drawCover(canvas: Canvas, cover: Bitmap?, rect: RectF, radius: Float) {
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); alpha = 60; setShadowLayer(40f, 0f, 16f, 0x55000000) })
        if (cover == null) {
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
            return
        }
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        canvas.drawBitmap(cover, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    /** « Mon top 5 »: the five most played titles of [period], each with its cover. */
    fun top5(heading: String, period: String, rows: List<Row>, accent: Int?): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ShareCard.drawField(canvas, rows.firstOrNull()?.cover, accent)
        val left = 110f
        canvas.drawText(fit(period.uppercase(), paint(34f, true, 200).apply { letterSpacing = .18f }, W - 2 * left), left, 250f,
            paint(34f, true, 200).apply { letterSpacing = .18f })
        canvas.drawText(fit(heading, paint(96f, true), W - 2 * left), left, 360f, paint(96f, true))
        val rank = paint(60f, true, 230)
        val title = paint(50f, true)
        val artist = paint(40f, false, 190)
        rows.take(5).forEachIndexed { i, row ->
            val top = 470f + i * 240f
            canvas.drawText("${i + 1}", left, top + 122f, rank)
            val rect = RectF(left + 80f, top, left + 280f, top + 200f)
            drawCover(canvas, row.cover, rect, 22f)
            val textLeft = rect.right + 44f
            val room = W - textLeft - left
            canvas.drawText(fit(row.title, title, room), textLeft, top + 88f, title)
            canvas.drawText(fit(row.artist, artist, room), textLeft, top + 146f, artist)
        }
        ShareCard.drawMark(canvas)
        return bitmap
    }

    /**
     * « Mes minutes »: the time of [period] in large, its change from the previous period, the bars of the period
     * ([bars], the current one in full white) and the #1 artist.
     */
    fun minutes(period: String, minutes: String, unit: String, delta: String?, bars: LongArray, now: Int, topLine: String?,
                cover: Bitmap?, accent: Int?): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ShareCard.drawField(canvas, cover, accent)
        val cx = W / 2f
        canvas.drawText(fit(period.uppercase(), paint(36f, true, 210, Paint.Align.CENTER).apply { letterSpacing = .18f }, W - 200f), cx, 300f,
            paint(36f, true, 210, Paint.Align.CENTER).apply { letterSpacing = .18f })
        drawCover(canvas, cover, RectF(cx - 200f, 380f, cx + 200f, 780f), 32f)
        val big = paint(if (minutes.length > 6) 190f else 230f, true, align = Paint.Align.CENTER).apply { letterSpacing = -.02f }
        canvas.drawText(minutes, cx, 1080f, big)
        canvas.drawText(fit(unit, paint(52f, true, 235, Paint.Align.CENTER), W - 200f), cx, 1160f, paint(52f, true, 235, Paint.Align.CENTER))
        if (!delta.isNullOrBlank()) canvas.drawText(fit(delta, paint(42f, false, 200, Paint.Align.CENTER), W - 200f), cx, 1232f,
            paint(42f, false, 200, Paint.Align.CENTER))
        // The bars, quiet ones for the other days, the current one in full white.
        if (bars.isNotEmpty()) {
            val longest = bars.max().coerceAtLeast(1L)
            val chartLeft = 150f; val chartRight = W - 150f; val bottom = 1560f; val height = 220f
            val step = (chartRight - chartLeft) / bars.size
            val barW = step * .56f
            bars.forEachIndexed { i, ms ->
                val share = if (ms <= 0L) .04f else (.08f + .92f * ms.toFloat() / longest)
                val x = chartLeft + i * step + (step - barW) / 2f
                canvas.drawRoundRect(RectF(x, bottom - height * share, x + barW, bottom), barW / 2.6f, barW / 2.6f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = if (i == now) 255 else 95 })
            }
        }
        topLine?.takeIf { it.isNotBlank() }?.let {
            canvas.drawText(fit(it, paint(40f, false, 210, Paint.Align.CENTER), W - 200f), cx, 1660f, paint(40f, false, 210, Paint.Align.CENTER))
        }
        ShareCard.drawMark(canvas)
        return bitmap
    }

    /** The system share sheet with the picture alone. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Partager")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
