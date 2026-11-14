package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.player.formatSecs
import java.time.LocalDate

/**
 * Section title in the editorial style: large and bold, with a chevron when the section
 * opens a full page.
 */
@Composable
internal fun EditorialHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onMore: (() -> Unit)? = null,
                             trailing: (@Composable () -> Unit)? = null) {
    val theme = LocalTheme.current
    Row(modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 26.dp, bottom = 12.dp)
        .then(if (onMore != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onMore) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, color = theme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.asHeading())
            if (subtitle != null) Text(subtitle, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (onMore != null) Icon(KetIcons.ChevronRight, null, tint = theme.secondary,
            modifier = Modifier.padding(start = 4.dp).size(20.dp).testTag("editorial-more"))
        Spacer(Modifier.weight(1f, fill = onMore == null))
        trailing?.invoke()
    }
}

/**
 * Counts a tap inside a Home block as an opening of that block (see HomeLearning). The
 * gesture is only observed: the block's own buttons keep working, and a scroll never counts.
 */
internal fun Modifier.learnsOpens(key: String): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) break
        }
        if (!moved) HomeLearning.record(key)
    }
}

internal enum class ForYouKind { Resume, NewRelease, Mix, None }

/** What the "For you" card shows now: a paused title first, then a fresh release, then a mix for the time of day. */
internal fun forYouKind(resumable: Boolean, freshRelease: Boolean, hasMix: Boolean): ForYouKind = when {
    resumable -> ForYouKind.Resume
    freshRelease -> ForYouKind.NewRelease
    hasMix -> ForYouKind.Mix
    else -> ForYouKind.None
}

/** A release counts as new for three weeks. */
internal fun isFreshRelease(date: String?, today: LocalDate): Boolean =
    date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { !it.isAfter(today) && !it.isBefore(today.minusDays(21)) } == true

/** The mix that suits the hour, and the name the card gives it. */
internal fun mixForHour(hour: Int, cards: List<MixCard>): Pair<MixCard, String>? {
    if (cards.isEmpty()) return null
    val (wanted, title) = when (hour.mod(24)) {
        in 5..11 -> MixKind.Rediscover to "Votre matinale"
        in 12..17 -> MixKind.Artist to "Pour votre après-midi"
        in 18..22 -> MixKind.OnRepeat to "Votre mix du soir"
        else -> MixKind.Style to "Pour la nuit"
    }
    return (cards.firstOrNull { it.kind == wanted } ?: cards.first()) to title
}

/** The large card at the top of Home: changes with what the listener was doing and the time of day. */
@Composable
internal fun HomeForYou(hour: Int, releases: List<CatalogAlbum>, fallback: @Composable () -> Unit) {
    val player by Player.state.collectAsState()
    val position by Player.positionMs.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val recent by ListeningHistory.tracks.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val favorites = AppSettings.favoriteIds.value
    val cards = remember(known, recent, counts, favorites) { PersonalMixes.propose(known, recent, counts, favorites) }
    val today = remember { LocalDate.now() }
    val fresh = releases.firstOrNull { isFreshRelease(it.releaseDate, today) }
    val current = player.current
    when (forYouKind(current != null && !player.playWhenReady && position > 5_000, fresh != null, cards.isNotEmpty())) {
        ForYouKind.Resume -> ForYouCard("Reprendre là où vous étiez", current!!.title,
            "${current.artist} · " + com.ketvoice.app.data.AppText.t("à ${formatSecs(position)}"),
            listOf(ArtworkRepo.coverForTrack(current) ?: current.coverUrl), action = "Reprendre", tag = "for-you-resume") {
            Player.toggle(); AppUi.showPlayer.value = true
        }
        ForYouKind.NewRelease -> ForYouCard("Nouveau de ${fresh!!.artist}", fresh.title, fresh.releaseDate.orEmpty(),
            listOf(fresh.cover), action = "Ouvrir", tag = "for-you-release") {
            AppUi.openCatalog(CatalogRequest("album", fresh.id, fresh.title))
        }
        ForYouKind.Mix -> {
            val (card, title) = mixForHour(hour, cards)!!
            ForYouCard(title, mixTitle(card), mixSubtitle(card), card.covers, action = "Écouter", tag = "for-you-mix") {
                AppUi.openCatalog(CatalogRequest("mix", card.id, mixTitle(card)))
            }
        }
        ForYouKind.None -> fallback()
    }
}

