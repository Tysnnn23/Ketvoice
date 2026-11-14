package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MixKind
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.motion.captureRevealOrigin
import com.ketvoice.app.ui.motion.rememberRevealOrigin
import kotlin.random.Random

/** The Home title follows the time of day instead of repeating the same words at every launch. */
internal fun homeGreeting(hour: Int): String = when (hour.mod(24)) {
    in 5..11 -> "Bonjour"
    in 12..17 -> "Bon après-midi"
    in 18..22 -> "Bonsoir"
    else -> "Bonne nuit"
}

/** Secondary Home blocks whose order may change between launches. */
internal enum class HomeBlock { Albums, Shelf0, Shelf1, Shelf2 }

/**
 * Order of the blocks under "Recent" for one launch. The first personal shelf always
 * leads so Home stays recognisable; the album row and the other shelves take turns.
 * The same [seed] always gives the same order, so nothing moves while browsing.
 */
internal fun homeBlockOrder(seed: Long, shelfCount: Int, hasAlbums: Boolean): List<HomeBlock> {
    val shelves = listOf(HomeBlock.Shelf0, HomeBlock.Shelf1, HomeBlock.Shelf2).take(shelfCount.coerceIn(0, 3))
    val lead = shelves.take(1)
    val rest = (shelves.drop(1) + if (hasAlbums) listOf(HomeBlock.Albums) else emptyList()).shuffled(Random(seed))
    return lead + rest
}

internal fun mixTitle(card: MixCard): String = when (card.kind) {
    MixKind.Artist -> "Mix ${card.artist.orEmpty()}"
    MixKind.Style -> "Dans le style de ${card.artist.orEmpty()}"
    MixKind.OnRepeat -> "En boucle"
    MixKind.Rediscover -> "À redécouvrir"
}

internal fun mixSubtitle(card: MixCard): String = when (card.kind) {
    MixKind.Artist -> "Vos titres et ses incontournables"
    MixKind.Style -> "Des artistes proches à découvrir"
    MixKind.OnRepeat -> "Ce que vous écoutez le plus"
    MixKind.Rediscover -> "Écoutés avant, oubliés depuis"
}

/** Shortcuts built only from what exists on this device: no dead button, no empty page. */
@Composable
internal fun HomeShortcuts(modifier: Modifier = Modifier) {
    val known by ListeningHistory.knownTracks.collectAsState()
    val recent by ListeningHistory.tracks.collectAsState()
    val downloads by Downloader.items.collectAsState()
    val favorites = AppSettings.favoriteIds.value
    val favoriteTracks = remember(known, favorites) { known.filter { it.id in favorites } }
    if (known.isEmpty() && recent.isEmpty() && downloads.isEmpty()) return
    LazyRow(modifier.testTag("home-shortcuts"), contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (known.size >= 4) item {
            Shortcut(KetIcons.Shuffle, "Lecture aléatoire") { Player.playQueue(known.shuffled()); Player.setShuffle(true) }
        }
        if (favoriteTracks.isNotEmpty()) item {
            Shortcut(KetIcons.Heart, "Favoris") { AppUi.openCatalog(CatalogRequest("favorites", "favorites", "Favoris")) }
        }
        if (downloads.isNotEmpty()) item {
            Shortcut(KetIcons.Download, "Hors ligne") { AppUi.openDownloads() }
        }
        // Gated on the very list the history page shows.
        if (recent.isNotEmpty()) item {
            Shortcut(KetIcons.Clock, "Historique") { AppUi.openCatalog(CatalogRequest("history", "history", "Historique")) }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(theme.card)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = theme.accent, modifier = Modifier.size(18.dp))
        Text(label, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * "Your mixes": proposed only once enough music has been played, at most four cards,
 * reshuffled at the next launch.
 */
@Composable
internal fun HomeMixes(modifier: Modifier = Modifier) {
    val known by ListeningHistory.knownTracks.collectAsState()
    val recent by ListeningHistory.tracks.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val favorites = AppSettings.favoriteIds.value
    val cards = remember(known, recent, counts, favorites) { PersonalMixes.propose(known, recent, counts, favorites) }
    if (cards.isEmpty()) return
    val theme = LocalTheme.current
    Column(modifier.testTag("home-mixes")) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 12.dp)) {
            Text("Vos mix", color = theme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.asHeading())
            Text("Composés d'après vos écoutes", color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cards, key = { it.id }) { card -> MixTile(card) }
        }
    }
}

@Composable
private fun MixTile(card: MixCard) {
    val theme = LocalTheme.current
    val origin = rememberRevealOrigin()
    val title = mixTitle(card)
    Column(Modifier.width(164.dp).captureRevealOrigin(origin).clickable(role = Role.Button) {
        AppUi.openCatalog(CatalogRequest("mix", card.id, title), origin.value)
    }.testTag("home-mix")) {
        Box(Modifier.size(164.dp).clip(RoundedCornerShape(22.dp)).background(
            Brush.linearGradient(listOf(theme.accent.copy(alpha = .55f), theme.card)))) {
            MixCollage(card.covers)
            // A soft floor so the label stays readable on any artwork.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Transparent, .5f to Color.Transparent, 1f to Color.Black.copy(alpha = .62f))))
            Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(when (card.kind) {
                    MixKind.Artist -> KetIcons.Music
                    MixKind.Style -> KetIcons.Globe
                    MixKind.OnRepeat -> KetIcons.Refresh
                    MixKind.Rediscover -> KetIcons.Clock
                }, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Mix", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp))
            }
        }
        Text(title, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(mixSubtitle(card), color = theme.secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One cover, or a 2 × 2 mosaic when the mix has four different ones. */
@Composable
private fun MixCollage(covers: List<String?>) {
    val shown = covers.filter { !it.isNullOrBlank() }
    when {
        shown.size >= 4 -> Column(Modifier.fillMaxSize()) {
            for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (column in 0..1) coil.compose.AsyncImage(shown[row * 2 + column], null,
                    Modifier.weight(1f).fillMaxHeight(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
        }
        shown.isNotEmpty() -> coil.compose.AsyncImage(shown.first(), null, Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
