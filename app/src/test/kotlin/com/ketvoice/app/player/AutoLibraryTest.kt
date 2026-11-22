@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaConstants
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MixKind
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake stores: what a listener could have, without the app's disk or network. */
internal class FakeAutoSource : AutoSource {
    var recent = emptyList<Track>()
    var favorites = emptyList<Track>()
    var playlists = emptyList<LocalPlaylist>()
    var downloads = emptyList<Track>()
    var mixes = emptyList<MixCard>()
    var mixTracks = emptyMap<String, List<Track>>()
    var mixDelayMs = 0L
    var catalogue: (String) -> List<Track> = { emptyList() }
    var catalogueDelayMs = 0L
    var catalogueCalls = 0
    var mixCalls = 0
    var shelves = emptyList<com.ketvoice.app.data.HomeShelf>()
    var stations = AutoStations()
    var current: Track? = null
    var playing = false
    var online = true
    var local = emptyList<Track>()

    override fun library() = (favorites + downloads + playlists.flatMap { p -> p.entries.mapNotNull { it.track } } + local).distinctBy { it.id }
    override fun shelves() = shelves
    override fun stations() = stations
    override fun current() = current
    override fun playing() = playing
    override fun online() = online

    override fun recent() = recent
    override fun favorites() = favorites
    override fun playlists() = playlists
    override fun downloads() = downloads
    override fun isDownloaded(trackId: String) = downloads.any { it.id == trackId }
    override fun mixes() = mixes
    override fun mixTitle(card: MixCard) = if (card.kind == MixKind.OnRepeat) "En boucle" else "Mix ${card.artist}"
    override fun mixSubtitle(card: MixCard) = "Vos titres et ses incontournables"
    override suspend fun mixTracks(id: String): List<Track>? { mixCalls++; if (mixDelayMs > 0) delay(mixDelayMs); return mixTracks[id] }
    private fun matches(t: Track, q: String) = t.title.contains(q, true) || t.artist.contains(q, true)
    override fun searchLibrary(query: String) =
        (downloads + playlists.flatMap { p -> p.entries.mapNotNull { it.track } } + recent).filter { matches(it, query) }.distinctBy { it.id }
    override fun searchPlaylists(query: String) = playlists.filter { it.name.contains(query, true) }
    override suspend fun searchCatalogue(query: String): List<Track> { catalogueCalls++; if (catalogueDelayMs > 0) delay(catalogueDelayMs); return catalogue(query) }
    override fun lookup(trackId: String) = (recent + favorites + downloads).firstOrNull { it.id == trackId }
    override fun cover(track: Track) = track.coverUrl
    override fun text(french: String) = french
}

internal class FakeAutoPlayback : AutoPlayback {
    val played = mutableListOf<Pair<List<String>, Int>>()
    val shuffled = mutableListOf<List<String>>()
    val queued = mutableListOf<List<String>>()
    val radios = mutableListOf<String>()
    val artistRadios = mutableListOf<Pair<String, List<String>>>()
    var canResume = false
    var resumed = 0
    override fun play(tracks: List<Track>, index: Int) { played += tracks.map { it.id } to index }
    override fun shuffle(tracks: List<Track>) { shuffled += tracks.map { it.id } }
    override fun enqueue(tracks: List<Track>) { queued += tracks.map { it.id } }
    override suspend fun resume(): Boolean { resumed++; return canResume }
    override fun radio(seed: Track) { radios += seed.id }
    override fun artistRadio(name: String, catalogId: String?, tracks: List<Track>) { artistRadios += name to tracks.map { it.id } }
}