@Composable
private fun ForYouCard(label: String, title: String, subtitle: String, covers: List<String?>, action: String, tag: String,
                       onClick: () -> Unit) {
    val theme = LocalTheme.current
    val cover = covers.firstOrNull { !it.isNullOrBlank() }
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).height(208.dp).clip(RoundedCornerShape(30.dp))
        .background(Brush.linearGradient(listOf(theme.accent.copy(alpha = .55f), theme.card)))
        .clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        if (cover != null) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // A floor of shade so the words stay readable on any artwork.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .05f), .45f to Color.Black.copy(alpha = .25f), 1f to Color.Black.copy(alpha = .78f))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp).fillMaxWidth(.78f)) {
            Text(label.uppercase(), localize = false, color = Color.White.copy(alpha = .82f), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, localize = false, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp,
                lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (subtitle.isNotBlank()) Text(subtitle, localize = false, color = Color.White.copy(alpha = .78f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(52.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center) {
            Icon(KetIcons.Play, action, tint = Color.Black, modifier = Modifier.size(24.dp))
        }
    }
}

/** New albums and singles of the artists played most. */
@Composable
internal fun HomeReleasesShelf(releases: List<CatalogAlbum>, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    Column(modifier.testTag("home-releases")) {
        EditorialHeader("Sorties de vos artistes", subtitle = "Les nouveautés de ceux que vous écoutez le plus")
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(releases, key = { it.id }) { album ->
                Column(Modifier.width(156.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) {
                    AppUi.openCatalog(CatalogRequest("album", album.id, album.title))
                }.padding(bottom = 4.dp)) {
                    Cover(album.title, album.cover, 156.dp, 13.dp)
                    Text(album.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(listOfNotNull(album.artist, album.releaseDate?.take(4)).joinToString(" · "), localize = false,
                        color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "Your week": real listening time, the artist and the title of the last seven days. */
@Composable
internal fun HomeWeekCard(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val days by ListeningHistory.days.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val week = remember(days, known) { ListeningWeek.summary(days, LocalDate.now(), known) }
    if (week.plays < 3) return
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp).clip(RoundedCornerShape(26.dp))
        // A solid card first: the accent tint never decides the contrast of the text.
        .background(theme.card).background(Brush.linearGradient(listOf(theme.accent.copy(alpha = .22f), Color.Transparent)))
        .clickable(role = Role.Button) { AppUi.openReplay() }.padding(20.dp).testTag("home-week"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Votre semaine", color = theme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).asHeading())
            Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WeekFigure(com.ketvoice.app.ui.sub.playtimeLabel(week.listenedMs).ifBlank { "< 1 min" }, "d'écoute", Modifier.weight(1f))
            WeekFigure("${week.plays}", "écoutes", Modifier.weight(1f))
        }
        week.topArtist?.let { WeekLine("Artiste de la semaine", it) }
        week.topTrack?.let { WeekLine("Titre de la semaine", "${it.title} · ${it.artist}") }
    }
}

@Composable
private fun WeekFigure(value: String, label: String, modifier: Modifier) {
    val theme = LocalTheme.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(theme.background.copy(alpha = .35f)).padding(12.dp)) {
        Text(value, localize = false, color = theme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = theme.secondary, fontSize = 12.sp)
    }
}

@Composable
private fun WeekLine(label: String, value: String) {
    val theme = LocalTheme.current
    Column {
        Text(label, color = theme.secondary, fontSize = 12.sp)
        Text(value, localize = false, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
