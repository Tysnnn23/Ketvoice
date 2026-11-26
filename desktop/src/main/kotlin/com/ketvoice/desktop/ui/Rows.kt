package com.ketvoice.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/** Context menu, double-click to play and drag onto a playlist, shared by every title row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.trackInteractions(track: Track, list: List<Track>, index: Int, onSelect: () -> Unit = {}, extraMenu: List<MenuItem> = emptyList()): Modifier {
    val actions = LocalTrackActions.current
    val menu = LocalMenu.current
    val drag = LocalTrackDrag.current
    val library = LocalLibrary.current
    return this
        .contextMenu(menu) { onSelect(); actions.menu(track) { actions.play(list, index) } + extraMenu }
        .draggableTrack(track, drag) { id, t -> library.addToPlaylist(id, t) }
        .combinedClickable(onClick = onSelect, onDoubleClick = { actions.play(list, index) })
}

/** True when [track] is the one playing. */
@Composable
fun isCurrent(track: Track): Boolean {
    val state by LocalPlayer.current.state.collectAsState()
    return state.current?.id == track.id
}

/**
 * An album's row: number (a play button while hovered, bars while playing), title, artist when it
 * differs from the album's, duration, and the ⋯ menu on hover.
 */
@Composable
fun NumberedTrackRow(track: Track, list: List<Track>, index: Int, showArtist: Boolean, accent: Color, modifier: Modifier = Modifier,
                     extraMenu: List<MenuItem> = emptyList()) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    val actions = LocalTrackActions.current
    val menu = LocalMenu.current
    val playing = isCurrent(track)
    Row(
        modifier.fillMaxWidth().height(Ket27Desk.Metrics.trackRow).clip(RoundedCornerShape(Ket27Desk.Radius.row))
            .background(if (hovered) ket.hover else Color.Transparent)
            .hoverable(source).trackInteractions(track, list, index, extraMenu = extraMenu).padding(horizontal = 12.dp).testTag("track:${track.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            when {
                hovered -> KetIconButton(KetIcons.Play, "Lecture", { actions.play(list, index) }, size = 26.dp, glyph = 14.dp, tint = ket.label)
                playing -> Icon(com.ketvoice.desktop.ui.theme.DeskGlyphs.Volume, null, Modifier.size(15.dp), tint = accent)
                else -> KText("${index + 1}", Ket27Desk.Type.subheadline, color = ket.secondary, translate = false)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            KText(track.title, Ket27Desk.Type.subheadline, color = if (playing) accent else ket.label, translate = false,
                weight = if (playing) FontWeight.SemiBold else null)
            if (showArtist) KText(track.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
        if (track.explicit) ExplicitMark()
        val liked by LocalLibrary.current.favourites.collectAsState()
        if (liked.any { it.id == track.id }) Icon(Ket27Glyphs.HeartFilled, null, Modifier.padding(horizontal = 8.dp).size(13.dp), tint = accent)
        KText(formatTime(track.durationMs), Ket27Desk.Type.subheadline, Modifier.width(52.dp), color = ket.secondary, translate = false)
        val at = androidx.compose.runtime.remember { arrayOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(Modifier.size(28.dp).onGloballyPositioned { at[0] = it.positionInWindow() }) {
            if (hovered) KetIconButton(KetIcons.More, "Plus d'options", {
                menu.open(actions.menu(track) { actions.play(list, index) } + extraMenu, androidx.compose.ui.unit.IntOffset(at[0].x.toInt() - 200, at[0].y.toInt() + 30))
            }, size = 28.dp, glyph = 16.dp, modifier = Modifier.testTag("more:${track.id}"))
        }
    }
}

@Composable
fun ExplicitMark() {
    val ket = LocalKet.current
    Box(Modifier.padding(horizontal = 6.dp).size(15.dp).clip(RoundedCornerShape(3.dp)).background(ket.secondary.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
        KText("E", Ket27Desk.Type.caption2, color = ket.ground, translate = false)
    }
}

/** A compact title (artwork, title, artist): search results, top titles, recommendations, history. */
@Composable
fun CompactTrackRow(track: Track, list: List<Track>, index: Int, modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    val actions = LocalTrackActions.current
    val playing = isCurrent(track)
    Row(
        modifier.height(Ket27Desk.Metrics.compactRow).clip(RoundedCornerShape(Ket27Desk.Radius.row)).background(if (hovered) ket.hover else Color.Transparent)
            .hoverable(source).trackInteractions(track, list, index).padding(horizontal = 8.dp).testTag("track:${track.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(CoverSources.track(track), 42.dp, radius = 6.dp)
            if (hovered) Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .35f))
                .androidxClickable { actions.play(list, index) }, contentAlignment = Alignment.Center) {
                Icon(KetIcons.Play, null, Modifier.size(16.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            KText(track.title, Ket27Desk.Type.subheadline, color = if (playing) ket.accent else ket.label, translate = false)
            KText(track.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
        if (track.explicit) ExplicitMark()
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit) = clickable(onClick = onClick)

/** Titles in columns of four rows, as Music for macOS lays out « Top titres ». */
@Composable
fun TrackColumns(tracks: List<Track>, columns: Int = 3, rows: Int = 4) {
    val rows = minOf(rows, (tracks.size + columns - 1) / columns).coerceAtLeast(1)
    val shown = tracks.take(columns * rows)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ket27Desk.Space.m)) {
        for (c in 0 until columns) {
            Column(Modifier.weight(1f)) {
                for (r in 0 until rows) {
                    val i = c * rows + r
                    if (i < shown.size) CompactTrackRow(shown[i], tracks, i, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** A cover with its title and artist; Play appears on hover. */
@Composable
fun AlbumCard(album: AlbumRef, width: Dp, onOpen: () -> Unit, onPlay: (() -> Unit)? = null, subtitle: String = album.artist,
              menu: (() -> List<MenuItem>)? = null, shared: Boolean = true, art: (@Composable () -> Unit)? = null) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    val lift by animateFloatAsState(if (hovered) 1.03f else 1f,
        if (Motion.reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .6f, stiffness = 420f))
    val menuState = LocalMenu.current
    Column(Modifier.width(width).hoverable(source).then(if (menu != null) Modifier.contextMenu(menuState, menu) else Modifier).testTag("album:${album.title}")) {
        Box(Modifier.graphicsLayer { scaleX = lift; scaleY = lift }.androidxClickable(onOpen)) {
            if (art != null) art() else Artwork(CoverSources.album(album), width, if (shared) Modifier.sharedCover(SharedMotion.albumKey(album)) else Modifier, radius = Ket27Desk.Radius.artwork,
                shadow = if (hovered) 18.dp else 4.dp)
            if (hovered && onPlay != null) Box(Modifier.align(Alignment.BottomStart).padding(10.dp).size(34.dp)
                .shadow(4.dp, CircleShape).clip(CircleShape).background(ket.accent).androidxClickable(onPlay), contentAlignment = Alignment.Center) {
                Icon(KetIcons.Play, null, Modifier.size(15.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(10.dp))
        KText(album.title, Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
        KText(subtitle, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
    }
}

@Composable
fun ArtistCard(artist: ArtistRef, width: Dp, onOpen: () -> Unit) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    Column(Modifier.width(width).hoverable(source).androidxClickable(onOpen).testTag("artist:${artist.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            val picture = CoverSources.artistPhoto(artist.name, artist.picture)
            Artwork(picture, width, circle = true, shadow = if (hovered) 8.dp else 0.dp)
            if (picture == null || Covers.cached(picture) == null)
                KText(artist.name.take(1).uppercase(), Ket27Desk.Type.largeTitle, color = ket.secondary, translate = false)
        }
        Spacer(Modifier.height(8.dp))
        KText(artist.name, Ket27Desk.Type.footnote, weight = FontWeight.Medium, translate = false)
    }
}

/** A horizontal shelf of covers. */
@Composable
fun AlbumShelf(albums: List<AlbumRef>, onPlay: ((AlbumRef) -> Unit)? = null, contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
    val nav = LocalNavigator.current
    val catalog = LocalCatalog.current
    val actions = LocalTrackActions.current
    fun items(a: AlbumRef) = actions.collectionMenu { catalog.album(a)?.tracks.orEmpty() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Ket27Desk.Space.l), contentPadding = contentPadding) {
        items(albums, key = { it.title + "|" + it.artist + "|" + it.id }) { a ->
            AlbumCard(a, 176.dp, onOpen = { nav.push(Route.Album(a)) }, onPlay = { onPlay?.invoke(a) ?: items(a).first().action() },
                subtitle = listOfNotNull(a.year, a.artist.takeIf { a.year == null }).joinToString(" · "), menu = { items(a) })
        }
    }
}

/** A shelf of titles shown as covers (Home's mixes): double-click plays, the card opens the album. */
@Composable
fun TrackShelf(tracks: List<Track>, width: Dp = 176.dp, contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
    val nav = LocalNavigator.current
    val actions = LocalTrackActions.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Ket27Desk.Space.l), contentPadding = contentPadding) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            Box(Modifier.trackInteractions(t, tracks, i)) {
                AlbumCard(AlbumRef(t.albumId, t.title, t.artist, CoverSources.track(t)), width,
                    onOpen = { actions.play(tracks, i) }, onPlay = { actions.play(tracks, i) }, shared = false)
            }
        }
    }
}

/** The big centred message of an empty page. */
@Composable
fun EmptyMessage(title: String, message: String, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = KetIcons.Music) {
    val ket = LocalKet.current
    Column(modifier.fillMaxWidth().padding(vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp), tint = ket.tertiary)
        Spacer(Modifier.height(14.dp))
        KText(title, Ket27Desk.Type.title3)
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.Text(com.ketvoice.app.data.AppText.t(message), style = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline),
            color = ket.secondary, textAlign = TextAlign.Center, modifier = Modifier.width(380.dp))
    }
}
