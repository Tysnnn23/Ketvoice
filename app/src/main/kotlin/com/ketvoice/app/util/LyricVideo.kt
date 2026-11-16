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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The lyrics video of a Moment (5.9.2): the title's Canvas blurred behind (or its cover's colours
 * flowing when it has no Canvas), the chosen lines — up to four — in a frosted card in the middle,
 * lit word by word in time with the song, the cover and the title under them. Encoded on the phone
 * as an H.264 MP4 in story format, without sound (the stories of most apps add their own music).
 */
object LyricVideo {
    const val WIDTH = 720
    const val HEIGHT = 1280
    const val FPS = 30
    const val MAX_MS = 15_000L
    const val LEAD_MS = 700L
    const val TAIL_MS = 1_300L
    /** The Canvas behind is read ten times a second; the frames between are cross-faded. */
    private const val BACKDROP_STEP_MS = 100L

    /** Where the video starts and ends in the song, around [lines]; [nextMs] is when the line after them starts (-1 unknown). */
    fun window(lines: List<MusicApi.LyricLine>, nextMs: Long): LongRange {
        val first = lines.firstOrNull { it.timeMs >= 0 }?.timeMs ?: 0L
        val lastStart = lines.lastOrNull { it.timeMs >= 0 }?.timeMs ?: first
        val lastWord = lines.lastOrNull()?.words?.maxOfOrNull { if (it.endMs > 0) it.endMs else it.startMs } ?: -1L
        val end = listOf(nextMs.takeIf { it > lastStart }, lastWord.takeIf { it > lastStart }?.plus(400), lastStart + 3_500L)
            .filterNotNull().min() + TAIL_MS
        val start = (first - LEAD_MS).coerceAtLeast(0L)
        return start..min(end, start + MAX_MS)
    }

    /**
     * How far each line is lit at [t] ms in the song, 0..1. A line with word times lights word by
     * word (the share of its letters sung); a line without lights at once when it starts.
     */
    fun lit(lines: List<MusicApi.LyricLine>, nextMs: Long, t: Long): List<Float> = lines.mapIndexed { i, line ->
        val words = line.words.filter { it.text.isNotEmpty() }
        val end = lines.getOrNull(i + 1)?.timeMs?.takeIf { it > line.timeMs } ?: nextMs.takeIf { it > line.timeMs } ?: (line.timeMs + 3_000L)
        when {
            line.timeMs < 0 -> 1f
            t < line.timeMs -> 0f
            words.size < 2 -> 1f
            else -> {
                val total = words.sumOf { it.text.length }.coerceAtLeast(1)
                var sung = 0f
                words.forEachIndexed { w, word ->
                    val wEnd = if (word.endMs > word.startMs) word.endMs else words.getOrNull(w + 1)?.startMs ?: end
                    sung += when {
                        t >= wEnd -> word.text.length.toFloat()
                        t <= word.startMs -> 0f
                        else -> word.text.length * ((t - word.startMs).toFloat() / (wEnd - word.startMs).coerceAtLeast(1))
                    }
                }
                (sung / total).coerceIn(0f, 1f)
            }
        }
    }

    /** The line being sung at [t]: the last one that has started, or -1 before the first. */
    fun active(lines: List<MusicApi.LyricLine>, t: Long): Int = lines.indexOfLast { it.timeMs in 0..t }

