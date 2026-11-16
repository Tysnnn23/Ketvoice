package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.ketvoice.app.data.AppText
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
internal enum class HomeBlock { Albums, Shelf0, Shelf1, Shelf2, Releases }

/** The key a block is learned under (see HomeLearning). */
internal val HomeBlock.learningKey: String get() = "block:" + name.lowercase()

/**
 * Order of the blocks under "Recent" for one launch. The first personal shelf always
 * leads so Home stays recognisable. The other blocks follow what the listener opens
 * most ([scores]); blocks never opened take turns from one launch to the next. The same
 * [seed] and scores always give the same order, so nothing moves while browsing.
 */
internal fun homeBlockOrder(seed: Long, shelfCount: Int, hasAlbums: Boolean, hasReleases: Boolean = false,
                            scores: Map<String, Double> = emptyMap()): List<HomeBlock> {
    val shelves = listOf(HomeBlock.Shelf0, HomeBlock.Shelf1, HomeBlock.Shelf2).take(shelfCount.coerceIn(0, 3))
    val lead = shelves.take(1)
    val rest = (shelves.drop(1) + (if (hasAlbums) listOf(HomeBlock.Albums) else emptyList()) +
        (if (hasReleases) listOf(HomeBlock.Releases) else emptyList())).shuffled(Random(seed))
    // A stable sort: blocks with the same score keep the order of this launch.
    return lead + rest.sortedByDescending { scores[it.learningKey] ?: 0.0 }
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
    fun count(n: Int) = AppText.t(if (n == 1) "1 titre" else "$n titres")
    val tiles = buildList {
        if (known.size >= 4) add(ShortcutTile(KetIcons.Shuffle, "Aléatoire", count(known.size)) { Player.playShuffled(known) })
        if (favoriteTracks.isNotEmpty()) add(ShortcutTile(KetIcons.Heart, "Favoris", count(favoriteTracks.size)) {
            AppUi.openCatalog(CatalogRequest("favorites", "favorites", "Favoris"))
        })
        if (downloads.isNotEmpty()) add(ShortcutTile(KetIcons.Download, "Hors ligne", count(downloads.size)) { AppUi.openDownloads() })
        // Gated on the very list the history page shows.
        if (recent.isNotEmpty()) add(ShortcutTile(KetIcons.Clock, "Historique", count(recent.size)) {
            AppUi.openCatalog(CatalogRequest("history", "history", "Historique"))
        })
    }
    if (tiles.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("home-shortcuts")) {
        // Two side by side on a phone, all four in a row on a wide window.
        val perRow = if (maxWidth >= 640.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { Shortcut(it, Modifier.weight(1f)) }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private class ShortcutTile(val icon: ImageVector, val label: String, val caption: String, val onClick: () -> Unit)

/** A shortcut as a tile: its sign on a patch of the accent, its name, and how much is behind it. */
@Composable
private fun Shortcut(tile: ShortcutTile, modifier: Modifier) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.heightIn(min = 62.dp).clip(shape).background(theme.card)
        .border(1.dp, theme.onBackground.copy(alpha = if (theme.isDark) .06f else .04f), shape)
        .clickable(role = Role.Button, onClick = tile.onClick).padding(horizontal = 12.dp, vertical = 10.dp).testTag("home-shortcut"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(theme.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(tile.icon, null, tint = theme.accent, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(tile.label, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.caption, localize = false, color = theme.secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    val blockedArtists = com.ketvoice.app.data.ArtistPrefs.blocked
    val cards = remember(known, recent, counts, favorites, blockedArtists) {
        PersonalMixes.propose(known.filter(com.ketvoice.app.data.ArtistPrefs::allows), recent.filter(com.ketvoice.app.data.ArtistPrefs::allows), counts, favorites)
    }
    if (cards.isEmpty()) return
    Column(modifier.testTag("home-mixes")) {
        EditorialHeader("Vos mix", subtitle = "Composés d'après vos écoutes")
        // Ket 27: large cards, as Apple Music shows what it composed for the listener.
        val width = com.ketvoice.app.ui.adaptive.shelfCell(HomeLayout.featureWidth, com.ketvoice.app.ui.common.pageMargin, HomeLayout.gap)
        HomeRow { items(cards, key = { it.id }) { card -> MixFeatureCard(card, width) } }
    }
}

/** One cover, or a 2 × 2 mosaic when the mix has four different ones. */
@Composable
internal fun MixCollage(covers: List<String?>) {
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
