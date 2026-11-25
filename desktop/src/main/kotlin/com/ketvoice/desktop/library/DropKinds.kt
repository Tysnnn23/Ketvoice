package com.ketvoice.desktop.library

import com.ketvoice.app.smartlib.PlaylistImport
import com.ketvoice.app.smartlib.YouTubeImport
import java.io.File
import java.net.URI

/**
 * What was dropped on the window (Windows 2.0 « Glisser-déposer partout »), pure: a playlist link of a service the import
 * reads, a Ketvoice share link, a source manifest, another link of a music service, audio files or folders, a lyrics
 * file, or nothing usable.
 */
object DropKinds {
    sealed interface Drop
    /** A playlist the import page reads ([PlaylistImport] or [YouTubeImport]). */
    data class PlaylistLink(val url: String) : Drop
    /** A Ketvoice share link (`listen.html?track=…`, `ketvoice://listen?…`). */
    data class ShareLink(val url: String, val trackId: String?, val title: String?, val artist: String?, val playlist: String?) : Drop
    /** A custom source's `manifest.json` (Settings › Sources adds it). */
    data class ManifestLink(val url: String) : Drop
    /** A title or album of a music service: looked up by its name. */
    data class ServiceLink(val url: String, val host: String) : Drop
    data class AudioFiles(val files: List<File>, val folders: List<File>) : Drop
    data class LyricsFile(val file: File) : Drop
    data object Nothing : Drop

    val LYRICS_EXTENSIONS = setOf("lrc", "ttml", "srt", "vtt", "ass", "elrc", "txt")
    private val SERVICE_HOSTS = setOf("open.spotify.com", "deezer.com", "www.deezer.com", "music.apple.com", "tidal.com", "listen.tidal.com",
        "soundcloud.com", "m.soundcloud.com") + YouTubeImport.hosts

    /** Dropped text: its first link decides. */
    fun ofText(text: String): Drop {
        val url = Regex("""(?:https?|ketvoice)://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '>', '"', '\'') ?: return Nothing
        val uri = runCatching { URI(url) }.getOrNull() ?: return Nothing
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        return when {
            uri.scheme == "ketvoice" || path.endsWith("/listen.html") -> share(url, uri)
            uri.scheme == "https" && path.endsWith("manifest.json") -> ManifestLink(url)
            uri.scheme != "https" && uri.scheme != "http" -> Nothing
            YouTubeImport.isLink(url) || runCatching { PlaylistImport.serviceOf(url) }.isSuccess && playlistLike(path) -> PlaylistLink(url)
            host in SERVICE_HOSTS -> ServiceLink(url, host)
            else -> Nothing
        }
    }

    private fun playlistLike(path: String) = path.contains("/playlist") || path.contains("/sets/")

    private fun share(url: String, uri: URI): Drop {
        val q = uri.rawQuery.orEmpty().split('&').mapNotNull { p ->
            val k = p.substringBefore('='); val v = p.substringAfter('=', "")
            if (k.isBlank()) null else k to runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }.toMap()
        return ShareLink(url, q["track"]?.takeIf { it.isNotBlank() }, q["title"], q["artist"], q["playlist"]?.takeIf { it.isNotBlank() })
    }

    /** Dropped files: a single lyrics file, else the audio files and folders among them. */
    fun ofFiles(files: List<File>): Drop {
        if (files.isEmpty()) return Nothing
        val lyrics = files.singleOrNull()?.takeIf { it.isFile && it.extension.lowercase() in LYRICS_EXTENSIONS }
        if (lyrics != null) return LyricsFile(lyrics)
        val audio = files.filter { it.isFile && it.extension.lowercase() in LocalFolders.EXTENSIONS }
        val folders = files.filter { it.isDirectory }
        return if (audio.isEmpty() && folders.isEmpty()) Nothing else AudioFiles(audio, folders)
    }
}
