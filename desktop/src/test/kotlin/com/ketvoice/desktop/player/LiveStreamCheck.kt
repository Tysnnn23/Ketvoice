package com.ketvoice.desktop.player

import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.StreamResolver
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.audio.AudioSource
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * One real resolution against the network, off by default (host tests never reach it):
 * `KETVOICE_LIVE=1 ./gradlew -p desktop test --tests '*LiveStreamCheck*'`. Uses the proxy the host's
 * JAVA_TOOL_OPTIONS names, if any. Prints what came for a well-known song.
 */
class LiveStreamCheck {
    @Test fun youtubeMusicResolvesToARangeReadableAudioFile() = runBlocking {
        assumeTrue(System.getenv("KETVOICE_LIVE") == "1")
        Regex("-D(https?\\.proxy(?:Host|Port))=(\\S+)").findAll(System.getenv("JAVA_TOOL_OPTIONS").orEmpty())
            .forEach { System.setProperty(it.groupValues[1], it.groupValues[2]) }
        val track = Track("yt:dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody",
            videoId = "dQw4w9WgXcQ", youtubeCatalogVerified = true)
        val stream = MusicApi.youtubeStream(track) ?: error("no YouTube stream")
        val source = DesktopStreams.toSource(stream) as AudioSource.Url
        val c = URL(source.url).openConnection() as HttpURLConnection
        source.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Range", "bytes=0-")
        val head = c.inputStream.use { it.readNBytes(12) }
        println("LIVE youtube: format=${stream.format} quality=${stream.quality} tag=${stream.cacheTag} dash=${stream.isDash} " +
            "status=${c.responseCode} type=${c.contentType} range=${c.getHeaderField("Content-Range")} " +
            "head=${head.joinToString("") { "%02x".format(it) }} host=${URL(source.url).host}")
        assertEquals(206, c.responseCode)
        c.disconnect()
        // The whole chain as playback runs it (source order of the shared resolver).
        val first = StreamResolver.candidates(track, download = false, lossless = false, race = false).firstOrNull()
        println("LIVE resolver: source=${first?.sourceId} format=${first?.stream?.format} quality=${first?.stream?.quality} " +
            "dash=${first?.stream?.isDash} engine=${first?.stream?.let { DesktopStreams.toSource(it) }?.let { (it as AudioSource.Url).url.take(48) }}")
    }
}
