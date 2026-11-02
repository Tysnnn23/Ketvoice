package com.ketvoice.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class StreamResolverTest {
    private fun stream(url: String, format: String = "mp3", quality: String = "128 kbps") =
        MusicApi.StreamInfo(url, format, quality, "cache:$url")

    @Test fun orderedModeStopsAfterTheFirstUsableSourceEvenIfALaterSourceIsBetter() = runBlocking {
        val attempts = mutableListOf<String>()
        val sources = listOf(
            StreamResolver.Source("first") { attempts += "first"; stream("https://a/FIRST") },
            StreamResolver.Source("better") { attempts += "better"; stream("https://a/BETTER", "flac", "Sans perte") }
        )
        val picked = StreamResolver.resolve(sources, race = false).toList()
        assertEquals(listOf("FIRST"), picked.map { it.stream.url.substringAfterLast('/') })
        assertEquals(listOf("first"), attempts)
    }

    @Test fun progressiveModeReturnsTheFirstValidatedStreamWithoutWaitingForSlowProviders() = runBlocking {
        val sources = listOf(
            StreamResolver.Source("slow") { delay(5_000); stream("https://a/SLOW", "flac", "Sans perte") },
            StreamResolver.Source("fast") { stream("https://a/FAST") }
        )
        val started = System.currentTimeMillis()
        val first = withTimeout(2_000) { StreamResolver.resolve(sources, race = true).first() }
        assertEquals("FAST", first.stream.url.substringAfterLast('/'))
        assertTrue("Progressive audio must not wait for the slow source", System.currentTimeMillis() - started < 2_000)
    }

    @Test fun aFailingSourceIsSkippedAndTheNextOrderedSourceWins() = runBlocking {
        val sources = listOf(
            StreamResolver.Source("expired") { error("expired provider") },
            StreamResolver.Source("empty") { null },
            StreamResolver.Source("working") { stream("https://a/WORKING") }
        )
        assertEquals("WORKING", StreamResolver.resolve(sources, race = false).toList().single().stream.url.substringAfterLast('/'))
    }

    @Test fun aHangingSourceIsBoundedInsteadOfBlockingPlaybackForever() = runBlocking {
        val sources = listOf(
            StreamResolver.Source("hang") { delay(60_000); null },
            StreamResolver.Source("next") { stream("https://a/NEXT") }
        )
        val started = System.currentTimeMillis()
        val result = StreamResolver.resolve(sources, race = false, timeoutMs = 150).toList()
        assertEquals("NEXT", result.single().stream.url.substringAfterLast('/'))
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test fun cancellationStopsPendingSources() = runBlocking {
        var reachedSecond = false
        val sources = listOf(
            StreamResolver.Source("first") { stream("https://a/ONE") },
            StreamResolver.Source("second") { reachedSecond = true; stream("https://a/TWO") }
        )
        StreamResolver.resolve(sources, race = false).toList().first()
        assertFalse("Ordered mode must stop after the first usable source", reachedSecond)
    }

    @Test fun qualityDownloadModeCollectsEverySourceEvenWhenOrderedIsOff() = runBlocking {
        val sources = listOf(
            StreamResolver.Source("api:um") { stream("https://a/MP3") },
            StreamResolver.Source("youtube") { stream("https://a/AAC", "aac", "192 kbps") },
            StreamResolver.Source("api:last") { stream("https://a/FLAC", "flac", "Sans perte") }
        )
        // Progressive playback off would stop at the first source…
        val ordered = StreamResolver.resolve(sources, race = false).toList()
        assertEquals(1, ordered.size)
        // …but a quality download races every source and keeps the best.
        val raced = StreamResolver.resolve(sources, race = true)
        val best = StreamResolver.bestWithin(raced, 2_000)
        assertEquals("flac", best?.stream?.format)
    }

    @Test fun sourceOrderReconciliationPreservesUserOrderAndAddsNewSources() {
        assertEquals(listOf("youtube", "api:um", "cs:new"),
            SourcePriority.reconcile(listOf("youtube", "api:um", "cs:gone"), listOf("api:um", "youtube", "cs:new")))
    }
}
