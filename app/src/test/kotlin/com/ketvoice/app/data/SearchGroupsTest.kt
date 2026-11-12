package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SearchGroupsTest {
    private val base = Track("yt:1", "Song", "Artist", "Album", durationMs = 180_000, videoId = "one")

    @Test fun groupsOnlyConfidentMatchesAndKeepsEachSelectableSource() {
        val tidal = base.copy(id = "cs:one:tidal:1", addon = "cs:one", addonId = "tidal:1", videoId = null, durationMs = 180_500)
        val qobuz = tidal.copy(id = "cs:one:qobuz:1", addonId = "qobuz:1")
        val groups = SearchGroups.group(listOf(base, tidal, qobuz))
        assertEquals(1, groups.size)
        assertEquals(listOf("YouTube", "TIDAL", "Qobuz"), groups.single().tracks.map(SearchGroups::sourceName))
        assertEquals(listOf("youtube", "cs:one", "cs:one"), groups.single().tracks.map(SearchGroups::sourceId))
    }

    @Test fun doesNotCollapseLiveRemixesExplicitVersionsOrUnknownDurations() {
        val variants = listOf(base, base.copy(id = "live", title = "Song (Live)"),
            base.copy(id = "remix", title = "Song (Remix)"), base.copy(id = "explicit", explicit = true),
            base.copy(id = "unknown", durationMs = 0), base.copy(id = "long", durationMs = 190_000))
        assertEquals(variants.size, SearchGroups.group(variants).size)
    }

    @Test fun matchingIsrcCanGroupWhenDurationIsMissing() {
        val first = base.copy(isrc = "USABC1234567")
        val second = first.copy(id = "other", durationMs = 0, isrc = "usabc1234567")
        assertTrue(SearchGroups.sameRecording(first, second))
        assertFalse(SearchGroups.sameRecording(first, second.copy(isrc = "USABC1234568")))
    }

    @Test fun firstProviderIsVisibleWithoutWaitingForTheSlowProvider() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first = CompletableDeferred<List<Track>>()
        val job = launch {
            SearchGroups.collectProgressively(listOf({ gate.await(); listOf(base.copy(id = "slow")) },
                { listOf(base) }, { error("unavailable") })) { first.complete(it) }
        }
        assertEquals(listOf(base.id), withTimeout(2_000) { first.await() }.map { it.id })
        assertFalse(job.isCompleted)
        gate.complete(Unit)
        withTimeout(2_000) { job.join() }
    }
}
