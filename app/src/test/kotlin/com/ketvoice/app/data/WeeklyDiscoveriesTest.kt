package com.ketvoice.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Weekly discoveries with fixed catalogue answers: no network. */
class WeeklyDiscoveriesTest {
    private fun t(id: String, title: String, artist: String, artistId: String? = null) =
        Track(id, title, artist, "Album $artist", coverUrl = "https://img.example/$id.jpg", addon = if (artistId != null) "um" else null, artistId = artistId)

    /** The listener: Nova (catalogue id 1) and Orion (2) a lot, Lyra once. */
    private val known = (1..5).map { t("nova$it", "Nova $it", "Nova", "1") } + (1..4).map { t("orion$it", "Orion $it", "Orion", "2") } +
        listOf(t("lyra1", "Lyra 1", "Lyra"))
    private val counts = known.associate { it.id to if (it.id.startsWith("lyra")) 1 else 3 }

    private class FakeSources : WeeklyDiscoveries.Sources {
        var calls = 0
        /** Each seed has six close artists; each artist has eight titles. */
        override suspend fun related(name: String, catalogId: String?): List<CatalogArtist> {
            calls++
            return (1..6).map { CatalogArtist("${name.hashCode() and 0xffff}$it", "$name Friend $it", null) }
        }
        override suspend fun top(name: String, catalogId: String?): List<Track> = (1..8).map {
            Track("um:${name.replace(' ', '_')}:$it", "$name song $it", name, "LP", coverUrl = "https://img.example/$name$it.jpg")
        }
        override suspend fun radio(name: String, catalogId: String?): List<Track> = (1..10).map {
            Track("um:radio:$name:$it", "Radio $name $it", "Radio Artist $it", "LP")
        }
        override suspend fun search(query: String): List<Track> = emptyList()
    }

    private class MemoryStore : WeeklyDiscoveries.Store {
        var saved: WeeklyDiscoveries.Week? = null
        var saves = 0
        override fun load() = saved
        override fun save(week: WeeklyDiscoveries.Week) { saved = week; saves++ }
    }

    private fun taste(known: List<Track> = this.known, counts: Map<String, Int> = this.counts, favorites: Set<String> = emptySet(),
                      library: List<Track> = emptyList(), allows: (Track) -> Boolean = { true }) =
        WeeklyDiscoveries.Taste(known, counts, emptyMap(), favorites, library, allows = allows)

    @Before @After fun reset() { WeeklyDiscoveries.useStore(null) }

    @Test fun theWeekStartsOnMondayLocalTime() {
        val monday = LocalDate.of(2026, 11, 16)
        assertEquals(monday, WeeklyDiscoveries.weekOf(LocalDate.of(2026, 11, 16)))
        assertEquals(monday, WeeklyDiscoveries.weekOf(LocalDate.of(2026, 11, 18)))
        assertEquals("Sunday still belongs to the week", monday, WeeklyDiscoveries.weekOf(LocalDate.of(2026, 11, 22)))
        assertEquals("the next Monday starts a new one", LocalDate.of(2026, 11, 23), WeeklyDiscoveries.weekOf(LocalDate.of(2026, 11, 23)))
        assertEquals("across a year", LocalDate.of(2026, 12, 28), WeeklyDiscoveries.weekOf(LocalDate.of(2027, 1, 2)))
    }

    @Test fun aSelectionIsMadeOnceAWeekAndKeptUntilMonday() = runBlocking {
        val store = MemoryStore()
        WeeklyDiscoveries.useStore(store)
        val sources = FakeSources()
        val first = WeeklyDiscoveries.ensure(taste(), sources, LocalDate.of(2026, 11, 17), clock = { 1L })!!
        assertEquals(WeeklyDiscoveries.SIZE, first.tracks.size)
        assertEquals(LocalDate.of(2026, 11, 16), first.monday)
        val calls = sources.calls
        // Later in the week, whatever was played since: the very same selection, no new request.
        val busier = counts + ("nova1" to 40)
        val sunday = WeeklyDiscoveries.ensure(taste(counts = busier), sources, LocalDate.of(2026, 11, 22), clock = { 2L })!!
        assertEquals(first.tracks.map { it.id }, sunday.tracks.map { it.id })
        assertEquals(calls, sources.calls)
        assertEquals(1, store.saves)
        // A process restart reads the stored week back.
        WeeklyDiscoveries.useStore(store)
        assertEquals(first.tracks.map { it.id }, WeeklyDiscoveries.current(LocalDate.of(2026, 11, 20))!!.tracks.map { it.id })
        // Monday: a new selection.
        assertNull(WeeklyDiscoveries.current(LocalDate.of(2026, 11, 23)))
        val next = WeeklyDiscoveries.ensure(taste(), sources, LocalDate.of(2026, 11, 23), clock = { 3L })!!
        assertEquals(LocalDate.of(2026, 11, 23), next.monday)
        assertEquals(2, store.saves)
        assertNotEquals("another week, another order", first.tracks.map { it.id }, next.tracks.map { it.id })
    }

    @Test fun theSameTasteGivesTheSameSelectionForAWeek() = runBlocking {
        val monday = LocalDate.of(2026, 11, 16)
        val a = WeeklyDiscoveries.build(taste(), FakeSources(), monday)
        val b = WeeklyDiscoveries.build(taste(known = known.reversed()), FakeSources(), monday)
        assertEquals(a.map { it.id }, b.map { it.id })
    }

