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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtistBio
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtistPages
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text

/**
 * An artist, immersive (1.1.0, « Page artiste immersive »): their photo fills the screen (the owner's chosen photo
 * first, then the artist's fan art, then the catalogue's portrait), the name very large over it with a short
 * biography and Play / Radio, then Popular titles, Albums, Singles and EP, the listener's own titles and Similar
 * artists as rows over the photo, which darkens as the rows come up.
 */
@Composable
fun TvArtistPage(route: TvRoute.Artist) {
    val actions = LocalTvActions.current
    val library = rememberTvLibrary()
    var attempt by remember { mutableIntStateOf(0) }
    val page by produceState<ArtistPage?>(null, route, attempt) {
        value = runCatching { ArtistPages.cachedOrFetch(route.id.takeIf { it.startsWith("UC") }.orEmpty(), route.name) }.getOrNull()
    }
    var loaded by remember(route, attempt) { mutableStateOf(false) }
    LaunchedEffect(page) { if (page != null) loaded = true }
    val name = page?.artist?.name ?: route.name
    val own = remember(library, route.name) { library.known.filter { PersonalMixes.primaryArtist(it).equals(route.name, ignoreCase = true) } }
    val top = page?.tracks?.ifEmpty { null } ?: own
    val shelves = remember(page, own) { TvArtistRows.shelves(page, own, library::cover) }
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        // Without any photo, the colours of their best-known cover fill the page (as the phone does).
        TvArtistBackdrop(rememberArtistPhoto(route, page), top.firstNotNullOfOrNull(library::cover), route, list)
        TvPivot(pivot = .16f, slack = .15f) {
            LazyColumn(Modifier.fillMaxSize().testTag("tv-artist"), state = list, contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(TvDesign.Size.rowSpacing)) {
                item(key = "header") {
                    TvArtistHero(name, page, onPlay = { actions.play(top) }, onRadio = {
                        Player.startArtistRadio(route.name, route.id.takeIf { id -> id.all(Char::isDigit) && id.isNotEmpty() }, top)
                        actions.nav.open(TvRoute.NowPlaying)
                    })
                }
                items(shelves, key = { it.key }) { shelf -> TvShelfRow(shelf, actions::open, onMenu = actions::options) }
                if (shelves.isEmpty() && loaded) item(key = "retry") {
                    TvButton("Réessayer", KetIcons.Refresh, { attempt++ }, Modifier.padding(start = TvDesign.Safe.horizontal))
                }
            }
        }
    }
}

/** The photo behind the page: the owner's chosen one, the artist's fan art, the catalogue's, the card's. */
@Composable
private fun rememberArtistPhoto(route: TvRoute.Artist, page: ArtistPage?): String? {
    val name = page?.artist?.name ?: route.name
    val art = com.ketvoice.app.ui.sub.rememberArtistArtwork(name)
    return MediaOverrides.artistHero(name) ?: art.hero ?: page?.artist?.photo?.takeIf { it.isNotBlank() } ?: route.picture
}

/**
 * The photo from edge to edge (under the side menu too), darkened from the bottom and the left where the name and
 * rows lie, and more as the rows scroll up (read while drawing). The card's picture lands on it ([tvZoomTarget]).
 */
@Composable
private fun TvArtistBackdrop(photo: String?, fallback: String?, route: TvRoute.Artist, list: LazyListState) {
    Box(Modifier.fillMaxSize().underRail().tvZoomTarget(route).testTag("artist-backdrop")) {
        TvBackdrop(photo ?: fallback, Modifier.fillMaxSize(), veil = .5f)
        TvPoster(photo, Modifier.fillMaxSize(), fill = true)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .72f), .45f to Color.Black.copy(alpha = .3f),
            .8f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .18f), .42f to Color.Transparent,
            .72f to TvDesign.Ink.page.copy(alpha = .78f), 1f to TvDesign.Ink.page.copy(alpha = .94f))))
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = TvArtistRows.scrim(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset.toFloat() / density) }
            .background(TvDesign.Ink.page.copy(alpha = .66f)))
    }
}

