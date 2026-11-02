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
    data class Source(val id: String, val resolve: suspend () -> MusicApi.StreamInfo?)
    data class Candidate(val sourceId: String, val priority: Int, val stream: MusicApi.StreamInfo)

    fun sources(track: Track, download: Boolean, lossless: Boolean): List<Source> =
        SourcePriority.entries().filter { it.enabled }.mapNotNull { entry ->
            when (entry.id) {
                "api:um" -> Source(entry.id) { MusicApi.addonStream(track, "um") }
                "api:last" -> if (lossless) Source(entry.id) { MusicApi.flacStream(track) } else null
                "youtube" -> Source(entry.id) { MusicApi.youtubeStream(track) }
                else -> CustomSources.sources.value.firstOrNull { "cs:${it.id}" == entry.id }?.let { source ->
                    Source(entry.id) { CustomSources.streamFrom(source, track, download) }
                }
            }
        }

    fun candidates(track: Track, download: Boolean, lossless: Boolean, race: Boolean,
                   excluded: Set<String> = emptySet()): Flow<Candidate> = resolve(
        sources(track, download, lossless).map { source -> Source(source.id) {
            val stream = source.resolve() ?: return@Source null
            if (stream.url in excluded) null else StreamProbe.verified(stream, download)
        } }, race)

    internal fun resolve(sources: List<Source>, race: Boolean, timeoutMs: Long = 14_000,
                         qualityWindowMs: Long = 80): Flow<Candidate> = flow {
        suspend fun attempt(source: Source): MusicApi.StreamInfo? = try {
            withTimeoutOrNull(timeoutMs) { source.resolve() }
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
