package com.ketvoice.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Ordered mode really tries sources in settings order. Racing mode emits usable
 * candidates immediately and keeps resolving alternatives without holding up audio.
 */
internal object StreamResolver {
    data class Source(val id: String, val timeoutMs: Long? = null, val resolve: suspend () -> MusicApi.StreamInfo?)
    data class Candidate(val sourceId: String, val priority: Int, val stream: MusicApi.StreamInfo)

    fun sources(track: Track, download: Boolean, lossless: Boolean): List<Source> =
        SourcePriority.entries(if (download) SourcePriority.Mode.DOWNLOAD else SourcePriority.Mode.PLAYBACK)
            .filter { it.enabled }
            .sortedBy { if (!download && it.id == track.sourceOverride) 0 else 1 }
            .mapNotNull { entry ->
            when (entry.id) {
                "api:um" -> Source(entry.id) { MusicApi.addonStream(track, "um") }
                "api:last" -> if (lossless || (!download && track.sourceOverride == entry.id))
                    Source(entry.id) { MusicApi.flacStream(track) } else null
                "youtube" -> Source(entry.id) { MusicApi.youtubeStream(track) }
                else -> CustomSources.sources.value.firstOrNull { "cs:${it.id}" == entry.id }?.let { source ->
                    Source(entry.id, timeoutMs = if (source.manifestUrl == CustomSources.LOSSLESS_MANIFEST) 35_000L else null) {
                        CustomSources.streamFrom(source, track, download)
                    }
                }
            }
        }

    /**
     * Candidates are returned exactly as the provider answered: no extra HTTP
     * request is made before playback. A pre-request can consume a one-shot CDN
     * token and leave ExoPlayer buffering forever, so verification happens later
     * by the player or by the downloader's own byte check.
     */
    fun candidates(track: Track, download: Boolean, lossless: Boolean, race: Boolean,
                    excluded: Set<String> = emptySet(), excludedSources: Set<String> = emptySet()): Flow<Candidate> {
        val available = sources(track, download, lossless).filterNot { it.id in excludedSources }
        return resolve(available.map { source -> Source(source.id, source.timeoutMs) {
            source.resolve()?.takeIf { it.url !in excluded }
        } }, shouldRace(race, download, track.sourceOverride, available.map { it.id }))
    }

    /** Ranks candidates by real quality, best first, keeping the source priority on ties. */
    internal fun ranked(collected: List<Candidate>): List<Candidate> =
        collected.sortedWith(compareByDescending<Candidate> { StreamQuality.rank(it.stream) }.thenBy { it.priority })

    internal fun best(collected: List<Candidate>): Candidate? = ranked(collected).firstOrNull()

    internal fun canUsePrefetched(race: Boolean, firstSource: String?, candidateSource: String): Boolean =
        race || firstSource == candidateSource

    /** A reusable DASH manifest is short-lived; a resolved file URL stays valid for minutes. */
    internal fun prefetchedFresh(isDash: Boolean, ageMs: Long): Boolean =
        ageMs in 0..(if (isDash) 60_000L else 240_000L)

    internal fun shouldRace(race: Boolean, download: Boolean, selected: String?, available: List<String>): Boolean =
        race && (download || selected == null || selected !in available)

    internal fun useCachedImmediately(progressive: Boolean, lossless: Boolean, online: Boolean,
                                      hasLosslessSource: Boolean, cached: MusicApi.StreamInfo): Boolean =
        progressive || !lossless || !online || !hasLosslessSource || StreamQuality.rank(cached) >= 10_000

    /** Stops as soon as every source answered, or after [windowMs], then ranks them. */
    suspend fun collectWithin(candidates: Flow<Candidate>, windowMs: Long): List<Candidate> = coroutineScope {
        val collected = mutableListOf<Candidate>()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch {
            try { candidates.collect { collected += it } } finally { done.complete(Unit) }
        }
        withTimeoutOrNull(windowMs) { done.await() }
        job.cancel()
        ranked(collected)
    }

    suspend fun bestWithin(candidates: Flow<Candidate>, windowMs: Long): Candidate? =
        collectWithin(candidates, windowMs).firstOrNull()

    internal fun resolve(sources: List<Source>, race: Boolean, timeoutMs: Long = 14_000,
                         qualityWindowMs: Long = 80): Flow<Candidate> = flow {
        suspend fun attempt(source: Source): MusicApi.StreamInfo? = try {
            withTimeoutOrNull(source.timeoutMs ?: timeoutMs) { source.resolve() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (!race) {
            for (source in sources) attempt(source)?.let { emit(Candidate(source.id, sources.indexOf(source), it)); return@flow }
        } else coroutineScope {
            val results = Channel<Candidate?>(Channel.UNLIMITED)
            val permits = Semaphore(4)
            val jobs = sources.mapIndexed { index, source -> launch {
                val stream = permits.withPermit { attempt(source) }
                results.send(stream?.let { Candidate(source.id, index, it) })
            } }
            try {
                var received = 0
                while (received < sources.size) {
                    val batch = mutableListOf<Candidate>()
                    val first = results.receive()
                    received++
                    first?.let { batch += it }
                    // Bounded coalescing (80 ms) prefers a better stream that is
                    // already arriving, never a long wait for an expired provider.
                    if (batch.isNotEmpty() && qualityWindowMs > 0) withTimeoutOrNull(qualityWindowMs) {
                        while (received < sources.size) {
                            val extra = results.receive(); received++
                            extra?.let { batch += it }
                        }
                    }
                    batch.sortedWith(compareByDescending<Candidate> { StreamQuality.rank(it.stream) }.thenBy { it.priority }).forEach { emit(it) }
                }
            } finally { jobs.forEach { it.cancel() }; results.cancel() }
        }
    }
}
