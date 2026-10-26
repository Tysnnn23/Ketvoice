package com.ketvoice.app.ui

object AppUi {
    val HOME = 0
    val EXPLORER = 1
    val LIBRARY = 2

    val tab = androidx.compose.runtime.mutableIntStateOf(HOME)

    val NONE = 0
    val SEARCH = 1
    val SETTINGS = 2
    val QUEUE = 3
    val DOWNLOADS = 4
    val LOCAL_FILES = 5
    val ADDONS_SEARCH = 6
    val LYRICS_SOURCES = 7
    val REPLAY = 8
    val EXPORT = 9
    val IMPORT = 10
    val LANGUAGE = 11
    val APP_ICON = 12
    val CANVAS_CONFIG = 13
    val lyricsSourcesList = 14
    val CATALOG = 15
    val SOURCES = 16
    val ARTIST = 17
    val PLAYLIST = 18
    val playlistId = androidx.compose.runtime.mutableStateOf<String?>(null)
    val showCreatePlaylist = androidx.compose.runtime.mutableStateOf(false)
    val showSpotifyImport = androidx.compose.runtime.mutableStateOf(false)
    val addToPlaylist = androidx.compose.runtime.mutableStateOf<com.ketvoice.app.data.Track?>(null)
    data class PlaylistResolution(val playlistId: String, val entryId: String)
    val playlistResolution = androidx.compose.runtime.mutableStateOf<PlaylistResolution?>(null)
    fun openPlaylist(id: String) { navigate(PLAYLIST, playlist = id) }
    val catalogRequest = androidx.compose.runtime.mutableStateOf<com.ketvoice.app.data.CatalogRequest?>(null)
    fun openCatalog(request: com.ketvoice.app.data.CatalogRequest) {
        navigate(CATALOG, catalog = request)
    }

    data class ArtistRequest(val id: String, val name: String)
    val artistRequest = androidx.compose.runtime.mutableStateOf<ArtistRequest?>(null)
    fun openArtist(id: String, name: String) {
        if (!id.startsWith("UC")) return
        navigate(ARTIST, artist = ArtistRequest(id, name))
    }

    val overlay = androidx.compose.runtime.mutableIntStateOf(NONE)

    private data class Destination(
        val tab: Int,
        val overlay: Int,
        val playlist: String?,
        val catalog: com.ketvoice.app.data.CatalogRequest?,
        val artist: ArtistRequest?,
        val player: Boolean,
        val lyrics: Boolean,
        val search: String
    )

    private val history = ArrayDeque<Destination>()

    private fun current() = Destination(tab.intValue, overlay.intValue, playlistId.value,
        catalogRequest.value, artistRequest.value, showPlayer.value, showLyrics.value, searchSeed.value)

    /** All route changes go through here; the previous screen and its arguments remain intact. */
    fun navigate(screen: Int, playlist: String? = null,
                 catalog: com.ketvoice.app.data.CatalogRequest? = null,
                 artist: ArtistRequest? = null) {
        require(screen != NONE)
        val previous = current()
        val target = previous.copy(overlay = screen, playlist = playlist ?: previous.playlist,
            catalog = catalog ?: previous.catalog, artist = artist ?: previous.artist,
            player = false, lyrics = false)
        if (previous == target) return
        history.addLast(previous)
        playlistId.value = target.playlist
        catalogRequest.value = target.catalog
        artistRequest.value = target.artist
        showPlayer.value = false
        showLyrics.value = false
        overlay.intValue = screen
        bottomCollapsed.value = false
    }

    fun back() {
        if (showPlayer.value) {
            showPlayer.value = false
            showLyrics.value = false
            return
        }
        val previous = if (history.isEmpty()) null else history.removeLast()
        if (previous == null) {
            overlay.intValue = NONE
            showLyrics.value = false
            return
        }
        tab.intValue = previous.tab
        playlistId.value = previous.playlist
        catalogRequest.value = previous.catalog
        artistRequest.value = previous.artist
        searchSeed.value = previous.search
        overlay.intValue = previous.overlay
        showPlayer.value = previous.player
        showLyrics.value = previous.lyrics
    }

    val searchSeed = androidx.compose.runtime.mutableStateOf("")

    val showPlayer = androidx.compose.runtime.mutableStateOf(false)
    val showPublisher = androidx.compose.runtime.mutableStateOf(false)
    val publisherTrack = androidx.compose.runtime.mutableStateOf<com.ketvoice.app.data.Track?>(null)
    val publisherLink = androidx.compose.runtime.mutableStateOf<String?>(null)
    val editImportedSong = androidx.compose.runtime.mutableStateOf(false)
    fun openPublisher(track: com.ketvoice.app.data.Track? = null, edit: Boolean = false) {
        if (edit && com.ketvoice.app.data.ImportedSong.id(track) == null) return
        editImportedSong.value = edit
        publisherTrack.value = track
        showPublisher.value = true
    }
    fun openPublisherLink(link: String) {
        if (!showPublisher.value) { publisherTrack.value = null; editImportedSong.value = false }
        publisherLink.value = link
        showPlayer.value = false
        showAccount.value = false
        trackMenuTrack.value = null
        showPublisher.value = true
    }

    data class CoverFlight(
        val cover: String?,
        val title: String,
        val from: androidx.compose.ui.geometry.Rect,
        val to: androidx.compose.ui.geometry.Rect,
        val opening: Boolean,
        val token: Long = System.nanoTime()
    )
    val playerCoverBounds = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    val miniCoverBounds = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    val coverFlight = androidx.compose.runtime.mutableStateOf<CoverFlight?>(null)

    val showLyrics = androidx.compose.runtime.mutableStateOf(false)

    val showAccount = androidx.compose.runtime.mutableStateOf(false)

    val bottomCollapsed = androidx.compose.runtime.mutableStateOf(false)

    val showTrackMenu = androidx.compose.runtime.mutableStateOf(false)
    val trackMenuTrack = androidx.compose.runtime.mutableStateOf<com.ketvoice.app.data.Track?>(null)
    fun selectTab(index: Int) {
        history.clear()
        playlistResolution.value = null
        tab.intValue = index
        overlay.intValue = NONE
        showPlayer.value = false
        showLyrics.value = false
        trackMenuTrack.value = null
        bottomCollapsed.value = false
    }
    val showAudioOutput = androidx.compose.runtime.mutableStateOf(false)
    val showEqualizer = androidx.compose.runtime.mutableStateOf(false)
    val showHandoff = androidx.compose.runtime.mutableStateOf(false)
    val showCapsule = androidx.compose.runtime.mutableStateOf(false)

    val showLyricsSource = androidx.compose.runtime.mutableStateOf(false)

    fun closeOverlays() {
        history.clear()
        overlay.value = NONE
        showPlayer.value = false
        showAccount.value = false
        showTrackMenu.value = false
        trackMenuTrack.value = null
        showAudioOutput.value = false
        showLyrics.value = false
        showLyricsSource.value = false
    }
}
