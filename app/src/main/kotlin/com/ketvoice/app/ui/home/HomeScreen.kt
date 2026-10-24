package com.ketvoice.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.R
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.AccountAvatar
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.player.PlayerSheetScrim
import com.ketvoice.app.ui.player.TrackMenuSheet

@Composable
fun HomeScreen() {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val state = rememberLazyListState()
    val recent by ListeningHistory.tracks.collectAsState()
    val shelves by HomeFeed.shelves.collectAsState()
    val albums by HomeFeed.albums.collectAsState()
    val loading by HomeFeed.loading.collectAsState()
    val origin by HomeFeed.origin.collectAsState()
    val youtube by YouTubeMusicSession.profile.collectAsState()
    val player by Player.state.collectAsState()
    var grid by remember { mutableStateOf(false) }
    val layout = LocalAdaptiveLayout.current
    val pageWidth = layout.recentPageWidthDp.dp
    val featured = shelves.firstOrNull { it.tracks.isNotEmpty() }?.tracks ?: recent.take(6)
    LaunchedEffect(Unit) { HomeFeed.start(context) }
    LaunchedEffect(recent) { ArtworkRepo.enrich(recent.take(20)) }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { AppUi.bottomCollapsed.value = it > 1 }
    }
    Box(Modifier.fillMaxSize().background(theme.background)) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(top = if (layout.hasRail) 24.dp else 12.dp, bottom = layout.bottomPaddingDp.dp)) {
            item {
                if (!layout.hasRail && !DesignChoices.enabled("D1")) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                        AccountAvatar("K", 36.dp, Modifier.align(Alignment.TopEnd).clickable { AppUi.showAccount.value = true })
                    }
                }
                Column(Modifier.padding(horizontal = 18.dp)) {
                    if(DesignChoices.enabled("D1"))Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.logo_clean),"Ketvoice",Modifier.size(30.dp),contentScale=ContentScale.Fit)
                        Text("KETVOICE",localize=false,color=theme.onBackground,fontSize=13.sp,fontWeight=FontWeight.Bold,
                            letterSpacing=2.sp,modifier=Modifier.weight(1f).padding(start=10.dp))
                        if(!layout.hasRail)AccountAvatar("K",36.dp,Modifier.clickable{AppUi.showAccount.value=true})
                    } else Image(painterResource(R.drawable.logo_clean), "Ketvoice",
                        Modifier.height(48.dp).width(48.dp), contentScale = ContentScale.Fit)
                    Text("Écouter maintenant", color = theme.onBackground, fontSize = if (layout.hasRail) 40.sp else 30.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp, maxLines = 2, modifier = Modifier.padding(top = 14.dp))
                    Row(Modifier.padding(top=3.dp,bottom=22.dp)) {
                        Text(origin,color=theme.secondary,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(youtube.connected)Text(" · ${youtube.name}",localize=false,color=theme.secondary,fontSize=12.sp,maxLines=1)
                    }
                }
            }
            if(DesignChoices.enabled("D2")&&featured.isNotEmpty())item(key="editorial"){HomeEditorial(featured)}
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Récents", color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.GridView, "Changer la présentation des récents", tint = theme.secondary,
                        modifier = Modifier.size(26.dp).clickable { grid = !grid })
                }
            }
            if (recent.isEmpty()) item {
                Text("Vos titres écoutés apparaîtront ici.", color = theme.secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp))
            } else if (!grid) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val pages = recent.take(20).chunked(4)
                    itemsIndexed(pages) { page, tracks ->
                        Column(Modifier.width(pageWidth)) {
                            tracks.forEachIndexed { row, track ->
                                HomeRecentRow(track, active = player.current?.id == track.id,
                                    onPlay = { Player.playQueue(recent, page * 4 + row) }, onMenu = { AppUi.trackMenuTrack.value = track })
                            }
                        }
                    }
                }
            } else item {
                TrackCarousel(recent.take(20), onMenu = { AppUi.trackMenuTrack.value = it })
            }
            if (albums.isNotEmpty()) item {
                Text("Albums du catalogue", color = theme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 18.dp, top = 28.dp, bottom = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(albums, key = { _, album -> album.id }) { _, album ->
                        Column(Modifier.width(156.dp).clickable { AppUi.openCatalog(CatalogRequest("album", album.id, album.title)) }) {
                            Cover(album.title, album.coverUrl, 156.dp, 13.dp)
                            Text(album.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(album.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            shelves.forEach { shelf ->
                val shown=if(DesignChoices.enabled("D2"))shelf.tracks.filterNot{it.id==featured.firstOrNull()?.id} else shelf.tracks
                if(shown.isNotEmpty()) {
                item(key = shelf.title) {
                    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 12.dp)) {
                        Text(shelf.title, color = theme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(shelf.subtitle, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    TrackCarousel(shown, onMenu = { AppUi.trackMenuTrack.value = it })
                }
                }
            }
            if (shelves.isEmpty()) item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = theme.accent, strokeWidth = 2.dp)
                    else Text("Connectez-vous à Internet pour renouveler votre sélection.", color = theme.secondary,
                        fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeRecentRow(track: Track, active: Boolean, onPlay: () -> Unit, onMenu: () -> Unit) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    Row(Modifier.fillMaxWidth().height(59.dp).clickable(onClick = onPlay), verticalAlignment = Alignment.CenterVertically) {
        Cover(track.title, art["t:${track.id}"] ?: track.coverUrl, 50.dp, 7.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, localize = false, color = if (active) theme.accent else theme.onBackground, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(38.dp).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MoreVert, "Options de ${track.title}", tint = theme.secondary, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun TrackCarousel(tracks: List<Track>, onMenu: (Track) -> Unit) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
            Column(Modifier.width(156.dp).clickable { Player.playQueue(tracks, i) }) {
                Cover(track.title, art["t:${track.id}"] ?: track.coverUrl, 156.dp, 13.dp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.MoreVert, "Options de ${track.title}", tint = theme.secondary,
                        modifier = Modifier.size(22.dp).clickable { onMenu(track) })
                }
                Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
