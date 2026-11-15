package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
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
            listOf(ArtworkRepo.coverForTrack(current) ?: current.coverUrl), action = "Reprendre", tag = "for-you-resume",
            progress = if (player.durationMs > 0) (position.toFloat() / player.durationMs).coerceIn(0f, 1f) else null) {
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

/**
 * The card the page leads with: the artwork edge to edge, a glow of its own colour under it,
 * what it is in a small capsule, its name in large, one button. A paused title also shows
 * how far it had got.
 */
@Composable
private fun ForYouCard(label: String, title: String, subtitle: String, covers: List<String?>, action: String, tag: String,
                       progress: Float? = null, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val cover = covers.firstOrNull { !it.isNullOrBlank() }
    val glow = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first ?: theme.accent
    val shape = RoundedCornerShape(32.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
        .shadow(if (theme.isDark) 28.dp else 14.dp, shape, clip = false, ambientColor = glow, spotColor = glow)
        .height(236.dp).clip(shape)
        .background(Brush.linearGradient(listOf(glow.copy(alpha = .62f), theme.card)))
        .border(1.dp, Color.White.copy(alpha = .10f), shape)
        .clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        if (cover != null) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // A floor of shade so the words stay readable on any artwork.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .16f), .42f to Color.Black.copy(alpha = .20f), 1f to Color.Black.copy(alpha = .86f))))
        Text(label.uppercase(), localize = false, color = Color.White, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = .40f)).border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 92.dp, bottom = if (progress != null) 28.dp else 22.dp)) {
            Text(title, localize = false, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp,
                lineHeight = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, localize = false, color = Color.White.copy(alpha = .80f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = if (progress != null) 24.dp else 18.dp).size(56.dp)
            .clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(KetIcons.Play, action, tint = Color.Black, modifier = Modifier.size(26.dp))
        }
        if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .20f))
            .testTag("for-you-progress")) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
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
                    Cover(album.title, com.ketvoice.app.data.MediaOverrides.albumCover(album.title, album.artist, album.cover), 156.dp, 16.dp)
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
        // The seven days, oldest first: today in the accent, each bar as tall as that day's listening.
        val daily = remember(days) { ListeningWeek.daily(days, LocalDate.now()) }
        val longest = daily.max()
        if (longest > 0L) Row(Modifier.fillMaxWidth().height(46.dp).testTag("home-week-days"),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            daily.forEachIndexed { index, ms ->
                Box(Modifier.weight(1f).fillMaxHeight(ListeningWeek.barShare(ms, longest)).clip(RoundedCornerShape(6.dp))
                    .background(if (index == daily.lastIndex) theme.accent else theme.onBackground.copy(alpha = .18f)))
            }
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
