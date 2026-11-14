package com.ketvoice.app.ui.explorer

import com.ketvoice.app.ui.common.asHeading
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ketvoice.app.ui.common.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.DesignTokens
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.AccountAvatar

/** Teintes vives stables par genre : la même catégorie garde toujours sa couleur. */
private val categoryTints = listOf(
    Color(0xFFE8B84B), Color(0xFF8FCB6B), Color(0xFF7C9FE8), Color(0xFFB9BEC7),
    Color(0xFFD97E3F), Color(0xFFE0A63C), Color(0xFF4A4E57), Color(0xFFC29B3C),
    Color(0xFFB01E28), Color(0xFF9B6BD4), Color(0xFF7B4FD4), Color(0xFF9AA0AB),
    Color(0xFF1E9E4E), Color(0xFF6E7683), Color(0xFFD45B2E), Color(0xFF2FA8B5),
    Color(0xFF4C6FD4), Color(0xFF8E5FC0)
)

internal fun categoryColumns(hasRail: Boolean, fontScale: Float): GridCells = when {
    hasRail || fontScale >= 1.2f -> GridCells.Adaptive(220.dp)
    else -> GridCells.Fixed(2)
}

internal fun tintFor(id: String, title: String): Color {
    val seed = (id + title).fold(0) { acc, c -> acc * 31 + c.code }
    return Color.Black.copy(alpha = .50f).compositeOver(categoryTints[((seed % categoryTints.size) + categoryTints.size) % categoryTints.size])
}

@Composable
fun ExplorerScreen() {
    val theme = LocalTheme.current
    val layout = LocalAdaptiveLayout.current
    var genres by remember { mutableStateOf<List<CatalogGenre>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var chart by remember { mutableStateOf<CatalogChart?>(null) }
    LaunchedEffect(attempt) { loading = true; genres = CatalogRepo.genres(); loading = false }
    // The chart loads beside the genres and never blocks them.
    LaunchedEffect(attempt) { chart = CatalogRepo.chart() }
    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Explorer", color = theme.onBackground, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).asHeading())
            if (!layout.hasRail) Box(Modifier.size(DesignTokens.Space.touch).clickable { AppUi.showAccount.value = true },
                contentAlignment = Alignment.Center) { AccountAvatar("K", 36.dp) }
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(25.dp), color = theme.accent, strokeWidth = 2.dp)
        } else if (genres.isEmpty()) Text("Catalogue indisponible · Réessayer", color = theme.secondary,
            modifier = Modifier.clickable { attempt++ }.padding(24.dp))
        else {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState) {
                snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                    .collect { (index, offset) -> AppUi.bottomCollapsed.value = index > 0 || offset > 24 }
            }
            // The chart arrives after the genres and is inserted above them. A grid that
            // was resting at its top must show it, not stay anchored on the first genre.
            LaunchedEffect(chart != null) {
                if (chart != null && !gridState.isScrollInProgress && gridState.firstVisibleItemScrollOffset == 0 &&
                    gridState.firstVisibleItemIndex <= 4) gridState.scrollToItem(0)
            }
            LazyVerticalGrid(categoryColumns(layout.hasRail, LocalDensity.current.fontScale),
                state = gridState,
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, layout.bottomPaddingDp.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            chart?.let { now ->
                if (now.tracks.isNotEmpty()) item(key = "chart-tracks", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        ShelfHeading("Titres du moment")
                        ChartTracksShelf(now.tracks)
                    }
                }
                if (now.albums.isNotEmpty()) item(key = "chart-albums", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        ShelfHeading("Albums du moment")
                        AlbumShelf(now.albums)
                    }
                }
                if (now.artists.isNotEmpty()) item(key = "chart-artists", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        ShelfHeading("Artistes du moment")
                        ArtistShelf(now.artists)
                    }
                }
                item(key = "genres-heading", span = { GridItemSpan(maxLineSpan) }) { ShelfHeading("Genres") }
            }
            items(genres, key = { it.id }) { genre ->
                GenreCategoryCard(genre, height = if (layout.hasRail) 150.dp else 112.dp) {
                    AppUi.openCatalog(CatalogRequest("genre", genre.id, genre.title))
                }
            }
            }
        }
    }
}

/** Matte category tile with an actual image when available; no backdrop blur. */
@Composable
internal fun GenreCategoryCard(genre: CatalogGenre, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val tint = tintFor(genre.id, genre.title)
    Box(
        Modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(24.dp))
            .background(tint)
            .clickable(onClick = onClick)
    ) {
        if (genre.cover != null) {
            AsyncImage(genre.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Even a white image remains dark enough for white category text.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .62f)))
        }
        Text(genre.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp))
    }
}
