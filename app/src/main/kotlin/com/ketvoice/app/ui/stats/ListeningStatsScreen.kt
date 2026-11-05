package com.ketvoice.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.ListeningStats
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Text

/**
 * "Ton récap" — a recap/Wrapped screen surfacing the stats already collected by
 * [com.ketvoice.app.data.ListeningHistory] and projected by [ListeningStats].
 *
 * Fully offline: nothing leaves the device. Purely reads the derived snapshot, so
 * the screen opens instantly. Tapping a top track could hook into Player.play in
 * a follow-up; here we keep the surface read-only to stay additive.
 */
@Composable
fun ListeningStatsScreen(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val layout = LocalAdaptiveLayout.current
    val snap by ListeningStats.snapshot.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ListeningStats.refresh() }

    val pageWidth = layout.recentPageWidthDp.dp
    val gutter = layout.pageGutterDp.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = pageWidth)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 20.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Ton récap",
                        color = theme.onBackground,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Ce que tu as écouté dans Ketvoice — rien ne quitte ton appareil.",
                        color = theme.secondary,
                        fontSize = 13.sp
                    )
                }
            }

            if (snap.isEmpty) {
                item { EmptyRecapCard() }
                return@LazyColumn
            }

            item { HeroStatsCard() }

            if (snap.topArtists.isNotEmpty()) {
                item { SectionTitle("Artistes les plus écoutés") }
                itemsIndexed(snap.topArtists.take(10)) { i, a ->
                    ArtistRow(rank = i + 1, name = a.name, plays = a.plays, coverUrl = a.coverUrl)
                }
            }

            if (snap.topTracks.isNotEmpty()) {
                item { SectionTitle("Titres les plus joués") }
                itemsIndexed(snap.topTracks.take(10)) { i, t ->
                    TrackRow(
                        rank = i + 1,
                        title = t.title,
                        artist = t.artist,
                        coverUrl = ArtworkRepo.coverForTrack(t)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(label: String) {
    val theme = LocalTheme.current
    Text(
        text = label,
        color = theme.onBackground,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun HeroStatsCard() {
    val theme = LocalTheme.current
    val snap by ListeningStats.snapshot.collectAsState()
    val accent = theme.accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.35f))
                )
            )
            .padding(22.dp)
    ) {
        Column {
            Text("TEMPS D'ÉCOUTE", color = androidx.compose.ui.graphics.Color.White.copy(0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = ListeningStats.formatListened(snap.totalListenedMs),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                MiniStat("${snap.totalPlays}", "lectures")
                MiniStat("${snap.uniqueArtists}", "artistes")
                MiniStat("${snap.topPlayCount}", "record")
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column {
        Text(value, color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(label, color = androidx.compose.ui.graphics.Color.White.copy(0.8f), fontSize = 11.sp)
    }
}

@Composable
private fun ArtistRow(rank: Int, name: String, plays: Int, coverUrl: String?) {
    val theme = LocalTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$rank",
            color = theme.secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(26.dp)
        )
        Cover(key = name, url = coverUrl, size = 46.dp, radius = 23.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$plays lectures", color = theme.secondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TrackRow(rank: Int, title: String, artist: String, coverUrl: String?) {
    val theme = LocalTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$rank",
            color = theme.secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(26.dp)
        )
        Cover(key = title, url = coverUrl, size = 46.dp, radius = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyRecapCard() {
    val theme = LocalTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(theme.card)
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎧", fontSize = 34.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Écoute quelques titres et ton récap apparaîtra ici.",
                color = theme.secondary,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
