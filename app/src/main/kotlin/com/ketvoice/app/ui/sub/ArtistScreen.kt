package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import com.ketvoice.app.ui.common.GlassBackButton
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.YouTubeCatalog
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.player.formatSecs

/**
 * Page d'artiste réelle : photo, écoute mensuelle, biographie, titres,
 * albums et artistes similaires proviennent de YouTube Music.
 */
@Composable
fun ArtistScreen() {
    val request = AppUi.artistRequest.value ?: return
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    val player by Player.state.collectAsState()
    var page by remember(request.id, request.name) { mutableStateOf<ArtistPage?>(null) }
    var loading by remember(request.id, request.name) { mutableStateOf(true) }
    var failed by remember(request.id, request.name) { mutableStateOf(false) }
    var attempt by remember(request.id, request.name) { mutableIntStateOf(0) }
    var expandedBio by remember(request.id, request.name) { mutableStateOf(false) }

    LaunchedEffect(request.id, request.name, attempt) {
        loading = true
        failed = false
        try {
            // Un morceau ne porte pas toujours de channelId YouTube ("UC...") :
            // on retombe alors sur une résolution par nom, sinon la page restait
            // vide sans explication.
            val directId = request.id.takeIf { it.matches(Regex("UC[A-Za-z0-9_-]+")) }
            var resolved = directId?.let { runCatching { YouTubeCatalog.artist(it) }.getOrNull() }
            if (resolved == null && request.name.isNotBlank()) {
                val candidates = runCatching { YouTubeCatalog.searchArtists(request.name) }
                    .getOrDefault(emptyList())
                // On privilégie une correspondance exacte du nom : sinon une
                // recherche approximative ouvrait la fiche d'un homonyme.
                val match = candidates.firstOrNull { it.name.equals(request.name, ignoreCase = true) }
                    ?: candidates.firstOrNull()
                if (match != null) resolved = YouTubeCatalog.artist(match.id)
            }
            page = resolved
            page?.let { runCatching { ArtworkRepo.enrich(it.tracks) } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            page = null
        }
        failed = page == null
        loading = false
    }

    val artist = page?.artist
    Box(Modifier.fillMaxSize().background(theme.background)) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            failed || page == null -> Column(Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Cette page d'artiste n'a pas pu être chargée.", color = theme.onBackground,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Vérifiez la connexion puis réessayez.", color = theme.secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp))
                Text("Réessayer", color = theme.accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { attempt++ }.padding(top = 20.dp, bottom = 12.dp))
            }
            else -> {
            // La photo ne disparaît JAMAIS : elle reste à sa place en tête de
            // page (pas de fondu au scroll, pas de remplacement par le canvas).
            // Le haut de la page porte le même blur prolongé que le lecteur :
            // bandes AGSL de fondu progressif sous la barre de statut.
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState,
                contentPadding = PaddingValues(bottom = 210.dp)) {
                val data = page!!
                item {
                    val heroHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .58f)
                        .coerceIn(400.dp, 720.dp)
                    Box(Modifier.fillMaxWidth().height(heroHeight)) {
                        if (!data.artist.photo.isNullOrBlank()) {
                            // Photo affichée ENTIÈRE (fit) sur un prolongement flouté
                            // d'elle-même, avec la bande de blur progressif en haut
                            // (shader AGSL, même matière que la pochette du lecteur).
                            ProgressiveBlurPhoto(
                                photo = data.artist.photo,
                                modifier = Modifier.fillMaxSize(),
                                bandTop = 170.dp,
                                bandBottom = 230.dp,
                                topDark = 0.34f,
                                bottomDark = 0.55f
                            )
                        } else {
                            // Repli : aucun hero photo disponible — dégradé du thème
                            // avec l'initiale, jamais un bloc vide.
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                                theme.background, theme.accent.copy(alpha = 0.35f), Color(0xFF101014))))) {
                                Text(data.artist.name.take(1).uppercase(), localize = false,
                                    color = Color.White.copy(alpha = 0.22f), fontSize = 150.sp,
                                    fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                        // Nom de l'artiste par-dessus la photo (la bande basse assombrit déjà)
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(data.artist.name, localize = false, color = Color.White, fontSize = 40.sp,
                                fontWeight = FontWeight.Black, letterSpacing = (-1.2).sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, lineHeight = 44.sp)
                            if (data.listeners.isNotBlank()) {
                                Text(data.listeners, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                            Text("Artiste · YouTube Music", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                                modifier = Modifier.padding(top = 3.dp))
                            // Actions posées dans la cover : lecture immédiate des titres
                            // de l'artiste et ouverture de la file d'attente du lecteur.
                            if (data.tracks.isNotEmpty()) Row(
                                Modifier.padding(top = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(Modifier.clip(RoundedCornerShape(999.dp)).background(theme.accent)
                                    .clickable { Player.playQueue(data.tracks, 0) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Lecture", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(Modifier.clip(RoundedCornerShape(999.dp))
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .clickable { AppUi.openQueue() }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("File d'attente", color = Color.White, fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                if (data.description.isNotBlank()) item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(data.description, localize = false, color = theme.secondary, fontSize = 13.5.sp, lineHeight = 19.sp,
                            maxLines = if (expandedBio) 40 else 4, overflow = TextOverflow.Ellipsis)
                        Text(if (expandedBio) "Réduire" else "Lire plus", color = theme.accent,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { expandedBio = !expandedBio }.padding(top = 8.dp))
                    }
                }
                if (data.tracks.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(theme.accent)
                            .clickable { Player.playQueue(data.tracks, 0) }
                            .padding(horizontal = 22.dp, vertical = 11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Lecture", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (data.tracks.isNotEmpty()) item {
                    Text("Titres populaires", color = theme.onBackground, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
                }
                itemsIndexed(data.tracks, key = { i, t -> "${t.id}:$i" }) { index, track ->
                    val active = player.current?.id == track.id
                    Row(Modifier.fillMaxWidth().clickable { Player.playQueue(data.tracks, index) }
                        .padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = theme.secondary, fontSize = 13.sp,
                            modifier = Modifier.width(24.dp))
                        Cover(track.title, art["t:${track.id}"] ?: track.coverUrl, 50.dp, 7.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, localize = false, color = if (active) theme.accent else theme.onBackground,
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.5.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (track.durationMs > 0) Text(formatSecs(track.durationMs), color = theme.secondary, fontSize = 12.sp)
                        Box(Modifier.size(34.dp).clickable { AppUi.trackMenuTrack.value = track }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.MoreVert, "Options", tint = theme.secondary, modifier = Modifier.size(19.dp))
                        }
                    }
                }
                if (data.albums.isNotEmpty()) item {
                    Text("Albums et singles", color = theme.onBackground, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(data.albums, key = { _, a -> a.id }) { _, album ->
                            Column(Modifier.width(150.dp).clickable {
                                AppUi.openCatalog(com.ketvoice.app.data.CatalogRequest("album", album.id, album.title))
                            }) {
                                Cover(album.title, art["a:${album.id}"] ?: album.coverUrl, 150.dp, 12.dp)
                                Text(album.title, localize = false, color = theme.onBackground, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 7.dp))
                                Text(album.type, color = theme.secondary, fontSize = 12.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (data.related.isNotEmpty()) item {
                    Text("Artistes similaires", color = theme.onBackground, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        itemsIndexed(data.related, key = { _, a -> a.id }) { _, related ->
                            Column(Modifier.width(104.dp).clickable { AppUi.openArtist(related.id, related.name) },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(94.dp).clip(CircleShape).background(theme.card),
                                    contentAlignment = Alignment.Center) {
                                    // Initiale en repli : le cercle n'est jamais vide.
                                    Text(related.name.take(1).uppercase(), localize = false, color = theme.secondary,
                                        fontSize = 30.sp, fontWeight = FontWeight.Bold)
                                    if (!related.photo.isNullOrBlank()) {
                                        AsyncImage(
                                            model = related.photo,
                                            contentDescription = related.name,
                                            contentScale = ContentScale.Crop,
                                            alignment = Alignment.TopCenter,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(Icons.Filled.Person, null,
                                            tint = theme.accent.copy(alpha = 0.7f),
                                            modifier = Modifier.size(36.dp))
                                    }
                                }
                                Text(related.name, localize = false, color = theme.onBackground, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp).width(92.dp))
                            }
                        }
                    }
                }
            }
            }
        }
        // Barre supérieure superposée : retour + actualisation flottent au-dessus
        // du hero (immersion), le nom reste porté par la bannière. En chargement
        // ou en erreur, le nom d'artiste est affiché pour garder le contexte.
        Row(Modifier.fillMaxWidth().statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Spacer(Modifier.width(12.dp))
            if (page == null) {
                Text(artist?.name ?: request.name, localize = false, color = theme.onBackground,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            if (page != null) Box(Modifier.size(38.dp).clickable { attempt++ }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Refresh, "Actualiser", tint = theme.secondary, modifier = Modifier.size(21.dp))
            }
        }
    }
}

