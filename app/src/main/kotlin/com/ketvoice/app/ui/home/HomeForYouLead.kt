package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.smartlib.Mood
import com.ketvoice.app.smartlib.MoodMix
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.collectPick
import com.ketvoice.app.ui.common.pageMargin
import com.ketvoice.app.ui.common.pick
import com.ketvoice.app.ui.smartlib.SmartLibrary

/*
 * 7.0 « Pour toi »: Home opens on the listener. Under the greeting of the hour, two large calm cards —
 * what was playing, to take up again, and the mix of the day — then the ambiances that suit the hour.
 * Everything comes from what the app already knows (the player, ListeningHistory, PersonalMixes, the
 * smart library's moods); nothing here asks the network.
 */

/** The ambiances in the order that suits [hour]: lively in the morning, focused in the afternoon, soft at night. */
internal fun moodsForHour(hour: Int, mixes: List<MoodMix>): List<MoodMix> {
    val order = when (hour.mod(24)) {
        in 5..11 -> listOf(Mood.Energy, Mood.Happy, Mood.Focus)
        in 12..17 -> listOf(Mood.Focus, Mood.Happy, Mood.Energy)
        in 18..22 -> listOf(Mood.Party, Mood.Calm, Mood.Happy)
        else -> listOf(Mood.Calm, Mood.Melancholy, Mood.Focus)
    }
    return mixes.filter { it.tracks.isNotEmpty() }
        .sortedBy { mix -> order.indexOf(mix.mood).let { if (it < 0) order.size else it } }
}

/** What the « Reprendre » card's button says: the title playing can be paused, any other taken up again. */
internal fun resumeAction(live: Boolean, playing: Boolean): String = if (live && playing) "Pause" else "Reprendre"

/**
 * The lead of the page: « Reprendre » and « Mix du jour ». Stacked on a phone; side by side, the same
 * height, in a large window ([wide], from LargeScreen.isLarge).
 */
@Composable
internal fun HomeForYouLead(recent: List<Track>, hasResume: Boolean, daily: Pair<MixCard, String>?, wide: Boolean,
                            modifier: Modifier = Modifier) {
    if (!hasResume && daily == null) return
    if (wide) Row(modifier.fillMaxWidth().padding(horizontal = pageMargin).padding(top = Ket27.Space.xs).testTag("home-for-you"),
        horizontalArrangement = Arrangement.spacedBy(Ket27.Space.m)) {
        if (hasResume) HomeResumeCard(recent, Modifier.weight(1f), height = WIDE_CARD)
        if (daily != null) HomeDailyMix(daily, Modifier.weight(1f), height = WIDE_CARD, large = true)
        // Alone, a card keeps its half of the page rather than stretching across a wide window.
        if (!hasResume || daily == null) Spacer(Modifier.weight(1f))
    } else Column(modifier.fillMaxWidth().padding(horizontal = pageMargin).padding(top = Ket27.Space.xs).testTag("home-for-you"),
        verticalArrangement = Arrangement.spacedBy(Ket27.Space.s)) {
        if (hasResume) HomeResumeCard(recent)
        if (daily != null) HomeDailyMix(daily, height = 196.dp)
    }
}

private val WIDE_CARD = 212.dp

/**
 * « Reprendre »: the title playing, or the last one listened to, on a plain card — its cover, its name,
 * one filled button. The progress line follows the position in the draw phase only: the card never
 * recomposes as the title plays.
 */
@Composable
internal fun HomeResumeCard(recent: List<Track>, modifier: Modifier = Modifier, height: Dp? = null) {
    val theme = LocalTheme.current
    val current by remember { Player.state.pick { it.current } }.collectPick()
    val playing by remember { Player.state.pick { it.isPlaying } }.collectPick()
    val duration by remember { Player.state.pick { it.durationMs } }.collectPick()
    val track = current ?: recent.firstOrNull() ?: return
    val live = current != null
    val position = Player.positionMs.collectAsState()
    val cover = com.ketvoice.app.ui.common.rememberTrackCover(track)
    val shape = RoundedCornerShape(Ket27.Radius.card)
    val start = { if (live) Player.toggle() else Player.playQueue(recent, 0) }
    // In a large window the card is as tall as the mix beside it: the cover fills its height.
    val art = if (height != null) height - Ket27.Space.s * 2 else 88.dp
    Row(modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier).clip(shape).background(theme.card)
        .clickable(role = Role.Button) { if (live) AppUi.showPlayer.value = true else start() }
        .padding(Ket27.Space.s).testTag("home-resume"), verticalAlignment = Alignment.CenterVertically) {
        com.ketvoice.app.ui.common.DownloadCover(track, cover, art, Ket27.Radius.artwork)
        Column(Modifier.weight(1f).padding(start = Ket27.Space.m, end = Ket27.Space.xxs)) {
            Text(track.title, localize = false, color = theme.onBackground,
                style = if (height != null) Ket27.Type.title3 else Ket27.Type.headline, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(track.artist, localize = false, color = theme.secondary, style = Ket27.Type.subheadline, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = Ket27.Space.s), verticalAlignment = Alignment.CenterVertically) {
                FilledCapsule(resumeAction(live, playing), if (live && playing) KetIcons.Pause else KetIcons.Play,
                    theme.onBackground, theme.background, Modifier.testTag("home-resume-play"), onClick = start)
                if (live && duration > 0) Box(Modifier.weight(1f).padding(start = Ket27.Space.s).height(3.dp).clip(CircleShape)
                    .background(theme.onBackground.copy(alpha = .12f))
                    .drawBehind {
                        val share = (position.value.toFloat() / duration).coerceIn(0f, 1f)
                        drawRect(theme.onBackground.copy(alpha = .55f), size = Size(size.width * share, size.height))
                    }.testTag("home-resume-progress"))
            }
        }
    }
}

