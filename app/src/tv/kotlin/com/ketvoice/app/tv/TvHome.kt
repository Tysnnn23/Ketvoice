package com.ketvoice.app.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
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
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Home on the TV (1.1.0, « Accueil affiche vivante »): the focused card's cover or artist photo fills the page behind
 * (a still picture, softly darkened towards the rows), its title large at the top with a short line; on arrival it is
 * the title playing (or played last) with one button to go on. Under it the rows the phone's Home is made of —
 * recently played, the library, personal mixes, new releases of the artists played most, those artists, the
 * catalogue's picks and what is popular now. Rows are built beside the main thread; moving the focus recomposes only
 * the poster and its titles, never the page.
 */
@Composable
fun TvHomePage() {
    val actions = LocalTvActions.current
    val library = rememberTvLibrary()
    val shelves by rememberHomeShelves(library)
    val current by remember { Player.state.pick { it.current } }.collectPick()
    val playing by remember { Player.state.pick { it.playWhenReady } }.collectPick()
    val lead = current ?: library.recent.firstOrNull()
    val leadSpot = lead?.let { TvSpot.lead(it, library.cover(it), current != null && playing) }
    // The poster tells what plays from the first frame (also on the way back from a page).
    val spotlight = remember { TvSpotlight().also { it.lead = leadSpot } }
    LaunchedEffect(leadSpot) { spotlight.lead = leadSpot }
    val onPlay: () -> Unit = {
        // The queue of the last session is still loaded: it resumes where it stopped.
        if (current != null) { if (!playing) Player.toggle(); actions.nav.open(TvRoute.NowPlaying) }
        else if (lead != null) actions.play(library.recent.ifEmpty { listOf(lead) }, 0)
    }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        TvHomeBackdrop(spotlight, Modifier.fillMaxSize().underRail())
        Column(Modifier.fillMaxSize()) {
            TvHomeHeader(spotlight, hasLead = lead != null, playing = current != null && playing, onPlay = onPlay)
            // The focused row stays at the top of the rows, under the poster's title; the next one peeks below.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TvPivot(pivot = HOME_PIVOT) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("tv-home"),
                        contentPadding = PaddingValues(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(TvDesign.Size.rowSpacing)) {
                        items(shelves, key = { it.key }, contentType = { "shelf" }) { shelf ->
                            TvShelfRow(shelf, actions::open, onMenu = actions::options, first = lead == null && shelf == shelves.firstOrNull(),
                                onFocusItem = spotlight::show)
                        }
                        if (shelves.isEmpty() && lead == null) item(key = "empty") {
                            TvEmpty("Bienvenue sur Ketvoice TV", "Connectez votre compte dans Réglages pour retrouver votre bibliothèque, ou cherchez un titre.")
                        }
                    }
                }
            }
        }
    }
}

