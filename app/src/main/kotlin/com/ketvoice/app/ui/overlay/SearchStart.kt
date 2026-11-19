package com.ketvoice.app.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CatalogGenre
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Text

/** The search page before anything is typed (7.3, owner: « Grille de catégories … pas les recherches, je veux les sons écoutés en premier »). */
internal object SearchStartModel {
    /** How many recently played titles lead the page. */
    const val RECENT = 12

    /** The titles played last, newest first, each once, without local files whose address may be gone. */
    fun recent(history: List<Track>): List<Track> = history.distinctBy { it.id }.take(RECENT)

    /**
     * The colour of a category tile from its artwork's [tone]: kept dark enough that white text stays above
     * WCAG AA (relative luminance at most [MAX_LUMINANCE]), and never a dead grey-black. [fallback] when unknown.
     */
    fun tileColor(tone: Color?, fallback: Color): Color {
        var c = tone ?: return fallback
        var guard = 0
        while (c.luminance() > MAX_LUMINANCE && guard++ < 12) c = Color(c.red * .86f, c.green * .86f, c.blue * .86f)
        return c.copy(alpha = 1f)
    }

    const val MAX_LUMINANCE = .16f
}

/** Recently played titles first, then the catalogue's categories as large coloured tiles. [genres] for tests. */
@Composable
internal fun SearchStart(modifier: Modifier = Modifier, columnsWide: Boolean = false, genres: List<CatalogGenre>? = null,
                         middle: @Composable ColumnScope.() -> Unit = {}) {
    val theme = LocalTheme.current
    val history by ListeningHistory.tracks.collectAsState()
    val recent = remember(history) { SearchStartModel.recent(history) }
    var loaded by remember { mutableStateOf(genres ?: emptyList()) }
    if (genres == null) LaunchedEffect(Unit) { loaded = runCatching { CatalogRepo.genres() }.getOrDefault(emptyList()) }
    Column(modifier.fillMaxWidth().testTag("search-start")) {
        if (recent.isNotEmpty()) {
            com.ketvoice.app.ui.common.SectionHeader("Écoutés récemment", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.testTag("search-recent-played")) {
                itemsIndexed(recent, key = { _, t -> t.id }) { index, track -> RecentTile(track) { Player.playQueue(recent, index) } }
            }
            Spacer(Modifier.height(22.dp))
        }
        middle()
        com.ketvoice.app.ui.common.SectionHeader("Parcourir les catégories", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        if (loaded.isEmpty()) {
            Text("Les catégories apparaîtront ici.", color = theme.secondary, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp).testTag("search-browse")) {
            val columns = if (columnsWide) com.ketvoice.app.ui.adaptive.LargeScreen.columns(maxWidth.value, minCellDp = 200f,
                gapDp = 12f, min = 2, max = 5) else 2
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                loaded.chunked(columns).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        line.forEach { genre ->
                            CategoryTile(genre, 104.dp, Modifier.weight(1f)) {
                                AppUi.openCatalog(CatalogRequest("genre", genre.id, genre.title))
                            }
                        }
                        repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** A recently played title: its cover, name and artist; a touch plays it again. */
@Composable
private fun RecentTile(track: Track, onPlay: () -> Unit) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl)
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onPlay)
        .testTag("search-recent-${track.id}")) {
        com.ketvoice.app.ui.common.Cover(track.title, cover, 132.dp, 12.dp)
        Text(track.title, localize = false, color = theme.onBackground, style = Ket27.Type.subheadline,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        Text(track.artist, localize = false, color = theme.secondary, style = Ket27.Type.footnote,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A category as Apple Music shows it: a large tile in its artwork's colour, the name at the top left and the
 * artwork tilted in the bottom-right corner, partly outside the tile.
 */
@Composable
internal fun CategoryTile(genre: CatalogGenre, height: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tone = com.ketvoice.app.theme.rememberCoverTone(genre.cover)
    val color = SearchStartModel.tileColor(tone, com.ketvoice.app.ui.explorer.tintFor(genre.id, genre.title))
    Box(modifier.height(height).clip(RoundedCornerShape(16.dp)).background(color)
        .clickable(role = Role.Button, onClick = onClick).testTag("search-category-${genre.id}")) {
        if (genre.cover != null) AsyncImage(genre.cover, null, contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 16.dp, y = 10.dp).size(height * .70f).rotate(25f)
                .shadow(8.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)))
        Text(genre.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(.72f).padding(start = 14.dp, top = 12.dp))
    }
}