/** The name, very large, the listeners, a short biography (in the app's language when it differs) and Play / Radio. */
@Composable
private fun TvArtistHero(name: String, page: ArtistPage?, onPlay: () -> Unit, onRadio: () -> Unit) {
    Column(Modifier.fillMaxWidth().bringWholeIntoView()
        .padding(start = TvDesign.Safe.horizontal, end = TvDesign.Safe.horizontal, top = HERO_TOP.dp)) {
        Text(name, localize = false, style = TvDesign.Type.hero.copy(fontSize = 72.sp, lineHeight = 76.sp), color = TvDesign.Ink.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("artist-name"))
        page?.listeners?.takeIf { it.isNotBlank() }?.let {
            Text(it, localize = false, style = TvDesign.Type.body, color = TvDesign.Ink.secondary, maxLines = 1)
        }
        TvArtistBio(page?.description.orEmpty())
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Lecture", KetIcons.Play, onPlay, Modifier.pageItem("buttons", "play", first = true), primary = true, tag = "artist-play")
            TvButton("Lancer la radio", KetIcons.Shuffle, onRadio, Modifier.pageItem("buttons", "radio"), tag = "artist-radio")
        }
    }
}

/**
 * A focused button of the hero asks the page to show the whole hero (from the top of the photo), never to scroll the
 * name away to bring the button to the rows' pivot.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.bringWholeIntoView(): Modifier = composed {
    val size = remember { arrayOf(androidx.compose.ui.unit.IntSize.Zero) }
    val responder = remember {
        object : androidx.compose.foundation.relocation.BringIntoViewResponder {
            override fun calculateRectForParent(localRect: androidx.compose.ui.geometry.Rect) =
                androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size[0].toSize())
            override suspend fun bringChildIntoView(localRect: () -> androidx.compose.ui.geometry.Rect?) = Unit
        }
    }
    this.onSizeChanged { size[0] = it }.bringIntoViewResponder(responder)
}

/** Where the name starts: low on the photo, so that the face (usually in the upper part) stays clear. */
private const val HERO_TOP = 150

@Composable
private fun TvArtistBio(description: String) {
    if (description.isBlank()) return
    val language = AppText.language()
    val translation by produceState<String?>(null, description, language) { value = runCatching { ArtistBio.translated(description, language) }.getOrNull() }
    val text = if (ArtistBio.differs(description, translation)) translation!! else description
    Spacer(Modifier.height(10.dp))
    Text(TvArtistRows.shortBio(text), localize = false, style = TvDesign.Type.body, color = TvDesign.Ink.primary.copy(alpha = .82f), maxLines = 3,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp).testTag("artist-bio"))
}

/** The rows of an artist's page and its pure rules (host-tested). */
object TvArtistRows {
    fun shelves(page: ArtistPage?, own: List<Track>, cover: (Track) -> String?): List<TvShelf> {
        val top = page?.tracks.orEmpty().take(TvRows.ROW_MAX)
        val discography = page?.albums.orEmpty().let(com.ketvoice.app.ui.sub.ArtistDiscography::shelves)
        return listOfNotNull(
            top.takeIf { it.isNotEmpty() }?.let { list -> TvShelf("top", "Titres populaires", list.mapIndexed { i, t -> TvRows.track(t, list, i, cover) }) },
        ) + discography.map { s -> TvShelf(if (s.singles) "singles" else "albums", s.title, s.albums.map(TvRows::album)) } + listOfNotNull(
            own.take(TvRows.ROW_MAX).takeIf { it.isNotEmpty() }?.let { list ->
                TvShelf("own", "Dans votre bibliothèque", list.mapIndexed { i, t -> TvRows.track(t, list, i, cover) }) },
            page?.related?.takeIf { it.isNotEmpty() }?.let { related ->
                TvShelf("related", "Artistes similaires", related.map { a ->
                    val photo = MediaOverrides.artistPhoto(a.name, a.photo)
                    TvItem("ar:${a.id}", TvItemKind.Artist, a.name, "", photo, TvRoute.Artist(a.id, a.name, photo))
                })
            },
        )
    }

    /** The first sentences of a biography, up to about [max] characters, cut at a sentence (else a word). */
    fun shortBio(text: String, max: Int = 260): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= max) return clean
        val cut = clean.take(max)
        val sentence = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (sentence >= max / 2) return cut.take(sentence + 1)
        val word = cut.lastIndexOf(' ').takeIf { it > 0 } ?: cut.length
        return cut.take(word).trimEnd(',', ';', ':') + "…"
    }

    /** The veil over the photo as the rows scroll up: none at the top, two thirds once the name has gone (dp scrolled). */
    fun scrim(firstIndex: Int, offsetDp: Float): Float = if (firstIndex > 0) 1f else (offsetDp / 300f).coerceIn(0f, 1f)
}
