package com.ketvoice.desktop.player

import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.audio.AudioEvent
import com.ketvoice.desktop.audio.CaptureSink
import com.ketvoice.desktop.audio.FfmpegAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole chain on the live network, off by default: a well-known song resolved by
 * [DesktopStreams] (the app's sources) and its first 5 seconds decoded by [FfmpegAudioEngine] into a
 * [CaptureSink]. `KETVOICE_LIVE=1 ./gradlew -p desktop test --tests '*LivePlaybackCheck*'`.
 */
class LivePlaybackCheck {
    @Test fun aWellKnownSongDecodesToSound() = runBlocking {
        assumeTrue(System.getenv("KETVOICE_LIVE") == "1")
        Regex("-D(https?\\.proxy(?:Host|Port))=(\\S+)").findAll(System.getenv("JAVA_TOOL_OPTIONS").orEmpty())
            .forEach { System.setProperty(it.groupValues[1], it.groupValues[2]) }
        runCatching { MusicApi.init(android.content.Context.app) }
        val track = Track("yt:dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody",
            videoId = "dQw4w9WgXcQ", youtubeCatalogVerified = true)
        val source = DesktopStreams.source(track)
        assertNotNull(source, "no source resolved")

        val fiveSeconds = CountDownLatch(1)
        val sink = CaptureSink { frames -> if (frames >= 5L * 48_000) fiveSeconds.countDown() }
        val engine = FfmpegAudioEngine(sink)
        val events = ArrayList<AudioEvent>()
        val watch = CoroutineScope(Dispatchers.Default)
        watch.launch { engine.events.collect { synchronized(events) { events += it } } }
        try {
            engine.load(source, 0, true)
            val done = fiveSeconds.await(60, TimeUnit.SECONDS)
            engine.pause()
            val samples = sink.samples().let { it.copyOf(minOf(it.size, 5 * 48_000 * sink.channels.coerceAtLeast(1))) }
            val rms = sqrt(samples.fold(0.0) { a, s -> a + s.toDouble() * s } / samples.size.coerceAtLeast(1))
            val peak = samples.maxOfOrNull { abs(it.toInt()) } ?: 0
            val seen = synchronized(events) { events.toList() }
            println("LIVE playback: source=${(source as? com.ketvoice.desktop.audio.AudioSource.Url)?.url?.substringBefore('?')?.take(60) ?: source} " +
                "format=${seen.filterIsInstance<AudioEvent.Format>().firstOrNull()} frames=${sink.frames} rate=${sink.sampleRate} " +
                "channels=${sink.channels} rms=${"%.1f".format(rms)} peak=$peak errors=${seen.filterIsInstance<AudioEvent.Error>()}")
            assertTrue(done, "5 s were not decoded within a minute")
            assertTrue(peak > 1_000 && rms > 100, "the first 5 s are silent")
        } finally {
            engine.release()
            watch.cancel()
        }
    }
}
