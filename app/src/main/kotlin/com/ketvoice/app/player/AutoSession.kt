@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.HomeFeed
import com.ketvoice.app.data.HomeShelf
import com.ketvoice.app.data.LibrarySearch
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.SearchGroups
import com.ketvoice.app.data.SearchRank
import com.ketvoice.app.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * The cover of a title for the session, the car and the notification: a downloaded title's saved cover (it shows
 * offline), else the enriched cover; the owner's overrides always apply.
 */
internal fun playbackCover(track: Track): String? =
    Downloader.itemFor(track.id)?.track?.coverUrl?.takeIf { it.startsWith("file:") }?.let { MediaOverrides.cover(track, it) }
        ?: ArtworkRepo.coverForTrack(track) ?: track.coverUrl

/** Whether the phone has a network that reaches the internet now. */
internal fun hasInternet(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
    capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(true)

/** The listener's stores, read the way the phone's screens read them. */
internal class AppAutoSource(private val context: Context) : AutoSource {
    override fun prepare() {
        // Opening the app does the same: the stores, artwork, overrides and the restored session.
        Player.connect(context)
        runCatching { ArtistPrefs.init(context) }
    }

    private fun live(tracks: List<Track>) = PersonalMixes.withLocalFiles(tracks, LocalMusic.tracks.value)

    override fun recent(): List<Track> = live(ListeningHistory.tracks.value)

    override fun favorites(): List<Track> {
        val ids = AppSettings.favoriteIds.value
        return live((ListeningHistory.knownTracks.value + ListeningHistory.tracks.value + Downloader.items.value.map { it.track })
            .distinctBy { it.id }.filter { it.id in ids })
    }

    override fun playlists(): List<LocalPlaylist> = PlaylistStore.lists.value
    override fun downloads(): List<Track> = Downloader.items.value.map { it.track }
    override fun isDownloaded(trackId: String): Boolean = Downloader.itemFor(trackId) != null

    override fun library(): List<Track> = live((favorites() + downloads() + playlists().flatMap { list -> list.entries.mapNotNull { it.track } } +
        LocalMusic.tracks.value).distinctBy { it.id })

    override fun mixes(): List<MixCard> = runCatching {
        PersonalMixes.propose(ListeningHistory.knownTracks.value.filter(ArtistPrefs::allows),
            ListeningHistory.tracks.value.filter(ArtistPrefs::allows), ListeningHistory.playCounts.value, AppSettings.favoriteIds.value)
    }.getOrDefault(emptyList())

    override fun mixTitle(card: MixCard): String = com.ketvoice.app.ui.home.mixTitle(card)
    override fun mixSubtitle(card: MixCard): String = com.ketvoice.app.ui.home.mixSubtitle(card)
    override suspend fun mixTracks(id: String): List<Track>? = withContext(Dispatchers.IO) { runCatching { PersonalMixes.tracks(id) }.getOrNull() }

    /** Home's shelves as the phone last built them (from its saved pool): never a network request of the car's. */
    override fun shelves(): List<HomeShelf> = HomeFeed.shelves.value
        .map { it.copy(tracks = it.tracks.filter(ArtistPrefs::allows)) }.filter { it.tracks.isNotEmpty() }

    override fun stations(): AutoStations = runCatching {
        val known = ListeningHistory.knownTracks.value.filter(ArtistPrefs::allows)
        val counts = ListeningHistory.playCounts.value
        AutoStations(
            PersonalMixes.topArtists(known, counts, AppSettings.favoriteIds.value).take(AutoLibrary.STATIONS)
                .map { AutoArtistSeed(it.name, it.catalogId, it.tracks) },
            PersonalMixes.onRepeat(known, counts).take(AutoLibrary.STATIONS))
    }.getOrDefault(AutoStations())

    override fun current(): Track? = Player.state.value.current
    override fun playing(): Boolean = Player.state.value.playWhenReady
    override fun online(): Boolean = hasInternet(context)
    override fun taste(): SearchRank.Taste = SearchRank.liveTaste()

    override fun searchLibrary(query: String): List<Track> = live(LibrarySearch.tracks(query, downloads(), PlaylistStore.lists.value,
        LocalMusic.tracks.value, ListeningHistory.knownTracks.value, limit = AutoLibrary.MAX_ITEMS))

    override fun searchPlaylists(query: String): List<LocalPlaylist> = LibrarySearch.playlists(query, PlaylistStore.lists.value)

    override suspend fun searchCatalogue(query: String): List<Track> {
        // Offline, the car is not kept waiting for sources that cannot answer: the library answers alone.
        if (!hasInternet(context)) return emptyList()
        val latest = AtomicReference<List<Track>>(emptyList())
        val searches = mutableListOf<suspend () -> List<Track>>(
            { MusicApi.searchYtMusic(query) }, { PublicationApi.search(query) }, { MusicApi.searchAddons(query) })
        CustomSources.sources.value.filter { it.enabled }.forEach { source -> searches += { CustomSources.search(source, query) } }
        // What arrived in time is kept: a slow source never holds the car's screen.
        withTimeoutOrNull(AutoLibrary.SEARCH_TIMEOUT_MS - 500) {
            withContext(Dispatchers.IO) { SearchGroups.collectProgressively(searches) { latest.set(it) } }
        }
        val found = SearchGroups.group(latest.get().filter(ArtistPrefs::allows)).map { it.primary }
        ArtworkRepo.enrich(found)
        return found
    }

    override fun lookup(trackId: String): Track? {
        val state = Player.state.value
        return live(listOfNotNull(
            state.queue.firstOrNull { it.id == trackId },
            Downloader.itemFor(trackId)?.track,
            ListeningHistory.tracks.value.firstOrNull { it.id == trackId },
            ListeningHistory.knownTracks.value.firstOrNull { it.id == trackId },
            PlaylistStore.lists.value.firstNotNullOfOrNull { list -> list.entries.firstOrNull { it.track?.id == trackId }?.track },
            LocalMusic.tracks.value.firstOrNull { it.id == trackId })).firstOrNull()
    }

    /** A downloaded title shows its saved cover (it reaches the car offline); the owner's overrides still apply. */
    override fun cover(track: Track): String? = playbackCover(track)
    override fun text(french: String): String = AppText.t(french)
}

/** Sends the car's requests to the phone's queue, on the main thread where [Player] lives. */
internal class PlayerAutoPlayback(private val context: Context) : AutoPlayback {
    override fun play(tracks: List<Track>, index: Int) {
        Player.connect(context)
        Player.playTrack(tracks[index], tracks, index)
    }

    override fun shuffle(tracks: List<Track>) {
        Player.connect(context)
        Player.playShuffled(tracks)
    }

    override fun enqueue(tracks: List<Track>) {
        Player.connect(context)
        Player.enqueue(tracks)
    }

    override suspend fun resume(): Boolean {
        Player.connect(context)
        // The saved session (its queue and its place) is read from the disk when the queue first connects.
        withTimeoutOrNull(2_500) { Player.state.first { it.current != null } } ?: return false
        if (!Player.state.value.playWhenReady) Player.toggle()
        return true
    }

    override fun radio(seed: Track) {
        Player.connect(context)
        Player.startRadio(seed)
    }

    override fun artistRadio(name: String, catalogId: String?, tracks: List<Track>) {
        Player.connect(context)
        Player.startArtistRadio(name, catalogId, tracks)
    }
}

/**
 * « Joue X sur Ketvoice » said to the phone's assistant outside the car arrives as an activity intent
 * (MEDIA_PLAY_FROM_SEARCH, declared in the mobile manifest): the same voice request as in the car.
 */
internal object AutoVoice {
    @Volatile private var library: AutoLibrary? = null

    /** Plays what [intent] asks for when it is a voice search; false for any other intent. */
    fun handle(context: Context, intent: Intent?, scope: CoroutineScope): Boolean {
        if (intent?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return false
        val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
        val extras = intent.extras?.let(::Bundle)
        // Handled once: a recreated activity must not play it again.
        intent.action = null
        val app = context.applicationContext
        val voice = library ?: AutoLibrary(AppAutoSource(app), PlayerAutoPlayback(app), artistRadio = com.ketvoice.app.BuildConfig.IS_TV)
            .also { library = it }
        scope.launch(Dispatchers.Main) {
            voice.prepare()
            val request = withContext(Dispatchers.Default) { voice.voice(query, extras) }
            val message = voice.perform(request)
            if (message != null) android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
            else if (request !is AutoRequest.Missing && request !is AutoRequest.Enqueue) com.ketvoice.app.ui.AppUi.showPlayer.value = true
        }
        return true
    }
}

/** Raised so the session leaves its player alone: the phone's queue took the request. */
private class HandledByQueue : CancellationException("Handled by the Ketvoice queue")

/**
 * The library half of the playback service's session callback. Requests carrying a stream
 * (the app's own controller) go to the player as before; requests carrying only media ids or a
 * voice query are resolved here and handed to the phone's queue. The car's buttons on its
 * Now Playing screen ([CarControls]) are carried out here too, for any controller.
 */
@UnstableApi
internal open class AutoLibraryCallback(private val context: Context) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** How many tabs the car shows (its root hints; Android Auto allows four). */
    @Volatile private var tabLimit = AutoIds.TABS.size

    internal val library: AutoLibrary by lazy {
        val app = context.applicationContext
        AutoLibrary(testSource ?: AppAutoSource(app), testPlayback ?: PlayerAutoPlayback(app), { AutoArtwork.uri(app, it) },
            iconPackage = app.packageName, artistRadio = com.ketvoice.app.BuildConfig.IS_TV)
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try { result.set(block()) }
            catch (cancelled: CancellationException) { result.cancel(false) }
            catch (error: Throwable) { result.setException(error) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }

    private fun listResult(params: LibraryParams?, block: suspend () -> List<MediaItem>?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        future {
            // Browsing reads the stores and the network off the main thread: the phone's screen never waits for the car.
            withContext(Dispatchers.Default) {
                try {
                    block()?.let { LibraryResult.ofItemList(it, params) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } catch (_: AutoUnavailable) {
                    LibraryResult.ofError(SessionError.ERROR_IO)
                }
            }
        }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                                  params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        runCatching { library.prepare() }
        params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)?.takeIf { it > 0 }?.let { tabLimit = it }
        val rootParams = LibraryParams.Builder().setOffline(params?.isOffline == true).setRecent(params?.isRecent == true)
            .setExtras(library.rootExtras()).build()
        val root = when {
            // The system's « recent » request (playback resumption): the title to pick up again, at its place.
            params?.isRecent == true -> library.recentRoot()
            // A car asking for what plays without a network gets the downloads as its root.
            params?.isOffline == true -> library.tabs().first { it.mediaId == AutoIds.DOWNLOADS }
            else -> library.root()
        }
        return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
    }

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                               page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        listResult(params) { library.children(parentId, page, pageSize, tabLimit) }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                           mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
        withContext(Dispatchers.Default) {
            try {
                library.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } catch (_: AutoUnavailable) { LibraryResult.ofError(SessionError.ERROR_IO) }
        }
    }

    override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                             params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(LibraryResult.ofVoid())

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                          params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            // Always answered, even with nothing: a car waits for this count before it shows the results.
            val count = withContext(Dispatchers.Default) { runCatching { library.searchCount(query) }.getOrDefault(0) }
            runCatching { session.notifySearchResultChanged(browser, query, count, params) }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
                                   page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        listResult(params) { library.searchResult(query, page, pageSize) }

    /**
     * Items without a stream (a car's media ids, a voice query) are resolved by the phone's queue. The app's own
     * items pass as before, and so does a bare address (« play from URI »), which stays unsupported as it was.
     */
    private fun needsQueue(items: List<MediaItem>) = items.isNotEmpty() && items.any { it.localConfiguration == null } &&
        items.none { it.mediaId.isBlank() && it.requestMetadata.searchQuery == null && it.requestMetadata.mediaUri != null }

    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                 mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
        if (!needsQueue(mediaItems)) return super.onAddMediaItems(mediaSession, controller, mediaItems)
        return future {
            val request = withContext(Dispatchers.Default) { library.enqueueRequest(mediaItems) }
            report(mediaSession, library.perform(request))
            throw HandledByQueue()
        }
    }

    override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                 mediaItems: MutableList<MediaItem>, startIndex: Int,
                                 startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (!needsQueue(mediaItems)) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        return future {
            // Resolved off the main thread; the phone's queue is then driven from the main thread, where it lives.
            val request = withContext(Dispatchers.Default) { library.request(mediaItems, startIndex) }
            report(mediaSession, library.perform(request))
            throw HandledByQueue()
        }
    }

    /** « Play » from a car with nothing loaded picks up the phone's last session (the app itself keeps its own behaviour). */
    override fun onPlaybackResumption(mediaSession: MediaSession,
                                      controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (controller.packageName == context.packageName && !mediaSession.isMediaNotificationController(controller))
            return super.onPlaybackResumption(mediaSession, controller)
        return future {
            report(mediaSession, library.perform(AutoRequest.Resume))
            throw HandledByQueue()
        }
    }

    /** The car's Now Playing buttons (like, shuffle, repeat, Ketmix, radio): from any controller, through [Player]. */
    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand,
                                 args: Bundle): ListenableFuture<SessionResult> {
        if (customCommand.customAction !in CarControls.ACTIONS) return super.onCustomCommand(session, controller, customCommand, args)
        val message = CarControls.perform(context.applicationContext, customCommand.customAction)
        if (message != null) report(session, message)
        return Futures.immediateFuture(SessionResult(if (message == null) SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE))
    }

    private fun report(session: MediaSession, message: String?) {
        if (message != null) runCatching { session.sendError(SessionError(SessionError.ERROR_BAD_VALUE, message)) }
    }

    companion object {
        /** Stores and queue of the Robolectric tests; never set by the app. */
        @androidx.annotation.VisibleForTesting internal var testSource: AutoSource? = null
        @androidx.annotation.VisibleForTesting internal var testPlayback: AutoPlayback? = null
    }
}

/**
 * Builds the playback service's session as a library session: same player, same activity, same
 * callback (which keeps the service's custom commands), plus the browse tree above.
 */
@UnstableApi
internal class AutoLibrarySessionBuilder(private val service: MediaLibraryService, private val player: MediaPlayer) {
    private var activity: PendingIntent? = null
    private var callback: AutoLibraryCallback? = null
    private var bitmaps: androidx.media3.common.util.BitmapLoader? = null

    fun setSessionActivity(intent: PendingIntent) = apply { activity = intent }
    fun setCallback(value: AutoLibraryCallback) = apply { callback = value }
    fun setBitmapLoader(value: androidx.media3.common.util.BitmapLoader) = apply { bitmaps = value }

    fun build(): MediaLibrarySession {
        val builder = MediaLibrarySession.Builder(service, player, checkNotNull(callback))
        activity?.let(builder::setSessionActivity)
        bitmaps?.let(builder::setBitmapLoader)
        return builder.build()
    }
}
