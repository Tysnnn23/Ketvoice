@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.ketvoice.app.data.HomeShelf
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MixKind
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 8.4 car tree: library categories, albums and artists, play all / shuffle, radio, resume, offline and voice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoTreeTest {
    private val source = FakeAutoSource()
    private val playback = FakeAutoPlayback()
    private val library = AutoLibrary(source, playback, clock = { 1_000_000L }, searchTimeoutMs = 300, mixTimeoutMs = 300,
        iconPackage = "com.ketvoice.test")

    private fun children(id: String, page: Int = 0, size: Int = Int.MAX_VALUE) = runBlocking { library.children(id, page, size) }
    private fun ids(id: String) = children(id)!!.map { it.mediaId }
    private fun extras(item: MediaItem) = item.mediaMetadata.extras ?: Bundle.EMPTY
    private fun group(item: MediaItem) = extras(item).getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)
    private fun play(id: String) = runBlocking { library.perform(library.request(listOf(MediaItem.Builder().setMediaId(id).build()), 0)) }

    private fun song(id: String, title: String, artist: String, album: String, explicit: Boolean = false) =
        Track(id = id, title = title, artist = artist, album = album, durationMs = 200_000, coverUrl = "https://img.example/$id.jpg",
            explicit = explicit)

    private val hello = song("h", "Hello", "Adele", "25")
    private val water = song("w", "Water under the bridge", "Adele", "25", explicit = true)
    private val chasing = song("c", "Chasing pavements", "Adele", "19")
    private val skyfall = song("s", "Skyfall", "Adele", "")
    private val blinding = song("b", "Blinding lights", "The Weeknd", "After hours")

    @Test fun theLibraryIsAListOfCategoriesWithIcons() {
        val categories = children(AutoIds.LIBRARY)!!
        assertEquals(listOf(AutoIds.PLAYLISTS, AutoIds.ALBUMS, AutoIds.ARTISTS, AutoIds.SONGS, AutoIds.FAVORITES, AutoIds.DOWNLOADS),
            categories.map { it.mediaId })
        assertEquals(listOf("Playlists", "Albums", "Artistes", "Titres", "Favoris", "Téléchargements"),
            categories.map { it.mediaMetadata.title.toString() })
        assertTrue(categories.all { it.mediaMetadata.isBrowsable == true })
        assertEquals("android.resource://com.ketvoice.test/drawable/media3_icon_album", categories[1].mediaMetadata.artworkUri.toString())
        // Albums and playlists are grids of covers; artists a list.
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, extras(categories[1]).getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, extras(categories[0]).getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, extras(categories[2]).getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        // Every tab has its icon for the car's tab bar.
        assertTrue(children(AutoIds.ROOT)!!.all { it.mediaMetadata.artworkUri.toString().startsWith("android.resource://com.ketvoice.test/drawable/") })
        assertEquals(AutoIds.ALBUMS, runBlocking { library.item(AutoIds.ALBUMS) }!!.mediaId)
    }

    @Test fun albumsAndArtistsAreGroupedFromTheTitlesTheListenerKeeps() {
        source.favorites = listOf(hello, chasing, skyfall)
        source.downloads = listOf(water)
        source.local = listOf(blinding)
        val albums = children(AutoIds.ALBUMS)!!
        assertEquals(listOf("19", "25", "After hours"), albums.map { it.mediaMetadata.title.toString() })
        assertEquals("Adele", albums[1].mediaMetadata.subtitle.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, albums[1].mediaMetadata.mediaType)
        // An album holding an explicit title says so.
        assertEquals(MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT, extras(albums[1]).getLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT))
        val album = albums[1].mediaId
        assertEquals(listOf(AutoIds.all(album), AutoIds.shuffle(album), AutoIds.track(album, "h"), AutoIds.track(album, "w")), ids(album))
        assertEquals("Tout lire", children(album)!![0].mediaMetadata.title.toString())
        assertEquals("2 titres", children(album)!![0].mediaMetadata.subtitle.toString())
        assertTrue(children(album)!![0].mediaMetadata.isPlayable == true)

        val artists = children(AutoIds.ARTISTS)!!
        assertEquals(listOf("Adele", "The Weeknd"), artists.map { it.mediaMetadata.title.toString() })
        assertEquals("4 titres", artists[0].mediaMetadata.subtitle.toString())
        val page = children(artists[0].mediaId)!!
        assertEquals(AutoIds.shuffle(artists[0].mediaId), page[0].mediaId)
        assertEquals(AutoIds.station("artist", "Adele"), page[1].mediaId)
        assertEquals(listOf("Albums", "Albums"), page.filter { it.mediaMetadata.isBrowsable == true }.map(::group))
        assertEquals(4, page.count { group(it) == "Titres" })

        // Songs: A to Z, shuffle first; the whole list is the queue of a title chosen in it.
        assertEquals(listOf(AutoIds.shuffle(AutoIds.SONGS)) + listOf("b", "c", "h", "s", "w").map { AutoIds.track(AutoIds.SONGS, it) }, ids(AutoIds.SONGS))
        play(AutoIds.track(AutoIds.SONGS, "h"))
        assertEquals(listOf("b", "c", "h", "s", "w") to 2, playback.played.last())
    }

    @Test fun playAllAndShuffleTakeTheWholeList() {
        source.playlists = listOf(LocalPlaylist(id = "road", name = "Route", entries = listOf(hello, water, chasing).map {
            PlaylistEntry(title = it.title, artist = it.artist, track = it) }))
        assertNull(play(AutoIds.all(AutoIds.playlist("road"))))
        assertEquals(listOf("h", "w", "c") to 0, playback.played.last())
        assertNull(play(AutoIds.shuffle(AutoIds.playlist("road"))))
        assertEquals(listOf("h", "w", "c"), playback.shuffled.last())
        // A list that is gone says so, and plays nothing.
        assertEquals("Titre introuvable", play(AutoIds.all(AutoIds.playlist("gone"))))
        assertEquals(1, playback.played.size)
    }

    @Test fun downloadedCollectionsAreMarked() {
        source.downloads = listOf(hello, water)
        source.favorites = listOf(chasing)
        val albums = children(AutoIds.ALBUMS)!!
        val full = albums.first { it.mediaMetadata.title.toString() == "25" }
        assertEquals(MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED, extras(full).getLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS))
        val partial = albums.first { it.mediaMetadata.title.toString() == "19" }
        assertFalse(extras(partial).containsKey(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS))
    }

    @Test fun theRadioTabOffersTheTitlePlayingYourArtistsAndYourTitles() {
        source.current = hello
        source.stations = AutoStations(listOf(AutoArtistSeed("Adele", "123", listOf(hello, water))), listOf(hello, blinding))
        val radio = children(AutoIds.RADIO)!!
        assertEquals(listOf(AutoIds.station("track", "h"), AutoIds.station("artist", "Adele"), AutoIds.station("track", "b")), radio.map { it.mediaId })
        assertEquals(listOf("Lecture en cours", "Vos artistes", "D'après vos écoutes"), radio.map(::group))
        assertTrue(radio.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION })
        assertNull(play(radio[0].mediaId))
        assertEquals(listOf("h"), playback.radios)
        assertNull(play(radio[1].mediaId))
        assertEquals("Adele" to listOf("h", "w"), playback.artistRadios.single())
        // Offline, a radio cannot start: the car is told why.
        source.online = false
        assertEquals("Hors ligne : la radio a besoin d'une connexion", play(radio[2].mediaId))
        assertEquals(listOf("h"), playback.radios)
    }

    @Test fun homeOffersToResumeTheFeaturedShelvesAndOfflineTheDownloads() {
        source.current = hello
        source.shelves = listOf(HomeShelf("Fait pour vous", "Basé sur vos favoris et écoutes", listOf(blinding, skyfall)),
            HomeShelf("À découvrir", "Changez d'horizon", emptyList()))
        val home = children(AutoIds.HOME)!!
        assertEquals(listOf(AutoIds.RESUME, AutoIds.shelf("Fait pour vous")), home.map { it.mediaId })
        assertEquals(listOf("Reprendre", "À la une"), home.map(::group))
        assertEquals("Hello", home[0].mediaMetadata.title.toString())
        assertEquals(listOf(AutoIds.all(AutoIds.shelf("Fait pour vous")), AutoIds.shuffle(AutoIds.shelf("Fait pour vous")),
            AutoIds.track(AutoIds.shelf("Fait pour vous"), "b"), AutoIds.track(AutoIds.shelf("Fait pour vous"), "s")), ids(AutoIds.shelf("Fait pour vous")))
        // Playing already: no « resume » line.
        source.playing = true
        assertFalse(AutoIds.RESUME in ids(AutoIds.HOME))
        // The resume line picks up the phone's own session.
        playback.canResume = true
        assertNull(play(AutoIds.RESUME))
        assertEquals(1, playback.resumed)

        source.online = false
        source.downloads = listOf(water)
        val offline = children(AutoIds.HOME)!!.first()
        assertEquals(AutoIds.shuffle(AutoIds.DOWNLOADS), offline.mediaId)
        assertEquals("Hors ligne", group(offline))
        assertNull(play(offline.mediaId))
        assertEquals(listOf("w"), playback.shuffled.last())
    }

    @Test fun offlineAMixIsNotAwaitedTheDownloadsAreOffered() {
        source.online = false
        source.downloads = listOf(water)
        source.mixes = listOf(MixCard("artist:Adele", MixKind.Artist, "Adele", emptyList()))
        source.mixDelayMs = 5_000
        val started = System.currentTimeMillis()
        assertEquals(listOf(AutoIds.shuffle(AutoIds.DOWNLOADS)), ids(AutoIds.mix("artist:Adele")))
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertEquals(0, source.mixCalls)
        // The downloads stay browsable without a network.
        assertEquals(listOf(AutoIds.all(AutoIds.DOWNLOADS), AutoIds.shuffle(AutoIds.DOWNLOADS), AutoIds.track(AutoIds.DOWNLOADS, "w")), ids(AutoIds.DOWNLOADS))
    }

    @Test fun theRecentRootHoldsTheTitleToResume() {
        assertEquals(emptyList<String>(), ids(AutoIds.RECENT))
        source.recent = listOf(chasing)
        assertEquals(listOf(AutoIds.RESUME), ids(AutoIds.RECENT))
        assertEquals("Chasing pavements", children(AutoIds.RECENT)!!.single().mediaMetadata.title.toString())
        source.current = hello
        assertEquals("Hello", children(AutoIds.RECENT)!!.single().mediaMetadata.title.toString())
        assertEquals(AutoIds.RECENT, library.recentRoot().mediaId)
        assertEquals(AutoRequest.Resume, runBlocking { library.request(listOf(MediaItem.Builder().setMediaId(AutoIds.RESUME).build()), 0) })
    }

    @Test fun searchShowsArtistsAndAlbumsOfTheLibraryThenRankedTitles() = runBlocking {
        source.favorites = listOf(hello, water, chasing)
        source.catalogue = { listOf(song("x1", "Someone like you (karaoke)", "Karaoke stars", "x"), song("x2", "Someone like you", "Adele", "21")) }
        val results = library.searchResult("Adele", 0, 100)
        assertEquals(AutoIds.artist("adele"), results.first().mediaId)
        assertEquals(listOf("Artistes", "Albums", "Albums"), results.take(3).map(::group))
        assertEquals(results.size, library.searchCount("Adele"))
        // The catalogue as the phone's search ranks it: the exact title first, a karaoke version after it.
        val catalogue = library.searchResult("someone like you", 0, 100).filter { group(it) == "Titres" }
            .map { AutoIds.trackOf(it.mediaId)!!.second }
        assertEquals(listOf("x2", "x1"), catalogue)
    }

    @Test fun voiceUnderstandsRadioShuffleDownloadsMixesAndNothingInParticular() = runBlocking {
        source.stations = AutoStations(listOf(AutoArtistSeed("Adele", null, listOf(hello))), emptyList())
        source.catalogue = { q -> listOf(hello, blinding, song("rg", "Radio Ga Ga", "Queen", "The Works")).filter {
            it.title.contains(q, true) || it.artist.contains(q, true) } }
        assertEquals(AutoRequest.ArtistRadio("Adele", null, listOf(hello)), library.voice("radio Adele", null))
        assertEquals(AutoRequest.ArtistRadio("Adele", null, listOf(hello)), library.voice("la radio de Adele", null))
        assertEquals(AutoRequest.Radio(blinding), library.voice("Blinding lights radio", null))
        // « Radio Ga Ga » is a song, not a radio.
        assertEquals("rg", (library.voice("Radio Ga Ga", null) as AutoRequest.Play).tracks.first().id)
        // « … en aléatoire »: the same titles, shuffled.
        assertTrue(library.voice("Adele en aléatoire", null) is AutoRequest.Shuffle)
        source.downloads = listOf(water, chasing)
        assertEquals(AutoRequest.Shuffle(listOf(water, chasing)), library.voice("mes téléchargements", null))
        source.favorites = listOf(skyfall)
        assertEquals(AutoRequest.Shuffle(listOf(skyfall)), library.voice("my liked songs", null))
        assertEquals(AutoRequest.Shuffle(listOf(skyfall)), library.voice("mes j'aime", null))
        source.mixes = listOf(MixCard("artist:Adele", MixKind.Artist, "Adele", emptyList()))
        source.mixTracks = mapOf("artist:Adele" to listOf(hello, water))
        assertEquals(AutoRequest.Play(listOf(hello, water), 0), library.voice("mon mix", null))
        assertEquals(AutoRequest.Resume, library.voice("de la musique", null))
        assertEquals(AutoRequest.Resume, library.voice("  ", null))
    }

    @Test fun voiceExtrasNarrowTheRequest() = runBlocking {
        source.favorites = listOf(chasing, hello, water)
        source.catalogue = { listOf(song("y1", "Hello", "Adele", "25"), song("y2", "Hello", "Lionel Richie", "Can't slow down"),
            song("y3", "Jazz night", "Trio", "Blue")) }
        // A title asked for alone plays alone (autoplay carries on after it).
        val title = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_TITLE, "Hello"); putString(MediaStore.EXTRA_MEDIA_ARTIST, "Adele") }
        assertEquals(listOf("y1"), (library.voice("Hello Adele", title) as AutoRequest.Play).tracks.map { it.id })
        // An album of the library plays in its own order.
        val album = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_ALBUM, "25"); putString(MediaStore.EXTRA_MEDIA_ARTIST, "Adele") }
        assertEquals(AutoRequest.Play(listOf(hello, water), 0), library.voice("25", album))
        // A genre is shuffled.
        val genre = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/genre"); putString(MediaStore.EXTRA_MEDIA_GENRE, "jazz") }
        assertTrue(library.voice("jazz", genre) is AutoRequest.Shuffle)
    }

    @Test fun anEmptyVoiceRequestFallsBackToAMixWhenNothingCanResume() = runBlocking {
        source.mixes = listOf(MixCard("repeat", MixKind.OnRepeat, null, emptyList()))
        source.mixTracks = mapOf("repeat" to listOf(hello, water))
        assertNull(library.perform(library.voice("", null)))
        assertEquals(listOf("h", "w"), playback.shuffled.single())
        // Offline, the downloads.
        source.online = false
        source.downloads = listOf(chasing)
        assertNull(library.perform(AutoRequest.Resume))
        assertEquals(listOf("c"), playback.shuffled.last())
    }

    @Test fun voiceMarkersStayInsideATitle() {
        assertEquals(listOf("Video", "killed", "the", "radio", "star") to false,
            VoiceMatch.marked(listOf("Video", "killed", "the", "radio", "star"), VoiceMatch.radioWords))
        assertEquals(listOf("Adele") to true, VoiceMatch.marked(listOf("Adele", "en", "aléatoire"), VoiceMatch.shuffleWords))
        assertEquals(emptyList<String>() to true, VoiceMatch.marked(listOf("radio"), VoiceMatch.radioWords))
    }
}
