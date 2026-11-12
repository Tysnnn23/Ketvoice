package com.ketvoice.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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

    @Test fun measuredSlowSourceCanArriveLaterWithoutDelayingTheFirstStream() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val first = CompletableDeferred<Unit>()
        val sources = listOf(
            StreamResolver.Source("lossless", timeoutMs = 2_000) { release.await(); stream("https://a/FLAC", "flac", "lossless") },
            StreamResolver.Source("fast") { stream("https://a/FAST") }
        )
        val arrivals = mutableListOf<String>()
        val job = launch {
            StreamResolver.resolve(sources, race = true, timeoutMs = 80, qualityWindowMs = 0).collect {
                arrivals += it.sourceId
                first.complete(Unit)
            }
        }
        withTimeout(1_000) { first.await() }
        assertEquals(listOf("fast"), arrivals)
        delay(120)
        release.complete(Unit)
        withTimeout(1_000) { job.join() }
        assertEquals(listOf("fast", "lossless"), arrivals)
    }

    @Test fun aFailingSourceIsSkippedAndTheNextOrderedSourceWins() = runBlocking {
        val sources = listOf(
            StreamResolver.Source("expired") { error("expired provider") },
            StreamResolver.Source("empty") { null },
            StreamResolver.Source("working") { stream("https://a/WORKING") }
        )
        assertEquals("WORKING", StreamResolver.resolve(sources, race = false).toList().single().stream.url.substringAfterLast('/'))
    }

    @Test fun addonTriesTidalWhenItsMatchingQobuzResultFails() = runBlocking {
        val wanted = Track("yt:fixture", "Without Me", "Eminem", "Album")
        val qobuz = wanted.copy(id = "qobuz", addonId = "qobuz:1")
        val tidal = wanted.copy(id = "tidal", addonId = "tidal:573293")
        val attempts = mutableListOf<String>()
        val qobuzAlternatives = (2..6).map { qobuz.copy(id = "qobuz$it", addonId = "qobuz:$it") }
        val result = CustomSources.resolveCatalogMatches(wanted, listOf(qobuz) + qobuzAlternatives + tidal, mutableSetOf()) { track ->
            attempts += track.addonId!!
            if (track.addonId!!.startsWith("tidal:")) stream("https://cdn.example/manifest", "dash", "lossless") else null
        }
        assertEquals(listOf("qobuz:1", "tidal:573293"), attempts)
        assertTrue(result!!.isDash)
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

    @Test fun prefetchedStreamCannotBypassTheOrderedMode() {
        assertFalse(StreamResolver.canUsePrefetched(false, "lossless", "youtube"))
        assertTrue(StreamResolver.canUsePrefetched(false, "lossless", "lossless"))
        assertTrue(StreamResolver.canUsePrefetched(true, "lossless", "youtube"))
        assertFalse(StreamResolver.canUsePrefetched(false, null, "youtube"))
    }

    @Test fun explicitAvailableSourceIsTriedBeforeTheProgressiveRace() {
        val available = listOf("youtube", "cs:lossless")
        assertFalse(StreamResolver.shouldRace(true, false, "cs:lossless", available))
        assertTrue(StreamResolver.shouldRace(true, false, "cs:removed", available))
        assertTrue(StreamResolver.shouldRace(true, true, "cs:lossless", available))
        assertFalse(StreamResolver.shouldRace(false, false, null, available))
    }

    @Test fun explicitYoutubeChoiceMovesThatEnabledSourceAheadOfSavedOrder() {
        val oldOrder = SourcePriority.playbackOrder.value
        val oldDisabled = SourcePriority.playbackDisabled.value
        try {
            SourcePriority.playbackOrder.value = listOf("api:um", "youtube")
            SourcePriority.playbackDisabled.value = emptySet()
            val track = Track("yt:fixture", "Song", "Artist", "Album", sourceOverride = "youtube")
            assertEquals("youtube", StreamResolver.sources(track, download = false, lossless = false).first().id)
            SourcePriority.playbackDisabled.value = setOf("youtube")
            assertFalse(StreamResolver.sources(track, download = false, lossless = false).any { it.id == "youtube" })
        } finally {
            SourcePriority.playbackOrder.value = oldOrder
            SourcePriority.playbackDisabled.value = oldDisabled
        }
    }
}
