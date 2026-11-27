package com.ketvoice.app.tv

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.delay

/** One picture of the art frame: an album's cover (or an artist's photo) and the little text under it. */
data class TvArtFrameItem(
    val key: String,
    val image: String,
    val title: String,
    val subtitle: String,
    val artist: Boolean = false,
    /** What plays when the listener asks for music from the frame. */
    val tracks: List<Track> = emptyList(),
)

/**
 * « Mode cadre d'art » (Ketvoice TV 1.1.0): which pictures the frame shows and in what order. The covers of the
 * albums the listener plays most and likes most (a favourite title weighs three plays), one per album and per
 * picture; artists' photos, when asked for, come every [ARTIST_EVERY] covers. Pure: host-tested.
 */
object TvArtFramePlan {
    /** How long a picture stays. */
    const val HOLD_MS = 20_000L
    /** The crossfade from one picture to the next (shorter on modest TVs, none under reduced motion). */
    const val FADE_MS = 2_400
    const val LIGHT_FADE_MS = 900
    const val MAX = 30
    const val ARTIST_EVERY = 3
    /** A favourite title counts as this many plays. */
    const val FAVORITE_WEIGHT = 3

    fun albums(own: List<Track>, counts: Map<String, Int>, favoriteIds: Set<String>, cover: (Track) -> String?): List<TvArtFrameItem> =
        own.filter { it.album.isNotBlank() }.distinctBy { it.id }.groupBy(TvRows::albumKey).values
            .mapNotNull { list -> albumItem(list, counts, favoriteIds, cover) }
            .sortedWith(compareByDescending<Pair<TvArtFrameItem, Int>> { it.second }.thenBy { it.first.key })
            .map { it.first }.distinctBy { it.image }.take(MAX)

    private fun albumItem(list: List<Track>, counts: Map<String, Int>, favoriteIds: Set<String>,
                          cover: (Track) -> String?): Pair<TvArtFrameItem, Int>? {
        val image = list.firstNotNullOfOrNull { cover(it)?.takeIf(String::isNotBlank) } ?: return null
        val score = list.sumOf { (counts[it.id] ?: 0) + if (it.id in favoriteIds) FAVORITE_WEIGHT else 0 }
        val first = list.first()
        return TvArtFrameItem("al:" + TvRows.albumKey(first), image, first.album, PersonalMixes.primaryArtist(first), tracks = list) to score
    }

    /** Artists' photos ([artists], best first) slipped in after every [every] covers. */
    fun withArtists(albums: List<TvArtFrameItem>, artists: List<TvArtFrameItem>, every: Int = ARTIST_EVERY): List<TvArtFrameItem> {
        if (artists.isEmpty() || every <= 0) return albums
        val out = ArrayList<TvArtFrameItem>(albums.size + artists.size)
        val queue = ArrayDeque(artists)
        albums.forEachIndexed { i, album ->
            out += album
            if ((i + 1) % every == 0) queue.removeFirstOrNull()?.let(out::add)
        }
        return out.distinctBy { it.image }.take(MAX)
    }

    fun next(index: Int, size: Int): Int = if (size <= 0) 0 else (index + 1).mod(size)
    fun previous(index: Int, size: Int): Int = if (size <= 0) 0 else (index - 1).mod(size)

    fun fadeMs(tier: TvTier, reduced: Boolean): Int = when {
        reduced -> 0
        tier == TvTier.Light -> LIGHT_FADE_MS
        else -> FADE_MS
    }

    /** The side of the square picture: 70 % of the screen's height (its name and the hint stay clear under it). */
    fun pictureDp(screenHeightDp: Float): Float = (screenHeightDp * .70f).coerceAtLeast(120f)

    /** The titles the frame plays: those of its pictures, in turn, each once. */
    fun music(items: List<TvArtFrameItem>, max: Int = 200): List<Track> = items.flatMap { it.tracks }.distinctBy { it.id }.take(max)
}

