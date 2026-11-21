package com.ketvoice.app.ui.sub

import com.ketvoice.app.theme.pageBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ListeningMonths
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.ui.common.CollectionPlaybackActions
import com.ketvoice.app.ui.common.SwipeTrackActions
import com.ketvoice.app.ui.common.TrackActionFeedback
import com.ketvoice.app.ui.common.asHeading
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
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
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

/** Replay's periods: each month counted since 6.1, newest first, then everything since the first launch. */
internal object ReplayPeriods {
    const val ALL = "all"

    fun of(months: List<String>): List<String> = months + ALL

    /** The month under way when it has plays, else the newest month, else all time. */
    fun default(months: List<String>, current: String): String = when {
        current in months -> current
        months.isNotEmpty() -> months.first()
        else -> ALL
    }
}

/** Replay : chaque mois — le mois en cours et les précédents — et tout ce qui a été écouté, compté à la lecture. */
@Composable
fun ReplayScreen() {
    val th = LocalTheme.current
    val known by ListeningHistory.knownTracks.collectAsState()
    val recent by ListeningHistory.tracks.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val listenedMs by ListeningHistory.listenedMs.collectAsState()
    val monthStats by ListeningHistory.months.collectAsState()
    val stats = remember(known, recent, counts) { replayStats(known, recent, counts) }
    val currentMonth = remember { ListeningMonths.key(java.time.LocalDate.now()) }
    val months = remember(monthStats) { ListeningMonths.ordered(monthStats) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val period = chosen?.takeIf { it == ReplayPeriods.ALL || it in months } ?: ReplayPeriods.default(months, currentMonth)
    var wrapped by remember { mutableStateOf<Wrapped.Recap?>(null) }

    Column(Modifier.fillMaxSize().pageBackground(th).statusBarsPadding()) {
        // Ketvoice Wrapped (5.9.0): the year as stories, shareable.
        com.ketvoice.app.ui.common.Ket27PageBar("Replay", { AppUi.closeOverlay() }) {
            if (stats.totalPlays > 0) Box(Modifier.clip(RoundedCornerShape(999.dp))
                .background(Brush.linearGradient(listOf(th.accent, th.accent.copy(alpha = .70f))))
                .clickable(role = Role.Button) { wrapped = wrappedRecapNow() }.padding(horizontal = 16.dp, vertical = 9.dp).testTag("replay-wrapped")) {
                Text("Wrapped", localize = false, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        wrapped?.let { recap -> WrappedStory(recap) { wrapped = null } }
        if (stats.totalPlays == 0 && months.isEmpty()) {
            EmptyState("Pas encore de Replay", "Vos titres les plus écoutés apparaîtront ici dès que vous aurez écouté de la musique.",
                "Rechercher des titres", icon = KetIcons.Clock, onAction = ::openTrackSearch)
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("replay-list"),
            contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
            if (months.isNotEmpty()) item(key = "periods") {
                ReplayPeriodChips(ReplayPeriods.of(months), period, currentMonth) { chosen = it }
            }
            if (period == ReplayPeriods.ALL) allTimeReplay(stats, listenedMs)
            else monthReplay(period, period == currentMonth) { recap -> wrapped = recap }
        }
    }
}

@Composable
private fun ReplayPeriodChips(periods: List<String>, selected: String, current: String, onSelect: (String) -> Unit) {
    val th = LocalTheme.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp).testTag("replay-months"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        periods.forEach { key ->
            val on = key == selected
            val label = when (key) {
                ReplayPeriods.ALL -> AppText.t("Depuis le début")
                current -> AppText.t("Ce mois-ci")
                else -> WrappedPeriod.monthName(key)
            }
            Text(label, localize = false, color = if (on) Color.White else th.onBackground, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) th.accent else th.card)
                    .clickable(role = Role.Tab) { onSelect(key) }.heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 16.dp).testTag("replay-period"))
        }
    }
}

/** A month's Replay: its time, its figures, its artists, albums and discoveries, then its playlist. */
private fun LazyListScope.monthReplay(key: String, inProgress: Boolean, onWrapped: (Wrapped.Recap) -> Unit) {
    item(key = "month:$key") { MonthReplay(key, inProgress, onWrapped) }
}

