package com.ketvoice.app.ui.library

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
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

    LazyColumn(
        Modifier.fillMaxSize().testTag("library-list").background(theme.background).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = layout.bottomPaddingDp.dp)
    ) {
        item {
            LibraryHeader()
        }
        item {
            LocalMusicCard(all, downloads.size)
        }
        item {
            SectionHeader("Playlists", "Vos collections")
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
                    ) { AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) }
                }
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("downloads", seen, 1),
                        title = "Téléchargements",
                        subtitle = "${downloads.size} hors ligne",
                        cover = downloads.firstOrNull()?.let { ArtworkRepo.coverForTrack(it.track) ?: it.track.coverUrl },
                        tint = Color(0xFF118AB2)
                    ) { AppUi.overlay.value = AppUi.DOWNLOADS }
                }
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("history", seen, 2),
                        title = "Récemment écoutés",
                        subtitle = "${recent.size} titres",
                        cover = recent.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl },
                        tint = Color(0xFF06D6A0)
                    ) { AppUi.openCatalog(CatalogRequest("history", "local", "Récemment écoutés")) }
                }
                item {
                    LibraryCollectionCard(
                        modifier = Modifier.collectionEntry("all", seen, 3),
                        title = "Tous les titres",
                        subtitle = "${all.size} titres",
                        cover = all.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl },
                        tint = Color(0xFFEF476F)
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
                                AppUi.overlay.value = AppUi.SEARCH
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
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(theme.accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Download, null, tint = Color.White, modifier = Modifier.size(23.dp))
        }
        Text(
            "Bibliothèque",
            color = theme.onBackground,
            fontSize = 31.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-.7).sp,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(theme.card)
                .clickable { AppUi.openCatalog(CatalogRequest("history", "local", "Récemment écoutés")) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.History, null, tint = theme.onBackground, modifier = Modifier.size(19.dp))
        }
        if (!LocalAdaptiveLayout.current.hasRail) {
            Spacer(Modifier.width(10.dp))
            AccountAvatar("K", 36.dp, Modifier.clickable { AppUi.showAccount.value = true })
        }
    }
}

@Composable
private fun LocalMusicCard(all: List<Track>, downloads: Int) {
    val theme = LocalTheme.current
    val first = all.firstOrNull()
    val cover = first?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(theme.card)
            .clickable { AppUi.openCatalog(CatalogRequest("all", "local", "Tous les titres")) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))) {
            Cover(first?.title ?: "Musique locale", cover, 64.dp, 14.dp)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .28f)))
                )
            )
            Icon(
                Icons.Filled.PlayArrow,
                null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp)
            )
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(
                "Musique locale",
                color = theme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (all.isEmpty()) "Tous les titres" else "${all.size} titres · $downloads hors ligne",
                color = theme.secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                "Tout ce que vous avez écouté et téléchargé",
                color = theme.secondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text("›", color = theme.secondary, fontSize = 26.sp)
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
    onClick: () -> Unit
) {
    val size = LocalAdaptiveLayout.current.collectionSizeDp.dp
    Column(modifier.width(size).clickable(onClick = onClick)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(15.dp))) {
            if (cover != null) {
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
                Icons.Filled.Star,
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
