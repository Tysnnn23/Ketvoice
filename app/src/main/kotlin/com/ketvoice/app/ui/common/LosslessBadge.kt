package com.ketvoice.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.io.File

/**
 * What the app really knows of a title's quality, in Apple Music's two steps: « Lossless » (up to 48 kHz)
 * and « Hi-Res Lossless » (above 48 kHz). Only a decoder or a file's own header says so; a provider's label
 * never does (Player.decodedAudioInfo says the same).
 */
internal enum class AudioGrade(val label: String) { Lossless("Lossless"), HiRes("Hi-Res Lossless") }

internal object AudioGrades {
    private val losslessMimes = setOf("audio/flac", "audio/x-flac", "audio/alac", "audio/raw", "audio/wav", "audio/x-wav")

    /** The grade of what the decoder plays: a lossless codec, Hi-Res once its rate passes 48 kHz. */
    fun ofDecoded(mime: String?, sampleRate: Int): AudioGrade? {
        if (mime?.lowercase() !in losslessMimes) return null
        return if (sampleRate > 48_000) AudioGrade.HiRes else AudioGrade.Lossless
    }

    /** The sample rate a FLAC file states in its STREAMINFO block, from its first 18 bytes; null when it is not one. */
    fun flacSampleRate(head: ByteArray): Int? {
        if (head.size < 21 || head[0] != 'f'.code.toByte() || head[1] != 'L'.code.toByte() ||
            head[2] != 'a'.code.toByte() || head[3] != 'C'.code.toByte()) return null
        // fLaC, a 4-byte block header (type 0 = STREAMINFO), then 10 bytes of block and frame sizes.
        if (head[4].toInt() and 0x7F != 0) return null
        val b = { i: Int -> head[i].toInt() and 0xFF }
        val rate = (b(18) shl 12) or (b(19) shl 4) or (b(20) shr 4)
        return rate.takeIf { it > 0 }
    }

    /** A saved file's grade: its FLAC header when it has one, else the stored format (« FLAC · Sans perte », « WAV … »). */
    fun ofFile(file: File, format: String): AudioGrade? {
        val head = runCatching { file.inputStream().use { input -> ByteArray(21).also { if (input.read(it) < 21) return@runCatching null } } }.getOrNull()
        val rate = head?.let(::flacSampleRate)
        if (rate != null) return if (rate > 48_000) AudioGrade.HiRes else AudioGrade.Lossless
        return if (com.ketvoice.app.ui.sub.isLosslessFormat(format)) AudioGrade.Lossless else null
    }

    private val _saved = MutableStateFlow<Map<String, AudioGrade>>(emptyMap())
    /** The grade of each saved title, by id, read off the main thread whenever the downloads change. */
    val saved: StateFlow<Map<String, AudioGrade>> = _saved
    private val read = HashMap<String, Pair<Long, AudioGrade?>>()

    private val following = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Starts, once, reading the grades again whenever the downloads change (the latest list only). */
    fun follow() {
        if (!following.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            Downloader.items.collectLatest { runCatching { refresh(it) } }
        }
    }

    internal suspend fun refresh(items: List<Downloader.Item>) = withContext(Dispatchers.IO) {
        val out = HashMap<String, AudioGrade>()
        synchronized(read) {
            for (item in items) {
                val stamp = item.file.lastModified()
                val known = read[item.file.path]?.takeIf { it.first == stamp }
                val grade = known?.second ?: ofFile(item.file, item.format).also { read[item.file.path] = stamp to it }
                if (grade != null) out[item.track.id] = grade
            }
        }
        _saved.value = out
    }
}

/** The grade of a saved title: one collector per row, of its own entry only (SmoothnessHostTest). */
@Composable
internal fun rememberSavedGrade(trackId: String): AudioGrade? {
    androidx.compose.runtime.SideEffect { AudioGrades.follow() }
    val grade by remember(trackId) { AudioGrades.saved.pick { it[trackId] } }.collectPick()
    return grade
}

/**
 * The grade of the title playing: its saved file's header first, else what the decoder reports, asked
 * again for a few seconds after each change of title (the format arrives once the stream is open).
 */
@Composable
internal fun rememberPlayingGrade(trackId: String?): AudioGrade? {
    val saved = trackId?.let { rememberSavedGrade(it) }
    val decoded by produceState<AudioGrade?>(null, trackId) {
        value = null
        if (trackId == null) return@produceState
        repeat(12) {
            val format = com.ketvoice.app.player.Player.decodedAudioFormat()
            val grade = format?.let { AudioGrades.ofDecoded(it.sampleMimeType, it.sampleRate) }
            value = grade
            if (format != null) return@produceState
            delay(1_000)
        }
    }
    return saved ?: decoded
}

/**
 * Apple Music's quality mark, drawn here: a small capsule outlined in [tint] with a waveform glyph and the
 * word. Never shown without a [grade].
 */
@Composable
internal fun LosslessBadge(grade: AudioGrade?, modifier: Modifier = Modifier, tint: Color = androidx.compose.ui.graphics.Color.Unspecified,
                           compact: Boolean = false) {
    grade ?: return
    val ink = if (tint == Color.Unspecified) com.ketvoice.app.theme.LocalTheme.current.secondary else tint
    val shape = RoundedCornerShape(5.dp)
    Row(modifier.border(.8.dp, ink.copy(alpha = .7f), shape).padding(horizontal = if (compact) 4.dp else 5.dp, vertical = 1.dp)
        .semantics { contentDescription = com.ketvoice.app.data.AppText.t(grade.label) }.testTag("lossless-badge"),
        verticalAlignment = Alignment.CenterVertically) {
        WaveGlyph(ink, Modifier.size(if (compact) 8.dp else 10.dp))
        Text(if (compact && grade == AudioGrade.HiRes) "Hi-Res" else grade.label, color = ink,
            fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .1.sp, maxLines = 1,
            modifier = Modifier.padding(start = 3.dp))
    }
}

/** Five rounded bars of a waveform, the tallest in the middle. */
@Composable
private fun WaveGlyph(ink: Color, modifier: Modifier) = Canvas(modifier) {
    val heights = floatArrayOf(.34f, .66f, 1f, .66f, .34f)
    val step = size.width / heights.size
    val stroke = step * .56f
    heights.forEachIndexed { i, h ->
        val x = step * (i + .5f)
        val half = size.height * h / 2f
        drawLine(ink, Offset(x, size.height / 2f - half + stroke / 2f), Offset(x, size.height / 2f + half - stroke / 2f),
            strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
