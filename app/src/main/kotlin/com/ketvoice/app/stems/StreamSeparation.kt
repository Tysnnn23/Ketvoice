@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.stems

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import com.ketvoice.app.automix.AnalysisDepth
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * The voice level on streamed titles (7.6, owner: « Rends possible l'écoute de voix en streaming »). A whole title is
 * separated about a minute at a time; on a stream, the minute under the playhead goes first, then the ones after it,
 * then the start: each piece done is taken up live by the voice stage ([StemStore.savePartial]), the whole result is
 * stored like a download's so that a replay is instant. On battery the work rests between pieces as much as the
 * analysis mode asks; a stream of several files (DASH) is read from the audio rendition the player uses.
 */
internal object StreamSeparation {
    /** Pieces start this long before the playhead, so that the sound heard next is covered first. */
    const val LEAD_MS = 3_000L
    /** Share of the progress a DASH stream's copy takes before its separation starts. */
    const val COPY_SHARE = .2f
    /** A DASH audio rendition larger than this is not copied (a FLAC title of an hour is about 300 MB). */
    const val MAX_COPY_BYTES = 160L * 1024 * 1024

    /** The order pieces are separated in: from [start] to the end, then from the beginning. */
    fun order(pieces: Int, start: Int): List<Int> {
        if (pieces <= 0) return emptyList()
        val s = start.coerceIn(0, pieces - 1)
        return (s until pieces) + (0 until s)
    }

    /** The piece that holds [positionMs] (a little before it) when the title is cut in [pieces] of [perMs]. */
    fun pieceAt(positionMs: Long, perMs: Long, pieces: Int): Int =
        if (pieces <= 0 || perMs <= 0) 0 else ((positionMs - LEAD_MS).coerceAtLeast(0L) / perMs).toInt().coerceIn(0, pieces - 1)

    /**
     * How long the work rests after a piece that took [workMs]: never while charging nor after the piece under the
     * playhead; on battery Light rests as long as it worked (half the time busy), Balanced half, Maximum a quarter.
     */
    fun restMs(workMs: Long, depth: AnalysisDepth, charging: Boolean, first: Boolean): Long {
        if (charging || first || workMs <= 0) return 0L
        val share = when (depth) { AnalysisDepth.Light -> 1.0; AnalysisDepth.Balanced -> .5; AnalysisDepth.Maximum -> .25 }
        return (workMs * share).toLong().coerceAtMost(30_000L)
    }

    /**
     * Runs the pieces of a title of [total] frames, cut in pieces of [per], in [order] from [startPiece]. [work] separates
     * frames first..last (false stops everything); after each piece of the stretch that runs on from the start piece,
     * [publish] hears the frames covered without a gap (from, until). [rest] is given the pause after each piece.
     * True when every piece was done.
     */
    fun run(total: Int, per: Int, startPiece: Int, goOn: () -> Boolean, now: () -> Long, rest: (Long, Boolean) -> Unit,
            work: (Int, Int) -> Boolean, publish: (Int, Int) -> Unit, progress: (Float) -> Unit): Boolean {
        if (total <= 0 || per <= 0) return false
        val pieces = (total + per - 1) / per
        var done = 0
        var forward = true
        val coveredFrom = startPiece.coerceIn(0, pieces - 1) * per
        for ((n, piece) in order(pieces, startPiece).withIndex()) {
            if (!goOn()) return false
            val first = piece * per
            val last = minOf(total, first + per)
            if (first < coveredFrom) forward = false
            val began = now()
            if (!work(first, last)) return false
            done += last - first
            progress(done.toFloat() / total)
            if (forward && done < total) publish(coveredFrom, last)
            if (done < total) rest(now() - began, n == 0)
        }
        return true
    }

    // ---- DASH: the audio rendition the player uses ----

    /** Where to read a DASH title's sound: one file ([remote], read in place), or a copy of its segments ([file]). */
    class DashSource(val remote: Uri?, val file: File?, val durationMs: Long)

    /** The audio representation to read: the one the player chose ([preferredId]), else the richest stereo one. */
    internal fun pick(manifest: DashManifest, preferredId: String?): Representation? {
        if (manifest.dynamic || manifest.periodCount != 1) return null
        val audio = (0 until manifest.periodCount).flatMap { manifest.getPeriod(it).adaptationSets }
            .filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { it.representations }
            .filter { it.format.drmInitData == null && (it.format.channelCount == Format_NO_VALUE || it.format.channelCount == 2) }
        return audio.firstOrNull { preferredId != null && it.format.id == preferredId } ?: audio.maxByOrNull { it.format.bitrate }
    }
    private const val Format_NO_VALUE = androidx.media3.common.Format.NO_VALUE

    private fun allowed(uri: Uri): Boolean = uri.scheme.equals("https", true) || uri.scheme.equals("file", true)

    /**
     * Reads the manifest at [manifest] through [factory] (the player's cache first) and makes [DashSource] for its audio:
     * a rendition in one file is read in place; one in segments is copied, in order, into [dir]. Null when it cannot be read.
     */
    fun dash(factory: DataSource.Factory, manifest: Uri, preferredId: String?, dir: File, progress: (Float) -> Unit, goOn: () -> Boolean): DashSource? {
        val bytes = read(factory, DataSpec(manifest), 1_048_576) ?: return null
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true) || Regex("<(?:[A-Za-z0-9_-]+:)?ContentProtection\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val parsed = runCatching { DashManifestParser().parse(manifest, ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val rep = pick(parsed, preferredId) ?: return null
        val base = rep.baseUrls.firstOrNull()?.url ?: return null
        val durationUs = parsed.getPeriodDurationUs(0)
        val durationMs = if (durationUs == C.TIME_UNSET) 0L else durationUs / 1000L
        if (rep is Representation.SingleSegmentRepresentation) return Uri.parse(base).takeIf(::allowed)?.let { DashSource(it, null, durationMs) }
        val init = rep.initializationUri
        val index = rep.index ?: return null
        if (durationUs == C.TIME_UNSET || durationUs <= 0) return null
        val count = index.getSegmentCount(durationUs).toInt()
        if (count !in 1..10_000) return null
        val parts = listOfNotNull(init) + (0 until count).map { index.getSegmentUrl(index.firstSegmentNum + it) }
        dir.mkdirs()
        val file = File(dir, "${java.util.UUID.randomUUID()}.mp4")
        var size = 0L
        try {
            file.outputStream().buffered().use { out ->
                for ((i, part) in parts.withIndex()) {
                    if (!goOn()) { file.delete(); return null }
                    val uri = part.resolveUri(base)
                    if (!allowed(uri)) { file.delete(); return null }
                    val chunk = read(factory, DataSpec.Builder().setUri(uri).setPosition(part.start).setLength(part.length).build(),
                        (MAX_COPY_BYTES - size).toInt().coerceAtLeast(1)) ?: run { file.delete(); return null }
                    size += chunk.size
                    out.write(chunk)
                    progress((i + 1f) / parts.size)
                }
            }
        } catch (_: IOException) { file.delete(); return null }
        return DashSource(null, file, durationMs)
    }

    private fun read(factory: DataSource.Factory, spec: DataSpec, limit: Int): ByteArray? {
        val source = factory.createDataSource()
        return try {
            source.open(spec)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                if (out.size() + n > limit) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } catch (_: IOException) { null } finally { runCatching { source.close() } }
    }

    /** Where the sound of a DASH title is read from in this process: the download's own cache, or the player's. */
    fun factoryFor(context: Context, trackId: String): DataSource.Factory =
        if (com.ketvoice.app.data.Downloader.itemFor(trackId)?.dashManifestUrl != null) com.ketvoice.app.player.OfflineDashStore.get(context).playbackFactory(trackId)
        else com.ketvoice.app.player.AudioCache.factory(context)
}