/** The frame's pictures from the listener's library; artists' photos (from the catalogue) when the setting asks for them. */
@Composable
fun rememberArtFrames(): State<List<TvArtFrameItem>> {
    val library = rememberTvLibrary()
    val withArtists by TvPrefs.artFrameArtists.collectAsState()
    return produceState(emptyList(), library, withArtists) {
        val albums = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            TvArtFramePlan.albums(library.favorites + library.known + library.downloads, library.counts, library.favoriteIds, library::cover)
        }
        value = albums
        if (withArtists) value = TvArtFramePlan.withArtists(albums, artistFrames(library))
    }
}

/** The photos of the six artists played most (the catalogue's; none offline). */
private suspend fun artistFrames(library: TvLibrary): List<TvArtFrameItem> =
    PersonalMixes.topArtists(library.known, library.counts, library.favoriteIds).take(6).mapNotNull { a ->
        val picture = runCatching { CatalogRepo.artistPicture(a.name, a.catalogId) }.getOrNull() ?: return@mapNotNull null
        TvArtFrameItem("ar:${a.name.lowercase()}", picture, a.name, "", artist = true, tracks = a.tracks)
    }

/** The picture shown: moves on every [TvArtFramePlan.HOLD_MS] while [running]; [MutableIntState] so the remote can step. */
@Composable
fun rememberFrameIndex(size: Int, running: Boolean = true): MutableIntState {
    val index = remember { mutableIntStateOf(0) }
    LaunchedEffect(size, running, index.intValue) {
        if (size <= 1 || !running) return@LaunchedEffect
        delay(TvArtFramePlan.HOLD_MS)
        index.intValue = TvArtFramePlan.next(index.intValue, size)
    }
    return index
}

/**
 * The gallery itself: the picture on a field of its own colours (a 48 × 27 decode, never a live blur), its name
 * under it, a slow crossfade to the next one, which is decoded ahead at the same size.
 */
