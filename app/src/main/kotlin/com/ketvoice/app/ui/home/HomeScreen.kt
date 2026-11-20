package com.ketvoice.app.ui.home

import com.ketvoice.app.ui.motion.StaggeredReveal

import com.ketvoice.app.ui.common.collectPick
import com.ketvoice.app.ui.common.pick
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
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun HomeScreen() {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val state = rememberLazyListState()
    val recent by ListeningHistory.tracks.collectAsState()
    val feedShelves by HomeFeed.shelves.collectAsState()
    val feedAlbums by HomeFeed.albums.collectAsState()
    // What Ketvoice proposes leaves out the artists the listener blocked from the search.
    val blockedArtists = com.ketvoice.app.data.ArtistPrefs.blocked
    val shelves = remember(feedShelves, blockedArtists) {
        feedShelves.map { it.copy(tracks = it.tracks.filter(com.ketvoice.app.data.ArtistPrefs::allows)) }
    }
    val albums = remember(feedAlbums, blockedArtists) { feedAlbums.filter { com.ketvoice.app.data.ArtistPrefs.allows(it.artist) } }
    val loading by HomeFeed.loading.collectAsState()
    val origin by HomeFeed.origin.collectAsState()
    val youtube by YouTubeMusicSession.profile.collectAsState()
    // Only what Home shows of the player (6.6): the whole state also moves with buffering, quality and sources,
    // and each of those changes recomposed the whole page.
    val playingId by remember { Player.state.pick { it.current?.id } }.collectPick()
    val isPlaying by remember { Player.state.pick { it.isPlaying } }.collectPick()
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
    val leadId = playingId ?: recent.firstOrNull()?.id ?: featured.firstOrNull()?.id
    val shelfRows = remember(shelves, leadId) {
        val seen = mutableSetOf<String>().apply { leadId?.let(::add) }
        shelves.map { shelf -> shelf to shelf.tracks.filter { seen.add(it.id) } }
            .filter { it.second.isNotEmpty() }.take(2)
    }
    LaunchedEffect(Unit) { HomeFeed.start(context); HomeLearning.init(context) }
    // New releases of the artists played most: loaded beside the page, never blocking it.
    val known by ListeningHistory.knownTracks.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    var loadedReleases by remember { mutableStateOf<List<CatalogAlbum>>(emptyList()) }
    val releases = remember(loadedReleases, blockedArtists) { loadedReleases.filter { com.ketvoice.app.data.ArtistPrefs.allows(it.artist) } }
    LaunchedEffect(known.size / 10, counts.size / 10) {
        loadedReleases = runCatching { HomeReleases.load(known, counts, AppSettings.favoriteIds.value) }.getOrDefault(emptyList())
    }
    val learned = HomeLearning.scores
    // « Mix du jour »: the personal mix that suits the hour (computed from ListeningHistory, as the mix row is).
    val favorites = AppSettings.favoriteIds.value
    val daily = remember(known, recent, counts, favorites, blockedArtists, hour) {
        mixForHour(hour, PersonalMixes.propose(known.filter(com.ketvoice.app.data.ArtistPrefs::allows),
            recent.filter(com.ketvoice.app.data.ArtistPrefs::allows), counts, favorites))
    }
    // Large windows (LargeScreen.isLarge: the window, not the device) set the two lead cards side by side.
    val wide = com.ketvoice.app.ui.adaptive.LargeScreen.isLarge(layout.widthDp, layout.heightDp, com.ketvoice.app.BuildConfig.IS_QUEST)
    LaunchedEffect(recent) { ArtworkRepo.enrich(recent.take(20)) }
    // Ket 27: the large title hands over to a small one in the bar as the page scrolls (read in layers only).
    val handover = with(androidx.compose.ui.platform.LocalDensity.current) { HomeLayout.titleHandover.toPx() }
    val collapse = { HomeLayout.collapse(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, handover) }
    LaunchedEffect(state) {
        val fold = com.ketvoice.app.ui.chrome.ChromeCollapse()
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> AppUi.bottomCollapsed.value = fold.update(index, offset) }
    }
    Box(Modifier.fillMaxSize().pageBackground(theme)) {
        // A seasonal theme's decor (6.2) frames the glow behind the greeting and leaves with it.
        theme.season?.HomeDecor(Modifier, fade = {
            if (state.firstVisibleItemIndex > 0) 1f else state.firstVisibleItemScrollOffset / handover.coerceAtLeast(1f) })
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().statusBarsPadding().testTag("home-list"),
            contentPadding = PaddingValues(top = if (layout.hasRail) 24.dp else 12.dp, bottom = layout.bottomPaddingDp.dp)) {
            // The page opens on who it greets: the brand in small, the hour in large, the account within reach.
            item(key = "greeting") {
                Column(Modifier.graphicsLayer {
                    // The large title clears as it leaves, a little slower than the page under it.
                    val gone = collapse()
                    alpha = 1f - gone
                    if (!com.ketvoice.app.ui.motion.Motion.reduced && state.firstVisibleItemIndex == 0)
                        translationY = state.firstVisibleItemScrollOffset * .3f
                }) {
                Row(Modifier.fillMaxWidth().padding(start = com.ketvoice.app.ui.common.pageMargin, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.logo_clean), null, Modifier.size(20.dp), contentScale = ContentScale.Fit)
                            Text("KETVOICE", localize = false, color = theme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 2.2.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                        com.ketvoice.app.ui.common.PageTitle(greeting,
                            Modifier.padding(top = 6.dp).asHeading().testTag("home-greeting"), maxLines = 2)
                    }
                    if (!layout.hasRail) Box(Modifier.size(DesignTokens.Space.touch).clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) { AppUi.showAccount.value = true },
                        contentAlignment = Alignment.Center) { AccountAvatar("K", 38.dp) }
                }
                Row(Modifier.padding(start = com.ketvoice.app.ui.common.pageMargin, end = 18.dp, top = 4.dp, bottom = 14.dp)) {
                    Text(origin, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (youtube.connected) Text(" · ${youtube.name}", localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1)
                }
                }
            }
            // What another of the listener's devices plays, to resume here (signed in only).
            item(key = "resume-elsewhere") { ResumeElsewhereBanner() }
            // 7.0 « Pour toi »: what was playing and the mix of the day, as two large calm cards.
            item(key = "for-you") {
                StaggeredReveal(order = 0) {
                    HomeForYouLead(recent, hasResume = playingId != null || recent.isNotEmpty(), daily = daily, wide = wide)
                }
            }
            // The Vice City edition's one illustration, on its radio's card, while the theme is on.
            if (theme.viceCity) item(key = "vicefm") { com.ketvoice.app.ui.vicecity.ViceFmCard() }
            // The ambiances of the smart library, in the order that suits the hour.
            item(key = "moods") { StaggeredReveal(order = 1) { HomeMoods(hour) } }
            // New releases of the artists followed and played most: a fixed place now, right under the moods.
            if (releases.isNotEmpty()) item(key = "home-releases") {
                StaggeredReveal(order = 2) { HomeReleasesShelf(releases, Modifier.learnsOpens(HomeBlock.Releases.learningKey)) }
            }
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
            } else if (!grid) item(key = "recent-carousel") {
                // Large covers to start again from, the way a record shelf reads: the title playing wears its bars.
                StaggeredReveal(order = 3) {
                    RecentCarousel(recent.take(20), playingId, isPlaying,
                        onPlay = { index -> Player.playQueue(recent, index) })
                }
            } else item(key = "recent-grid") {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = com.ketvoice.app.ui.common.pageMargin).testTag("home-recent-grid")) {
                    val columns = recentColumns(maxWidth.value)
                    val side = (maxWidth - 12.dp * (columns - 1)) / columns
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        recent.take(columns * 2).chunked(columns).forEachIndexed { line, tracks ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                tracks.forEachIndexed { column, track ->
                                    RecentCover(track, side, playingId == track.id, isPlaying,
                                        onPlay = { Player.playQueue(recent, line * columns + column) })
                                }
                            }
                        }
                    }
                }
            }
            // 7.2: Favoris, Téléchargements and the pinned playlists, with their covers (replaces « Épinglés »).
            item(key = "home-library") { StaggeredReveal(order = 4) { HomeLibraryShelf() } }
            // What the public profiles the listener follows published and listened to (signed in, once there is some).
            item(key = "friends") { com.ketvoice.app.ui.friends.FriendsHomeSection() }
            // The other personal mixes (the mix of the day leads the page).
            item(key = "mixes") { HomeMixes(Modifier.padding(bottom = 6.dp), exclude = daily?.first?.id) }
            item(key = "week") { HomeWeekCard() }
            item(key = "replay") { HomeReplayLink() }
            // The first personal shelf always leads; the album row and the other shelves
            // take turns from one launch to the next, and never move while browsing.
            // Fewer sections fighting for attention (7.0): the catalogue's albums and two shelves close the page.
            homeBlockOrder(PersonalMixes.sessionSeed, shelfRows.size, albums.isNotEmpty(), false, learned).forEach { block ->
                when (block) {
                    // Placed under the moods since 7.0, never rotated.
                    HomeBlock.Releases -> Unit
                    HomeBlock.Albums -> if (albums.isNotEmpty()) item(key = "home-albums") {
                        Column(Modifier.learnsOpens(block.learningKey)) {
                        EditorialHeader("Albums du catalogue", onMore = { AppUi.selectTab(AppUi.EXPLORER) })
                        val cell = com.ketvoice.app.ui.adaptive.shelfCell(156.dp, com.ketvoice.app.ui.common.pageMargin, HomeLayout.gap)
                        HomeRow {
                            itemsIndexed(albums, key = { _, album -> album.id }) { _, album ->
                                val albumCover = com.ketvoice.app.data.MediaOverrides.albumCover(album.title, album.artist, album.coverUrl)
                                val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(albumCover, com.ketvoice.app.theme.Ket27.Radius.artwork)
                                Column(Modifier.width(cell).clickable {
                                    zoom.openCatalog(CatalogRequest("album", album.id, album.title))
                                }) {
                                    Cover(album.title, albumCover, cell, com.ketvoice.app.theme.Ket27.Radius.artwork, modifier = com.ketvoice.app.ui.motion.zoomSourceOf(zoom))
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
                                TrackCarousel(shown)
                            }
                        }
                    }
                }
            }
            if (shelves.isEmpty()) item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    if (loading) com.ketvoice.app.ui.common.ActivitySpinner(size = 24.dp)
                    else Text("Connectez-vous à Internet pour renouveler votre sélection.", color = theme.secondary,
                        fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                }
            }
        }
        HomeInlineTitle(greeting, collapse, Modifier.align(Alignment.TopCenter))
    }
}

