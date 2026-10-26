package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.GlassBackButton
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TrackTitle
import com.ketvoice.app.ui.common.openTrackSearch

/** Replay : les titres et artistes réellement écoutés, comptés à la lecture. */
@Composable
fun ReplayScreen() {
    val th = LocalTheme.current
    val known by ListeningHistory.knownTracks.collectAsState()
    val recent by ListeningHistory.tracks.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val catalog = (known + recent).associateBy { it.id }
    val topTracks = counts.entries
        .mapNotNull { (id, count) -> catalog[id]?.let { it to count } }
        .sortedByDescending { it.second }
        .take(20)
    val totalPlays = counts.values.sum()
    val topArtists = counts.entries
        .mapNotNull { (id, count) -> catalog[id] }
        .groupBy { it.artist.substringBefore(',').trim() }
        .filterKeys { it.isNotBlank() }
        .map { (artist, tracks) -> artist to tracks.sumOf { counts[it.id] ?: 0 } }
        .sortedByDescending { it.second }
        .take(10)
    val artistCover = topArtists.associate { (artist, _) ->
        artist to catalog.values.firstOrNull { it.artist.substringBefore(',').trim() == artist }
            ?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
    }

    Column(Modifier.fillMaxSize().background(th.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Spacer(Modifier.width(12.dp))
            Text("Replay", color = th.onBackground, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp)
        }
        if (totalPlays == 0) {
            EmptyState("Pas encore de Replay", "Vos titres les plus écoutés apparaîtront ici dès que vous aurez écouté de la musique.",
                "Rechercher des titres", onAction = ::openTrackSearch)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("$totalPlays", "écoutes comptées", Modifier.weight(1f))
                        StatCard("${counts.size}", "titres écoutés", Modifier.weight(1f))
                        StatCard("${topArtists.size}", "artistes", Modifier.weight(1f))
                    }
                }
                if (topArtists.isNotEmpty()) {
                    item {
                        Text("Vos artistes", color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 10.dp))
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            topArtists.forEach { (artist, _) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(82.dp).clickable {
                                        AppUi.searchSeed.value = artist
                                        AppUi.openSearch()
                                    }) {
                                    Cover(artist, artistCover[artist], 74.dp, 37.dp)
                                    Text(artist, localize = false, color = th.onBackground, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                                }
                            }
                        }
                    }
                }
                if (topTracks.isNotEmpty()) {
                    item {
                        Text("Vos titres les plus écoutés", color = th.onBackground, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 20.dp, bottom = 6.dp))
                    }
                    itemsIndexed(topTracks, key = { _, pair -> pair.first.id }) { index, pair ->
                        val track = pair.first
                        Row(Modifier.fillMaxWidth().clickable {
                            Player.playQueue(topTracks.map { it.first }, index)
                        }.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", localize = false, color = th.secondary, fontSize = 13.sp, modifier = Modifier.width(26.dp))
                            val cover = ArtworkRepo.coverForTrack(track) ?: track.coverUrl
                            Cover(track.title, cover, 50.dp, 9.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                TrackTitle(track.title, th.onBackground, fontSize = 15.sp)
                                Text(track.artist, localize = false, color = th.secondary, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.MusicNote, null, tint = th.accent, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("${pair.second}", localize = false, color = th.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    val th = LocalTheme.current
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(th.card).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, localize = false, color = th.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = th.secondary, fontSize = 12.sp)
    }
}
