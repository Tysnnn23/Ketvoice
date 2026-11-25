package com.ketvoice.desktop.player

import com.ketvoice.app.data.CustomSource
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.parseManifest
import com.ketvoice.desktop.audio.AudioEvent
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.FfmpegDecoder
import com.ketvoice.desktop.audio.HttpRangeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * « Tester » on a source (Windows 2.0's « Sources »): a real search, then the first result really opened and decoded,
 * to say whether the source plays and in what quality (codec, rate, bits, bitrate, read from the audio itself).
 */
object SourceTester {
    data class Result(
        val searchOk: Boolean,
        val results: Int = 0,
        val searchMs: Long = 0,
        val playable: Boolean = false,
        val format: AudioEvent.Format? = null,
        /** What the manifest says it serves beyond audio (lyrics, canvas, cover). */
        val extras: Set<String> = emptySet(),
        /** Why it failed (French text), or null. */
        val problem: String? = null,
    )

    /** How a format reads in the status line: « FLAC · 44,1 kHz · 16 bits » or « AAC · 256 kbit/s ». */
    fun describe(f: AudioEvent.Format): String {
        val khz = "%.1f".format(java.util.Locale.FRANCE, f.sampleRate / 1000.0).removeSuffix(",0")
        return listOfNotNull(f.codec.uppercase(), "$khz kHz", f.bitDepth.takeIf { it > 0 }?.let { "$it bits" },
            f.bitrate.takeIf { it > 0 && !f.lossless }?.let { "${it / 1000} kbit/s" }).joinToString(" · ")
    }

    /** Opens the stream behind [stream] and reads its real format; null when it does not decode. */
    internal var probe: suspend (MusicApi.StreamInfo) -> AudioEvent.Format? = { stream ->
        val source = DesktopStreams.toSource(stream)
        if (source == null) null else withContext(Dispatchers.IO) { decodeFormat(source) }
    }

    private fun decodeFormat(source: AudioSource.Url): AudioEvent.Format? {
        val bytes = HttpRangeSource(source.url, source.headers)
        return try { FfmpegDecoder(bytes).use { it.format } } catch (_: Exception) { null } finally { bytes.close() }
    }

    suspend fun test(source: CustomSource, query: String = "Daft Punk"): Result {
        val manifest = parseManifest(source.manifestUrl, source.manifestJson)
            ?: return Result(false, problem = "Manifeste illisible : actualisez la source")
        val extras = setOfNotNull(manifest.resolvedLyrics?.let { "lyrics" }, manifest.resolvedCanvas?.let { "canvas" },
            manifest.resolvedCover?.let { "cover" })
        val started = System.currentTimeMillis()
        val found: List<Track> = withTimeoutOrNull(30_000) { runCatching { CustomSources.search(source, query) }.getOrDefault(emptyList()) }.orEmpty()
        val ms = System.currentTimeMillis() - started
        if (found.isEmpty()) return Result(false, 0, ms, extras = extras, problem = "La recherche de test n'a rien renvoyé")
        for (track in found.take(3)) {
            val stream = withTimeoutOrNull(40_000) { runCatching { CustomSources.streamFrom(source.copy(enabled = true), track, false) }.getOrNull() } ?: continue
            val format = withTimeoutOrNull(30_000) { runCatching { probe(stream) }.getOrNull() } ?: continue
            return Result(true, found.size, ms, playable = true, format = format, extras = extras)
        }
        return Result(true, found.size, ms, extras = extras, problem = "Aucun résultat n'a pu être lu")
    }
}