    suspend fun render(context: Context, track: Track, lines: List<MusicApi.LyricLine>, nextMs: Long, canvas: String?,
                       accent: Int?, logo: Boolean, onProgress: (Float) -> Unit): File = withContext(Dispatchers.Default) {
        require(lines.isNotEmpty()) { "Aucune ligne choisie" }
        val shown = lines.take(4)
        val window = window(shown, nextMs)
        val duration = window.last - window.first
        val frames = (duration * FPS / 1000).toInt().coerceAtLeast(FPS)
        val coverUrl = ArtworkRepo.coverForTrack(track) ?: track.coverUrl
        val cover = coverUrl?.let { url ->
            runCatching {
                val request = coil.request.ImageRequest.Builder(context).data(url).allowHardware(false).size(512).build()
                (coil.Coil.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()
        }
        val backdrop = canvas?.let { readCanvas(it, duration) } ?: emptyList()
        val flow = cover?.scale(16, 16)?.scale(64, 64)
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        folder.listFiles { f -> f.name.startsWith("lyrics-") }?.forEach { it.delete() }
        val out = File(folder, "lyrics-${System.currentTimeMillis()}.mp4")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Baseline: no reordered frames, so the frames leave the encoder in the order they were drawn.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var written = 0
        var muxing = false
        val info = MediaCodec.BufferInfo()
        fun drain(endOfStream: Boolean, untilFrames: Int) {
            var idle = 0
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Caught up enough (or the encoder is silent for three seconds): go on drawing.
                    if (!endOfStream && written >= untilFrames) return
                    if (++idle > 300) return
                    continue
                }
                idle = 0
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(encoder.outputFormat); muxer.start(); muxing = true
                    continue
                }
                if (index < 0) continue
                val data = encoder.getOutputBuffer(index)
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (data != null && info.size > 0 && !config && muxing) {
                    // Times come from the frame count: the frames are drawn faster than they play.
                    info.presentationTimeUs = written * 1_000_000L / FPS
                    data.position(info.offset); data.limit(info.offset + info.size)
                    muxer.writeSampleData(videoTrack, data, info)
                    written++
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
                if (eos) return
            }
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            val painter = Painter(context, track, shown, nextMs, cover, accent, logo)
            for (frame in 0 until frames) {
                coroutineContext.ensureActive()
                val songMs = window.first + frame * 1000L / FPS
                val c = surface.lockHardwareCanvas()
                try {
                    painter.background(c, backdrop, flow, songMs - window.first, frame)
                    painter.card(c, songMs)
                } finally { surface.unlockCanvasAndPost(c) }
                // Never more than a few frames ahead of the encoder: a full queue would drop frames.
                drain(endOfStream = false, untilFrames = frame - 4)
                if (frame % 6 == 0) onProgress(frame.toFloat() / frames)
            }
            encoder.signalEndOfInputStream()
            drain(endOfStream = true, untilFrames = Int.MAX_VALUE)
            surface.release()
            onProgress(1f)
        } finally {
            runCatching { encoder.stop() }; runCatching { encoder.release() }
            runCatching { if (muxing) muxer.stop() }; runCatching { muxer.release() }
            backdrop.forEach { runCatching { it.recycle() } }
        }
        require(written > 0 && out.length() > 0) { "Vidéo indisponible sur cet appareil" }
        out
    }

    /** One frame of the video at [songMs], drawn into a picture: what a host test looks at. */
    internal fun previewFrame(context: Context, track: Track, lines: List<MusicApi.LyricLine>, nextMs: Long, cover: Bitmap?,
                              accent: Int?, logo: Boolean, songMs: Long): Bitmap {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        val c = Canvas(bitmap)
        val painter = Painter(context, track, lines.take(4), nextMs, cover, accent, logo)
        val start = window(lines.take(4), nextMs).first
        painter.background(c, emptyList(), cover?.scale(16, 16)?.scale(64, 64), songMs - start, ((songMs - start) * FPS / 1000).toInt())
        painter.card(c, songMs)
        return bitmap
    }

    /** Small frames of the Canvas, every [BACKDROP_STEP_MS], looping when the Canvas is shorter than the video. */
    private fun readCanvas(path: String, durationMs: Long): List<Bitmap> {
        val reader = MediaMetadataRetriever()
        return try {
            if (path.startsWith("http")) reader.setDataSource(path, emptyMap()) else reader.setDataSource(path)
            val length = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: return emptyList()
            val steps = (min(durationMs, length) / BACKDROP_STEP_MS).toInt().coerceIn(1, 150)
            (0 until steps).mapNotNull { i ->
                reader.getScaledFrameAtTime(i * BACKDROP_STEP_MS * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 54, 96)
                    ?.let { frame -> frame.scale(27, 48).also { if (it !== frame) frame.recycle() } }
            }
        } catch (_: Exception) { emptyList() } finally { runCatching { reader.release() } }
    }

    private class Painter(context: Context, val track: Track, val lines: List<MusicApi.LyricLine>, val nextMs: Long,
                          val cover: Bitmap?, accent: Int?, val logo: Boolean) {
        private val base = accent ?: 0xFF2C2C34.toInt()
        private val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        private val lyric = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = if (lines.sumOf { it.text.length } > 150) 38f else 44f; typeface = bold; color = 0xFFFFFFFF.toInt()
        }
        private val cardWidth = WIDTH - 120
        private val textWidth = cardWidth - 72
        private val layouts = lines.map { line ->
            StaticLayout.Builder.obtain(line.text, 0, line.text.length, lyric, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f).setMaxLines(3)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        }
        private val gap = 22f
        private val textHeight = layouts.sumOf { it.height } + gap * (layouts.size - 1)
        private val footer = 112f
        private val cardHeight = 64f + textHeight + footer
        private val cardTop = (HEIGHT - cardHeight) / 2f
        private val cardLeft = (WIDTH - cardWidth) / 2f

