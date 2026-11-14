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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.CollectionPlaybackActions
import com.ketvoice.app.ui.common.SwipeTrackActions
import com.ketvoice.app.ui.common.TrackActionFeedback
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.common.trackRowClick
import com.ketvoice.app.ui.player.NowPlayingBars
import com.ketvoice.app.ui.player.savedQueueName
import kotlinx.coroutines.launch
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

/** What Replay shows, computed from the plays the app really counted. */
internal data class ReplayStats(val totalPlays: Int, val distinctTracks: Int, val artistCount: Int,
                                val topTracks: List<Pair<Track, Int>>, val topArtists: List<PersonalMixes.ArtistTaste>)

internal fun replayStats(known: List<Track>, recent: List<Track>, counts: Map<String, Int>): ReplayStats {
    val catalog = (known + recent).associateBy { it.id }
    val topTracks = counts.entries
        .mapNotNull { (id, count) -> catalog[id]?.takeIf { count > 0 }?.let { it to count } }
        .sortedWith(compareByDescending<Pair<Track, Int>> { it.second }.thenBy { it.first.id })
    // The same artist rule as the mixes: full credit for catalogue titles, no placeholder names.
    val artists = PersonalMixes.topArtists(topTracks.map { it.first }, counts, emptySet()).filter { it.plays > 0 }
    return ReplayStats(counts.values.sum(), counts.size, artists.size, topTracks.take(20), artists.take(10))
}

/** Replay : les titres et artistes réellement écoutés, comptés à la lecture. */
@Composable
fun ReplayScreen() {
    val th = LocalTheme.current
    val known by ListeningHistory.knownTracks.collectAsState()
    val recent by ListeningHistory.tracks.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val listenedMs by ListeningHistory.listenedMs.collectAsState()
    val player by Player.state.collectAsState()
    val stats = remember(known, recent, counts) { replayStats(known, recent, counts) }
    val topTracks = stats.topTracks
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(th.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Spacer(Modifier.width(12.dp))
            Text("Replay", color = th.onBackground, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp,
                modifier = Modifier.asHeading())
        }
        if (stats.totalPlays == 0) {
            EmptyState("Pas encore de Replay", "Vos titres les plus écoutés apparaîtront ici dès que vous aurez écouté de la musique.",
                "Rechercher des titres", onAction = ::openTrackSearch)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("replay-list"),
                contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
                item(key = "time") {
                    // The time really spent listening: counted while audio plays, never estimated.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(th.accent.copy(alpha = .30f), th.accent.copy(alpha = .08f))))
                        .padding(horizontal = 20.dp, vertical = 18.dp).testTag("replay-time")) {
                        Text("Temps d'écoute", color = th.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(playtimeLabel(listenedMs).ifBlank { "< 1 min" }, localize = false, color = th.onBackground,
                            fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    }
                }
                item(key = "stats") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("${stats.totalPlays}", "écoutes comptées", Modifier.weight(1f))
                        StatCard("${stats.distinctTracks}", "titres écoutés", Modifier.weight(1f))
                        StatCard("${stats.artistCount}", "artistes", Modifier.weight(1f))
                    }
                }
                if (stats.topArtists.isNotEmpty()) {
                    item(key = "artists") {
                        Text("Vos artistes", color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 10.dp).asHeading())
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            stats.topArtists.forEach { artist ->
                                val cover = artist.tracks.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(82.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) {
                                        // A catalogue artist has a page; any other name is searched.
                                        if (artist.catalogId != null) AppUi.openArtist(artist.catalogId, artist.name)
                                        else AppUi.openSearch(artist.name)
                                    }.testTag("replay-artist")) {
                                    Cover(artist.name, cover, 74.dp, 37.dp)
                                    Text(artist.name, localize = false, color = th.onBackground, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                                    Text(AppText.t(if (artist.plays == 1) "1 écoute" else "${artist.plays} écoutes"), localize = false,
                                        color = th.secondary, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (topTracks.isNotEmpty()) {
                    item(key = "tracks-heading") {
                        Text("Vos titres les plus écoutés", color = th.onBackground, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 20.dp, bottom = 6.dp).asHeading())
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                            CollectionPlaybackActions(topTracks.map { it.first }, Modifier.fillMaxWidth())
                            TextButton(enabled = !saving && !saved, onClick = {
                                saving = true
                                scope.launch {
                                    val tracks = topTracks.map { it.first }
                                    val done = runCatching {
                                        PlaylistStore.create(savedQueueName("Replay", java.time.LocalDate.now(), java.util.Locale.getDefault()),
                                            tracks.map(PlaylistRules::entry),
                                            cover = tracks.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl })
                                    }.isSuccess
                                    saved = done
                                    saving = false
                                    TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
                                    TrackActionFeedback.snackbar.showSnackbar(AppText.t(
                                        if (done) "Playlist enregistrée dans la bibliothèque" else "Impossible d'enregistrer la playlist"))
                                }
                            }, modifier = Modifier.testTag("replay-save")) {
                                Text(if (saved) "Playlist enregistrée dans la bibliothèque" else "Enregistrer comme playlist",
                                    color = if (saved) th.secondary else th.accent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    itemsIndexed(topTracks, key = { _, pair -> pair.first.id }) { index, pair ->
                        val track = pair.first
                        val active = player.current?.id == track.id
                        SwipeTrackActions(track) {
                            Row(Modifier.fillMaxWidth().trackRowClick(track) {
                                Player.playQueue(topTracks.map { it.first }, index)
                            }.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", localize = false, color = th.secondary, fontSize = 13.sp, modifier = Modifier.width(26.dp))
                                val cover = ArtworkRepo.coverForTrack(track) ?: track.coverUrl
                                Cover(track.title, cover, 50.dp, 9.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    TrackTitle(track.title, th.onBackground, selected = active, fontSize = 15.sp)
                                    Text(track.artist, localize = false, color = th.secondary, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (active) NowPlayingBars(player.isPlaying, Modifier.padding(end = 10.dp))
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