@Composable
private fun MonthReplay(key: String, inProgress: Boolean, onWrapped: (Wrapped.Recap) -> Unit) {
    val th = LocalTheme.current
    val monthStats by ListeningHistory.months.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val player by Player.state.collectAsState()
    val recap = remember(key, monthStats, known) {
        Wrapped.month(key, monthStats, known, coverOf = { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }, bpmOf = ::measuredBpm)
    }
    val tracks = remember(recap) { PersonalMixes.withLocalFiles(recap.tracks.map { it.first }, com.ketvoice.app.data.LocalMusic.tracks.value) }
    val name = WrappedPeriod.monthName(key)
    val scope = rememberCoroutineScope()
    var saving by remember(key) { mutableStateOf(false) }
    var saved by remember(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // The month in large over the colour of its most played cover.
        val cover = recap.topTrack?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
        val tone = com.ketvoice.app.theme.rememberCoverTone(cover) ?: th.accent
        val deep = Color(tone.red * .45f, tone.green * .45f, tone.blue * .45f)
        val shape = RoundedCornerShape(28.dp)
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).clip(shape)
            .background(Brush.linearGradient(listOf(deep, Color(0xFF15151C))))
            .border(1.dp, Color.White.copy(alpha = .08f), shape).testTag("replay-month")) {
            if (cover != null) Box(Modifier.align(Alignment.TopEnd).padding(18.dp)) { Cover(name, cover, 76.dp, 16.dp) }
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("REPLAY", localize = false, color = Color.White.copy(alpha = .78f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                    if (inProgress) Text(AppText.t("En cours").uppercase(), localize = false, color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = .22f)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("replay-in-progress"))
                }
                Text(name, localize = false, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp, lineHeight = 32.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, end = 84.dp).asHeading())
                Text(playtimeLabel(recap.minutes * 60_000L).ifBlank { "< 1 min" }, localize = false, color = Color.White, fontSize = 44.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, modifier = Modifier.padding(top = 18.dp).testTag("replay-month-time"))
                Text(AppText.t("{0} écoutes de {1} titres.").replace("{0}", recap.plays.toString()).replace("{1}", recap.titles.toString()),
                    localize = false, color = Color.White.copy(alpha = .80f), fontSize = 14.sp)
                if (inProgress) Text(AppText.t("Mis à jour à chaque écoute, jusqu'à la fin du mois."), localize = false,
                    color = Color.White.copy(alpha = .62f), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                if (recap.plays > 0) Row(Modifier.padding(top = 16.dp).clip(RoundedCornerShape(999.dp)).background(Color.White)
                    .clickable(role = Role.Button) { onWrapped(recap) }.heightIn(min = 44.dp).padding(horizontal = 18.dp)
                    .testTag("replay-month-wrapped"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(KetIcons.Play, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Text(AppText.t("Wrapped du mois"), localize = false, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp))
                }
                // 8.0: the live statistics (week, month, year), without waiting for the month's end.
                if (inProgress) Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = .16f))
                    .clickable(role = Role.Button) { AppUi.openStats() }.heightIn(min = 44.dp).padding(horizontal = 18.dp)
                    .testTag("replay-month-stats"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(com.ketvoice.app.ui.stats.StatsIcon, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(AppText.t("Statistiques en direct"), localize = false, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("${recap.titles}", "titres écoutés", Modifier.weight(1f))
            StatCard("${recap.artistCount}", "artistes", Modifier.weight(1f))
            StatCard("${recap.newArtists.size}", "découverts", Modifier.weight(1f))
        }
        if (recap.artists.isNotEmpty()) {
            com.ketvoice.app.ui.common.SectionHeader("Vos artistes", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                recap.artists.forEach { artist ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(82.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) {
                            if (artist.catalogId != null) AppUi.openArtist(artist.catalogId, artist.name) else AppUi.openSearch(artist.name)
                        }.testTag("replay-month-artist")) {
                        Cover(artist.name, com.ketvoice.app.data.MediaOverrides.artistPhoto(artist.name, artist.cover), 74.dp, 37.dp)
                        Text(artist.name, localize = false, color = th.onBackground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp))
                        Text(playsLabel(artist.plays), localize = false, color = th.secondary, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        if (recap.albums.isNotEmpty()) {
            com.ketvoice.app.ui.common.SectionHeader("Vos albums", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                recap.albums.forEach { album ->
                    Column(Modifier.width(118.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) {
                        AppUi.openSearch("${album.title} ${album.artist}")
                    }.testTag("replay-month-album")) {
                        Cover(album.title, com.ketvoice.app.data.MediaOverrides.albumCover(album.title, album.artist, album.cover), 118.dp, 14.dp)
                        Text(album.title, localize = false, color = th.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(playsLabel(album.plays), localize = false, color = th.secondary, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        if (recap.newArtists.isNotEmpty()) {
            com.ketvoice.app.ui.common.SectionHeader("Découverts ce mois-ci", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recap.newArtists.take(20).forEach { artist ->
                    Text(artist, localize = false, color = th.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(th.card).clickable(role = Role.Button) { AppUi.openSearch(artist) }
                            .heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 14.dp).testTag("replay-new-artist"))
                }
            }
        }
        if (tracks.isNotEmpty()) {
            Text(AppText.t("Vos titres du mois"), localize = false, color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 6.dp).asHeading())
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                CollectionPlaybackActions(tracks, Modifier.fillMaxWidth())
                com.ketvoice.app.ui.common.KetTextButton(if (saved) "Playlist enregistrée dans la bibliothèque" else "Enregistrer comme playlist",
                    enabled = !saving && !saved, primary = true, tint = if (saved) th.secondary else null, onClick = {
                        saving = true
                        scope.launch {
                            val done = runCatching {
                                PlaylistStore.create("Replay · $name".take(80), tracks.map(PlaylistRules::entry),
                                    cover = tracks.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl })
                            }.isSuccess
                            saved = done
                            saving = false
                            TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
                            TrackActionFeedback.snackbar.showSnackbar(AppText.t(
                                if (done) "Playlist enregistrée dans la bibliothèque" else "Impossible d'enregistrer la playlist"))
                        }
                    }, modifier = Modifier.testTag("replay-month-save"))
            }
            recap.tracks.forEachIndexed { index, (track, n) ->
                val playable = tracks[index]
                SwipeTrackActions(playable) {
                    com.ketvoice.app.ui.common.TrackRow(playable, ArtworkRepo.coverForTrack(track) ?: track.coverUrl, player.current?.id == track.id,
                        player.isPlaying, { Player.playQueue(tracks, index) }, modifier = Modifier.testTag("replay-month-track"), rank = index + 1) {
                        Icon(Icons.Filled.MusicNote, null, tint = th.accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$n", localize = false, color = th.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Everything counted since the first launch (the Replay of 5.x). */
private fun LazyListScope.allTimeReplay(stats: ReplayStats, listenedMs: Long) {
    val topTracks = stats.topTracks
    item(key = "time") {
        val th = LocalTheme.current
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
            val th = LocalTheme.current
            com.ketvoice.app.ui.common.SectionHeader("Vos artistes", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 10.dp))
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
            val th = LocalTheme.current
            val scope = rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            var saved by remember { mutableStateOf(false) }
            Text("Vos titres les plus écoutés", color = th.onBackground, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 20.dp, bottom = 6.dp).asHeading())
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                CollectionPlaybackActions(topTracks.map { it.first }, Modifier.fillMaxWidth())
                com.ketvoice.app.ui.common.KetTextButton(if (saved) "Playlist enregistrée dans la bibliothèque" else "Enregistrer comme playlist",
                    enabled = !saving && !saved, primary = true, tint = if (saved) th.secondary else null, onClick = {
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
                }, modifier = Modifier.testTag("replay-save"))
            }
        }
        itemsIndexed(topTracks, key = { _, pair -> pair.first.id }) { index, pair ->
            val th = LocalTheme.current
            val player by Player.state.collectAsState()
            val track = pair.first
            val active = player.current?.id == track.id
            SwipeTrackActions(track) {
                com.ketvoice.app.ui.common.TrackRow(track, ArtworkRepo.coverForTrack(track) ?: track.coverUrl, active,
                    player.isPlaying, { Player.playQueue(topTracks.map { it.first }, index) }, rank = index + 1) {
                    // The number of counted plays replaces the duration here.
                    Icon(Icons.Filled.MusicNote, null, tint = th.accent, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${pair.second}", localize = false, color = th.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
