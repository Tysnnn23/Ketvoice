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
    // Menu d'actions d'une playlist : porté au niveau racine pour que la feuille
    // se dessine au-dessus du chrome flottant (mini-lecteur + navigation) au lieu
    // d'être recouverte par lui, et pour hériter du vrai verre dynamique. Les
    // actions restent la propriété de PlaylistScreen (elles y ont leur contexte).
    data class PlaylistMenuRequest(
        val playlist: com.ketvoice.app.data.LocalPlaylist,
        val onRename: () -> Unit = {},
        val onExportJson: () -> Unit = {},
        val onExportM3u: () -> Unit = {},
        val onDelete: () -> Unit = {}
    )
    val playlistMenu = androidx.compose.runtime.mutableStateOf<PlaylistMenuRequest?>(null)
    data class PlaylistResolution(val playlistId: String, val entryId: String)
    val playlistResolution = androidx.compose.runtime.mutableStateOf<PlaylistResolution?>(null)
    fun openPlaylist(id: String) {
        push(OverlayEntry(PLAYLIST, null, null, null, ""))
        playlistId.value = id
        showPlayer.value = false
        bottomCollapsed.value = false
    }
    val catalogRequest = androidx.compose.runtime.mutableStateOf<com.ketvoice.app.data.CatalogRequest?>(null)

    /** Bounds of the card that opened a detail page, for an origin-aware reveal. */
    val revealOrigin = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)

    fun openCatalog(
        request: com.ketvoice.app.data.CatalogRequest,
        origin: androidx.compose.ui.geometry.Rect? = null
    ) {
        push(OverlayEntry(CATALOG, null, null, null, ""))
        catalogRequest.value = request
        revealOrigin.value = origin
        bottomCollapsed.value = false
    }

    data class ArtistRequest(val id: String, val name: String)
    val artistRequest = androidx.compose.runtime.mutableStateOf<ArtistRequest?>(null)
    fun openArtist(
        id: String,
        name: String,
        origin: androidx.compose.ui.geometry.Rect? = null
    ) {
        // On n'exige plus un channelId YouTube ("UC..." ) : les cartes d'artiste
        // issues de la recherche ou des métadonnées locales n'en fournissent pas
        // toujours, et le `return` silencieux faisait échouer l'ouverture sans
        // aucun retour visuel. On accepte donc tout identifiant exploitable.
        if (id.isBlank() && name.isBlank()) return
        push(OverlayEntry(ARTIST, null, null, null, ""))
        artistRequest.value = ArtistRequest(id, name)
        revealOrigin.value = origin
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
        val searchSeed: String,
        val origin: androidx.compose.ui.geometry.Rect? = null
    )
    private val overlayStack = ArrayDeque<OverlayEntry>()

    /** Direction de la dernière navigation : pour la transition AnimatedContent. */
    val NAV_JUMP = 0
    val NAV_PUSH = 1
    val NAV_POP = 2
    val navKind = androidx.compose.runtime.mutableIntStateOf(NAV_JUMP)

    fun snapshot(): OverlayEntry = OverlayEntry(
        overlay.intValue, playlistId.value, catalogRequest.value, artistRequest.value, searchSeed.value,
        revealOrigin.value
    )

    private fun restore(entry: OverlayEntry) {
        playlistId.value = entry.playlistId
        catalogRequest.value = entry.catalogRequest
        artistRequest.value = entry.artistRequest
        searchSeed.value = entry.searchSeed
        revealOrigin.value = entry.origin
        overlay.intValue = entry.overlay
    }

    private fun push(entry: OverlayEntry) {
        navKind.intValue = NAV_PUSH
        overlayStack.addLast(snapshot())
        playlistId.value = null
        catalogRequest.value = null
        artistRequest.value = null
        revealOrigin.value = null
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

    /* ── Navigation that survives process death ── */

    private var navigationRestoreDone = false

    /** The tab and the overlay stack as JSON, without transient reveal origins. */
    fun saveNavigation(): String {
        val entries = org.json.JSONArray()
        (overlayStack.toList() + snapshot()).takeLast(MAX_SAVED_ENTRIES).forEach { entry ->
            entries.put(org.json.JSONObject().apply {
                put("overlay", entry.overlay)
                entry.playlistId?.let { put("playlistId", it) }
                entry.catalogRequest?.let { put("catalog", org.json.JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title)) }
                entry.artistRequest?.let { put("artist", org.json.JSONObject().put("id", it.id).put("name", it.name)) }
                put("searchSeed", entry.searchSeed)
            })
        }
        return org.json.JSONObject().put("version", 1).put("tab", tab.intValue).put("entries", entries).toString()
    }

    /** Parsed, validated navigation: entries that cannot be shown again are dropped. */
    internal fun parseNavigation(json: String?): Pair<Int, List<OverlayEntry>>? = runCatching {
        val root = org.json.JSONObject(json ?: return null)
        if (root.optInt("version") != 1) return null
        val savedTab = root.optInt("tab", HOME).takeIf { it in HOME..LIBRARY } ?: HOME
        val array = root.optJSONArray("entries") ?: return savedTab to emptyList()
        val entries = (0 until minOf(array.length(), MAX_SAVED_ENTRIES)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val catalog = item.optJSONObject("catalog")?.let {
                com.ketvoice.app.data.CatalogRequest(it.optString("kind"), it.optString("id"), it.optString("title"))
            }?.takeIf { it.kind.isNotBlank() && it.id.isNotBlank() }
            val artist = item.optJSONObject("artist")?.let { ArtistRequest(it.optString("id"), it.optString("name")) }
                ?.takeIf { it.id.isNotBlank() || it.name.isNotBlank() }
            val playlist = item.optString("playlistId").takeIf { it.isNotBlank() }
            val entry = OverlayEntry(item.optInt("overlay", NONE), playlist, catalog, artist,
                item.optString("searchSeed").take(200))
            when (entry.overlay) {
                NONE, SEARCH, SETTINGS, DOWNLOADS, LOCAL_FILES, ADDONS_SEARCH, LYRICS_SOURCES, REPLAY, EXPORT, IMPORT,
                LANGUAGE, APP_ICON, CANVAS_CONFIG, lyricsSourcesList, SOURCES -> entry
                PLAYLIST -> entry.takeIf { playlist != null }
                CATALOG -> entry.takeIf { catalog != null }
                ARTIST -> entry.takeIf { artist != null }
                // The queue needs a live player session; never reopen it blindly.
                else -> null
            }
        }
        savedTab to entries
    }.getOrNull()

    /**
     * Restores a saved navigation once per process, and only over a pristine state:
     * an activity recreated in a living process already has the real one.
     */
    fun restoreNavigationOnce(json: String?) {
        if (navigationRestoreDone) return
        navigationRestoreDone = true
        if (tab.intValue != HOME || overlay.intValue != NONE || overlayStack.isNotEmpty()) return
        val (savedTab, entries) = parseNavigation(json) ?: return
        tab.intValue = savedTab
        val current = entries.lastOrNull() ?: return
        overlayStack.clear()
        entries.dropLast(1).filter { it.overlay != NONE || overlayStack.isEmpty() }.forEach(overlayStack::addLast)
        navKind.intValue = NAV_JUMP
        restore(current)
    }

    internal fun resetNavigationRestoreForTests() { navigationRestoreDone = false }

    /** A cold launch has nothing to restore later in this process. */
    fun markNavigationFresh() { navigationRestoreDone = true }

    private const val MAX_SAVED_ENTRIES = 12

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

    val miniCoverBounds = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    val miniPlayerBounds = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)

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
        revealOrigin.value = null
        tab.intValue = index
        overlayStack.clear()
        overlay.intValue = NONE
        showPlayer.value = false
        showLyrics.value = false
        trackMenuTrack.value = null
        playlistMenu.value = null
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
        playlistMenu.value = null
        showAudioOutput.value = false
        showLyrics.value = false
        showLyricsSource.value = false
    }
}
