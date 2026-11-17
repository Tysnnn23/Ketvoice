package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.ListeningMonths
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.WeeklyDiscoveries
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.pageMargin
import com.ketvoice.app.ui.motion.captureRevealOrigin
import com.ketvoice.app.ui.motion.rememberRevealOrigin
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * « Découvertes de la semaine » on Home: a large card whose artwork is made of the week's covers,
 * renewed every Monday. Made on the spot when the week has none yet (and enough was listened to).
 */
@Composable
internal fun HomeDiscoveries(modifier: Modifier = Modifier) {
    val week by WeeklyDiscoveries.week.collectAsState()
    val building by WeeklyDiscoveries.building.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val today = remember { LocalDate.now() }
    val current = week?.takeIf { it.monday == WeeklyDiscoveries.weekOf(today) && it.tracks.isNotEmpty() }
    val enough = counts.values.sum() >= WeeklyDiscoveries.MIN_PLAYS
    // On demand: the background work may not have run yet this week (no Wi-Fi since Monday).
    LaunchedEffect(current == null, enough) { if (current == null && enough) WeeklyDiscoveries.ensureNow() }
    val blocked = com.ketvoice.app.data.ArtistPrefs.blocked
    val tracks = remember(current, blocked) { current?.tracks?.filter(com.ketvoice.app.data.ArtistPrefs::allows).orEmpty() }
    when {
        current != null && tracks.isNotEmpty() -> DiscoveriesCard(tracks.mapNotNull { it.coverUrl }.distinct(), tracks.size, current.monday,
            onOpen = { origin -> AppUi.openCatalog(CatalogRequest("mix", WeeklyDiscoveries.MIX_ID, "Découvertes de la semaine"), origin) },
            onPlay = { Player.setShuffle(false); Player.playQueue(PersonalMixes.withLocalFiles(tracks, com.ketvoice.app.data.LocalMusic.tracks.value), 0) },
            modifier = modifier)
        building && enough -> DiscoveriesPlaceholder(modifier)
    }
}

/** The card itself: the week's covers as a fanned mosaic, the name in large, a play button. */
@Composable
internal fun DiscoveriesCard(covers: List<String>, count: Int, monday: LocalDate, onOpen: (androidx.compose.ui.geometry.Rect?) -> Unit,
                             onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val origin = rememberRevealOrigin()
    val lead = covers.firstOrNull()
    val tone = com.ketvoice.app.theme.rememberCoverTone(lead) ?: Color(0xFF6B4BD8)
    // A deep version of the lead cover's colour, so white words read on it whatever the covers.
    val deep = Color(tone.red * .42f, tone.green * .42f, tone.blue * .42f)
    val shape = RoundedCornerShape(30.dp)
    val locale = com.ketvoice.app.ui.sub.WrappedPeriod.locale()
    Column(modifier.fillMaxWidth().padding(horizontal = pageMargin, vertical = 10.dp)) {
        Box(Modifier.fillMaxWidth().captureRevealOrigin(origin)
            .shadow(if (theme.isDark) 24.dp else 12.dp, shape, clip = false, ambientColor = tone, spotColor = tone)
            .clip(shape).background(Brush.linearGradient(listOf(deep, Color(0xFF120E1E))))
            .border(1.dp, Color.White.copy(alpha = .10f), shape)
            .clickable(role = Role.Button) { onOpen(origin.value) }.testTag("home-discoveries")) {
            DiscoveriesArtwork(covers, Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 18.dp).size(150.dp))
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 20.dp)) {
                Row(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = .16f))
                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Text(AppText.t("Nouveau chaque lundi").uppercase(), localize = false, color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 5.dp))
                }
                Spacer(Modifier.height(64.dp))
                Text("Découvertes de la semaine", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-.7).sp, lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 150.dp).testTag("home-discoveries-title"))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(AppText.t(if (count == 1) "1 titre" else "{0} titres").replace("{0}", count.toString()) + " · " +
                            AppText.t("semaine du {0}").replace("{0}", monday.format(DateTimeFormatter.ofPattern("d MMMM", locale))),
                            localize = false, color = Color.White.copy(alpha = .86f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Choisis d'après vos écoutes", color = Color.White.copy(alpha = .66f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.padding(start = 12.dp).size(52.dp).clip(CircleShape).background(Color.White)
                        .clickable(role = Role.Button, onClick = onPlay).testTag("home-discoveries-play"), contentAlignment = Alignment.Center) {
                        Icon(KetIcons.Play, "Écouter", tint = Color.Black, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * The week's artwork: three covers fanned out, the first in front, on the card's colour; with
 * no cover, the sign of the discoveries on a gradient.
 */
@Composable
internal fun DiscoveriesArtwork(covers: List<String>, modifier: Modifier = Modifier) {
    val reduced = com.ketvoice.app.ui.motion.Motion.reduced
    Box(modifier.testTag("home-discoveries-art"), contentAlignment = Alignment.Center) {
        if (covers.isEmpty()) {
            Box(Modifier.fillMaxSize(.82f).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8E6BFF), Color(0xFFFF6FA8)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            return@Box
        }
        val shown = covers.take(3)
        // Back to front: the third cover furthest left and lowest, the first one upright in front.
        shown.indices.reversed().forEach { i ->
            val angle = when (i) { 0 -> 0f; 1 -> 9f; else -> -11f }
            val dx = when (i) { 0 -> 0.dp; 1 -> 20.dp; else -> (-22).dp }
            Box(Modifier.fillMaxSize(if (i == 0) .78f else .66f).offset(x = dx, y = if (i == 0) 0.dp else 6.dp)
                .graphicsLayer { rotationZ = if (reduced) angle * .6f else angle }
                .shadow(10.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A2340))) {
                AsyncImage(shown[i], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

/** While the week's selection is being made for the first time. */
@Composable
private fun DiscoveriesPlaceholder(modifier: Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(modifier.fillMaxWidth().padding(horizontal = pageMargin, vertical = 10.dp).clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFF2B2145), Color(0xFF15121F)))).padding(20.dp).testTag("home-discoveries-building"),
        verticalAlignment = Alignment.CenterVertically) {
        com.ketvoice.app.ui.common.ActivitySpinner(size = 22.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text("Découvertes de la semaine", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Préparation de votre sélection…", color = Color.White.copy(alpha = .7f), fontSize = 13.sp)
        }
    }
}

/** A short way into the month's Replay: its name, its time and its artist. */
@Composable
internal fun HomeReplayLink(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val months by ListeningHistory.months.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val key = remember { ListeningMonths.key(LocalDate.now()) }
    val recap = remember(months, known) { com.ketvoice.app.data.Wrapped.month(key, months, known) }
    if (recap.plays < 3) return
    Row(modifier.fillMaxWidth().padding(horizontal = pageMargin, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(theme.card)
        .clickable(role = Role.Button) { AppUi.openReplay() }.padding(horizontal = 16.dp, vertical = 14.dp).testTag("home-replay"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(theme.accent.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
            Icon(KetIcons.Clock, null, tint = theme.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Replay · " + com.ketvoice.app.ui.sub.WrappedPeriod.monthName(key), localize = false, color = theme.onBackground, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(com.ketvoice.app.ui.sub.playtimeLabel(recap.minutes * 60_000L).ifBlank { "< 1 min" }, recap.topArtist?.name)
                .joinToString(" · "), localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
    }
}
