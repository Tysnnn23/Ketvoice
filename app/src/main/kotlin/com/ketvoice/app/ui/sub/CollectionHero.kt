package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.gradientFor

/**
 * Shared hero for the app's collections (liked songs, recent, all tracks,
 * downloads): the same presentation as a playlist/album — blurred ambience,
 * large artwork or a 2x2 mosaic, title, count and the same actions.
 */
@Composable
internal fun CollectionHero(
    title: String,
    subtitle: String,
    covers: List<String?>,
    seed: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onShare: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false
) {
    val theme = LocalTheme.current
    val lead = covers.firstOrNull { !it.isNullOrBlank() }
    Box(Modifier.fillMaxWidth()) {
        if (lead != null) {
            AsyncImage(model = lead, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
                    .graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }
                    .blur(44.dp, BlurredEdgeTreatment.Unbounded))
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            theme.background.copy(alpha = .55f), Color.Transparent,
            theme.background.copy(alpha = .82f), theme.background))))
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(contentAlignment = Alignment.Center) {
                val edge = (maxWidth * .58f).coerceIn(132.dp, 196.dp)
                Box(Modifier.size(edge).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(gradientFor(seed)))) {
                    val slots = (0 until 4).map { covers.getOrNull(it) }
                    if (covers.size <= 1 || slots.count { !it.isNullOrBlank() } <= 1) {
                        val single = covers.firstOrNull()
                        if (single != null) AsyncImage(model = single, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(title.take(1).uppercase(), localize = false, color = Color.White.copy(alpha = .9f),
                            fontSize = 46.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            listOf(0, 2).forEach { start ->
                                Row(Modifier.weight(1f).fillMaxWidth()) {
                                    listOf(start, start + 1).forEach { index ->
                                        val url = covers.getOrNull(index)
                                        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(alpha = .2f))) {
                                            if (url != null) AsyncImage(model = url, contentDescription = null,
                                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(title, localize = false, color = theme.onBackground, fontSize = 25.sp,
                fontWeight = FontWeight.Black, letterSpacing = (-.5).sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (subtitle.isNotBlank()) Text(subtitle, localize = false, color = theme.secondary,
                fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp))
                    .background(theme.accent).clickable(onClick = onPlay),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Écouter", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp))
                    .background(theme.card).clickable(onClick = onShuffle),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shuffle, null, tint = theme.accent, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aléatoire", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text("Partager", color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onShare)
                        .padding(horizontal = 16.dp, vertical = 8.dp))
                if (onRefresh != null) Text(if (refreshing) "Actualisation…" else "Actualiser",
                    color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .clickable(enabled = !refreshing, onClick = onRefresh)
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}