/**
 * « Mix du jour »: the personal mix that suits the hour (see mixForHour), on a band of its covers'
 * deep colour — words in white on it whatever the covers — with its mosaic at the end.
 */
@Composable
internal fun HomeDailyMix(daily: Pair<MixCard, String>, modifier: Modifier = Modifier, height: Dp = 196.dp, large: Boolean = false) {
    val theme = LocalTheme.current
    val (card, hourName) = daily
    val title = mixTitle(card)
    val cover = card.covers.firstOrNull { !it.isNullOrBlank() }
    val tone = com.ketvoice.app.theme.rememberCoverTone(cover) ?: theme.accent
    val band = Color(tone.red * .42f, tone.green * .42f, tone.blue * .42f)
    val shape = RoundedCornerShape(Ket27.Radius.card)
    val open = { AppUi.openCatalog(CatalogRequest("mix", card.id, title)) }
    Row(modifier.fillMaxWidth().height(height).clip(shape).background(band)
        .border(.8.dp, Color.White.copy(alpha = if (theme.isDark) .08f else 0f), shape)
        .clickable(role = Role.Button, onClick = open).testTag("home-daily-mix")) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(Ket27.Space.l), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                // Title 1 in a large window; title 2 on a phone, where the mosaic leaves half the card.
                Text("Mix du jour", color = Color.White, style = if (large) Ket27.Type.title1 else Ket27.Type.title2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hourName, color = Color.White.copy(alpha = .8f), style = Ket27.Type.subheadline, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Ket27.Space.xxs))
                Text(title, color = Color.White.copy(alpha = .8f), style = Ket27.Type.subheadline, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            FilledCapsule("Écouter", KetIcons.Play, Color.White, Color.Black, onClick = open)
        }
        Box(Modifier.fillMaxHeight().aspectRatio(1f)) { MixCollage(card.covers) }
    }
}

/** The one filled button of a large card: a capsule, its symbol then its action, 44 dp tall. */
@Composable
private fun FilledCapsule(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, fill: Color, ink: Color,
                          modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(999.dp)).background(fill)
        .clickable(role = Role.Button, onClick = onClick).padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
        Text(label, color = ink, style = Ket27.Type.subheadline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * « Selon votre humeur »: the smart library's ambiances (6.2) in the order that suits the hour. They are
 * built from what is on the device, after the page has settled, and without asking the catalogue.
 */
@Composable
internal fun HomeMoods(hour: Int, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val mixes by SmartLibrary.mixes.collectAsState()
    LaunchedEffect(Unit) {
        runCatching {
            SmartLibrary.init(context)
            if (SmartLibrary.mixes.value.isEmpty()) { kotlinx.coroutines.delay(1_500); SmartLibrary.rebuild(learn = false) }
        }
    }
    val shown = remember(mixes, hour) { moodsForHour(hour, mixes) }
    if (shown.isEmpty()) return
    Column(modifier.testTag("home-moods")) {
        EditorialHeader("Selon votre humeur")
        val cell = com.ketvoice.app.ui.adaptive.shelfCell(148.dp, pageMargin, HomeLayout.gap)
        HomeRow {
            items(shown, key = { it.id }) { mix ->
                Column(Modifier.width(cell).clip(RoundedCornerShape(Ket27.Radius.artwork))
                    .clickable(role = Role.Button) { AppUi.openMood(mix.mood) }.testTag("home-mood")) {
                    com.ketvoice.app.ui.smartlib.MoodCover(mix.mood, cell, radius = Ket27.Radius.artwork)
                    Text(AppText.t("${mix.tracks.size} titres"), localize = false, color = theme.secondary, style = Ket27.Type.footnote,
                        maxLines = 1, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                }
            }
        }
    }
}
