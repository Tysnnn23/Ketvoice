package com.ketvoice.app.ui.overlay

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
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
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.YouTubeCatalog
import com.ketvoice.app.data.YouTubeMusicSession
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.Palette
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.AccountAvatar
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.DownloadCover
import com.ketvoice.app.ui.common.TrackTitle
import com.ketvoice.app.ui.common.EmptyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    var segment by rememberSaveable { mutableIntStateOf(0) } // 0 YouTube | 1 Sources | 2 Bibliothèque
    var query by rememberSaveable { mutableStateOf(AppUi.searchSeed.value) }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var allResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var artists by remember { mutableStateOf<List<MusicArtist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
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
    LaunchedEffect(Unit) { AppUi.searchSeed.value = "" }

    LaunchedEffect(query, segment, attempt) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            allResults = emptyList()
            artists = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(220)
        val needle = q.lowercase()
        if (segment == 2) {
            results = Downloader.items.value.map { it.track }.filter {
                it.title.lowercase().contains(needle) ||
                    it.artist.lowercase().contains(needle) ||
                    it.album.lowercase().contains(needle)
            }
            allResults = results
            artists = emptyList()
        } else if (segment == 0) {
            coroutineScope {
                val yt = async { MusicApi.searchYtMusic(q) }
                val foundArtists = async { YouTubeCatalog.searchArtists(q) }
                val publications = async { PublicationApi.search(q) }
                val addons = async { MusicApi.searchAddons(q) }
                results = yt.await()
                allResults = results
                artists = foundArtists.await()
                loading = false
                results = (results + publications.await()).distinctBy { it.id }
                // Addon latency must never hold the YouTube catalogue hostage.
                allResults = results + addons.await()
            }
        } else {
            results = emptyList()
            allResults = emptyList()
            artists = emptyList()
            coroutineScope {
                val custom = async { CustomSources.searchAll(q) }
                val ketvoice = async { MusicApi.searchAddons(q) }
                results = custom.await() + ketvoice.await()
                allResults = results
            }
        }
        loading = false
        // Pochettes réelles, y compris pour les sources personnalisées.
        if (results.isNotEmpty()) ArtworkRepo.enrich(results)
    }

    // Vraies pochettes d'album (jamais les vignettes YouTube).
    LaunchedEffect(results) { ArtworkRepo.enrich(results) }

    Column(
        Modifier
            .fillMaxSize()
            .background(th.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(top = 12.dp)
    ) {
        if (!layout.hasRail) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                AccountAvatar(
                    "K", size = 42.dp, modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { AppUi.showAccount.value = true })
            }
        }

        // Champ de recherche iOS
        Row(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (th.isDark) Color(0xFF1C1C1E) else Color(0x19909093))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, tint = th.secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Rechercher un titre ou un artiste…", color = th.secondary, fontSize = 16.sp)
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = th.onBackground, fontSize = 17.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(th.accent),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submitSearch(true) }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        AppUi.playlistResolution.value?.let { pending ->
            Column(Modifier.padding(horizontal=20.dp)) {
                Text("Choisissez une version pour la playlist",color=th.accent)
                if(playlistError.isNotBlank())Text(playlistError,color=th.secondary)
                androidx.compose.material3.TextButton(onClick={AppUi.playlistResolution.value=null;AppUi.openPlaylist(pending.playlistId)}){Text("Annuler")}
            }
        }

        // Segments
        Row(
            Modifier
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (th.isDark) Color(0xFF2C2C2E) else Color(0x19909093))
                .padding(3.dp)
        ) {
            listOf("YouTube", "Sources", "Bibliothèque").forEachIndexed { i, label ->
                val sel = segment == i
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (sel) (if (th.isDark) Palette.White else Palette.DarkGray) else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { segment = i }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (sel) (if (th.isDark) Palette.Black else Palette.White) else th.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        when {
            query.trim().length < 2 -> {
                if (segment == 1) {
                    SourcesCard()
                } else {
                    Text(
                        "Recherches récentes",
                        style = T.section,
                        color = th.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    if (recentQueries.isEmpty()) Text(
                        if (segment == 2) "Recherchez dans vos téléchargements."
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

            loading -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 50.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = th.accent, modifier = Modifier.size(28.dp))
                }
            }

            results.isEmpty() && artists.isEmpty() -> {
                EmptyState("Aucun résultat pour « $query »", when (segment) {
                    1 -> "Vérifiez que vos sources sont activées et accessibles."
                    2 -> "Essayez un autre titre, artiste ou album parmi vos téléchargements."
                    else -> "Essayez un titre plus court ou le nom de l'artiste. Vérifiez aussi votre connexion."
                }, if (segment == 1) "Gérer les sources" else "Réessayer") {
                    if (segment == 1) AppUi.overlay.value = AppUi.SOURCES else attempt++
                }
            }

            else -> {
                if (artists.isNotEmpty()) {
                    Text("Artistes", style = T.section, color = th.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        itemsIndexed(artists, key = { _, a -> a.id }) { _, artist ->
                            Column(Modifier.width(96.dp).clickable {
                                submitSearch()
                                AppUi.openArtist(artist.id, artist.name)
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(88.dp).clip(CircleShape).background(th.card)) {
                                    if (!artist.photo.isNullOrBlank()) {
                                        AsyncImage(model = artist.photo, contentDescription = artist.name,
                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    }
                                }
                                Text(artist.name, localize = false, color = th.onBackground, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 7.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (segment == 1) {
                    SourcesCard(compact = true)
                    Spacer(Modifier.height(6.dp))
                }
                if (segment == 0) {
                    Text("Titres", style = T.section, color = th.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    if (results.isEmpty()) Text("Catalogue indisponible · Réessayer", color = th.secondary,
                        modifier = Modifier.clickable { attempt++ }.padding(horizontal = 20.dp, vertical = 16.dp))
                }
                results.chunked(layout.trackColumns).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { column, track ->
                    val index = rowIndex * layout.trackColumns + column
                    SearchResultRow(
                        track = track,
                        cover = art["t:${track.id}"] ?: track.coverUrl,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            submitSearch()
                            // Correspondance addon déjà présente dans les résultats :
                            // le son démarre immédiatement, sans recherche réseau.
                            val match = if (track.addonId.isNullOrBlank()) {
                                MusicApi.addonMatchIn(allResults, track)?.takeIf { it.addon == "um" }
                            } else null
                            val playable = if (match?.addonId != null) {
                                track.copy(
                                    addon = "um",
                                    addonId = match.addonId,
                                    coverUrl = track.coverUrl ?: match.coverUrl,
                                    album = track.album.ifBlank { match.album },
                                    durationMs = if (track.durationMs > 0) track.durationMs else match.durationMs
                                )
                            } else track
                            val queue = results.toMutableList().also { it[index] = playable }
                            // Pas d'ouverture forcée du lecteur : le mini-lecteur prend le relais.
                            val pending=AppUi.playlistResolution.value
                            if(pending==null)Player.playQueue(queue,index) else scope.launch {
                                try{PlaylistStore.resolve(pending.playlistId,pending.entryId,playable)
                                    AppUi.playlistResolution.value=null;AppUi.openPlaylist(pending.playlistId)}
                                catch(_:Exception){playlistError="Impossible d'enregistrer la playlist"}
                            }
                        }
                    )
                    }
                    if (row.size < layout.trackColumns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(layout.bottomPaddingDp.dp))
    }
}

@Composable
private fun SearchResultRow(track: Track, cover: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val th = LocalTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
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
            custom -> "SRC"
            ketvoice -> "API"
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
}

@Composable
private fun SourcesCard(compact: Boolean = false) {
    val th = LocalTheme.current
    val custom by CustomSources.sources.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compact) 4.dp else 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ketvoice API", style = T.section, color = th.onBackground)
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
                modifier = Modifier.clickable { AppUi.overlay.value = AppUi.SOURCES }
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("Sources personnalisées", style = T.section, color = th.onBackground)
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
