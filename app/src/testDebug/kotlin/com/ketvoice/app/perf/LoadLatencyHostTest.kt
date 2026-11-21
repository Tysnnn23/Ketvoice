package com.ketvoice.app.perf

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.HomeFeed
import com.ketvoice.app.data.HomeReleases
import com.ketvoice.app.data.KetvoiceApi
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.SearchGroups
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.YouTubeMusicSession
import com.ketvoice.app.data.YouTubeProfile
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 8.1 perf81load (owner: « optimise les performances, chargement et latence de l'app car actuellement c'est
 * catastrophique »): the app's own network code, timed against a fake network with the delays of a phone on 4G
 * (Deezer 250 ms, YouTube Music 450 ms, its home 700 ms, YouTube's player 350 ms, the Ketvoice API 700 ms, its
 * publications 400 ms, a Canvas lookup 600 ms and its file 1.2 s). Each test prints its numbers (`perf81 …`) and keeps
 * them in `build/perf81/load.txt`; the assertions hold what 8.1 fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoadLatencyHostTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
    private fun report(line: String) {
        println("perf81 $line")
        File("build/perf81").apply { mkdirs() }.resolve("load.txt").appendText(line + "\n")
    }

    // --- Answers shaped as the real services answer --------------------------------------------------------------

    private fun deezerTrack(i: Int, artist: String, artistId: Int = 100 + i) = JSONObject().put("id", 9000 + i)
        .put("title", "Titre $i").put("duration", 200).put("artist", JSONObject().put("id", artistId).put("name", artist))
        .put("album", JSONObject().put("id", 7000 + i).put("title", "Album $i").put("cover_xl", "https://e-cdns-images.dzcdn.net/$i.jpg"))
    private fun deezerTracks(artist: String, n: Int = 20) = JSONObject().put("data", JSONArray().apply { repeat(n) { put(deezerTrack(it, artist)) } }).toString()
    private fun deezerAlbums(artist: String) = JSONObject().put("data", JSONArray().apply {
        repeat(6) { put(JSONObject().put("id", 5000 + it).put("title", "$artist $it").put("release_date", "2026-0${it + 1}-01")
            .put("cover_xl", "https://e-cdns-images.dzcdn.net/a$it.jpg").put("record_type", "album")) }
    }).toString()
    private fun deezerArtistHit(name: String) = JSONObject().put("data", JSONArray().put(JSONObject().put("id", name.hashCode() and 0xffff).put("name", name))).toString()

    private fun ytRow(videoId: String, title: String, artist: String): JSONObject {
        fun browse(id: String, type: String) = JSONObject().put("browseEndpoint", JSONObject().put("browseId", id)
            .put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", type))))
        val watch = JSONObject().put("watchEndpoint", JSONObject().put("videoId", videoId)
            .put("watchEndpointMusicSupportedConfigs", JSONObject().put("watchEndpointMusicConfig", JSONObject().put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV"))))
        val meta = JSONArray().put(JSONObject().put("text", artist).put("navigationEndpoint", browse("UCartist", "MUSIC_PAGE_TYPE_ARTIST")))
            .put(JSONObject().put("text", "Album").put("navigationEndpoint", browse("MPREalbum", "MUSIC_PAGE_TYPE_ALBUM")))
            .put(JSONObject().put("text", "3:20"))
        fun column(runs: JSONArray) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", runs)))
        return JSONObject().put("musicResponsiveListItemRenderer", JSONObject().put("flexColumns", JSONArray()
            .put(column(JSONArray().put(JSONObject().put("text", title).put("navigationEndpoint", watch)))).put(column(meta))))
    }
    private fun ytSearch(q: String) = JSONObject().put("contents", JSONArray().apply { repeat(8) { put(ytRow("vid${q.hashCode().toString(16).takeLast(4)}$it".padEnd(11, 'x').take(11), if (it == 0) "Titre 0" else "$q $it", "Artiste")) } }).toString()
    private fun ytHome() = JSONObject().put("contents", JSONArray().apply {
        repeat(3) { s -> put(JSONObject().put("musicCarouselShelfRenderer", JSONObject()
            .put("header", JSONObject().put("musicCarouselShelfBasicHeaderRenderer", JSONObject().put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Étagère $s"))))))
            .put("contents", JSONArray().apply { repeat(6) { put(ytRow("home${s}x$it".padEnd(11, 'h').take(11), "Accueil $s $it", "Artiste")) } }))) }
    }).toString()
    private fun ytPlayer(ok: Boolean) = if (!ok) JSONObject().put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED")).toString()
        else JSONObject().put("playabilityStatus", JSONObject().put("status", "OK")).put("streamingData", JSONObject().put("adaptiveFormats",
            JSONArray().put(JSONObject().put("mimeType", "audio/webm; codecs=\"opus\"").put("bitrate", 160_000).put("itag", 251)
                .put("url", "https://rr1---sn.googlevideo.com/videoplayback?id=1")))).toString()
    private fun kvTracks(q: String) = JSONObject().put("tracks", JSONArray().apply { repeat(10) {
        put(JSONObject().put("id", "kv:um:$q$it").put("title", "$q $it").put("artist", "Artiste").put("source", "um").put("addonId", "$q$it").put("durationMs", 200_000)) } }).toString()

    /** The 4G phone: every service with its delay; anything else (covers, lyrics) is offline. */
    private fun phone(url: String, method: String, body: String): FakeNet.Answer? = when {
        "api.deezer.com/search/artist" in url -> FakeNet.Answer(250, deezerArtistHit(java.net.URLDecoder.decode(url.substringAfter("q=").substringBefore('&'), "UTF-8")))
        "api.deezer.com/search/album" in url -> FakeNet.Answer(250, deezerAlbums("Artiste"))
        "api.deezer.com/search" in url -> FakeNet.Answer(250, deezerTracks(java.net.URLDecoder.decode(url.substringAfter("q=").substringBefore('&'), "UTF-8").removePrefix("artist:").trim('"')))
        Regex("api.deezer.com/artist/\\d+/albums").containsMatchIn(url) -> FakeNet.Answer(250, deezerAlbums("Artiste"))
        Regex("api.deezer.com/artist/\\d+/top").containsMatchIn(url) -> FakeNet.Answer(250, deezerTracks("Artiste", 30))
        Regex("api.deezer.com/album/\\d+").containsMatchIn(url) -> FakeNet.Answer(250, JSONObject().put("title", "Album").put("artist", JSONObject().put("id", 1).put("name", "Artiste"))
            .put("tracks", JSONObject().put("data", JSONArray().apply { repeat(14) { put(deezerTrack(it, "Artiste")) } })).toString())
        "music.youtube.com/youtubei/v1/browse" in url -> FakeNet.Answer(700, ytHome())
        "music.youtube.com/youtubei/v1/search" in url -> FakeNet.Answer(450, ytSearch(JSONObject(body).optString("query")))
        "www.youtube.com/youtubei/v1/player" in url -> FakeNet.Answer(350, ytPlayer(ok = "ANDROID_VR" in body))
        "ketvoice-content/search" in url -> FakeNet.Answer(400, JSONObject().put("tracks", JSONArray()).toString())
        "ketvoice-api/search" in url -> FakeNet.Answer(700, kvTracks(java.net.URLDecoder.decode(url.substringAfter("q=").substringBefore('&'), "UTF-8")))
        "ketvoice-api/stream" in url -> FakeNet.Answer(900, JSONObject().put("url", "https://cdn.example/um.flac").put("format", "flac").put("quality", "16-bit").toString())
        else -> null
    }

    @After fun realNetwork() { FakeNet.off() }

    // --- Search ---------------------------------------------------------------------------------------------------

    /** What the search page asks for « Tous » (SearchScreen): YouTube Music, the publications and the Ketvoice API. */
    private suspend fun searchAll(q: String): Pair<Long, Long> {
        val start = System.nanoTime()
        var apiAt = -1L
        SearchGroups.collectProgressively(listOf({ MusicApi.searchYtMusic(q) }, { PublicationApi.search(q) }, { MusicApi.searchAddons(q) })) { found ->
            if (apiAt < 0 && found.any { it.addon == "um" }) apiAt = ms(start)
        }
        return apiAt to ms(start)
    }

    @Test fun searchAsksEachServiceOnceAndARepeatedQueryAtOnce() = runBlocking {
        FakeNet.reset(::phone)
        val (apiFirst, allFirst) = searchAll("damso")
        val requestsFirst = FakeNet.calls.size
        val publications = FakeNet.count("ketvoice-content/search")
        // The listener erases a letter and types it again: the same query a moment later.
        FakeNet.calls.clear()
        val (_, allAgain) = searchAll("damso")
        val requestsAgain = FakeNet.calls.size
        report("search: Ketvoice API results at $apiFirst ms, all at $allFirst ms, $requestsFirst requests ($publications publication searches); same query again: $allAgain ms, $requestsAgain requests")
        assertEquals("one publication search per query", 1, publications)
        assertEquals("a repeated query asks nothing", 0, requestsAgain)
        assertTrue("a repeated query answers at once: $allAgain ms", allAgain < 100)
    }

    // --- First sound ----------------------------------------------------------------------------------------------

    @Test fun aTitlePlayedAgainResolvesItsYouTubeStreamInOneRequest() = runBlocking {
        FakeNet.reset(::phone)
        val song = Track("dz:9000", "Titre 0", "Artiste", "Album 0", durationMs = 200_000)
        var start = System.nanoTime()
        val first = MusicApi.youtubeStream(song)
        val firstMs = ms(start); val firstRequests = FakeNet.calls.size
        FakeNet.calls.clear()
        start = System.nanoTime()
        val again = MusicApi.youtubeStream(song)
        val againMs = ms(start); val againRequests = FakeNet.calls.size
        report("first sound (YouTube source): stream resolved in $firstMs ms ($firstRequests requests); played again or skipped back: $againMs ms ($againRequests requests)")
        assertTrue(first != null && again != null)
        assertEquals("played again: only the player request", 1, againRequests)
    }

    // --- Home -----------------------------------------------------------------------------------------------------

    @Test fun homeReleasesOfTheFollowedArtistsLoadTogether() = runBlocking {
        FakeNet.reset(::phone)
        val artists = listOf("Werenoi", "Gazo", "Tiakola", "Ninho", "SCH", "Damso")
        val known = artists.flatMapIndexed { a, name -> (0..2).map { Track("h$a-$it", "T$it", name, "A") } }
        val counts = known.associate { it.id to 3 }
        val start = System.nanoTime()
        val releases = HomeReleases.load(known, counts, emptySet(), nowMs = 1L)
        val took = ms(start)
        report("home: « Nouveautés de vos artistes » for ${artists.size} artists in $took ms (${FakeNet.calls.size} requests, ${releases.size} albums)")
        assertTrue("the releases load in parallel: $took ms", took < 1_500)
    }

    @Test fun homeShowsThePersonalYouTubeHomeWithoutWaitingForTheCatalogue() = runBlocking {
        FakeNet.reset(::phone)
        @Suppress("UNCHECKED_CAST")
        (YouTubeMusicSession::class.java.getDeclaredField("_profile").apply { isAccessible = true }.get(YouTubeMusicSession)
            as MutableStateFlow<YouTubeProfile>).value = YouTubeProfile(connected = true, name = "Test")
        val prefs = context.getSharedPreferences("home-feed-v1", Context.MODE_PRIVATE)
        prefs.edit().putString("pool", com.google.gson.Gson().toJson((0 until 30).map { Track("dz:$it", "Titre $it", "Artiste", "Album", albumId = "$it") })).commit()
        fun startAgain(): Long {
            HomeFeed::class.java.getDeclaredField("started").apply { isAccessible = true }.setBoolean(HomeFeed, false)
            HomeFeed::class.java.getDeclaredField("personal").apply { isAccessible = true }.setBoolean(HomeFeed, false)
            val start = System.nanoTime()
            HomeFeed.start(context)
            while (HomeFeed.origin.value != "Accueil YouTube Music personnel" && ms(start) < 5_000) Thread.sleep(5)
            val shown = ms(start)
            while (HomeFeed.loading.value && ms(start) < 5_000) Thread.sleep(5)
            Thread.sleep(800)
            (HomeFeed::class.java.getDeclaredField("_origin").apply { isAccessible = true }.get(HomeFeed) as MutableStateFlow<String>).value = "Sélections locales"
            return shown
        }
        val firstStart = startAgain()
        val local = HomeFeed.shelves.value.isNotEmpty()
        val nextStart = startAgain()
        report("home: personal YouTube Music home shown after $firstStart ms on a first start, $nextStart ms on the next start (local shelves from disk at once: $local)")
        assertTrue("the YouTube home does not wait for the catalogue: $firstStart ms", firstStart < 900)
        assertTrue("the last YouTube home comes from the disk at once: $nextStart ms", nextStart < 100)
    }

    // --- Album and artist pages -----------------------------------------------------------------------------------

    @Test fun anAlbumOpenedWhileItsTileFetchesItIsAskedOnce() = runBlocking {
        FakeNet.reset(::phone)
        val request = CatalogRequest("album", "7001", "Album 1")
        val prefetch = async(kotlinx.coroutines.Dispatchers.IO) { CatalogRepo.page(request) }
        delay(150)
        val start = System.nanoTime()
        val page = CatalogRepo.page(request)
        val opened = ms(start)
        prefetch.await()
        val requests = FakeNet.count("api.deezer.com/album/")
        report("album page: opened ${opened} ms after the tap while its tile was fetching it (${requests} album requests)")
        assertTrue(page != null)
        assertEquals("one request for the prefetch and the page", 1, requests)
    }

    @Test fun anArtistNameIsLookedUpOnce() = runBlocking {
        FakeNet.reset(::phone)
        val start = System.nanoTime()
        CatalogRepo.artistTop("Aya Nakamura")
        val firstMs = ms(start)
        val second = System.nanoTime()
        CatalogRepo.artistReleases("Aya Nakamura")
        val secondMs = ms(second)
        val searches = FakeNet.count("search/artist")
        report("artist: top titles in $firstMs ms, then releases in $secondMs ms ($searches name lookups)")
        assertEquals(1, searches)
    }

    // --- Canvas ---------------------------------------------------------------------------------------------------

    @Test fun thePlayingTitlesCanvasDoesNotQueueBehindTheLookAhead() = runBlocking {
        File(context.filesDir, "canvas").deleteRecursively()
        context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE).edit().clear().commit()
        CanvasRepo::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(CanvasRepo, null)
        CanvasRepo.init(context)
        CanvasRepo.mayFetch = { _, _ -> true }
        CanvasRepo.extractFrame = { Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888) }
        CanvasRepo.lookup = { track -> delay(600); KetvoiceApi.CanvasResult("https://canvaz.scdn.co/${track.id}.mp4", "sp${track.id}") }
        CanvasRepo.fetchFile = { _, dest, _ ->
            Thread.sleep(1_200)
            dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(20_000).also { "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1).copyInto(it) }); dest
        }
        try {
            val queue = (0..3).map { Track("cv$it", "Titre $it", "Artiste", "Album $it") }
            // Title 0 plays: the next two are fetched ahead, then the listener taps another title.
            val ahead = async(kotlinx.coroutines.Dispatchers.IO) { CanvasRepo.aheadOf(queue, 0) }
            delay(100)
            val start = System.nanoTime()
            CanvasRepo.resolve(queue[3])
            val shown = ms(start)
            ahead.await()
            report("canvas: the tapped title's Canvas ready after $shown ms while two look-ahead Canvas were fetching (lookup 600 ms + file 1.2 s)")
            assertTrue("own lookup and file only: $shown ms", shown < 2_400)
        } finally {
            CanvasRepo.lookup = { KetvoiceApi.canvas(it) }
        }
    }
}
