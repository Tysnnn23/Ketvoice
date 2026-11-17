package com.ketvoice.app.ui.overlay

import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.ui.motion.captureRevealOrigin
import com.ketvoice.app.ui.motion.rememberRevealOrigin

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import com.ketvoice.app.ui.common.KetIcons
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.data.SearchHistory
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.SearchGroups
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.YouTubeCatalog
import com.ketvoice.app.data.YouTubeMusicSession
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.Palette
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.adaptive.searchFieldFocus
import com.ketvoice.app.ui.common.AccountAvatar
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.DownloadCover
import com.ketvoice.app.ui.common.TrackTitle
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.trackRowClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RECHERCHE (captures 22-23) : YouTube Music + addons BitChord
 * (Last Babe FLAC / Ultra MAX Deezer), résultats réels jouables.
 */
@Composable
fun SearchScreen() {
    val th = LocalTheme.current
    val scope = rememberCoroutineScope()
    var playlistError by remember { mutableStateOf("") }
    val layout = LocalAdaptiveLayout.current
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var segment by rememberSaveable { mutableIntStateOf(0) } // 0 Tout | 1 Sources | 2 Bibliothèque
    // Phones (6.0.0): the field is the floating one at the bottom of the screen (ui/chrome/FloatingSearch).
    val floating = com.ketvoice.app.ui.chrome.FloatingSearch.applies(layout.hasRail, com.ketvoice.app.BuildConfig.IS_QUEST)
    val pageQuery = rememberSaveable { mutableStateOf(AppUi.searchSeed.value) }
    var query by (if (floating) com.ketvoice.app.ui.chrome.FloatingSearch.query else pageQuery)
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    // Found by their lyrics: on the device (playable at once), and online (searched in the catalogue when touched).
    var lyricTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var lyricFound by remember { mutableStateOf<List<com.ketvoice.app.data.LyricsSearch.Found>>(emptyList()) }
    var allResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var artists by remember { mutableStateOf<List<MusicArtist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<com.ketvoice.app.data.CatalogAlbum>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<com.ketvoice.app.data.LocalPlaylist>>(emptyList()) }
    var shownSegment by remember { mutableIntStateOf(segment) }
    var loading by remember { mutableStateOf(false) }
    // Natural search (smart library): what was understood of the query, and the chips removed from it.
    var understood by remember { mutableStateOf<com.ketvoice.app.smartlib.NaturalQuery?>(null) }
    var dismissed by remember(query) { mutableStateOf(emptySet<String>()) }
    val art by ArtworkRepo.covers.collectAsState()
    val customSources by CustomSources.sources.collectAsState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val recentQueries by SearchHistory.queries.collectAsState()
    var lastSubmitted by rememberSaveable { mutableStateOf("") }
    fun submitSearch(force: Boolean = false) {
        val q = query.trim()
        if (q.length < 2) return
        SearchHistory.record(q)
        if (force || q != lastSubmitted) {
            lastSubmitted = q
            YouTubeMusicSession.submitSearch(q)
        }
        focusManager.clearFocus()
    }

    // La graine est consommée une seule fois (tuile genre / album).
    LaunchedEffect(Unit) {
        // A seeded search (a genre tile) fills the floating field too.
        if (floating && AppUi.searchSeed.value.isNotBlank()) com.ketvoice.app.ui.chrome.FloatingSearch.query.value = AppUi.searchSeed.value
        AppUi.searchSeed.value = ""
    }
    // The keyboard's search key in the floating field searches here.
    DisposableEffect(floating) {
        val submit = { submitSearch(true) }
        if (floating) com.ketvoice.app.ui.chrome.FloatingSearch.onSubmit = submit
        onDispose { if (com.ketvoice.app.ui.chrome.FloatingSearch.onSubmit === submit) com.ketvoice.app.ui.chrome.FloatingSearch.onSubmit = null }
    }

    LaunchedEffect(query, segment, attempt, customSources, dismissed) {
        val q = query.trim()
        fun clear() {
            results = emptyList()
            allResults = emptyList()
            artists = emptyList()
            albums = emptyList()
            playlists = emptyList()
        }
        if (q.length < 2) {
            clear()
            lyricTracks = emptyList(); lyricFound = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        // Results of the previous query stay on screen until the new ones arrive: typing
        // no longer blanks the page at every letter. Another scope starts from nothing.
        if (segment != shownSegment) { clear(); shownSegment = segment }
        delay(220)
        val smart = if (segment == 1) null else com.ketvoice.app.ui.smartlib.SmartSearch.understand(q, dismissed)
        understood = smart
        if (segment != 1) {
            lyricTracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.ketvoice.app.data.LyricsSearch.local(q) }
            if (segment == 2) lyricFound = emptyList()
        }
        if (segment == 0) launch {
            lyricFound = if (com.ketvoice.app.data.LyricsSearch.looksLikeLyrics(q)) com.ketvoice.app.data.LyricsSearch.online(q)
                .filter { com.ketvoice.app.data.ArtistPrefs.allows(it.artist) } else emptyList()
        }
        if (segment == 2) {
            val lists = PlaylistStore.lists.value
            results = smart?.let { com.ketvoice.app.ui.smartlib.SmartSearch.library(it) }?.takeIf { it.isNotEmpty() }
                ?: com.ketvoice.app.data.LibrarySearch.tracks(q, Downloader.items.value.map { it.track }, lists,
                com.ketvoice.app.data.LocalMusic.tracks.value, com.ketvoice.app.data.ListeningHistory.knownTracks.value)
            allResults = results
            playlists = com.ketvoice.app.data.LibrarySearch.playlists(q, lists)
            artists = emptyList()
            albums = emptyList()
        } else {
            var gotTracks = false
            coroutineScope {
                val searches = mutableListOf<suspend () -> List<Track>>()
                // A request ("rap français calme") asks the catalogue for its words, then filters what comes back.
                val asked = smart?.catalogueText() ?: q
                if (segment == 0) {
                    searches += { MusicApi.searchYtMusic(asked) }
                    searches += { PublicationApi.search(asked) }
                    if (smart != null) searches += com.ketvoice.app.ui.smartlib.SmartSearch.chartSearches(smart)
                }
                searches += { MusicApi.searchAddons(asked) }
                customSources.filter { it.enabled }.forEach { source -> searches += { CustomSources.search(source, asked) } }
                playlists = emptyList()
                launch {
                    SearchGroups.collectProgressively(searches) { tracks ->
                        gotTracks = true
                        results = smart?.let { com.ketvoice.app.ui.smartlib.SmartSearch.refineNow(tracks, it) } ?: tracks
                        allResults = tracks
                    }
                    if (smart != null && allResults.isNotEmpty()) results = com.ketvoice.app.ui.smartlib.SmartSearch.refine(allResults, smart)
                }
                if (segment == 0) {
                    launch {
                        artists = try { YouTubeCatalog.searchArtists(q) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { emptyList() /* Tracks remain available. */ }
                    }
                    launch {
                        albums = try { com.ketvoice.app.data.CatalogRepo.searchAlbums(q) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { emptyList() }
                    }
                } else {
                    artists = emptyList()
                    albums = emptyList()
                }
            }
            // Nothing came back for this query: the previous titles must not stay.
            if (!gotTracks) { results = emptyList(); allResults = emptyList() }
        }
        loading = false
    }

    // Vraies pochettes d'album (jamais les vignettes YouTube).
    LaunchedEffect(results) { ArtworkRepo.enrich(results) }
    val blockedArtists = com.ketvoice.app.data.ArtistPrefs.blocked
    val grouped = remember(results, segment, blockedArtists) {
        // The catalogue ("All") leaves blocked artists out; the listener's own sources and library are shown as they are.
        if (segment == 2) results.map { SearchGroups.Group(listOf(it)) }
        else SearchGroups.group(if (segment == 0) results.filter(com.ketvoice.app.data.ArtistPrefs::allows) else results)
    }

    // Wide: the query and the recent searches on the left, the results beside them (ui/adaptive/SearchPanes).
    com.ketvoice.app.ui.adaptive.SearchPanes(Modifier.fillMaxSize().pageBackground(th),
        idle = query.trim().length < 2 && segment != 1, recent = recentQueries,
        onRecent = { previous -> query = previous; SearchHistory.record(previous); YouTubeMusicSession.submitSearch(previous) },
        head = {
        if (!layout.hasRail) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = com.ketvoice.app.ui.common.pageMargin, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.ketvoice.app.ui.common.PageTitle("Rechercher", Modifier.weight(1f))
                AccountAvatar(
                    "K", size = 42.dp, modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { AppUi.showAccount.value = true })
            }
        }

        if (!floating) {
            com.ketvoice.app.ui.common.CompactSearchField(query, { query = it }, { submitSearch(true) },
                Modifier.padding(horizontal = 20.dp).align(Alignment.CenterHorizontally).searchFieldFocus())
            Spacer(Modifier.height(14.dp))
        }
        AppUi.playlistResolution.value?.let { pending ->
            Column(Modifier.padding(horizontal=20.dp)) {
                Text("Choisissez une version pour la playlist",color=th.accent)
                if(playlistError.isNotBlank())Text(playlistError,color=th.secondary)
                com.ketvoice.app.ui.common.KetTextButton("Annuler", {AppUi.playlistResolution.value=null;AppUi.openPlaylist(pending.playlistId)})
            }
        }

        // Segments : la même lentille de verre glissante que les autres sélecteurs.
        com.ketvoice.app.ui.common.GlassSegmentedControl(listOf("Tout", "Sources", "Bibliothèque"), segment, { segment = it },
            Modifier.padding(horizontal = 20.dp).widthIn(max = 640.dp).align(Alignment.CenterHorizontally), tag = "search-scope")
        Spacer(Modifier.height(if (understood != null && query.trim().length >= 2) 8.dp else 18.dp))
        if (query.trim().length >= 2) com.ketvoice.app.ui.smartlib.UnderstoodChips(understood, { dismissed = dismissed + it.key },
            Modifier.padding(bottom = 8.dp))
        }, results = {
        when {
            query.trim().length < 2 -> {
                if (segment == 1) {
                    SourcesCard()
                } else {
                    val favourites = com.ketvoice.app.data.ArtistPrefs.favourites
                    if (favourites.isNotEmpty()) {
                        com.ketvoice.app.ui.common.SectionHeader("Vos artistes favoris", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.testTag("search-favourite-artists")) {
                            itemsIndexed(favourites, key = { _, a -> a.key }) { _, entry ->
                                SearchArtistCircle(com.ketvoice.app.data.MusicArtist(entry.id, entry.name, entry.photo)) { origin ->
                                    AppUi.openArtist(entry.id, entry.name, origin)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    com.ketvoice.app.ui.common.SectionHeader("Recherches récentes", Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(6.dp))
                    if (recentQueries.isEmpty()) Text(
                        if (segment == 2) "Recherchez dans vos téléchargements, playlists, fichiers locaux et titres écoutés."
                        else "Vos recherches apparaîtront ici.",
                        color = th.secondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                    if (recentQueries.isNotEmpty()) Text("Effacer l'historique", color = th.secondary,
                        modifier = Modifier.clickable { SearchHistory.clear() }.padding(horizontal = 20.dp, vertical = 8.dp))
                    recentQueries.forEach { previousQuery ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    query = previousQuery
                                    SearchHistory.record(previousQuery)
                                    YouTubeMusicSession.submitSearch(previousQuery)
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Search, null, tint = th.secondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(previousQuery, localize = false, color = th.onBackground, fontSize = 16.sp)
                        }
                    }
                }
            }

            loading && results.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() -> {
                // Placeholder rows laid out as the results will be, instead of a lone spinner.
                com.ketvoice.app.ui.sub.CatalogSkeletonList(rows = 6, modifier = Modifier.padding(top = 8.dp))
            }

            results.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && lyricTracks.isEmpty() && lyricFound.isEmpty() -> {
                EmptyState("Aucun résultat pour « $query »", when (segment) {
                    1 -> "Vérifiez que vos sources sont activées et accessibles."
                    2 -> "Essayez un autre titre, artiste ou album de votre bibliothèque."
                    else -> "Essayez un titre plus court ou le nom de l'artiste. Vérifiez aussi votre connexion."
                }, if (segment == 1) "Gérer les sources" else "Réessayer", icon = com.ketvoice.app.ui.common.KetIcons.Search) {
                    if (segment == 1) AppUi.pushOverlay(AppUi.SOURCES) else attempt++
                }
            }

            else -> {
                // A quiet line while a new query is still loading over the previous results.
                if (loading) com.ketvoice.app.ui.common.KetProgressBar(null, Modifier.padding(horizontal = 20.dp), track = Color.Transparent, height = 2.dp)
                if (artists.isNotEmpty()) {
                    com.ketvoice.app.ui.common.SectionHeader("Artistes", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        itemsIndexed(artists, key = { _, a -> a.id }) { _, artist ->
                            SearchArtistCircle(artist) { origin -> submitSearch(); AppUi.openArtist(artist.id, artist.name, origin) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                // Titles found by their words.
                if (lyricTracks.isNotEmpty() || lyricFound.isNotEmpty()) Column(Modifier.testTag("search-lyrics")) {
                    com.ketvoice.app.ui.common.SectionHeader("D'après les paroles", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    lyricTracks.forEachIndexed { index, track ->
                        SearchResultRow(track = track, variants = listOf(track),
                            cover = com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl),
                            onClick = { submitSearch(); Player.playQueue(lyricTracks, index) }, onVariant = { Player.playQueue(listOf(it), 0) })
                    }
                    lyricFound.forEach { found ->
                        Row(Modifier.fillMaxWidth().clickable { query = found.query }.padding(horizontal = 20.dp, vertical = 8.dp)
                            .testTag("search-lyrics-online"), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(th.card)) {
                                if (found.cover != null) AsyncImage(found.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(found.title, localize = false, color = th.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(found.artist, localize = false, color = th.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Filled.Search, "Rechercher ce titre", tint = th.secondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                val shownAlbums = remember(albums, blockedArtists) { albums.filter { com.ketvoice.app.data.ArtistPrefs.allows(it.artist) } }
                if (shownAlbums.isNotEmpty()) {
                    com.ketvoice.app.ui.common.SectionHeader("Albums", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    com.ketvoice.app.ui.explorer.AlbumShelf(shownAlbums, edge = 20.dp) { album, origin ->
                        submitSearch()
                        AppUi.openCatalog(com.ketvoice.app.data.CatalogRequest("album", album.id, album.title), origin)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (playlists.isNotEmpty()) {
                    com.ketvoice.app.ui.common.SectionHeader("Playlists", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    playlists.forEach { playlist ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { submitSearch(); AppUi.openPlaylist(playlist.id) }
                            .padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(playlist.name, playlist.coverUrl, 52.dp, 8.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(playlist.name, localize = false, color = th.onBackground, fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (playlist.entries.size == 1) "1 titre" else "${playlist.entries.size} titres",
                                    color = th.secondary, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (segment == 1) {
                    SourcesCard(compact = true)
                    Spacer(Modifier.height(6.dp))
                }
                if (segment == 2 && results.isNotEmpty()) com.ketvoice.app.ui.common.SectionHeader("Titres", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                if (segment == 0) {
                    com.ketvoice.app.ui.common.SectionHeader("Titres", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    if (results.isEmpty()) Text("Catalogue indisponible · Réessayer", color = th.secondary,
                        modifier = Modifier.clickable { attempt++ }.padding(horizontal = 20.dp, vertical = 16.dp))
                }
                grouped.chunked(layout.trackColumns).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { column, group ->
                    val index = rowIndex * layout.trackColumns + column
                    val track = group.primary
                    fun choose(chosen: Track, explicit: Boolean) {
                        submitSearch()
                        val selection = if (explicit) SearchGroups.sourceId(chosen)?.let { chosen.copy(sourceOverride = it) } ?: chosen else chosen
                        val match = if (!explicit && selection.addonId.isNullOrBlank()) {
                            MusicApi.addonMatchIn(allResults, selection)?.takeIf { it.addon == "um" }
                        } else null
                        val playable = if (match?.addonId != null) {
                            selection.copy(addon = "um", addonId = match.addonId,
                                coverUrl = selection.coverUrl ?: match.coverUrl,
                                album = selection.album.ifBlank { match.album },
                                durationMs = if (selection.durationMs > 0) selection.durationMs else match.durationMs)
                        } else selection
                        val queue = grouped.map { it.primary }.toMutableList().also { it[index] = playable }
                        val pending = AppUi.playlistResolution.value
                        if (pending == null) Player.playQueue(queue, index) else scope.launch {
                            try {
                                PlaylistStore.resolve(pending.playlistId, pending.entryId, playable)
                                AppUi.playlistResolution.value = null
                                AppUi.openPlaylist(pending.playlistId)
                            } catch (_: Exception) { playlistError = "Impossible d'enregistrer la playlist" }
                        }
                    }
                    SearchResultRow(
                        track = track,
                        variants = group.tracks,
                        cover = com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl),
                        modifier = Modifier.weight(1f),
                        onClick = { choose(track, false) }, onVariant = { choose(it, true) }
                    )
                    }
                    if (row.size < layout.trackColumns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(layout.bottomPaddingDp.dp))
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchResultRow(track: Track, variants: List<Track>, cover: String?, modifier: Modifier = Modifier,
                             onClick: () -> Unit, onVariant: (Track) -> Unit) {
    val th = LocalTheme.current
    com.ketvoice.app.ui.common.SwipeTrackActions(track, modifier) {
    Column(Modifier.fillMaxWidth()) {
    Row(
        Modifier
            .fillMaxWidth()
            .trackRowClick(track) { onClick() }
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DownloadCover(track, cover, size = 52.dp, radius = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            TrackTitle(track.title, color = th.onBackground)
            Text(
                buildString {
                    append(track.artist)
                    if (track.album.isNotEmpty() && track.album != track.artist) {
                        append(" · ")
                        append(track.album)
                    }
                },
                color = th.secondary,
                style = T.sub,
                fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = track.artistId?.startsWith("UC") == true) {
                    AppUi.openArtist(track.artistId.orEmpty(), track.artist.substringBefore(','))
                }
            )
        }
        Spacer(Modifier.width(8.dp))
        val custom = track.addon?.startsWith("cs:") == true
        val ketvoice = track.addon in setOf("um", "last", "upload")
        val badge = when {
            custom && track.addonId?.startsWith("tidal:", true) == true -> "TIDAL"
            custom && track.addonId?.startsWith("qobuz:", true) == true -> "Qobuz"
            custom -> "SRC"
            ketvoice -> "API"
            track.id.startsWith("local:") -> "LOCAL"
            else -> "YT"
        }
        Text(
            badge,
            color = if (custom || ketvoice) th.accent else th.secondary,
            fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 12.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(if (custom || ketvoice)
                    th.accent.copy(alpha = 0.12f) else th.card)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
    if (variants.size > 1) {
        FlowRow(Modifier.fillMaxWidth().padding(start = 84.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            variants.filter { SearchGroups.sourceId(it) != null }.forEach { option ->
                Text(SearchGroups.sourceName(option), localize = false, color = th.accent, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onVariant(option) }
                        .heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 12.dp))
            }
        }
    }
    }
    }
}

@Composable
private fun SourcesCard(compact: Boolean = false) {
    val th = LocalTheme.current
    val custom by CustomSources.sources.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compact) 4.dp else 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                com.ketvoice.app.ui.common.SectionHeader("Ketvoice API")
                Text(
                    "Recherche, streaming et canvas · Supabase",
                    color = th.secondary, style = T.caption, modifier = Modifier.padding(top = 3.dp)
                )
            }
            Box(Modifier.size(34.dp).clip(CircleShape).background(th.accent.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Tune, null, tint = th.accent, modifier = Modifier.size(17.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Source intégrée · active", color = th.secondary, style = T.caption)
            Spacer(Modifier.weight(1f))
            Text("Gérer", color = th.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.clickable { AppUi.pushOverlay(AppUi.SOURCES) }
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
        }
        Spacer(Modifier.height(10.dp))
        com.ketvoice.app.ui.common.SectionHeader("Sources personnalisées")
        Text(
            if (custom.isEmpty()) "Aucune source .json ajoutée"
            else "${custom.count { it.enabled }} active(s) sur ${custom.size}",
            color = th.secondary, style = T.caption, modifier = Modifier.padding(top = 3.dp)
        )
        if (custom.isNotEmpty()) {
            custom.take(4).forEach { source ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(th.card), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Tune, null, tint = th.onBackground, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(source.name, color = th.onBackground, style = T.bodyBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(if (source.quality == "high") "Haute qualité" else "Qualité économique",
                            color = th.secondary, style = T.caption)
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(if (source.enabled) th.accent else th.hairline))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Ajoutez des manifestes .json pour étendre la recherche, le streaming et les téléchargements.",
            style = T.sub, color = th.secondary
        )
    }
}

/**
 * An artist's circle in the search. A tap opens the artist; a long press opens a small menu
 * under the circle to block or unblock the artist, or keep it as a favourite.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SearchArtistCircle(artist: com.ketvoice.app.data.MusicArtist, onOpen: (androidx.compose.ui.geometry.Rect?) -> Unit) {
    val th = LocalTheme.current
    val origin = rememberRevealOrigin()
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    val prefs = com.ketvoice.app.data.ArtistPrefs
    val entry = remember(artist) { com.ketvoice.app.data.ArtistPrefs.Entry(artist.name, artist.id, artist.photo) }
    val blocked = prefs.isBlocked(artist.name)
    val favourite = prefs.isFavourite(artist.name)
    val lift by androidx.compose.animation.core.animateFloatAsState(if (menu) 1.06f else 1f,
        if (com.ketvoice.app.ui.motion.Motion.reduced) androidx.compose.animation.core.snap()
        else androidx.compose.animation.core.spring(dampingRatio = .62f, stiffness = 520f), label = "artist-circle-lift")
    Box {
        val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(
            com.ketvoice.app.data.MediaOverrides.artistPhoto(artist.name, artist.photo), round = true)
        Column(Modifier.width(96.dp).captureRevealOrigin(origin)
            .combinedClickable(onLongClick = { haptic(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); menu = true },
                onLongClickLabel = com.ketvoice.app.data.AppText.t("Options de l'artiste")) { zoom.arm(); onOpen(zoom.bounds ?: origin.value) }
            .testTag("search-artist-${artist.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).then(com.ketvoice.app.ui.motion.zoomSourceOf(zoom))
                .graphicsLayer { scaleX = lift; scaleY = lift; alpha = if (blocked) .45f else 1f }
                .clip(CircleShape).background(th.card).border(1.dp, th.onBackground.copy(alpha = .10f), CircleShape),
                contentAlignment = Alignment.Center) {
                // Never an empty grey disc: the initial is always behind the photo and shows while it loads.
                Text(artist.name.take(1).uppercase(), localize = false, color = th.secondary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                val artistPhoto = com.ketvoice.app.data.MediaOverrides.artistPhoto(artist.name, artist.photo)
                if (!artistPhoto.isNullOrBlank()) AsyncImage(model = artistPhoto, contentDescription = artist.name,
                    contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (favourite) Icon(KetIcons.Heart, "Artiste favori", tint = th.accent, modifier = Modifier.size(12.dp).padding(end = 3.dp))
                if (blocked) Icon(KetIcons.Close, "Artiste bloqué", tint = th.secondary, modifier = Modifier.size(12.dp).padding(end = 3.dp))
                Text(artist.name, localize = false, color = if (blocked) th.secondary else th.onBackground, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        androidx.compose.material3.DropdownMenu(menu, { menu = false }, offset = androidx.compose.ui.unit.DpOffset(4.dp, 2.dp),
            shape = RoundedCornerShape(18.dp), containerColor = th.card,
            modifier = Modifier.testTag("search-artist-menu")) {
            @Composable fun option(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, action: () -> Unit) =
                androidx.compose.material3.DropdownMenuItem(text = { Text(label, color = th.onBackground, fontSize = 14.sp) },
                    leadingIcon = { Icon(icon, null, tint = th.onBackground, modifier = Modifier.size(18.dp)) },
                    onClick = { menu = false; haptic(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); action() },
                    modifier = Modifier.heightIn(min = 44.dp).testTag(tag))
            if (blocked) option("Débloquer l'artiste", KetIcons.Refresh, "artist-unblock") { prefs.setBlocked(entry, false) }
            else option("Bloquer l'artiste", KetIcons.Close, "artist-block") { prefs.setBlocked(entry, true) }
            if (favourite) option("Retirer des favoris", KetIcons.Heart, "artist-unfavourite") { prefs.setFavourite(entry, false) }
            else option("Mettre en favori", KetIcons.Heart, "artist-favourite") { prefs.setFavourite(entry, true) }
        }
    }
}
