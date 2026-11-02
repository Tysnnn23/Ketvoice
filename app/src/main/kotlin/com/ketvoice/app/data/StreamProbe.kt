package com.ketvoice.app.data

import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URL

/** A JSON URL is not success: require actual audio bytes, with bounded HTTPS redirects. */
internal object StreamProbe {
    suspend fun verified(stream: MusicApi.StreamInfo, forDownload: Boolean = false): MusicApi.StreamInfo? {
        val urls = if (forDownload) listOfNotNull(stream.downloadUrl, stream.url).distinct() else listOf(stream.url)
        for (initial in urls) {
            var current = initial
            try {
                for (hop in 0..4) {
                    if (!current.startsWith("https://")) break
                    var next: String? = null
                    val format = CancellableConnection.use({ URL(current).openConnection() as HttpURLConnection }) { c ->
                        c.instanceFollowRedirects = false; c.connectTimeout = 3500; c.readTimeout = 4500
                        c.setRequestProperty("Range", "bytes=0-4095")
                        c.setRequestProperty("Accept-Encoding", "identity")
                        c.setRequestProperty("User-Agent", "Mozilla/5.0")
                        val status = c.responseCode
                        if (status in listOf(301, 302, 303, 307, 308)) {
                            next = c.getHeaderField("Location")?.let { URL(URL(current), it).toString() }; null
                        } else if (status in 200..299) {
                            val head = ByteArray(64); var read = 0
                            c.inputStream.use { input -> while (read < head.size) { val n = input.read(head, read, head.size - read); if (n < 0) break; read += n } }
                            detect(head.copyOf(read), stream.format)
                        } else null
                    }
                    if (format != null) return stream.copy(format = format,
                        url = if (forDownload) stream.url else current,
                        downloadUrl = if (forDownload) current else stream.downloadUrl)
                    current = next ?: break
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A failed endpoint must not win the race. */ }
        }
        return null
    }
    internal fun detect(head: ByteArray, declared: String): String? {
        if (!DownloadValidation.audio(head)) return null
        val ascii = String(head, Charsets.ISO_8859_1)
        return when {
            ascii.startsWith("fLaC") -> "flac"
            ascii.length >= 12 && ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WAVE" -> "wav"
            ascii.startsWith("OggS") -> if (declared.contains("vorbis", true)) "ogg" else "opus"
            ascii.length >= 8 && ascii.substring(4, 8) == "ftyp" -> "aac"
            ascii.startsWith("ID3") -> "mp3"
            (head[0].toInt() and 255) == 255 -> if ((head[1].toInt() and 0xF6) == 0xF0) "aac" else "mp3"
            else -> null
        }
    }
}
