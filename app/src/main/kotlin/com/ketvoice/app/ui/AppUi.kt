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
    val catalogRequest = androidx.compose.runtime.mutableStateOf<com.ketvoice.app.data.CatalogRequest?>(null)
    fun openCatalog(request: com.ketvoice.app.data.CatalogRequest) {
        catalogRequest.value = request
        overlay.intValue = CATALOG
        bottomCollapsed.value = false
    }

    data class ArtistRequest(val id: String, val name: String)
    val artistRequest = androidx.compose.runtime.mutableStateOf<ArtistRequest?>(null)
    fun openArtist(id: String, name: String) {
        if (!id.startsWith("UC")) return
        artistRequest.value = ArtistRequest(id, name)
        overlay.intValue = ARTIST
        bottomCollapsed.value = false
    }

    val overlay = androidx.compose.runtime.mutableIntStateOf(NONE)

    /** Bonus : recherche pré-remplie quand on tape une tuile (genre, mood...). */
    val searchSeed = androidx.compose.runtime.mutableStateOf("")

    /** Lecteur plein écran ouvert */
    val showPlayer = androidx.compose.runtime.mutableStateOf(false)

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
        tab.intValue = index
        overlay.intValue = NONE
        showPlayer.value = false
        showLyrics.value = false
        trackMenuTrack.value = null
        bottomCollapsed.value = false
    }
    val showAudioOutput = androidx.compose.runtime.mutableStateOf(false)

    /** Sélecteur de source des paroles (feuille du lecteur) */
    val showLyricsSource = androidx.compose.runtime.mutableStateOf(false)

    fun closeOverlays() {
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