/** How many recent covers fit side by side in the grid: three on a phone, up to six on a wide window. */
internal fun recentColumns(widthDp: Float): Int = (widthDp / 120f).toInt().coerceIn(3, 6)

/** The recent titles as a shelf of large covers. */
@Composable
private fun RecentCarousel(tracks: List<Track>, playingId: String?, playing: Boolean, onPlay: (Int) -> Unit) {
    // Large windows: more whole covers per row, sized to the page (ui/adaptive/LargeScreen.shelfCell); 150 dp on a phone.
    val cell = com.ketvoice.app.ui.adaptive.shelfCell(150.dp, com.ketvoice.app.ui.common.pageMargin, HomeLayout.gap)
    HomeRow(Modifier.testTag("home-recent-carousel")) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            RecentCover(track, cell, track.id == playingId, playing, onPlay = { onPlay(index) })
        }
    }
}

/**
 * One recent title: its cover large, lifted by a shadow of its own colour, a small play
 * button on it, the bars of the title playing, then its name and artist. A long press opens
 * the title's menu.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RecentCover(track: Track, side: androidx.compose.ui.unit.Dp, active: Boolean, playing: Boolean, onPlay: () -> Unit) {
    val menuAnchor = com.ketvoice.app.ui.common.rememberRowAnchor()
    val theme = LocalTheme.current
    val cover = com.ketvoice.app.ui.common.rememberTrackCover(track)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(com.ketvoice.app.theme.Ket27.Radius.artwork)
    val options = AppText.t("Options de ${track.title}")
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    Column(Modifier.width(side).then(menuAnchor.modifier).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        .combinedClickable(role = androidx.compose.ui.semantics.Role.Button, onLongClickLabel = options,
            onLongClick = { haptic(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); menuAnchor.open(track) }, onClick = onPlay)
        .testTag("home-recent")) {
        // 7.0: a soft neutral shadow under the cover (a halo of its own colour read as a glow); none on black.
        Box(Modifier.size(side).then(if (theme.isDark) Modifier else Modifier.shadow(6.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .18f), spotColor = Color.Black.copy(alpha = .22f)))) {
            com.ketvoice.app.ui.common.DownloadCover(track, cover, side, com.ketvoice.app.theme.Ket27.Radius.artwork)
            // A thin light rim: the cover reads as an object on a dark page.
            Box(Modifier.matchParentSize().border(.8.dp, Color.White.copy(alpha = if (theme.isDark) .10f else .0f), shape))
            if (active) Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.Black.copy(alpha = .55f)).padding(horizontal = 6.dp, vertical = 5.dp).testTag("home-recent-playing")) {
                com.ketvoice.app.ui.player.NowPlayingBars(playing, Modifier.size(width = 18.dp, height = 14.dp))
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White.copy(alpha = .92f)), contentAlignment = Alignment.Center) {
                Icon(com.ketvoice.app.ui.common.KetIcons.Play, null, tint = Color.Black, modifier = Modifier.size(15.dp))
            }
        }
        Text(track.title, localize = false, color = if (active) theme.accent else theme.onBackground, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp, start = 2.dp, end = 2.dp))
        Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp))
    }
}

@Composable
private fun TrackCarousel(tracks: List<Track>) {
    val theme = LocalTheme.current
    val cell = com.ketvoice.app.ui.adaptive.shelfCell(156.dp, com.ketvoice.app.ui.common.pageMargin, HomeLayout.gap)
    HomeRow {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
            Column(Modifier.width(cell).clickable { Player.playQueue(tracks, i) }) {
                com.ketvoice.app.ui.common.DownloadCover(track, com.ketvoice.app.ui.common.rememberTrackCover(track), cell, com.ketvoice.app.theme.Ket27.Radius.artwork)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val menuAnchor = com.ketvoice.app.ui.common.rememberRowAnchor()
                    Box(Modifier.size(DesignTokens.Space.touch).then(menuAnchor.modifier).clickable { menuAnchor.open(track) }, contentAlignment = Alignment.Center) {
                        Icon(com.ketvoice.app.ui.common.KetIcons.More, "Options de ${track.title}", tint = theme.secondary, modifier = Modifier.size(22.dp))
                    }
                }
                Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
