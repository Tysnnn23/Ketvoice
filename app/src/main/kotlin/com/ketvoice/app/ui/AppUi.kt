package com.ketvoice.app.ui

/**
 * État d'interface global de l'app (léger, sans navigation-compose pour garder
 * le Liquide Glass stable sur les transitions).
 */
object AppUi {
    val HOME = 0
    val EXPLORER = 1
    val LIBRARY = 2

    /** Onglet racine actif (HOME/EXPLORER/LIBRARY) */
    val tab = androidx.compose.runtime.mutableIntStateOf(HOME)

    /** Overlay plein écran au-dessus des onglets (remplace toute la zone de contenu) */
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
    fun openPlaylist(id: String) {
        push(OverlayEntry(PLAYLIST, null, null, null, ""))
        playlistId.value = id
        showPlayer.value = false
        bottomCollapsed.value = false
    }
    val catalogRequest = androidx.compose.runtime.mutableStateOf<com.ketvoice.app.data.CatalogRequest?>(null)
    fun openCatalog(request: com.ketvoice.app.data.CatalogRequest) {
        push(OverlayEntry(CATALOG, null, null, null, ""))
        catalogRequest.value = request
        bottomCollapsed.value = false
    }

    data class ArtistRequest(val id: String, val name: String)
    val artistRequest = androidx.compose.runtime.mutableStateOf<ArtistRequest?>(null)
    fun openArtist(id: String, name: String) {
        // On n'exige plus un channelId YouTube ("UC..." ) : les cartes d'artiste
        // issues de la recherche ou des métadonnées locales n'en fournissent pas
        // toujours, et le `return` silencieux faisait échouer l'ouverture sans
        // aucun retour visuel. On accepte donc tout identifiant exploitable.
        if (id.isBlank() && name.isBlank()) return
        push(OverlayEntry(ARTIST, null, null, null, ""))
        artistRequest.value = ArtistRequest(id, name)
        // Le lecteur plein écran est au-dessus des overlays : sans cette ligne,
        // ouvrir une fiche artiste depuis le lecteur ne montrait rien.
        showPlayer.value = false
        showLyrics.value = false
        bottomCollapsed.value = false
    }

    val overlay = androidx.compose.runtime.mutableIntStateOf(NONE)

    /** Pile d'overlays : artiste → artiste similaire, album → artiste, etc. */
    data class OverlayEntry(
        val overlay: Int,
        val playlistId: String?,
        val catalogRequest: com.ketvoice.app.data.CatalogRequest?,
        val artistRequest: ArtistRequest?,
        val searchSeed: String
    )
    private val overlayStack = ArrayDeque<OverlayEntry>()

    /** Direction de la dernière navigation : pour la transition AnimatedContent. */
    val NAV_JUMP = 0
    val NAV_PUSH = 1
    val NAV_POP = 2
    val navKind = androidx.compose.runtime.mutableIntStateOf(NAV_JUMP)

    private fun snapshot(): OverlayEntry = OverlayEntry(
        overlay.intValue, playlistId.value, catalogRequest.value, artistRequest.value, searchSeed.value
    )

    private fun restore(entry: OverlayEntry) {
        playlistId.value = entry.playlistId
        catalogRequest.value = entry.catalogRequest
        artistRequest.value = entry.artistRequest
        searchSeed.value = entry.searchSeed
        overlay.intValue = entry.overlay
    }

    private fun push(entry: OverlayEntry) {
        navKind.intValue = NAV_PUSH
        overlayStack.addLast(snapshot())
        playlistId.value = null
        catalogRequest.value = null
        artistRequest.value = null
        searchSeed.value = ""
        overlay.intValue = entry.overlay
    }

    private fun jump(target: Int) {
        navKind.intValue = NAV_JUMP
        overlayStack.clear()
        overlay.intValue = target
    }

    /** Ouvre un overlay au-dessus du courant : le retour y reviendra. */
    fun closeOverlay(): Boolean {
        val previous = overlayStack.removeLastOrNull() ?: run {
            navKind.intValue = NAV_JUMP
            overlay.intValue = NONE
            return false
        }
        navKind.intValue = NAV_POP
        restore(previous)
        return true
    }

    /** Bonus : recherche pré-remplie quand on tape une tuile (genre, mood...). */
    val searchSeed = androidx.compose.runtime.mutableStateOf("")

    /** Lecteur plein écran ouvert */
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

    /** Vol de pochette entre le mini-lecteur et le lecteur plein écran. */
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

    /** Vue paroles dans le lecteur */
    val showLyrics = androidx.compose.runtime.mutableStateOf(false)

    /** Dialog iOS "Changer de compte" */
    val showAccount = androidx.compose.runtime.mutableStateOf(false)

    /** Bottom bar collapsée (comme iOS quand on scrolle) */
    val bottomCollapsed = androidx.compose.runtime.mutableStateOf(false)

    /** Sheets dans le lecteur */
    val showTrackMenu = androidx.compose.runtime.mutableStateOf(false)
    val trackMenuTrack = androidx.compose.runtime.mutableStateOf<com.ketvoice.app.data.Track?>(null)
    fun selectTab(index: Int) {
        playlistResolution.value = null
        tab.intValue = index
        overlayStack.clear()
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

    /** Sélecteur de source des paroles (feuille du lecteur) */
    val showLyricsSource = androidx.compose.runtime.mutableStateOf(false)

    /** Saut racine : remplace l'overlay courant, la pile est oubliée. */
    fun openSearch(seed: String = "") { searchSeed.value = seed; jump(SEARCH); bottomCollapsed.value = false }
    fun openSettings() { jump(SETTINGS); bottomCollapsed.value = false }
    fun openDownloads() = jump(DOWNLOADS)
    fun openLocalFiles() = jump(LOCAL_FILES)
    fun openSources() = jump(SOURCES)
    fun openReplay() = jump(REPLAY)
    fun openQueue() = jump(QUEUE)

    /** Ouvre un overlay en gardant le courant dans la pile (retour possible). */
    fun pushOverlay(target: Int) {
        navKind.intValue = NAV_PUSH
        overlayStack.addLast(snapshot())
        searchSeed.value = ""
        overlay.intValue = target
    }

    fun closeOverlays() {
        overlayStack.clear()
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