@Composable
fun ArtFrameGallery(items: List<TvArtFrameItem>, index: Int, modifier: Modifier = Modifier, showText: Boolean = true) {
    val fade = TvArtFramePlan.fadeMs(tvTier(), Motion.reduced)
    BoxWithConstraints(modifier.background(TvDesign.Ink.page)) {
        val side = TvArtFramePlan.pictureDp(maxHeight.value).dp
        PrefetchNext(items, index, side)
        val item = items.getOrNull(index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        Crossfade(item, Modifier.fillMaxSize(), animationSpec = tween(fade), label = "art-frame") { shown ->
            if (shown != null) ArtFramePicture(shown, side, showText)
        }
    }
}

@Composable
private fun ArtFramePicture(item: TvArtFrameItem, side: Dp, showText: Boolean) {
    Box(Modifier.fillMaxSize().testTag("art-frame-picture"), contentAlignment = Alignment.Center) {
        TvBackdrop(item.image, Modifier.fillMaxSize(), veil = .62f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shape = if (item.artist) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(10.dp)
            ArtFrameImage(item.image, side, shape)
            if (showText) {
                Spacer(Modifier.height(16.dp))
                Text(item.title, localize = false, style = TvDesign.Type.label, color = TvDesign.Ink.secondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = side))
                if (item.subtitle.isNotBlank()) Text(item.subtitle, localize = false, style = TvDesign.Type.caption,
                    color = TvDesign.Ink.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The picture at its displayed size, the request built as [TvPrefetch.picture] builds it (a memory-cache hit) and
 * without the image loader's own fade: the frame's crossfade is the only one.
 */
@Composable
private fun ArtFrameImage(url: String, side: Dp, shape: androidx.compose.ui.graphics.Shape) {
    val context = LocalContext.current
    val px = TvImages.decodePx(side.value, LocalDensity.current.density)
    val light = tvTier() == TvTier.Light
    val request = remember(url, px, light) { TvPrefetch.pictureRequest(context, url, px, light) }
    coil.compose.AsyncImage(request, null, Modifier.size(side).clip(shape).background(Color.White.copy(alpha = .06f)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

/** The next picture (and its background) decoded while this one shows: the crossfade never waits for the network. */
@Composable
private fun PrefetchNext(items: List<TvArtFrameItem>, index: Int, side: Dp) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val light = tvTier() == TvTier.Light
    LaunchedEffect(items, index) {
        val next = items.getOrNull(TvArtFramePlan.next(index, items.size))?.takeIf { items.size > 1 } ?: return@LaunchedEffect
        delay(1_500)
        TvPrefetch.picture(context, next.image, TvImages.decodePx(side.value, density), light)
    }
}

/** Requests built exactly as [TvCover] and [TvBackdrop] build theirs, so that a prefetched picture is a memory-cache hit. */
object TvPrefetch {
    fun pictureRequest(context: Context, url: String, px: Int, light: Boolean): ImageRequest =
        ImageRequest.Builder(context).data(url).size(px, px).precision(Precision.INEXACT).allowRgb565(light).crossfade(false).build()

    fun picture(context: Context, url: String, px: Int, light: Boolean) {
        val loader = context.imageLoader
        loader.enqueue(pictureRequest(context, url, px, light))
        loader.enqueue(ImageRequest.Builder(context).data(url).size(TvImages.BACKDROP_W, TvImages.BACKDROP_H).precision(Precision.EXACT)
            .transformations(TinyBlur()).allowHardware(false).build())
    }
}

/**
 * The art frame page: the gallery over the whole screen, the screen kept on. OK (or the remote's play key) plays or
 * pauses music — the frame's own albums when nothing is loaded —, the arrows step through the pictures, Back leaves.
 */
@Composable
fun TvArtFramePage() {
    val items by rememberArtFrames()
    val index = rememberFrameIndex(items.size)
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(6_000); hint = false }
    KeepScreenOn()
    Box(Modifier.fillMaxSize().pageItem("art-frame", "gallery", first = true).focusable()
        .onKeyEvent { artFrameKey(it, items, index) }.testTag("tv-art-frame")) {
        if (items.isEmpty()) TvEmpty("Pas encore de pochettes",
            "Écoutez des albums ou ajoutez des titres à vos favoris : leurs pochettes s'afficheront ici.", Modifier.align(Alignment.Center))
        else ArtFrameGallery(items, index.intValue, Modifier.fillMaxSize())
        ArtFrameHint(hint, Modifier.align(Alignment.BottomCenter))
    }
}

private fun artFrameKey(event: androidx.compose.ui.input.key.KeyEvent, items: List<TvArtFrameItem>, index: MutableIntState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionRight -> { index.intValue = TvArtFramePlan.next(index.intValue, items.size); true }
        Key.DirectionLeft -> { index.intValue = TvArtFramePlan.previous(index.intValue, items.size); true }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { artFrameMusic(items); true }
        else -> false
    }
}

/** Plays or pauses what is loaded; with nothing loaded, the frame's own albums in a random order. */
private fun artFrameMusic(items: List<TvArtFrameItem>) {
    if (Player.state.value.current != null) { Player.toggle(); return }
    TvArtFramePlan.music(items).takeIf { it.isNotEmpty() }?.let(Player::playShuffled)
}

@Composable
private fun ArtFrameHint(visible: Boolean, modifier: Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (Motion.reduced) 0 else 900), label = "art-hint")
    Text("OK : musique · ◀ ▶ : images · Retour : quitter", style = TvDesign.Type.caption, color = Color.White.copy(alpha = .7f),
        modifier = modifier.padding(bottom = 14.dp).graphicsLayer { this.alpha = alpha }
            .background(Color.Black.copy(alpha = .35f), RoundedCornerShape(TvDesign.Radius.pill)).padding(horizontal = 18.dp, vertical = 8.dp))
}

/** The art frame is meant to stay: the TV's own screensaver does not take over while it shows. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
