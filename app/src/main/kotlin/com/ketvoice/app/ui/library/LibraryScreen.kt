package com.ketvoice.app.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import kotlin.math.min
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import com.ketvoice.app.R
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.AccountAvatar
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.DownloadCover
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.openTrackSearch
import com.ketvoice.app.ui.common.TrackTitle
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.collectionEntry
import com.ketvoice.app.ui.motion.rememberSeenCollections
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.testTag

private val playlistTints = listOf(
    Color(0xFFEF476F), Color(0xFF118AB2), Color(0xFF6C5CE7),
    Color(0xFF06D6A0), Color(0xFFFFB703), Color(0xFF8E44AD)
)

@Composable
fun LibraryScreen() {
    val theme = LocalTheme.current
    val layout = LocalAdaptiveLayout.current
    val columns = layout.trackColumns
    val recent by ListeningHistory.tracks.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val downloads by Downloader.items.collectAsState()
    val playlists by PlaylistStore.lists.collectAsState()
    val playlistLoadError by PlaylistStore.loadError.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var importError by remember { mutableStateOf("") }
    val playlistFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return@launch
                val parsed = if (text.trimStart().startsWith("{")) PlaylistExchange.parseJson(text)
                    else PlaylistExchange.parseM3u(text)
                PlaylistStore.create(parsed.name, parsed.entries, parsed.spotifyUrl)
                importError = ""
            } catch (_: Exception) {
                importError = "Fichier de playlist illisible ou invalide"
            }
        }
    }
    val seen = rememberSeenCollections()
    var albumOrder by rememberSaveable { mutableIntStateOf(0) }
    var sortOpen by remember { mutableStateOf(false) }
    val all = (known + recent + downloads.map { it.track }).distinctBy { it.id }
    val liked = all.filter { it.id in AppSettings.favoriteIds.value }
    val sourceAlbums = (recent + downloads.map { it.track })
        .filter { !it.albumId.isNullOrBlank() && (it.albumId!!.all(Char::isDigit) || it.albumId.startsWith("MPRE")) }
        .distinctBy { it.albumId }
    val albums = remember(sourceAlbums, albumOrder) { AlbumOrder.sort(sourceAlbums, albumOrder) }
    val artists = all.groupBy { it.artist.substringBefore(',').trim() }
        .filterKeys { it.isNotBlank() }
        .entries
        .sortedByDescending { it.value.size }
        .take(12)

    // Statistiques réelles : temps d'écoute RÉELLEMENT joué (deltas de position
    // du lecteur, persistés) et compteurs d'écoute par titre.
    val playCounts by ListeningHistory.playCounts.collectAsState()
    val listenedMs by ListeningHistory.listenedMs.collectAsState()
    val listenedLabel = remember(listenedMs) {
        val totalMin = (listenedMs / 60_000L).toInt()
        if (totalMin >= 60) "${totalMin / 60} h ${"%02d".format(totalMin % 60)}"
        else "$totalMin"
    }
    val listenedUnit = remember(listenedMs) {
        if (listenedMs >= 3_600_000L) "TEMPS D'ÉCOUTE" else "MINUTES ÉCOUTÉES"
    }
    val estimatedMinutes = (listenedMs / 60_000L).toInt()
    val totalPlays = playCounts.values.sum()
    val topArtistName = remember(artists) { artists.firstOrNull()?.key }
    val memberSince = remember {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = AppSettings.firstLaunchAt.value
        "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.YEAR) % 100}"
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("library-list").background(theme.background).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = layout.bottomPaddingDp.dp)
    ) {
        item {
            LibraryHeader()
        }

        // CARROUSEL "VOTRE EXPÉRIENCE D'ÉCOUTE" (Frame 120)
        item {
            ExperienceCarousel(
                minutesListened = estimatedMinutes,
                totalPlays = totalPlays,
                topArtist = topArtistName,
                memberSince = memberSince,
                listenedLabel = listenedLabel,
                listenedUnit = listenedUnit,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // RACCOURCIS SYSTÈME VERTICAUX (Frame 120)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                // 1. Téléchargements
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { AppUi.openDownloads() }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Téléchargements",
                        tint = theme.onBackground,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Téléchargements",
                        color = theme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    ChevronRightIcon(
                        modifier = Modifier.size(width = 10.dp, height = 16.dp),
                        tint = theme.secondary.copy(alpha = 0.5f)
                    )
                }

                // 2. Musique locale
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { AppUi.openLocalFiles() }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = "Musique locale",
                        tint = theme.onBackground,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Musique locale",
                        color = theme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    ChevronRightIcon(
                        modifier = Modifier.size(width = 10.dp, height = 16.dp),
                        tint = theme.secondary.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // SECTION PLAYLISTS (Frame 120)
        item {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    "Playlists",
                    color = theme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, bottom = 12.dp)
                )

                val buttonBg = if (theme.isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFF3F3F5)
                val cardSize = 132.dp
                val cardShape = RoundedCornerShape(20.dp)

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Carte 1 : Bouton "+" (Créer une playlist)
                    item {
                        Box(
                            modifier = Modifier
                                .size(cardSize)
                                .clip(cardShape)
                                .background(buttonBg)
                                .clickable(enabled = !playlistLoadError) { AppUi.showCreatePlaylist.value = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Créer une playlist",
                                tint = theme.onBackground,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    // Carte 2 : Bouton "Spotify" (Importer depuis Spotify)
                    item {
                        Box(
                            modifier = Modifier
                                .size(cardSize)
                                .clip(cardShape)
                                .background(buttonBg)
                                .clickable(enabled = !playlistLoadError) { AppUi.showSpotifyImport.value = true },
                            contentAlignment = Alignment.Center
                        ) {
                            SpotifyIcon(modifier = Modifier.size(46.dp))
                        }
                    }

                    // Cartes 3+ : Playlists existantes
                    items(playlists, key = { it.id }) { playlist ->
                        Column(
                            Modifier
                                .width(cardSize)
                                .clickable { AppUi.openPlaylist(playlist.id) }
                        ) {
                            PlaylistArtwork(playlist, playlist.name, Modifier.size(cardSize).clip(cardShape))
                            Text(
                                playlist.name,
                                localize = false,
                                color = theme.onBackground,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Text(
                                "${playlist.entries.size} titres",
                                color = theme.secondary,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }

                if (playlistLoadError) {
                    Text("Impossible de lire les playlists", color = theme.secondary, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                }
                if (importError.isNotBlank()) {
                    Text(importError, color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                Text(
                    "Importer un fichier · .json / .m3u",
                    color = theme.accent,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .padding(start = 20.dp, top = 8.dp, bottom = 8.dp)
                        .clickable(enabled = !playlistLoadError) {
                            importError = ""
                            playlistFilePicker.launch(arrayOf("application/json", "text/*", "audio/x-mpegurl", "audio/mpegurl", "*/*"))
                        }
                )
            }
        }
        item {
            SectionHeader("Collections", "Votre bibliothèque")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("favorites", seen, 0),
                        title = "Titres que vous aimez",
                        subtitle = "${liked.size} titres",
                        cover = liked.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl },
                        tint = Color(0xFF8E44AD)
                        , kindIcon = Icons.Filled.Star, artwork = liked.take(4)
                    ) { AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) }
                }
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("downloads", seen, 1),
                        title = "Téléchargements",
                        subtitle = "${downloads.size} hors ligne",
                        cover = downloads.firstOrNull()?.let { ArtworkRepo.coverForTrack(it.track) ?: it.track.coverUrl },
                        tint = Color(0xFF118AB2)
                        , kindIcon = Icons.Filled.Download, artwork = downloads.take(4).map { it.track }
                    ) { AppUi.openDownloads() }
                }
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("localmusic", seen, 1),
                        title = "Musique locale",
                        subtitle = "Fichiers de cet appareil",
                        cover = null,
                        tint = Color(0xFFF4A261)
                        , kindIcon = Icons.Filled.Folder, artwork = listOf<Track>()
                    ) { AppUi.openLocalFiles() }
                }
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("history", seen, 2),
                        title = "Récemment écoutés",
                        subtitle = "${recent.size} titres",
                        cover = recent.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl },
                        tint = Color(0xFF06D6A0)
                        , kindIcon = Icons.Filled.History, artwork = recent.take(4)
                    ) { AppUi.openCatalog(CatalogRequest("history", "local", "Récemment écoutés")) }
                }
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("replay", seen, 3),
                        title = "Replay",
                        subtitle = "Vos tops d'écoute",
                        cover = null,
                        tint = Color(0xFFD81159)
                        , kindIcon = Icons.Filled.History, artwork = listOf()
                    ) { AppUi.openReplay() }
                }
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("all", seen, 4),
                        title = "Tous les titres",
                        subtitle = "${all.size} titres",
                        cover = all.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl },
                        tint = Color(0xFFEF476F)
                        , kindIcon = Icons.Filled.MusicNote, artwork = all.take(4)
                    ) { AppUi.openCatalog(CatalogRequest("all", "local", "Tous les titres")) }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item {
                SectionHeader("Artistes", "Ceux que vous écoutez")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(artists, key = { it.key }) { entry ->
                        val first = entry.value.first()
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(82.dp).clickable {
                                AppUi.searchSeed.value = entry.key
                                AppUi.openSearch()
                            }
                        ) {
                            Cover(
                                entry.key,
                                ArtworkRepo.coverForTrack(first) ?: first.coverUrl,
                                size = 74.dp,
                                radius = 37.dp
                            )
                            Text(
                                entry.key,
                                localize = false,
                                color = theme.onBackground,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 7.dp)
                            )
                        }
                    }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item {
                SectionHeader("Albums", "Vos écoutes récentes")
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                    TextButton(onClick = { sortOpen = true }) {
                        Text("Trier", color = theme.accent)
                        Text(" · ", localize = false, color = theme.secondary)
                        Text(AlbumOrder.labels[albumOrder], color = theme.accent)
                    }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        AlbumOrder.labels.forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(label, color = theme.onBackground) }, onClick = {
                                albumOrder = index; sortOpen = false
                            })
                        }
                    }
                }
                LazyRow(
                    modifier = Modifier.testTag("library-albums"),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                     horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(albums, key = { _, track -> track.albumId!! }) { index, track ->
                         Column(
                            Modifier.then(if (Motion.reduced) Modifier else Modifier.animateItem())
                                .collectionEntry("album:${track.albumId}", seen, index)
                                .width(layout.collectionSizeDp.dp).clickable {
                                AppUi.openCatalog(CatalogRequest("album", track.albumId!!, track.album))
                            }
                        ) {
                            Cover(track.album, track.coverUrl, layout.collectionSizeDp.dp, 12.dp)
                            Text(
                                track.album,
                                localize = false,
                                color = theme.onBackground,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 7.dp)
                            )
                            Text(
                                track.artist,
                                localize = false,
                                color = theme.secondary,
                                fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 13.sp else 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("Titres récents", if (recent.isEmpty()) "Votre historique apparaîtra ici" else "Reprenez l'écoute")
        }
        if (recent.isNotEmpty()) {
            items(recent.take(6).chunked(columns), key = { "recent-${it.first().id}" }) { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { track ->
                        LibraryTrackRow(track, modifier = Modifier.weight(1f)) {
                            Player.playQueue(recent.take(6), recent.indexOf(track))
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (downloads.isNotEmpty()) {
            item {
                SectionHeader("Téléchargements", "${downloads.size} titres disponibles hors ligne")
            }
            items(downloads.take(6).chunked(columns), key = { "download-${it.first().track.id}" }) { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { item ->
                        LibraryTrackRow(item.track, downloaded = true, modifier = Modifier.weight(1f)) {
                            Player.playQueue(downloads.map { it.track }, downloads.indexOf(item))
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (all.isEmpty()) {
            item {
                EmptyState("Votre bibliothèque est vide", "Écoutez un titre ou téléchargez-le pour le retrouver ici, même hors ligne.",
                    "Rechercher des titres", onAction = ::openTrackSearch)
            }
        }
    }
}

@Composable
private fun LibraryHeader() {
    val theme = LocalTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Bibliothèque",
            color = theme.onBackground,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.8).sp,
            modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(theme.card)
                .clickable { AppUi.openCatalog(CatalogRequest("history", "local", "Récemment écoutés")) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.History, "Historique d'écoute", tint = theme.onBackground, modifier = Modifier.size(22.dp))
        }
        if (!LocalAdaptiveLayout.current.hasRail) {
            Spacer(Modifier.width(12.dp))
            AccountAvatar("K", 38.dp, Modifier.clickable { AppUi.showAccount.value = true })
        }
    }
}

@Composable
private fun SoundWaveIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 3.dp.toPx()
        val spacing = h / 3.8f
        for (i in 0..2) {
            val y = 4.dp.toPx() + i * spacing
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, y)
                cubicTo(w * 0.35f, y - 4.dp.toPx(), w * 0.65f, y + 4.dp.toPx(), w, y)
            }
            drawPath(path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun SpotifyIcon(modifier: Modifier = Modifier) {
    // Logo Spotify officiel (vector drawable : disque noir + glyphe vert #1DB954).
    Image(
        painter = painterResource(R.drawable.ic_spotify_official),
        contentDescription = "Spotify",
        modifier = modifier
    )
}

@Composable
private fun ChevronRightIcon(modifier: Modifier = Modifier, tint: Color = Color.Gray) {
    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.3f, h * 0.2f)
            lineTo(w * 0.7f, h * 0.5f)
            lineTo(w * 0.3f, h * 0.8f)
        }
        drawPath(path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
private fun ExperienceCarousel(
    minutesListened: Int,
    totalPlays: Int,
    topArtist: String?,
    memberSince: String,
    modifier: Modifier = Modifier,
    listenedLabel: String = "$minutesListened",
    listenedUnit: String = "MINUTES ÉCOUTÉES"
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Carte 1 : "VOTRE EXPÉRIENCE D'ÉCOUTE" (Frame 120)
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .height(176.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF2E1938),
                                Color(0xFF1B1124),
                                Color(0xFF140D1A)
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                // Haut : Label + 3 vagues ondulées
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "VOTRE EXPÉRIENCE D'ÉCOUTE",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    SoundWaveIcon(
                        modifier = Modifier.size(width = 32.dp, height = 20.dp),
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }

                // Milieu : Grand chiffre des minutes
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(top = 10.dp)
                ) {
                    Text(
                        listenedLabel,
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1.2).sp
                    )
                    Text(
                        listenedUnit,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                // Bas : Signature KONNEKT et membre depuis
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            "KETVOICE",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            "$totalPlays écoutes",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "MEMBRE DEPUIS",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp
                        )
                        Text(
                            memberSince,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            // Carte 2 : "VOTRE ARTISTE DU MOMENT" — uniquement avec de vraies écoutes.
            val artistDisplay = topArtist?.takeIf { it.isNotBlank() } ?: return@item
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .height(176.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF22202A),
                                Color(0xFF16151E),
                                Color(0xFF0F0E14)
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "VOTRE ARTISTE DU MOMENT",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(top = 10.dp)
                ) {
                    Text(
                        artistDisplay,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "MEILLEUR ARTISTE",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            "KETVOICE",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            "$totalPlays écoutes",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    val theme = LocalTheme.current
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            title,
            color = theme.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-.3).sp
        )
        Spacer(Modifier.width(9.dp))
        Text(
            subtitle,
            color = theme.secondary,
            fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 13.sp else 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 3.dp)
        )
    }
}

@Composable
private fun LibraryCollectionCard(
    title: String,
    subtitle: String,
    cover: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    kindIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Star,
    artwork: List<Track> = emptyList(),
    onClick: () -> Unit
) {
    val size = LocalAdaptiveLayout.current.collectionSizeDp.dp
    Column(modifier.width(size).clickable(onClick = onClick)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(15.dp))) {
            if (DesignChoices.enabled("D3") && artwork.size > 1) {
                Row(Modifier.fillMaxSize()) { repeat(2){column->
                    Column(Modifier.weight(1f)) { repeat(2){row->
                        val track=artwork[(column+row*2)%artwork.size]
                        Box(Modifier.weight(1f).fillMaxWidth().background(tint.copy(alpha=.32f))) {
                            coil.compose.AsyncImage(ArtworkRepo.coverForTrack(track)?:track.coverUrl,null,
                                Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
                        }
                    } }
                } }
            } else if (cover != null) {
                Cover(title, cover, size, 15.dp)
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(tint, tint.copy(alpha = .55f), Color(0xFF1C1C1E)))
                    )
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .46f)))
                )
            )
            Icon(
                if (DesignChoices.enabled("D3")) kindIcon else Icons.Filled.Star,
                null,
                tint = Color.White.copy(alpha = .9f),
                modifier = Modifier.align(Alignment.TopEnd).padding(11.dp).size(18.dp)
            )
        }
        Text(
            title,
            color = LocalTheme.current.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            subtitle,
            color = LocalTheme.current.secondary,
            fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 13.sp else 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LibraryTrackRow(track: Track, downloaded: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val cover = ArtworkRepo.coverForTrack(track) ?: track.coverUrl
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DownloadCover(track, cover, 50.dp, 9.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            TrackTitle(track.title, color = theme.onBackground, fontSize = 15.sp)
            Text(
                track.artist,
                localize = false,
                color = theme.secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        if (downloaded) {
            Icon(Icons.Filled.Download, "Téléchargé", tint = theme.accent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).clickable {
                AppUi.trackMenuTrack.value = track
            },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MoreHoriz, "Options", tint = theme.secondary, modifier = Modifier.size(21.dp))
        }
    }
}

