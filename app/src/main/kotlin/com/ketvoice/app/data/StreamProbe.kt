package com.ketvoice.app.data

import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Audio verification helper. It is **not** called on the playback or download hot
 * path any more: pre-requesting a URL can consume a one-shot CDN token and hang
 * the player. The decision table below is kept (and tested) so a future opt-in
 * check can never reject a stream for a CDN 403/429, an empty read or an unknown
 * container; only 401/404/410/451 or a textual error page counts as unavailable.
 */
internal object StreamProbe {
    internal enum class Verdict { AUDIO, REJECTED, UNKNOWN, REDIRECT }

    /** Pure decision table, unit-tested: only gone/expired or textual content reject. */
    internal fun classify(status: Int, head: ByteArray, declared: String): Pair<Verdict, String?> = when {
        status in listOf(301, 302, 303, 307, 308) -> Verdict.REDIRECT to null
        status in 200..299 -> {
            val format = detect(head, declared)
            when {
                format != null -> Verdict.AUDIO to format
                looksTextual(head) -> Verdict.REJECTED to null
                else -> Verdict.UNKNOWN to null
            }
        }
        status in setOf(401, 404, 410, 451) -> Verdict.REJECTED to null
        else -> Verdict.UNKNOWN to null
    }

    suspend fun verified(stream: MusicApi.StreamInfo, forDownload: Boolean = false): MusicApi.StreamInfo? {
        val urls = if (forDownload) listOfNotNull(stream.downloadUrl, stream.url).distinct() else listOf(stream.url)
        var unknown = false
        for (initial in urls) {
            var current = initial
            try {
                for (hop in 0..4) {
                    if (!current.startsWith("https://")) break
                    var next: String? = null
                    var verdict = Verdict.UNKNOWN
                    var format: String? = null
                    try {
                        CancellableConnection.use({ URL(current).openConnection() as HttpURLConnection }) { c ->
                            c.instanceFollowRedirects = false
                            c.connectTimeout = 3500
                            c.readTimeout = 4500
                            c.setRequestProperty("Range", "bytes=0-4095")
                            c.setRequestProperty("Accept-Encoding", "identity")
                            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                            val status = c.responseCode
                            if (status in listOf(301, 302, 303, 307, 308)) {
                                next = c.getHeaderField("Location")?.let { URL(URL(current), it).toString() }
                                verdict = Verdict.REDIRECT
                            } else {
                                val head = if (status in 200..299) readHead(c) else ByteArray(0)
                                val (decision, detected) = classify(status, head, stream.format)
                                verdict = decision
                                format = detected
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        verdict = Verdict.UNKNOWN
                    }
                    if (verdict == Verdict.AUDIO) {
                        return stream.copy(
                            format = format ?: stream.format,
                            quality = if (StreamQuality.rank(stream) <= 128 && stream.quality != "YouTube Music")
                                "Vérifié · ${stream.quality}" else stream.quality,
                            url = if (forDownload) stream.url else current,
                            downloadUrl = if (forDownload) current else stream.downloadUrl
                        )
                    }
                    if (verdict == Verdict.REJECTED) break
                    unknown = true
                    current = next ?: break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unknown = true
            }
        }
        return stream.takeIf { unknown }
    }

    private fun readHead(c: HttpURLConnection): ByteArray {
        val buffer = ByteArray(64)
        var read = 0
        try {
            c.inputStream.use { input ->
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
            }
        } catch (_: Exception) {
            // Partial/empty read: classify() treats it as UNKNOWN, never as gone.
        }
        return buffer.copyOf(read)
    }

    internal fun looksTextual(bytes: ByteArray): Boolean {        val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() && it != 0.toByte() } ?: return false
        return first.toInt().toChar() in charArrayOf('<', '{', '[')
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
