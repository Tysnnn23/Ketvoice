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
 * The share card of a title (7.3), as Apple Music shares to Instagram Stories: the cover large and
 * centred on a field made of its own colours, the title and artist under it, optionally one line of
 * the song, and Ketvoice's mark. A 1080×1920 picture; [MomentCard] keeps the lyric card of the lyrics
 * screen and the video.
 */
object ShareCard {
    const val WIDTH = MomentCard.WIDTH
    const val HEIGHT = MomentCard.HEIGHT
    const val INSTAGRAM = "com.instagram.android"
    private const val STORY_ACTION = "com.instagram.share.ADD_TO_STORY"

    /** Where the cover sits, larger when no line of the song takes room under it. */
    fun coverRect(withLine: Boolean): RectF {
        val side = if (withLine) 640f else 780f
        val left = (WIDTH - side) / 2f
        val top = if (withLine) 300f else 400f
        return RectF(left, top, left + side, top + side)
    }

    suspend fun loadCover(context: Context, track: Track): Bitmap? = withContext(Dispatchers.IO) {
        val url = ArtworkRepo.coverForTrack(track) ?: track.coverUrl ?: return@withContext null
        runCatching {
            val request = coil.request.ImageRequest.Builder(context).data(url).allowHardware(false).size(1000).build()
            (coil.Coil.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    /** Draws the card. [line] is the chosen line of the song, or blank for the title alone. */
    fun draw(cover: Bitmap?, title: String, artist: String, line: String, accent: Int?): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = WIDTH.toFloat(); val h = HEIGHT.toFloat()
        val base = accent ?: 0xFF2C2C34.toInt()
        drawField(canvas, cover, accent)
        val words = MomentCard.text(listOf(line))
        val rect = coverRect(words.isNotEmpty())
        val radius = 36f
        // The cover lifts off the field with a soft shadow.
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); alpha = 70; setShadowLayer(60f, 0f, 24f, 0x66000000)
        })
        if (cover != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(cover, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MomentCard.darken(base, .25f) })
        }
        val textWidth = WIDTH - 200
        var y = rect.bottom + 110f
        val ink = 0xFFFFFFFF.toInt()
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textSize = 58f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        canvas.drawText(ellipsize(title, titlePaint, textWidth.toFloat()), w / 2f, y, titlePaint)
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; alpha = 190; textSize = 44f; typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER
        }
        y += 66f
        canvas.drawText(ellipsize(artist, artistPaint, textWidth.toFloat()), w / 2f, y, artistPaint)
        if (words.isNotEmpty()) {
            val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink; textSize = if (words.length > 70) 56f else 66f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val layout = StaticLayout.Builder.obtain(words, 0, words.length, lyricPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.1f).setMaxLines(4)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            canvas.save(); canvas.translate(100f, y + 90f); layout.draw(canvas); canvas.restore()
        }
        drawMark(canvas)
        return bitmap
    }

    /**
     * The field of a card: the cover reduced to a few colour patches and stretched, so its own colours make the
     * background, under a quiet shade deeper at the foot so white words read whatever the cover. Shared with the
     * statistics cards (8.0).
     */
    fun drawField(canvas: Canvas, cover: Bitmap?, accent: Int?) {
        val w = WIDTH.toFloat(); val h = HEIGHT.toFloat()
        val base = accent ?: 0xFF2C2C34.toInt()
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, base, MomentCard.darken(base, .6f), Shader.TileMode.CLAMP)
        })
        if (cover != null) {
            canvas.drawBitmap(cover.scale(3, 5).scale(WIDTH / 8, HEIGHT / 8).scale(WIDTH, HEIGHT), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        canvas.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x26000000, 0x40000000, 0x8C000000.toInt()), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /** Ketvoice's mark at the foot of a card. */
    fun drawMark(canvas: Canvas) {
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); alpha = 200; textSize = 32f; letterSpacing = .32f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("KETVOICE", WIDTH / 2f, HEIGHT - 150f, brand)
    }

    /** Keeps [card] as the only share card in the cache's exports. */
    suspend fun save(context: Context, card: Bitmap): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        folder.listFiles { file -> file.name.startsWith("card-") }?.forEach { it.delete() }
        val file = File(folder, "card-${System.currentTimeMillis()}.png")
        file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    }

    fun instagramInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(INSTAGRAM, 0); true }.getOrDefault(false)

    /**
     * Opens Instagram's story composer with the card as its background. Instagram may ask apps for a
     * Meta app id there; when its composer cannot be opened, the card goes to Instagram's own share
     * screen instead (which offers Stories). False when neither opened.
     */
    fun shareToStory(context: Context, file: File, track: Track): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.grantUriPermission(INSTAGRAM, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val story = Intent(STORY_ACTION).apply {
            setDataAndType(uri, "image/png")
            setPackage(INSTAGRAM)
            putExtra("source_application", context.packageName)
            putExtra("content_url", ShareLinks.trackUrl(track.id, track.title, track.artist))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(story) }.isSuccess) return true
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; setPackage(INSTAGRAM)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(send) }.isSuccess
    }

    /** The system share sheet with the card and the title's link. */
    fun share(context: Context, file: File, track: Track) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, ShareLinks.trackUrl(track.id, track.title, track.artist))
            clipData = android.content.ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Partager")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END).toString()
}