    @Test fun nothingBeforeEnoughListening() = runBlocking {
        val thin = known.take(2).associate { it.id to 1 }
        assertTrue(WeeklyDiscoveries.build(taste(counts = thin), FakeSources(), LocalDate.of(2026, 11, 16)).isEmpty())
        assertNull(WeeklyDiscoveries.ensure(taste(counts = thin), FakeSources(), LocalDate.of(2026, 11, 16)))
    }

    @Test fun leavesOutBlockedArtistsTheLibraryAndWhatWasHeard() = runBlocking {
        val sources = object : WeeklyDiscoveries.Sources by FakeSources() {
            // The catalogue answers with titles the listener already has, under other ids and versions.
            override suspend fun radio(name: String, catalogId: String?) = listOf(
                Track("um:x1", "Nova 1 (Remastered 2021)", "Nova", "LP"),                     // heard, another version
                Track("um:x2", "Kept Song", "Stranger", "LP"),                               // in a playlist
                Track("um:x3", "Fav Song - Radio Edit", "Faved", "LP"),                      // a favourite, another version
                Track("um:x4", "Blocked tune", "Banned feat. Someone", "LP"),               // blocked artist
                Track("downloaded", "Offline", "Saver", "LP"),                               // downloaded
            ) + (1..10).map { Track("um:radio:$name:$it", "Radio $name $it", "Radio Artist $it", "LP") }
        }
        val favourite = Track("fav", "Fav Song", "Faved", "")
        val library = listOf(Track("pl", "Kept Song", "Stranger", ""), Track("downloaded", "Offline", "Saver", ""))
        val picked = WeeklyDiscoveries.build(taste(known = known + favourite, favorites = setOf("fav"), library = library,
            allows = { !it.artist.contains("Banned") }), sources, LocalDate.of(2026, 11, 16))
        assertTrue(picked.isNotEmpty())
        val ids = picked.map { it.id }
        assertTrue("heard titles never come back", known.none { it.id in ids })
        listOf("um:x1", "um:x2", "um:x3", "um:x4", "downloaded").forEach { assertFalse(it, it in ids) }
        assertTrue("no blocked artist", picked.none { it.artist.contains("Banned") })
    }

    @Test fun oneVersionOfARecordingAndFewTitlesPerArtist() = runBlocking {
        // A small catalogue: every version of « Skyline » would be picked if versions were not told apart.
        val sources = object : WeeklyDiscoveries.Sources by FakeSources() {
            override suspend fun related(name: String, catalogId: String?) = emptyList<CatalogArtist>()
            override suspend fun top(name: String, catalogId: String?) = emptyList<Track>()
            override suspend fun radio(name: String, catalogId: String?) = listOf(
                Track("um:v1", "Skyline", "Echo", "LP"), Track("um:v2", "Skyline (Live)", "Echo", "LP"),
                Track("yt:v3", "Skyline - Official Video", "Echo", ""), Track("um:v4", "Skyline", "Echo feat. Guest", "LP"),
            ) + (1..10).map { Track("um:radio:$name:$it", "Radio $name $it", "Radio Artist $it", "LP") }
        }
        val picked = WeeklyDiscoveries.build(taste(), sources, LocalDate.of(2026, 11, 16))
        assertEquals(1, picked.count { it.title.startsWith("Skyline") })
        assertEquals(picked.size, picked.map(WeeklyDiscoveries::recording).toSet().size)
        val perArtist = picked.groupingBy { MediaOverrides.normalize(MediaOverrides.mainArtist(it.artist)) }.eachCount()
        assertTrue(perArtist.toString(), perArtist.values.all { it <= WeeklyDiscoveries.PER_ARTIST })
        assertTrue("one artist never follows itself", picked.zipWithNext().none { (a, b) -> a.artist == b.artist })
    }

    @Test fun mostlyNewArtistsWithSomeUnheardTitlesOfTheListenersOwn() = runBlocking {
        val picked = WeeklyDiscoveries.build(taste(), FakeSources(), LocalDate.of(2026, 11, 16))
        val own = picked.count { it.artist == "Nova" || it.artist == "Orion" }
        assertTrue("own artists: $own", own in 1..WeeklyDiscoveries.KNOWN_ARTIST_SHARE)
        assertTrue(picked.size - own > own)
    }

    @Test fun theWeekSurvivesStorage() {
        val week = WeeklyDiscoveries.Week(LocalDate.of(2026, 11, 16), listOf(t("a", "A", "Nova", "1"), Track("b", "B", "Orion", "")), 42L)
        val back = WeeklyDiscoveries.decode(WeeklyDiscoveries.encode(week))!!
        assertEquals(week.monday, back.monday)
        assertEquals(week.tracks, back.tracks)
        assertEquals(42L, back.madeAt)
        assertNull(WeeklyDiscoveries.decode("not json"))
    }

    @Test fun theCardAppearsOnlyForThisWeek() = runBlocking {
        WeeklyDiscoveries.useStore(MemoryStore())
        assertNull(WeeklyDiscoveries.card(LocalDate.of(2026, 11, 17)))
        WeeklyDiscoveries.ensure(taste(), FakeSources(), LocalDate.of(2026, 11, 17))
        val card = WeeklyDiscoveries.card(LocalDate.of(2026, 11, 17))!!
        assertEquals(MixKind.Discover, card.kind)
        assertEquals(WeeklyDiscoveries.MIX_ID, card.id)
        assertTrue(card.covers.size in 1..4)
        assertNull(WeeklyDiscoveries.card(LocalDate.of(2026, 11, 23)))
    }
}
