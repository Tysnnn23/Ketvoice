package com.ketvoice.app.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CatalogAlbum
import com.ketvoice.app.data.CatalogArtist
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.common.trackRowClick

/**
 * Touch shape of a shelf card: it follows the artwork at the top and stays nearly square
 * at the bottom, where a round corner would cut the first letter of the caption.
 */
private val shelfCard = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 6.dp, bottomStart = 6.dp)

@Composable
internal fun ShelfHeading(title: String, modifier: Modifier = Modifier) {
    Text(title, color = LocalTheme.current.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(vertical = 8.dp).asHeading())
}

/** Ranked titles in a row: a tap plays the chart from that title, a long-press opens its menu. */
@Composable
internal fun ChartTracksShelf(tracks: List<Track>, modifier: Modifier = Modifier, edge: androidx.compose.ui.unit.Dp = 0.dp) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    LazyRow(modifier.testTag("chart-tracks"), contentPadding = PaddingValues(horizontal = edge),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            Column(Modifier.width(132.dp).clip(shelfCard).trackRowClick(track) { Player.playQueue(tracks, index) }
                .padding(bottom = 4.dp)) {
                Box {
                    Cover(track.title, art["t:${track.id}"] ?: track.coverUrl, 132.dp, 14.dp)
                    Text("${index + 1}", localize = false, color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f)).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Text(track.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun AlbumShelf(albums: List<CatalogAlbum>, modifier: Modifier = Modifier, edge: androidx.compose.ui.unit.Dp = 0.dp,
                        onOpen: (CatalogAlbum) -> Unit = { AppUi.openCatalog(CatalogRequest("album", it.id, it.title)) }) {
    val theme = LocalTheme.current
    LazyRow(modifier.testTag("album-shelf"), contentPadding = PaddingValues(horizontal = edge),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(Modifier.width(132.dp).clip(shelfCard).clickable(role = Role.Button) { onOpen(album) }
                .padding(bottom = 4.dp)) {
                Cover(album.title, album.cover, 132.dp, 14.dp)
                Text(album.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(album.artist, localize = false, color = theme.secondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ArtistShelf(artists: List<CatalogArtist>, modifier: Modifier = Modifier, edge: androidx.compose.ui.unit.Dp = 0.dp) {
    val theme = LocalTheme.current
    LazyRow(modifier.testTag("artist-shelf"), contentPadding = PaddingValues(horizontal = edge),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(artists, key = { it.id }) { artist ->
            Column(Modifier.width(96.dp).clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button) { AppUi.openArtist(artist.id, artist.name) }.padding(bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(88.dp).clip(CircleShape).background(theme.card)
                    .border(1.dp, theme.onBackground.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                    // The initial stays behind the photo while it loads.
                    Text(artist.name.take(1).uppercase(), localize = false, color = theme.secondary, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold)
                    if (artist.picture != null) AsyncImage(model = artist.picture, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(artist.name, localize = false, color = theme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
            }
        }
    }
}