        fun background(c: Canvas, frames: List<Bitmap>, flow: Bitmap?, sinceStartMs: Long, frame: Int) {
            c.drawColor(darken(base, .4f))
            val dst = RectF(-40f, -40f, WIDTH + 40f, HEIGHT + 40f)
            if (frames.isNotEmpty()) {
                val pos = sinceStartMs.toFloat() / BACKDROP_STEP_MS
                val a = frames[pos.toInt() % frames.size]; val b = frames[(pos.toInt() + 1) % frames.size]
                val mix = pos - pos.toInt()
                smooth.alpha = 255; c.drawBitmap(a, null, dst, smooth)
                smooth.alpha = (mix * 255).toInt(); c.drawBitmap(b, null, dst, smooth)
                smooth.alpha = 255
            } else if (flow != null) {
                // The cover's colours, three blurred copies turning slowly, as in the player.
                val seconds = frame / FPS.toFloat()
                for (i in 0 until 3) {
                    val angle = seconds * 360f / floatArrayOf(18f, -24f, 31f)[i]
                    val size = max(WIDTH, HEIGHT) * floatArrayOf(2.1f, 1.7f, 1.35f)[i]
                    val cx = WIDTH / 2f + cos(seconds / (6f + i * 3)) * WIDTH * .12f
                    val cy = HEIGHT / 2f + sin(seconds / (7f + i * 2)) * HEIGHT * .08f
                    smooth.alpha = intArrayOf(255, 190, 150)[i]
                    c.withRotation(angle, cx, cy) { drawBitmap(flow, null, RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), smooth) }
                }
                smooth.alpha = 255
            }
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0x33000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
            })
        }

        fun card(c: Canvas, songMs: Long) {
            val rect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
            val shape = Path().apply { addRoundRect(rect, 42f, 42f, Path.Direction.CW) }
            c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF })
            c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x33FFFFFF })
            val lit = lit(lines, nextMs, songMs)
            val active = active(lines, songMs)
            var y = cardTop + 40f
            layouts.forEachIndexed { i, layout ->
                val left = cardLeft + 36f
                c.withTranslation(left, y) {
                    // The line being sung stands a little forward, the others wait in a soft white.
                    val sungBefore = i < active
                    lyric.color = 0xFFFFFFFF.toInt(); lyric.alpha = if (sungBefore) 200 else 92
                    layout.draw(this)
                    val share = lit[i]
                    if (share > 0f && !sungBefore) {
                        lyric.alpha = 255
                        val chars = (layout.text.length * share).toInt()
                        for (row in 0 until layout.lineCount) {
                            val rowStart = layout.getLineStart(row); val rowEnd = layout.getLineEnd(row)
                            if (chars <= rowStart) break
                            val right = if (chars >= rowEnd) layout.getLineRight(row) else layout.getPrimaryHorizontal(chars)
                            withClip(layout.getLineLeft(row) - 4f, layout.getLineTop(row).toFloat(), right + 2f, layout.getLineBottom(row).toFloat()) {
                                layout.draw(this)
                            }
                        }
                    }
                }
                y += layout.height + gap
            }
            // The cover and the title under the words; the Ketvoice name when wanted.
            val footTop = cardTop + cardHeight - footer + 12f
            c.drawLine(cardLeft + 24f, footTop - 6f, cardLeft + cardWidth - 24f, footTop - 6f, Paint().apply { color = 0x22FFFFFF; strokeWidth = 2f })
            val thumb = RectF(cardLeft + 30f, footTop + 14f, cardLeft + 30f + 64f, footTop + 78f)
            if (cover != null) c.withClip(Path().apply { addRoundRect(thumb, 12f, 12f, Path.Direction.CW) }) { drawBitmap(cover, null, thumb, smooth) }
            val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 26f; typeface = bold }
            val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 22f }
            val textLeft = thumb.right + 18f
            val room = cardLeft + cardWidth - 30f - textLeft
            c.drawText(ellipsize(track.title, name, room), textLeft, footTop + 42f, name)
            c.drawText(ellipsize(track.artist, sub, room), textLeft, footTop + 72f, sub)
            if (logo) {
                val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 24f; typeface = bold; letterSpacing = .25f }
                c.drawText("KETVOICE", (WIDTH - brand.measureText("KETVOICE")) / 2f, HEIGHT - 90f, brand)
            }
        }

        private fun ellipsize(text: String, paint: TextPaint, width: Float) =
            android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END).toString()

        private fun darken(color: Int, f: Float): Int {
            val r = ((color shr 16 and 255) * f).toInt(); val g = ((color shr 8 and 255) * f).toInt(); val b = ((color and 255) * f).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    fun share(context: Context, file: File, track: Track) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, ShareLinks.trackUrl(track.id, track.title, track.artist))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Partager ce moment")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
