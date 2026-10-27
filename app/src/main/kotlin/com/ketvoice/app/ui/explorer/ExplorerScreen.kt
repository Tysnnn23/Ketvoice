package com.ketvoice.app.ui.explorer

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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.LocalTheme
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

private fun tintFor(id: String, title: String): Color {
    val seed = (id + title).fold(0) { acc, c -> acc * 31 + c.code }
    return categoryTints[((seed % categoryTints.size) + categoryTints.size) % categoryTints.size]
}

@Composable
fun ExplorerScreen() {
    val theme = LocalTheme.current
    val layout = LocalAdaptiveLayout.current
    var genres by remember { mutableStateOf<List<CatalogGenre>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) { loading = true; genres = CatalogRepo.genres(); loading = false }
    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Explorer", color = theme.onBackground, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!layout.hasRail) AccountAvatar("K", 36.dp, Modifier.clickable { AppUi.showAccount.value = true })
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(25.dp), color = theme.accent, strokeWidth = 2.dp)
        } else if (genres.isEmpty()) Text("Catalogue indisponible · Réessayer", color = theme.secondary,
            modifier = Modifier.clickable { attempt++ }.padding(24.dp))
        else LazyVerticalGrid(if (layout.hasRail) GridCells.Adaptive(220.dp) else GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, layout.bottomPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(genres, key = { it.id }) { genre ->
                GenreCategoryCard(genre, height = if (layout.hasRail) 150.dp else 112.dp) {
                    AppUi.openCatalog(CatalogRequest("genre", genre.id, genre.title))
                }
            }
        }
    }
}

/** Carte de catégorie : bande de teinte pleine à gauche, pochette fondue dans la teinte à droite. */
@Composable
private fun GenreCategoryCard(genre: CatalogGenre, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val tint = tintFor(genre.id, genre.title)
    Box(
        Modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(24.dp))
            .background(tint)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxSize()) {
            // Bande pleine : le titre vit ici, comme sur la référence.
            Box(Modifier.fillMaxHeight().weight(.34f)) {
                Text(
                    genre.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 21.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                )
            }
            // Pochette teintée : monochrome fondu dans la couleur de la carte.
            Box(Modifier.fillMaxHeight().weight(.66f)) {
                if (genre.cover != null) {
                    AsyncImage(
                        genre.cover, null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply {
                            setToSaturation(0f)
                        })
                    )
                    Box(Modifier.fillMaxSize().background(tint.copy(alpha = .62f)))
                }
            }
        }
    }
}
