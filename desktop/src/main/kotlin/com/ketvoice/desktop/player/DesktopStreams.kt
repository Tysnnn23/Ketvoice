package com.ketvoice.desktop.player

import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.StreamResolver
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.StreamCache
import com.ketvoice.desktop.library.DesktopDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A [Track] to what the engine reads: the downloaded file first, then the app's own sources in their
 * order (`StreamResolver`: Ketvoice add-ons, YouTube Music through InnerTube, custom sources). The
 * engine range-reads single files, so a DASH offer becomes its audio rendition's direct address.
 */
object DesktopStreams {
    /** Sent with every stream request (googlevideo and the add-ons accept a desktop browser). */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** The first candidate of the sources left, as the shared resolver ranks them for playback. */
    internal var candidate: suspend (Track, Set<String>) -> StreamResolver.Candidate? = { track, excluded ->
        StreamResolver.candidates(track, download = false, lossless = AppSettings.wifiQuality.value == "Sans perte",
            race = false, excludedSources = excluded).firstOrNull()
    }
    internal var downloaded: (Track) -> File? = { DesktopDownloader.fileOf(it.id) }
    internal var fetchText: suspend (String) -> String? = ::httpText

    /** The quality tier a title is cached under (« Sans perte » and the others are separate files). */
    internal var tier: () -> String = { AppSettings.wifiQuality.value }

    suspend fun source(track: Track): AudioSource? {
        downloaded(track)?.takeIf { it.isFile }?.let { return AudioSource.LocalFile(it.path) }
        track.streamUrl?.takeIf { it.startsWith("file:") }?.let { uri ->
            runCatching { File(URI(uri)) }.getOrNull()?.takeIf { it.isFile }?.let { return AudioSource.LocalFile(it.path) }
        }
        // The smart cache: a title all on disk, or warmed ahead, starts without asking its sources first.
        if (track.id.isNotBlank()) StreamCache.ready(track.id, tier(), HEADERS, refresh = { refreshed(track) })?.let { return it }
        return resolved(track)
    }

    /** The title's sources asked in order; the address is cached under the title's name. */
    private suspend fun resolved(track: Track): AudioSource.Url? {
        val key = track.id.takeIf { it.isNotBlank() }?.let { StreamCache.key(it, tier()) }
        val excluded = HashSet<String>()
        // A source whose offer cannot be read as one file gives way to the next one.
        repeat(8) {
            val found = candidate(track, excluded) ?: return null
            toSource(found.stream, key)?.let { return it }
            if (!excluded.add(found.sourceId)) return null
        }
        return null
    }

    /** A fresh address for [track] (called from the cache's fetcher thread when a kept one no longer answers). */
    private fun refreshed(track: Track): String? = runCatching { runBlocking { resolved(track)?.url } }.getOrNull()

    /** One stream offer as the engine reads it, or null when it holds no single audio file. */
    internal suspend fun toSource(stream: MusicApi.StreamInfo, key: String? = null): AudioSource.Url? {
        if (!stream.isDash) return stream.url.takeIf(::web)?.let { url(it, key) }
        stream.downloadUrl?.takeIf { it != stream.url && web(it) }?.let { return url(it, key) }
        val manifest = fetchText(stream.url) ?: return null
        return DashAudio.directUrl(manifest, stream.url)?.takeIf(::web)?.let { url(it, key) }
    }

    private val HEADERS = mapOf("User-Agent" to USER_AGENT)
    private fun web(address: String) = address.startsWith("https://") || address.startsWith("http://")
    private fun url(address: String, key: String? = null) = AudioSource.Url(address, HEADERS, cacheKey = key)

    private suspend fun httpText(address: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(address).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8_000; c.readTimeout = 12_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                if (c.responseCode !in 200..299 || c.contentLengthLong > 4L * 1024 * 1024) null
                else c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }.getOrNull()
    }
}

/** The audio rendition of a DASH manifest that is one whole file (a `BaseURL`, no segments). */
internal object DashAudio {
    fun directUrl(manifest: String, manifestUrl: String): String? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(manifest.byteInputStream()).documentElement
        fun children(e: org.w3c.dom.Element, name: String) = (0 until e.childNodes.length).map { e.childNodes.item(it) }
            .filterIsInstance<org.w3c.dom.Element>().filter { (it.localName ?: it.tagName).substringAfter(':') == name }
        fun base(e: org.w3c.dom.Element, parent: String): String =
            children(e, "BaseURL").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { URI(parent).resolve(it).toString() } ?: parent
        val top = base(root, manifestUrl)
        children(root, "Period").flatMap { period ->
            val periodBase = base(period, top)
            children(period, "AdaptationSet").flatMap { set ->
                val setAudio = set.getAttribute("mimeType").startsWith("audio") || set.getAttribute("contentType") == "audio"
                val setBase = base(set, periodBase)
                children(set, "Representation").filter { rep ->
                    (setAudio || rep.getAttribute("mimeType").startsWith("audio")) &&
                        children(rep, "BaseURL").isNotEmpty() && children(rep, "SegmentTemplate").isEmpty() &&
                        children(rep, "SegmentList").isEmpty() && children(set, "SegmentTemplate").isEmpty()
                }.map { rep -> (rep.getAttribute("bandwidth").toLongOrNull() ?: 0L) to base(rep, setBase) }
            }
        }.maxByOrNull { it.first }?.second
    }.getOrNull()
}