/** Home's rows, built off the main thread from the library, the feed, new releases and the chart. */
@Composable
private fun rememberHomeShelves(library: TvLibrary): androidx.compose.runtime.State<List<TvShelf>> {
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
    return produceState(emptyList(), library, feed, feedAlbums, releases, chart, blocked, pictures) {
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

/**
 * What Home's poster shows: a title (song, album, artist… or the app's own words when [appText]), a small kind above
 * it, a short line under it (parts joined with « · », each translated when it is the app's text) and its picture.
 */
data class TvSpot(val key: String, val title: String, val eyebrow: String, val line: List<Pair<String, Boolean>>, val image: String?,
                  val appText: Boolean = false) {
    companion object {
        /** The card [item] as the poster tells it. Pure. */
        fun of(item: TvItem): TvSpot {
            val subtitle = item.subtitle.takeIf { it.isNotBlank() }
            val subtitleIsApp = item.appText || item.kind == TvItemKind.Playlist
            val (eyebrow, line) = when (item.kind) {
                TvItemKind.Track -> "Titre" to listOfNotNull(subtitle?.let { it to false })
                TvItemKind.Album -> item.release.ifBlank { "Album" } to listOfNotNull(subtitle?.let { it to false }, item.year.takeIf { it.isNotBlank() }?.let { it to false })
                TvItemKind.Artist -> "Artiste" to emptyList()
                TvItemKind.Mix -> "Mix" to listOfNotNull(subtitle?.let { it to true })
                TvItemKind.Playlist -> "Playlist" to listOfNotNull(subtitle?.let { it to subtitleIsApp })
                TvItemKind.Collection -> "Bibliothèque" to listOfNotNull(subtitle?.let { it to true })
            }
            return TvSpot(item.key, item.title, eyebrow, line, item.image, item.appText)
        }

        /** What plays (or played last), Home's poster on arrival. */
        fun lead(track: Track, cover: String?, playing: Boolean): TvSpot =
            TvSpot("lead:" + track.id, track.title, if (playing) "En cours de lecture" else "Reprendre",
                listOfNotNull(track.artist.takeIf { it.isNotBlank() }?.let { it to false }, track.album.takeIf { it.isNotBlank() }?.let { it to false }),
                cover)
    }
}

/** Which card Home's poster follows: the focused one, else (the resume button focused, or on arrival) what plays. */
class TvSpotlight {
    var focused by mutableStateOf<TvSpot?>(null)
    var lead by mutableStateOf<TvSpot?>(null)
    val shown: TvSpot? get() = focused ?: lead

    fun show(item: TvItem) { focused = TvSpot.of(item) }
    fun showLead() { focused = null }
}

/** The poster waits this long after the focus stops moving before it decodes the next picture. */
const val POSTER_DEBOUNCE_MS = 250L

/**
 * Behind Home: the shown card's colours as a soft field (a 48 × 27 decode), its picture large at the right edge
 * (decoded at the screen's height, once the focus has rested [POSTER_DEBOUNCE_MS]), and two veils that keep the
 * titles and the rows readable.
 */
@Composable
private fun TvHomeBackdrop(spotlight: TvSpotlight, modifier: Modifier) {
    val wanted by remember { androidx.compose.runtime.derivedStateOf { spotlight.shown?.image } }
    val url by produceState(wanted, wanted) { delay(POSTER_DEBOUNCE_MS); value = wanted }
    Box(modifier) {
        TvBackdrop(url, Modifier.fillMaxSize(), veil = .62f)
        TvPoster(url, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to TvDesign.Ink.page.copy(alpha = .86f), .38f to TvDesign.Ink.page.copy(alpha = .5f),
            .7f to TvDesign.Ink.page.copy(alpha = 0f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to TvDesign.Ink.page.copy(alpha = 0f), .4f to TvDesign.Ink.page.copy(alpha = .2f),
            .62f to TvDesign.Ink.page.copy(alpha = .82f), 1f to TvDesign.Ink.page.copy(alpha = .95f))))
    }
}

/** The top of Home: the greeting, the shown card's title large with its line, and the resume button. */
@Composable
private fun TvHomeHeader(spotlight: TvSpotlight, hasLead: Boolean, playing: Boolean, onPlay: () -> Unit) {
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    Column(Modifier.fillMaxWidth().height(HEADER_H.dp).padding(start = TvDesign.Safe.horizontal, end = TvDesign.Safe.horizontal,
        top = TvDesign.Safe.vertical)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(com.ketvoice.app.ui.home.homeGreeting(hour), style = TvDesign.Type.label, color = TvDesign.Ink.secondary,
                modifier = Modifier.weight(1f).testTag("tv-greeting"))
            TvSignInShortcut(first = !hasLead)
        }
        Spacer(Modifier.weight(1f))
        TvSpotText(spotlight)
        Spacer(Modifier.height(14.dp))
        if (hasLead) TvResumeButton(spotlight, playing, onPlay)
        Spacer(Modifier.height(10.dp))
    }
}

private const val HEADER_H = 262

/** Where the focused row's cards stay in the rows under the poster: its title just above them, the next row peeking below. */
private const val HOME_PIVOT = .17f

/** Not signed in yet: the one step that brings the phone's library here, beside the greeting. */
@Composable
private fun TvSignInShortcut(first: Boolean) {
    val account by com.ketvoice.app.account.AccountSession.state.collectAsState()
    if (account.signedIn) return
    val actions = LocalTvActions.current
    TvButton("Se connecter avec le téléphone", null, { actions.nav.open(TvRoute.SignIn) },
        Modifier.pageItem("hero", "sign-in", first = first), primary = first, tag = "hero-sign-in")
}

/** The shown card's kind, title and line; they cross-fade quickly when the focus moves. */
@Composable
private fun TvSpotText(spotlight: TvSpotlight) {
    val spot = spotlight.shown
    androidx.compose.animation.Crossfade(spot, animationSpec = tween(Motion.duration(220)), label = "home-spot") { s ->
        Column(Modifier.widthIn(max = 640.dp).testTag("home-spot")) {
            if (s == null) return@Column
            Text(s.eyebrow, style = TvDesign.Type.caption, color = TvDesign.Ink.secondary)
            Spacer(Modifier.height(2.dp))
            Text(s.title, localize = s.appText, style = TvDesign.Type.hero, color = TvDesign.Ink.primary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("home-spot-title"))
            val line = s.line.joinToString(" · ") { (text, app) -> if (app) AppText.t(text) else text }
            if (line.isNotBlank()) Text(line, localize = false, style = TvDesign.Type.body, color = TvDesign.Ink.secondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * « Reprendre » / « Ouvrir le lecteur »: shown while the poster tells what plays (on arrival, or the button focused);
 * it steps back while the focus walks the rows and comes back with Up.
 */
@Composable
private fun TvResumeButton(spotlight: TvSpotlight, playing: Boolean, onPlay: () -> Unit) {
    val onLead by remember { androidx.compose.runtime.derivedStateOf { spotlight.focused == null } }
    val shown by animateFloatAsState(if (onLead) 1f else 0f, tween(Motion.duration(200)), label = "home-resume")
    TvButton(if (playing) "Ouvrir le lecteur" else "Reprendre", KetIcons.Play, onPlay,
        Modifier.graphicsLayer { alpha = shown }.onFocusChanged { if (it.isFocused) spotlight.showLead() }
            .pageItem("hero", "play", first = true), primary = true, tag = "hero-play")
}
