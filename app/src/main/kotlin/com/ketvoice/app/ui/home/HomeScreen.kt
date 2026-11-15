package com.ketvoice.app.ui.home

import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.motion.captureRevealOrigin
import com.ketvoice.app.ui.motion.rememberRevealOrigin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import com.ketvoice.app.theme.DesignTokens
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.AccountAvatar
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.player.PlayerSheetScrim
import com.ketvoice.app.ui.player.TrackMenuSheet
import com.ketvoice.app.ui.library.PlaylistShelf

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
    val playlists by PlaylistStore.lists.collectAsState()
    var grid by remember { mutableStateOf(false) }
    val layout = LocalAdaptiveLayout.current
    // Read when the app comes back to the foreground: the title follows the day without
    // ever flipping while the page is being looked at.
    fun hourNow() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    var hour by remember { mutableIntStateOf(hourNow()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { hour = hourNow(); onPauseOrDispose { } }
    val greeting = homeGreeting(hour)
    val featured = shelves.firstOrNull { it.tracks.isNotEmpty() }?.tracks ?: recent.take(6)
    val leadId = player.current?.id ?: recent.firstOrNull()?.id ?: featured.firstOrNull()?.id
    val shelfRows = remember(shelves, leadId) {
        val seen = mutableSetOf<String>().apply { leadId?.let(::add) }
        shelves.map { shelf -> shelf to shelf.tracks.filter { seen.add(it.id) } }
            .filter { it.second.isNotEmpty() }.take(3)
    }
    LaunchedEffect(Unit) { HomeFeed.start(context); HomeLearning.init(context) }
    // New releases of the artists played most: loaded beside the page, never blocking it.
    val known by ListeningHistory.knownTracks.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    var releases by remember { mutableStateOf<List<CatalogAlbum>>(emptyList()) }
    LaunchedEffect(known.size / 10, counts.size / 10) {
        releases = runCatching { HomeReleases.load(known, counts, AppSettings.favoriteIds.value) }.getOrDefault(emptyList())
    }
    val learned = HomeLearning.scores
    LaunchedEffect(recent) { ArtworkRepo.enrich(recent.take(20)) }
    LaunchedEffect(state) {
        val fold = com.ketvoice.app.ui.chrome.ChromeCollapse()
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> AppUi.bottomCollapsed.value = fold.update(index, offset) }
    }
    Box(Modifier.fillMaxSize().pageBackground(theme)) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().statusBarsPadding().testTag("home-list"),
            contentPadding = PaddingValues(top = if (layout.hasRail) 24.dp else 12.dp, bottom = layout.bottomPaddingDp.dp)) {
            // The page opens on who it greets: the brand in small, the hour in large, the account within reach.
            item(key = "greeting") {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.logo_clean), null, Modifier.size(20.dp), contentScale = ContentScale.Fit)
                            Text("KETVOICE", localize = false, color = theme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 2.2.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(greeting, color = theme.onBackground, fontSize = if (layout.hasRail) 42.sp else 34.sp, fontWeight = FontWeight.Black,
                            letterSpacing = (-1.2).sp, lineHeight = if (layout.hasRail) 46.sp else 38.sp, maxLines = 2,
                            modifier = Modifier.padding(top = 6.dp).asHeading().testTag("home-greeting"))
                    }
                    if (!layout.hasRail) Box(Modifier.size(DesignTokens.Space.touch).clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { AppUi.showAccount.value = true },
                        contentAlignment = Alignment.Center) { AccountAvatar("K", 38.dp) }
                }
                Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 14.dp)) {
                    Text(origin, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (youtube.connected) Text(" · ${youtube.name}", localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1)
                }
            }
            // "For you" leads: a paused title, a fresh release or the mix of the hour; else the former card.
            item(key = "for-you") {
                HomeForYou(hour, releases) {
                    if (player.current != null || recent.isNotEmpty()) HomeEditorial(if (player.current != null) player.queue else recent, resume = true)
                    else if (featured.isNotEmpty()) HomeEditorial(featured)
                }
            }
            // Shortcuts to what already exists on this device.
            item(key = "shortcuts") { HomeShortcuts(Modifier.padding(top = 8.dp)) }
            // What was played last, within a thumb's reach: tiles to start again from, or large covers.
            item(key = "recent-heading") {
                EditorialHeader("Récents", onMore = if (recent.isNotEmpty()) ({
                    AppUi.openCatalog(CatalogRequest("history", "history", "Historique"))
                }) else null) {
                    androidx.compose.material3.IconButton(onClick = { grid = !grid }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.GridView, "Changer la présentation des récents", tint = theme.secondary, modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (recent.isEmpty()) item {
                Text("Vos titres écoutés apparaîtront ici.", color = theme.secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp))
            } else if (!grid) item(key = "recent-tiles") {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("home-recent-tiles")) {
                    val columns = recentColumns(maxWidth.value)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.take(columns * 3).chunked(columns).forEachIndexed { line, tracks ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                tracks.forEachIndexed { column, track ->
                                    RecentTile(track, active = player.current?.id == track.id, modifier = Modifier.weight(1f),
                                        onPlay = { Player.playQueue(recent, line * columns + column) },
                                        onMenu = { AppUi.trackMenuTrack.value = track })
                                }
                                repeat(columns - tracks.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            } else item(key = "recent-covers") {
                TrackCarousel(recent.take(20), onMenu = { AppUi.trackMenuTrack.value = it })
            }
            if (playlists.any { it.pinned }) item(key = "pinned") {
                EditorialHeader("Épinglés", onMore = { AppUi.selectTab(AppUi.LIBRARY) })
                PlaylistShelf(playlists.filter { it.pinned })
            }
            // Personal mixes appear once enough music has been played, and rotate at each launch.
            item(key = "mixes") { HomeMixes(Modifier.padding(bottom = 6.dp)) }
            item(key = "week") { HomeWeekCard() }
            // The first personal shelf always leads; the album row and the other shelves
            // take turns from one launch to the next, and never move while browsing.
            homeBlockOrder(PersonalMixes.sessionSeed, shelfRows.size, albums.isNotEmpty(), releases.isNotEmpty(), learned).forEach { block ->
                when (block) {
                    HomeBlock.Releases -> item(key = "home-releases") {
                        HomeReleasesShelf(releases, Modifier.learnsOpens(block.learningKey))
                    }
                    HomeBlock.Albums -> if (albums.isNotEmpty()) item(key = "home-albums") {
                        Column(Modifier.learnsOpens(block.learningKey)) {
                        EditorialHeader("Albums du catalogue", onMore = { AppUi.selectTab(AppUi.EXPLORER) })
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(albums, key = { _, album -> album.id }) { _, album ->
                                val origin = rememberRevealOrigin()
                                Column(Modifier.width(156.dp).captureRevealOrigin(origin).clickable {
                                    AppUi.openCatalog(CatalogRequest("album", album.id, album.title), origin.value)
                                }) {
                                    Cover(album.title, com.ketvoice.app.data.MediaOverrides.albumCover(album.title, album.artist, album.coverUrl), 156.dp, 16.dp)
                                    Text(album.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                    Text(album.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        }
                    }
                    else -> shelfRows.getOrNull(block.ordinal - 1)?.let { (shelf, shown) ->
                        if (shown.isNotEmpty()) item(key = "home-shelf:${block.ordinal - 1}:${shelf.title}") {
                            Column(Modifier.learnsOpens(block.learningKey)) {
                                EditorialHeader(shelf.title, subtitle = shelf.subtitle)
                                TrackCarousel(shown, onMenu = { AppUi.trackMenuTrack.value = it })
                            }
                        }
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

/** How many recent titles fit side by side: two on a phone, up to four on a wide window. */
internal fun recentColumns(widthDp: Float): Int = (widthDp / 190f).toInt().coerceIn(2, 4)

/** A recent title as a compact tile: its cover flush with the edge, its name beside it. A long press opens its menu. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RecentTile(track: Track, active: Boolean, modifier: Modifier, onPlay: () -> Unit, onMenu: () -> Unit) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    val options = AppText.t("Options de ${track.title}")
    Row(modifier.height(60.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(theme.card)
        .combinedClickable(role = androidx.compose.ui.semantics.Role.Button, onLongClickLabel = options, onLongClick = onMenu, onClick = onPlay)
        .testTag("home-recent-tile"), verticalAlignment = Alignment.CenterVertically) {
        com.ketvoice.app.ui.common.DownloadCover(track, com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl), 60.dp, 0.dp)
        Text(track.title, localize = false, color = if (active) theme.accent else theme.onBackground, fontSize = 13.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
    }
}

@Composable
private fun TrackCarousel(tracks: List<Track>, onMenu: (Track) -> Unit) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
            Column(Modifier.width(156.dp).clickable { Player.playQueue(tracks, i) }) {
                com.ketvoice.app.ui.common.DownloadCover(track, com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl), 156.dp, 16.dp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(Modifier.size(DesignTokens.Space.touch).clickable { onMenu(track) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MoreVert, "Options de ${track.title}", tint = theme.secondary, modifier = Modifier.size(22.dp))
                    }
                }
                Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
