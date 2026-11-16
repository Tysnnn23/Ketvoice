package com.ketvoice.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Moment" cards: one or two lines of a song set on its cover colours, as a vertical
 * image ready for a story, shared with a link that opens the title in Ketvoice.
 */
object MomentCard {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    /** Up to four lines since 5.9.2 (the video and the picture). */
    const val MAX_LINES = 4
    private const val MAX_CHARS = 280

    /**
     * Selecting lines for a card: a line is added if it touches the selection, the
     * selection never exceeds [MAX_LINES] consecutive lines, and a selected line is removed.
     */
    fun toggle(selection: List<Int>, index: Int): List<Int> {
        if (index < 0) return selection
        if (index in selection) return selection - index
        val next = (selection + index).sorted()
        val contiguous = next.zipWithNext().all { (a, b) -> b == a + 1 }
        return if (contiguous && next.size <= MAX_LINES) next else listOf(index)
    }

    /** The words that go on the card: trimmed, never empty, never longer than a story can hold. */
    fun text(lines: List<String>): String {
        val joined = lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return if (joined.length <= MAX_CHARS) joined else joined.take(MAX_CHARS - 1).trimEnd() + "…"
    }

    /** The line being sung when [track] is the title playing; otherwise none. */
    fun currentLine(track: Track): List<String> {
        val player = com.ketvoice.app.player.Player.state.value
        if (player.current?.id != track.id) return emptyList()
        val lyrics = com.ketvoice.app.data.LyricsRepo.state.value
        val index = lyrics.indexAt(com.ketvoice.app.player.Player.currentPositionMs())
        return listOfNotNull(lyrics.lines.getOrNull(index)?.text?.takeIf { it.isNotBlank() })
    }

    /** Text colour readable on a background colour (ARGB). */
    fun inkOn(background: Int): Int {
        val r = (background shr 16 and 0xFF) / 255.0; val g = (background shr 8 and 0xFF) / 255.0; val b = (background and 0xFF) / 255.0
        fun lin(c: Double) = if (c <= .03928) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4)
        val luminance = .2126 * lin(r) + .7152 * lin(g) + .0722 * lin(b)
        return if (luminance > .36) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
    }

    suspend fun render(context: Context, track: Track, lines: List<String>, accent: Int?): File = withContext(Dispatchers.Default) {
        val coverUrl = ArtworkRepo.coverForTrack(track) ?: track.coverUrl
        val cover = coverUrl?.let { url ->
            runCatching {
                val request = coil.request.ImageRequest.Builder(context).data(url).allowHardware(false).size(900).build()
                (coil.Coil.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()
        }
        val base = accent ?: 0xFF2C2C34.toInt()
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // The cover's colour, darkening downwards, so white words read on any palette.
        val shade = darken(base, .55f)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), base, shade, Shader.TileMode.CLAMP)
        })
        // Since 5.9.0 the cover itself, blurred to colour fields, lies under the shade, as in Apple Music's shared lyrics.
        if (cover != null) {
            val tiny = cover.scale(9, 16)
            canvas.drawBitmap(tiny.scale(WIDTH, HEIGHT), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 })
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0x33000000, darken(base, .7f) and 0x00FFFFFF or 0xB3000000.toInt(), Shader.TileMode.CLAMP)
            })
        }
        // Over the darkened cover the words are white; on the flat colour they take the readable ink.
        val ink = if (cover != null) 0xFFFFFFFF.toInt() else inkOn(darken(base, .3f))
        val side = 640f
        val left = (WIDTH - side) / 2f
        val top = 260f
        if (cover != null) {
            val clip = Path().apply { addRoundRect(RectF(left, top, left + side, top + side), 44f, 44f, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(clip)
            canvas.drawBitmap(cover, null, RectF(left, top, left + side, top + side), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }
        val words = text(lines)
        val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textSize = if (words.length > 90) 64f else 76f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textWidth = WIDTH - 160
        var y = top + side + 110f
        if (words.isNotEmpty()) {
            val layout = StaticLayout.Builder.obtain(words, 0, words.length, lyricPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).setMaxLines(6)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            canvas.save(); canvas.translate(80f, y); layout.draw(canvas); canvas.restore()
            y += layout.height + 70f
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; alpha = 210; textSize = 42f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(ellipsize(track.title, metaPaint, textWidth.toFloat()), 80f, y, metaPaint)
        metaPaint.typeface = Typeface.DEFAULT; metaPaint.alpha = 170
        canvas.drawText(ellipsize(track.artist, metaPaint, textWidth.toFloat()), 80f, y + 58f, metaPaint)
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; alpha = 190; textSize = 34f; letterSpacing = .3f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("KETVOICE", 80f, HEIGHT - 140f, brand)
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        // Only the latest card is kept.
        folder.listFiles { file -> file.name.startsWith("moment-") }?.forEach { it.delete() }
        val file = File(folder, "moment-${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
        file
    }

    fun share(context: Context, file: File, track: Track) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, ShareLinks.trackUrl(track.id, track.title, track.artist))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Partager ce moment")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END).toString()

    private fun darken(color: Int, amount: Float): Int {
        val f = 1f - amount.coerceIn(0f, 1f)
        val r = ((color shr 16 and 0xFF) * f).toInt(); val g = ((color shr 8 and 0xFF) * f).toInt(); val b = ((color and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
