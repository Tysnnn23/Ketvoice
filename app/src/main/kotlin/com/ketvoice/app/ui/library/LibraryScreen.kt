package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.AccountAvatar
import com.ketvoice.app.ui.common.Cover

@Composable
fun LibraryScreen() {
    val theme = LocalTheme.current
    val recent by ListeningHistory.tracks.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val downloads by Downloader.items.collectAsState()
    val all = (known + recent + downloads.map { it.track }).distinctBy { it.id }
    val liked = all.filter { it.id in AppSettings.favoriteIds.value }
    val albums = (recent + downloads.map { it.track }).filter { !it.albumId.isNullOrBlank() && it.albumId!!.all(Char::isDigit) }.distinctBy { it.albumId }
    LazyColumn(Modifier.fillMaxSize().background(theme.background).statusBarsPadding(), contentPadding = PaddingValues(bottom = 205.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bibliothèque", color = theme.onBackground, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                AccountAvatar("K", 36.dp, Modifier.clickable { AppUi.showAccount.value = true })
            }
            LibraryLink("Récemment écoutés", "${recent.size} titres") { AppUi.openCatalog(CatalogRequest("history", "local", "Récemment écoutés")) }
            LibraryLink("Favoris", "${liked.size} titres") { AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) }
            LibraryLink("Téléchargements", "${downloads.size} titres hors ligne") { AppUi.overlay.value = AppUi.DOWNLOADS }
        }
        if (albums.isNotEmpty()) item {
            Text("Albums écoutés", color = theme.onBackground, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(18.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(albums, key = { it.albumId!! }) { track ->
                    Column(Modifier.width(150.dp).clickable { AppUi.openCatalog(CatalogRequest("album", track.albumId!!, track.album)) }) {
                        Cover(track.album, track.coverUrl, 150.dp, 12.dp)
                        Text(track.album, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(track.artist, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (all.isEmpty()) item {
            Text("Vos écoutes, favoris et téléchargements apparaîtront ici.", color = theme.secondary,
                fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun LibraryLink(title: String, subtitle: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = theme.secondary, fontSize = 13.sp)
        }
        Text("›", color = theme.secondary, fontSize = 24.sp)
    }
}
