package com.ketvoice.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.CatalogAlbum
import com.ketvoice.app.data.CatalogChart
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.HomeFeed
import com.ketvoice.app.data.HomeReleases
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.collectPick
import com.ketvoice.app.ui.common.pick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Home on the TV: what was playing in large, then the rows the phone's Home is made of — recently played, the
 * library (favourites, downloads, playlists), personal mixes, new releases of the artists played most, those artists,
 * the catalogue's picks and what is popular now. Rows are built beside the main thread.
 */
@Composable
fun TvHomePage() {
    val actions = LocalTvActions.current
    val library = rememberTvLibrary()
    val context = LocalContext.current
    LaunchedEffect(Unit) { HomeFeed.start(context) }
    val feed by HomeFeed.shelves.collectAsState()
    val feedAlbums by HomeFeed.albums.collectAsState()
    val blocked = ArtistPrefs.blocked
    var releases by remember { mutableStateOf<List<CatalogAlbum>>(emptyList()) }
    var chart by remember { mutableStateOf<CatalogChart?>(null) }
    LaunchedEffect(library.known.size / 10, library.counts.size / 10) {
        releases = runCatching { HomeReleases.load(library.known, library.counts, library.favoriteIds) }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { chart = runCatching { CatalogRepo.chart() }.getOrNull() }
    val pictures by rememberArtistPictures(library)
    val shelves by produceState(emptyList<TvShelf>(), library, feed, feedAlbums, releases, chart, blocked, pictures) {
        value = withContext(Dispatchers.Default) {
            val allowed = { t: Track -> ArtistPrefs.allows(t) }
            val known = library.known.filter(allowed)
            val recent = library.recent.filter(allowed)
            TvRows.home(TvRows.HomeInput(
                recent = library.recent,
                playlists = library.playlists,
                favorites = library.favorites,
                downloads = library.downloads,
                mixes = PersonalMixes.propose(known, recent, library.counts, library.favoriteIds),
                releases = releases.filter { ArtistPrefs.allows(it.artist) },
                artists = PersonalMixes.topArtists(known, library.counts, library.favoriteIds),
                artistPictures = pictures,
                feed = feed.map { s -> s.copy(tracks = s.tracks.filter(allowed)) },
                feedAlbums = feedAlbums.filter { ArtistPrefs.allows(it.artist) },
                chart = chart,
                cover = library::cover,
            ))
        }
    }
    val current by remember { Player.state.pick { it.current } }.collectPick()
    val playing by remember { Player.state.pick { it.playWhenReady } }.collectPick()
    val lead = current ?: library.recent.firstOrNull()
    // 1.1.0: Replay, Wrapped and the art frame, third on the page.
    val extras = rememberTvExtras()
    val rows = remember(shelves, extras) { TvExtras.placeIn(shelves, extras) }
    val state = rememberLazyListState()
    TvPivot(pivot = .16f, slack = .25f) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("tv-home"),
            contentPadding = PaddingValues(top = TvDesign.Safe.vertical, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(TvDesign.Size.rowSpacing)) {
            item(key = "hero", contentType = "hero") {
                TvHomeHero(lead, playing = current != null && playing, cover = lead?.let(library::cover)) {
                    // The queue of the last session is still loaded: it resumes where it stopped.
                    if (current != null) { if (!playing) Player.toggle(); actions.nav.open(TvRoute.NowPlaying) }
                    else lead?.let { actions.play(library.recent.ifEmpty { listOf(it) }, 0) }
                }
            }
            items(rows, key = { it.key }, contentType = { "shelf" }) { shelf ->
                TvShelfRow(shelf, actions::open, onMenu = actions::options, first = lead == null && shelf == rows.firstOrNull())
            }
            if (rows.isEmpty() && lead == null) item(key = "empty") {
                TvEmpty("Bienvenue sur Ketvoice TV", "Connectez votre compte dans Réglages pour retrouver votre bibliothèque, ou cherchez un titre.")
            }
        }
    }
}

/** The photos of the artists played most (the catalogue's, a dozen at most, four asked at a time); names in lower case. */
@Composable
private fun rememberArtistPictures(library: TvLibrary) = produceState(emptyMap<String, String>(), library.known.size / 10, library.counts.size / 10) {
    val artists = PersonalMixes.topArtists(library.known, library.counts, library.favoriteIds).take(12)
    val permits = kotlinx.coroutines.sync.Semaphore(4)
    value = kotlinx.coroutines.coroutineScope {
        artists.map { a ->
            async { permits.withPermit { runCatching { CatalogRepo.artistPicture(a.name, a.catalogId) }.getOrNull()?.let { a.name.lowercase() to it } } }
        }.mapNotNull { it.await() }.toMap()
    }
}

/** The first thing Home shows: the title playing (or played last), large, with one button to go on. */
@Composable
private fun TvHomeHero(track: Track?, playing: Boolean, cover: String?, onPlay: () -> Unit) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    Column(Modifier.fillMaxWidth().padding(horizontal = TvDesign.Safe.horizontal)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(com.ketvoice.app.ui.home.homeGreeting(hour), style = TvDesign.Type.display, color = TvDesign.Ink.primary,
                modifier = Modifier.weight(1f).testTag("tv-greeting"))
            // Not signed in yet: the one step that brings the phone's library here, beside the greeting.
            val account by com.ketvoice.app.account.AccountSession.state.collectAsState()
            if (!account.signedIn) {
                val actions = LocalTvActions.current
                TvButton("Se connecter avec le téléphone", null, { actions.nav.open(TvRoute.SignIn) },
                    Modifier.pageItem("hero", "sign-in", first = track == null), primary = track == null, tag = "hero-sign-in")
            }
        }
        if (track == null) return@Column
        Spacer(Modifier.height(18.dp))
        Row(Modifier.clip(RoundedCornerShape(TvDesign.Radius.panel)).background(TvDesign.Ink.glass).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TvCover(cover, 132.dp, RoundedCornerShape(TvDesign.Radius.card), placeholder = KetIcons.Music)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.width(380.dp)) {
                Text(if (playing) "En cours de lecture" else "Reprendre", style = TvDesign.Type.caption, color = TvDesign.Ink.secondary)
                Spacer(Modifier.height(4.dp))
                Text(track.title, localize = false, style = TvDesign.Type.title, color = TvDesign.Ink.primary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(track.artist, localize = false, style = TvDesign.Type.body, color = TvDesign.Ink.secondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                TvButton(if (playing) "Ouvrir le lecteur" else "Reprendre", KetIcons.Play, onPlay,
                    Modifier.pageItem("hero", "play", first = true), primary = true, tag = "hero-play")
            }
        }
    }
}