internal fun track(id: String, title: String = "Titre $id", artist: String = "Artiste", explicit: Boolean = false) =
    Track(id = id, title = title, artist = artist, album = "Album", durationMs = 180_000, coverUrl = "https://img.example/$id.jpg",
        explicit = explicit)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoLibraryTest {
    private val source = FakeAutoSource()
    private val playback = FakeAutoPlayback()
    private var now = 1_000_000L
    private val library = AutoLibrary(source, playback, clock = { now }, searchTimeoutMs = 300, mixTimeoutMs = 300)

    private fun children(id: String, page: Int = 0, size: Int = Int.MAX_VALUE) = runBlocking { library.children(id, page, size) }
    private fun item(mediaId: String, query: String? = null, extras: Bundle? = null) = MediaItem.Builder().setMediaId(mediaId)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build()).build()

    @Test fun theRootHasFourBrowsableTabsEvenWithNothingSaved() {
        val tabs = children(AutoIds.ROOT)!!
        assertEquals(listOf(AutoIds.HOME, AutoIds.LIBRARY, AutoIds.RADIO, AutoIds.DOWNLOADS), tabs.map { it.mediaId })
        assertEquals(listOf("Accueil", "Bibliothèque", "Radio", "Téléchargements"), tabs.map { it.mediaMetadata.title.toString() })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        // Home's mixes are covers in a grid, the library a list of categories, radio stations a grid; titles stay a list.
        fun style(item: MediaItem, key: String) = item.mediaMetadata.extras!!.getInt(key)
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, style(tabs[0], MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM, style(tabs[1], MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, style(tabs[2], MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, style(tabs[3], MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            library.rootExtras().getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        // A car that shows fewer tabs gets the first ones.
        assertEquals(listOf(AutoIds.HOME, AutoIds.LIBRARY, AutoIds.RADIO), runBlocking { library.children(AutoIds.ROOT, 0, 10, 3) }!!.map { it.mediaId })
        // Nothing listened, liked, saved or downloaded: every list is empty, never an error.
        listOf(AutoIds.HOME, AutoIds.RADIO, AutoIds.FAVORITES, AutoIds.PLAYLISTS, AutoIds.ALBUMS, AutoIds.ARTISTS, AutoIds.SONGS,
            AutoIds.DOWNLOADS).forEach { assertEquals(it, emptyList<MediaItem>(), children(it)) }
        assertEquals(6, children(AutoIds.LIBRARY)!!.size)
        assertNull(children("ketvoice.auto.nothing"))
        assertTrue(library.root().mediaMetadata.isBrowsable == true)
    }

    @Test fun homeShowsTheMixesThenTheRecentTitlesInGroups() {
        source.mixes = listOf(MixCard("artist:Adele", MixKind.Artist, "Adele", listOf("https://img.example/mix.jpg")),
            MixCard("repeat", MixKind.OnRepeat, null, emptyList()))
        source.recent = listOf(track("a"), track("b", explicit = true))
        source.downloads = listOf(track("b"))
        val home = children(AutoIds.HOME)!!
        assertEquals(listOf(AutoIds.mix("artist:Adele"), AutoIds.mix("repeat"), AutoIds.track(AutoIds.HOME, "a"), AutoIds.track(AutoIds.HOME, "b")),
            home.map { it.mediaId })
        assertEquals("Mix Adele", home[0].mediaMetadata.title.toString())
        assertTrue(home[0].mediaMetadata.isBrowsable == true)
        assertEquals("https://img.example/mix.jpg", home[0].mediaMetadata.artworkUri.toString())
        assertEquals(listOf("Vos mix", "Vos mix", "Récemment écoutés", "Récemment écoutés"),
            home.map { it.mediaMetadata.extras!!.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE) })
        val b = home[3].mediaMetadata
        assertTrue(b.isPlayable == true && b.isBrowsable == false)
        assertEquals("Titre b", b.title.toString()); assertEquals("Artiste", b.artist.toString())
        assertEquals(MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT, b.extras!!.getLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT))
        assertEquals(MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED, b.extras!!.getLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS))
        assertEquals("https://img.example/b.jpg", b.artworkUri.toString())
        assertEquals(180_000L, b.durationMs)
    }

    @Test fun listsArePagedAndCappedForTheCar() {
        source.favorites = (1..250).map { track("f$it") }
        assertEquals(AutoLibrary.MAX_ITEMS, children(AutoIds.FAVORITES)!!.size)
        // « Tout lire » and « Lecture aléatoire » lead the list, then its titles.
        assertEquals(listOf(AutoIds.all(AutoIds.FAVORITES), AutoIds.shuffle(AutoIds.FAVORITES)) +
            (1..28).map { AutoIds.track(AutoIds.FAVORITES, "f$it") }, children(AutoIds.FAVORITES, 0, 30)!!.map { it.mediaId })
        assertEquals((89..98).map { AutoIds.track(AutoIds.FAVORITES, "f$it") }, children(AutoIds.FAVORITES, 3, 30)!!.map { it.mediaId })
        assertEquals(emptyList<MediaItem>(), children(AutoIds.FAVORITES, 4, 30))
        // Media3's « everything » request on a later page must not overflow.
        assertEquals(emptyList<MediaItem>(), children(AutoIds.FAVORITES, 2, Int.MAX_VALUE))
        assertEquals(listOf(3, 4), paged(listOf(1, 2, 3, 4, 5), 1, 2))
        assertEquals(emptyList<Int>(), paged(listOf(1), -1, 2))
    }

    @Test fun pagesStayTheSameWhileTheCarScrollsThenFollowTheStore() {
        source.favorites = (1..10).map { track("f$it") }
        children(AutoIds.FAVORITES, 0, 5)
        source.favorites = (11..20).map { track("f$it") }
        assertEquals(AutoIds.track(AutoIds.FAVORITES, "f4"), children(AutoIds.FAVORITES, 1, 5)!!.first().mediaId)
        now += AutoLibrary.LOCAL_TTL_MS + 1
        assertEquals(AutoIds.track(AutoIds.FAVORITES, "f14"), children(AutoIds.FAVORITES, 1, 5)!!.first().mediaId)
    }

    @Test fun playlistsAreFoldersOfTheirFoundTitles() {
        val entries = listOf(PlaylistEntry(title = "x", artist = "y", track = track("p1")),
            PlaylistEntry(title = "Non retrouvé", artist = "?", track = null), PlaylistEntry(title = "z", artist = "y", track = track("p2")))
        source.playlists = listOf(LocalPlaylist(id = "l1", name = "Route", entries = entries),
            LocalPlaylist(id = "l2", name = "Épinglée", pinned = true, coverUrl = "https://img.example/cover.jpg"))
        val lists = children(AutoIds.PLAYLISTS)!!
        assertEquals(listOf(AutoIds.playlist("l2"), AutoIds.playlist("l1")), lists.map { it.mediaId })
        assertEquals("2 titres", lists[1].mediaMetadata.subtitle.toString())
        assertEquals("0 titres", lists[0].mediaMetadata.subtitle.toString())
        assertEquals("https://img.example/cover.jpg", lists[0].mediaMetadata.artworkUri.toString())
        assertEquals("https://img.example/p1.jpg", lists[1].mediaMetadata.artworkUri.toString())
        assertEquals(listOf(AutoIds.all(AutoIds.playlist("l1")), AutoIds.shuffle(AutoIds.playlist("l1")),
            AutoIds.track(AutoIds.playlist("l1"), "p1"), AutoIds.track(AutoIds.playlist("l1"), "p2")),
            children(AutoIds.playlist("l1"))!!.map { it.mediaId })
        assertNull(children(AutoIds.playlist("gone")))
        assertEquals("Route", runBlocking { library.item(AutoIds.playlist("l1")) }!!.mediaMetadata.title.toString())
    }

    @Test fun idsSurviveAnyCharacter() {
        val parent = AutoIds.playlist("a/b|c d:é")
        assertEquals("a/b|c d:é", AutoIds.playlistId(parent))
        assertEquals(parent to "um:12/3?x", AutoIds.trackOf(AutoIds.track(parent, "um:12/3?x")))
        assertNull(AutoIds.trackOf("ketvoice.auto.track/only"))
        assertEquals("joue adele", AutoIds.searchQuery(AutoIds.search(" joue adele ")))
    }

    @Test fun aMixIsLoadedOnceAndAnUnansweredOneIsAnError() {
        source.mixes = listOf(MixCard("style:Adele", MixKind.Style, "Adele", emptyList()))
        source.mixTracks = mapOf("style:Adele" to listOf(track("m1"), track("m2")))
        assertEquals(listOf(AutoIds.all(AutoIds.mix("style:Adele")), AutoIds.shuffle(AutoIds.mix("style:Adele")),
            AutoIds.track(AutoIds.mix("style:Adele"), "m1"), AutoIds.track(AutoIds.mix("style:Adele"), "m2")),
            children(AutoIds.mix("style:Adele"))!!.map { it.mediaId })
        children(AutoIds.mix("style:Adele"), 0, 1)
        assertEquals(1, source.mixCalls)
        // The catalogue did not answer in time: an error for the car, not an endless spinner.
        source.mixDelayMs = 5_000
        source.mixTracks = mapOf("artist:Other" to listOf(track("o")))
        assertThrows(AutoUnavailable::class.java) { children(AutoIds.mix("artist:Other")) }
        source.mixDelayMs = 0
        source.mixTracks = emptyMap()
        assertThrows(AutoUnavailable::class.java) { children(AutoIds.mix("artist:Nobody")) }
    }

    @Test fun searchPutsTheLibraryFirstAndKeepsOneCopyOfARecording() = runBlocking {
        source.downloads = listOf(track("d1", "Hello", "Adele"))
        source.playlists = listOf(LocalPlaylist(id = "l", name = "Hello road"))
        source.catalogue = { listOf(track("c1", "Hello", "Adele").copy(album = "Album"), track("c2", "Hello", "Lionel Richie"), track("d1", "Hello", "Adele")) }
        assertEquals(3, library.searchCount("Hello"))
        val results = library.searchResult("Hello", 0, 100)
        assertEquals(listOf(AutoIds.playlist("l"), AutoIds.track(AutoIds.search("Hello"), "d1"), AutoIds.track(AutoIds.search("Hello"), "c2")),
            results.map { it.mediaId })
        assertEquals(listOf("Playlists", "Bibliothèque", "Titres"),
            results.map { it.mediaMetadata.extras!!.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE) })
        assertEquals(1, library.searchResult("Hello", 1, 2).size)
        // The car asks for the count, then the results: one catalogue search.
        assertEquals(1, source.catalogueCalls)
    }

    @Test fun aSlowCatalogueStillAnswersWithTheLibraryAndNothingIsNotKept() = runBlocking {
        source.recent = listOf(track("r1", "Rolling in the deep", "Adele"))
        source.catalogueDelayMs = 5_000
        source.catalogue = { listOf(track("late", "Rolling", "Adele")) }
        val started = System.currentTimeMillis()
        assertEquals(listOf(AutoIds.track(AutoIds.search("Rolling"), "r1")), library.searchResult("Rolling", 0, 50).map { it.mediaId })
        assertTrue(System.currentTimeMillis() - started < 3_000)
        assertEquals(0, library.searchCount("   "))
        source.catalogueDelayMs = 0
        source.catalogue = { emptyList() }
        assertEquals(0, library.searchCount("zzz"))
        source.catalogue = { listOf(track("z", "zzz")) }
        assertEquals(1, library.searchCount("zzz"))
    }

    @Test fun aTitleChosenInAListQueuesTheWholeList() = runBlocking {
        val entries = (1..150).map { PlaylistEntry(title = "t$it", artist = "a", track = track("p$it")) }
        source.playlists = listOf(LocalPlaylist(id = "big", name = "Longue", entries = entries))
        // Only the first hundred are shown, but the 120th plays with the whole playlist around it.
        val request = library.request(listOf(item(AutoIds.track(AutoIds.playlist("big"), "p120"))), 0)
        assertEquals(150, (request as AutoRequest.Play).tracks.size)
        assertEquals(119, request.index)
        assertNull(library.perform(request))
        assertEquals((1..150).map { "p$it" } to 119, playback.played.single())
    }

    @Test fun searchResultsAndFavouritesResolveLazilyFromTheirIds() = runBlocking {
        source.favorites = listOf(track("f1"), track("f2"), track("f3"))
        library.perform(library.request(listOf(item(AutoIds.track(AutoIds.FAVORITES, "f2"))), 0))
        assertEquals(listOf("f1", "f2", "f3") to 1, playback.played.last())
        // A search result whose search is no longer cached runs the search again.
        source.catalogue = { listOf(track("s1", "Song"), track("s2", "Song two")) }
        library.perform(library.request(listOf(item(AutoIds.track(AutoIds.search("Song"), "s2"))), 0))
        assertEquals(listOf("s1", "s2") to 1, playback.played.last())
        // Several ids at once (a Media3 controller): they are the queue.
        library.perform(library.request(listOf(item(AutoIds.track(AutoIds.FAVORITES, "f3")), item("unknown"), item(AutoIds.track(AutoIds.FAVORITES, "f1"))), 2))
        assertEquals(listOf("f3", "f1") to 1, playback.played.last())
        // A whole list, by its own id.
        library.perform(library.request(listOf(item(AutoIds.FAVORITES)), 0))
        assertEquals(listOf("f1", "f2", "f3") to 0, playback.played.last())
        // Unknown: a message, nothing played.
        val count = playback.played.size
        assertEquals("Titre introuvable", library.perform(library.request(listOf(item(AutoIds.track(AutoIds.FAVORITES, "gone"))), 0)))
        assertEquals(count, playback.played.size)
        library.perform(library.enqueueRequest(listOf(item(AutoIds.track(AutoIds.FAVORITES, "f1")), item(AutoIds.FAVORITES))))
        assertEquals(listOf("f1", "f1", "f2", "f3"), playback.queued.single())
    }

    @Test fun voicePlaysTheBestMatchFirst() = runBlocking {
        source.catalogue = { listOf(track("c1", "Someone like you", "Adele"), track("c2", "Hello", "Adele"), track("c3", "Hello", "Lionel Richie")) }
        val request = library.voice("hello adele", null) as AutoRequest.Play
        assertEquals(listOf("c2", "c3", "c1"), request.tracks.map { it.id })
        assertEquals(0, request.index)
        // « Joue Adele » with the artist focus: Adele's titles only.
        val artist = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/artist"); putString(MediaStore.EXTRA_MEDIA_ARTIST, "Adele")
        }
        assertEquals(listOf("c1", "c2"), (library.voice("Adele", artist) as AutoRequest.Play).tracks.map { it.id })
        // Through the session's path: the query rides in the request metadata of an item without id.
        library.perform(library.request(listOf(item("", query = "hello adele")), 0))
        assertEquals("c2", playback.played.last().first.first())
        source.catalogue = { emptyList() }
        assertEquals("Aucun résultat pour « inconnu »", library.perform(library.voice("inconnu", null)))
    }

    @Test fun voiceNamesAPlaylistOrTheFavouritesOrNothing() = runBlocking {
        source.playlists = listOf(LocalPlaylist(id = "l", name = "Route du soleil", entries = listOf(PlaylistEntry(title = "a", artist = "b", track = track("p1")))))
        assertEquals(listOf("p1"), (library.voice("route du soleil", null) as AutoRequest.Play).tracks.map { it.id })
        val focus = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist"); putString(MediaStore.EXTRA_MEDIA_PLAYLIST, "soleil") }
        assertEquals(listOf("p1"), (library.voice("ma playlist soleil", focus) as AutoRequest.Play).tracks.map { it.id })
        source.favorites = listOf(track("f1"), track("f2"))
        assertEquals(listOf("f1", "f2"), (library.voice("mes favoris", null) as AutoRequest.Shuffle).tracks.map { it.id })
        // « Joue de la musique sur Ketvoice »: what was playing, else the favourites.
        assertEquals(AutoRequest.Resume, library.voice("", null))
        assertEquals(AutoRequest.Resume, library.request(listOf(item("")), 0))
        playback.canResume = true
        assertNull(library.perform(AutoRequest.Resume))
        assertTrue(playback.shuffled.isEmpty())
        playback.canResume = false
        library.perform(AutoRequest.Resume)
        assertEquals(listOf("f1", "f2"), playback.shuffled.single())
        source.favorites = emptyList()
        assertEquals("Aucun résultat", library.perform(AutoRequest.Resume))
    }

    @Test fun voiceWithTheAlbumFocusKeepsThatAlbum() = runBlocking {
        source.catalogue = { listOf(track("x1", "Hello", "Adele").copy(album = "25"), track("x2", "Chasing pavements", "Adele").copy(album = "19"),
            track("x3", "Water under the bridge", "Adele").copy(album = "25")) }
        val album = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/album"); putString(MediaStore.EXTRA_MEDIA_ALBUM, "25") }
        assertEquals(setOf("x1", "x3"), (library.voice("25 adele", album) as AutoRequest.Play).tracks.map { it.id }.toSet())
    }

    @Test fun voiceScoring() {
        val hello = track("1", "Hello", "Adele")
        assertTrue(VoiceMatch.score(hello, "hello adele", null, null) > VoiceMatch.score(track("2", "Hello", "Lionel Richie"), "hello adele", null, null))
        assertTrue(VoiceMatch.sameArtist(track("3", "x", "Adele, Someone"), "adele"))
        assertFalse(VoiceMatch.sameArtist(hello, ""))
        assertEquals(listOf("1"), VoiceMatch.rank(listOf(hello), "", null, null).map { it.id })
    }
}
