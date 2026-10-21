package com.ketvoice.app.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.AccountAvatar

@Composable
fun ExplorerScreen() {
    val theme = LocalTheme.current
    var genres by remember { mutableStateOf<List<CatalogGenre>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) { loading = true; genres = CatalogRepo.genres(); loading = false }
    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Explorer", color = theme.onBackground, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            AccountAvatar("K", 36.dp, Modifier.clickable { AppUi.showAccount.value = true })
        }
        Text("Genres du catalogue", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(25.dp), color = theme.accent, strokeWidth = 2.dp)
        } else if (genres.isEmpty()) Text("Catalogue indisponible · Réessayer", color = theme.secondary,
            modifier = Modifier.clickable { attempt++ }.padding(24.dp))
        else LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 205.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(genres, key = { it.id }) { genre ->
                Box(Modifier.height(112.dp).clip(RoundedCornerShape(15.dp)).background(theme.card).clickable {
                    AppUi.openCatalog(CatalogRequest("genre", genre.id, genre.title))
                }) {
                    if (genre.cover != null) AsyncImage(genre.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
                    Text(genre.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
            }
        }
    }
}
