package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.player.formatSecs
import com.ketvoice.app.util.ShareLinks

/** One scrolling surface: artwork, metadata and tracks never form disconnected panels. */
@Composable
internal fun AlbumPage(request: CatalogRequest, page: CatalogPage?, tracks: List<Track>, loading: Boolean,
                       onRetry: () -> Unit) {
    val theme = LocalTheme.current
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val current by Player.state.collectAsState()
    val artwork by ArtworkRepo.covers.collectAsState()
    val context = LocalContext.current
    val title = page?.title ?: request.title
    val artist = page?.subtitle.orEmpty()
    val cover = page?.cover ?: tracks.firstOrNull()?.coverUrl
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 66.dp
    val onAccent = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    Box(Modifier.fillMaxSize().background(theme.background)) {
        // A very restrained cover wash behind the entire page, not a hard-edged hero rectangle.
        if (cover != null && !AppSettings.reduceDynamicBlur.value && !AppSettings.highPerformanceMode.value) AsyncImage(cover, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(620.dp)
                .graphicsLayer { alpha = if (theme.isDark) .20f else .12f; scaleX = 1.15f; scaleY = 1.15f }
                .blur(64.dp, BlurredEdgeTreatment.Unbounded))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to theme.background.copy(alpha = .15f), .65f to theme.background, 1f to theme.background)))
        LazyColumn(Modifier.fillMaxSize().testTag("album-list"), state = list,
            contentPadding = PaddingValues(top = top, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp + 20.dp)) {
            item(key = "album-hero") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val edge = (maxWidth - 60.dp).coerceIn(160.dp, 256.dp)
                        Cover(title, cover, edge, 18.dp,
                            Modifier.shadow(18.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = .23f)))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(title, localize = false, color = theme.onBackground, fontSize = 27.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp, lineHeight = 32.sp,
                        textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (artist.isNotBlank()) Text(artist, localize = false, color = theme.accent, fontSize = 17.sp,
                        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp).clickable {
                            AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist)
                        }.padding(vertical = 5.dp))
                    val meta = listOfNotNull(page?.releaseDate?.take(4), AppText.t("${tracks.size} titres")).joinToString("  ·  ")
                    if (!loading) Text(meta, localize = false, color = theme.secondary, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(theme.accent.copy(alpha = if (tracks.isEmpty()) .35f else 1f))
                            .clickable(enabled = tracks.isNotEmpty()) { Player.setShuffle(false); Player.playQueue(tracks) }
                            .heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = onAccent, modifier = Modifier.size(21.dp))
                            Spacer(Modifier.width(6.dp));Text("Lecture", color = onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        GlassPanel(Modifier.weight(1f), RoundedCornerShape(16.dp),
                            GlassParams.card(16f, theme.isDark), enabled = AppSettings.liquidGlass.value, captured = true) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = tracks.isNotEmpty()) {
                                Player.playQueue(tracks.shuffled()); Player.setShuffle(true)
                            }.heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Shuffle, null, tint = theme.accent, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp));Text("Aléatoire", color = theme.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), color = theme.accent, strokeWidth = 2.dp)
            } }
            else if (tracks.isEmpty()) item {
                EmptyState("Catalogue indisponible", "Vérifiez votre connexion, puis réessayez.", "Réessayer", onAction = onRetry)
            }
            itemsIndexed(tracks, key = { index, track -> "${track.id}:$index" }) { index, track ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clickable { Player.playQueue(tracks, index) }
                        .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 13.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
                        Spacer(Modifier.width(10.dp))
                        DownloadCover(track, artwork["t:${track.id}"] ?: track.coverUrl ?: cover, 44.dp, 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            TrackTitle(track.title, color = if (current.current?.id == track.id) theme.accent else theme.onBackground,
                                selected = current.current?.id == track.id)
                            Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false,
                            color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable { AppUi.trackMenuTrack.value = track },
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.MoreHoriz, "Options", tint = theme.secondary, modifier = Modifier.size(22.dp))
                        }
                    }
                    Box(Modifier.padding(start = 100.dp, end = 20.dp).fillMaxWidth().height(.5.dp)
                        .background(theme.onBackground.copy(alpha = .07f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
            theme.background.copy(alpha = .94f), theme.background.copy(alpha = if (collapsed) .85f else 0f))))
            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Text(if (collapsed) title else "", localize = false, color = theme.onBackground,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
            GlassPanel(Modifier.size(48.dp), CircleShape, GlassParams.circle(dark = theme.isDark),
                enabled = AppSettings.liquidGlass.value, captured = true) {
                Box(Modifier.fillMaxSize().clickable { ShareLinks.shareAlbum(context, request.id, title, artist) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Share, "Partager", tint = theme.onBackground, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}
