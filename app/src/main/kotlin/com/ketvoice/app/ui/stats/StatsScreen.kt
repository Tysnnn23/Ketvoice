package com.ketvoice.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.ListeningWeek
import com.ketvoice.app.data.LiveStats
import com.ketvoice.app.data.LiveStats.Period
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.player.Player
import com.ketvoice.app.smartlib.SmartFacts
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.CollectionPlaybackActions
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.SectionHeader
import com.ketvoice.app.ui.common.SwipeTrackActions
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.asHeading
import com.ketvoice.app.ui.common.openTrackSearch
import com.ketvoice.app.ui.sub.WrappedPeriod
import com.ketvoice.app.ui.sub.playsLabel
import com.ketvoice.app.util.ShareCard
import com.ketvoice.app.util.StatsShareCard
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import kotlin.math.abs

/** Where the statistics are worked out: off the main thread; the host tests may run it in place. */
internal object StatsWork { @Volatile var dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default }

/** The bar-chart mark of « Statistiques », drawn like [KetIcons] (24-unit grid, round 1.8 strokes). */
internal val StatsIcon: ImageVector by lazy {
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = KetIcons.STROKE, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 20f); lineTo(5f, 13f); moveTo(10f, 20f); lineTo(10f, 6f); moveTo(15f, 20f); lineTo(15f, 10f)
            moveTo(20f, 20f); lineTo(20f, 4f)
        }
    }.build()
}

private val PERIODS = listOf(Period.Week to "Cette semaine", Period.Month to "Ce mois", Period.Year to "Cette année", Period.All to "Tout")

private fun genresOf(tracks: List<Track>): Map<String, Set<String>> = runCatching {
    SmartFacts.build(tracks) { null }.mapValues { (_, facts) -> facts.genres.mapTo(LinkedHashSet()) { it.label } }
}.getOrDefault(emptyMap())

internal fun statsInput(today: LocalDate = LocalDate.now()) = LiveStats.Input(today, ListeningHistory.days.value, ListeningHistory.months.value,
    ListeningHistory.years.value, ListeningHistory.listenedMs.value, ListeningHistory.playCounts.value, ListeningHistory.knownTracks.value)

private fun compute(period: Period, input: LiveStats.Input) = LiveStats.compute(period, input, artistOf = { MediaOverrides.mainArtist(it.artist) },
    coverOf = { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }, genresOf = ::genresOf)

/**
 * « Statistiques » (8.0, « Replay en direct »): the week, month, year or everything, always up to date. The history
 * publishes every [ListeningHistory.PUBLISH_MS]; only the period on screen is worked out again, off the main thread,
 * and each period keeps its last result so switching shows it at once.
 */
@Composable
fun StatsScreen() {
    val th = LocalTheme.current
    val days by ListeningHistory.days.collectAsState()
    val months by ListeningHistory.months.collectAsState()
    val years by ListeningHistory.years.collectAsState()
    val total by ListeningHistory.listenedMs.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    var period by rememberSaveable { mutableStateOf(Period.Week) }
    var genreRound by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyMap<Period, LiveStats.Result>()) }
    LaunchedEffect(period, days, months, years, total, counts, known, genreRound) {
        val input = LiveStats.Input(LocalDate.now(), days, months, years, total, counts, known)
        val result = withContext(StatsWork.dispatcher) { compute(period, input) }
        results = results + (period to result)
    }
    // Styles of the catalogue's albums not asked about yet (a few, once per visit): the genres fill in.
    LaunchedEffect(Unit) {
        if (runCatching { SmartFacts.learnAlbums(ListeningHistory.knownTracks.value.take(40), budget = 12) }.getOrDefault(0) > 0) genreRound++
    }
    var sharing by remember { mutableStateOf(false) }
    val result = results[period]
    val nothingYet = total <= 0L && days.isEmpty() && months.isEmpty() && counts.isEmpty()
    Column(Modifier.fillMaxSize().pageBackground(th).statusBarsPadding().testTag("stats-page")) {
        com.ketvoice.app.ui.common.Ket27PageBar("Statistiques", { AppUi.closeOverlay() }) {
            if (result != null && !result.isEmpty) Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { sharing = true }
                .testTag("stats-share"), contentAlignment = Alignment.Center) {
                Icon(KetIcons.Share, "Partager", tint = th.accent, modifier = Modifier.size(22.dp))
            }
        }
        if (nothingYet) {
            EmptyState("Pas encore de statistiques", "Elles se mettent à jour en direct pendant que vous écoutez de la musique.",
                "Rechercher des titres", icon = StatsIcon, onAction = ::openTrackSearch)
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("stats-list"),
                contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
                item(key = "periods") { PeriodChips(period) { period = it } }
                if (result != null) statsSections(result)
            }
        }
    }
    if (sharing && result != null) StatsShareSheet(result) { sharing = false }
}

@Composable
private fun PeriodChips(selected: Period, onSelect: (Period) -> Unit) {
    val th = LocalTheme.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp).testTag("stats-periods"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PERIODS.forEach { (p, label) ->
            val on = p == selected
            Text(label, color = if (on) Color.White else th.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) th.accent else th.card)
                    .clickable(role = Role.Tab) { onSelect(p) }.heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 16.dp).testTag("stats-period"))
        }
    }
}

internal fun periodLabel(result: LiveStats.Result, today: LocalDate = LocalDate.now()): String = when (result.period) {
    Period.Week -> AppText.t("Cette semaine")
    Period.Month -> WrappedPeriod.monthName(YearMonth.from(today).toString())
    Period.Year -> today.year.toString()
    Period.All -> AppText.t("Depuis le début")
}

/** « ▲ 12 % vs la semaine dernière », or null without a previous period. */
internal fun deltaText(result: LiveStats.Result): String? {
    val d = result.deltaPercent ?: return null
    val vs = when (result.period) {
        Period.Week -> "{0} % vs la semaine dernière"
        Period.Month -> "{0} % vs le mois dernier"
        Period.Year -> "{0} % vs l'an dernier"
        Period.All -> return null
    }
    return (if (d >= 0) "▲ " else "▼ ") + AppText.t(vs).replace("{0}", abs(d).toString())
}

internal fun minutesText(ms: Long): String =
    java.text.NumberFormat.getIntegerInstance(WrappedPeriod.locale()).format(ms / 60_000L)

private fun barLabel(period: Period, key: String): String {
    val locale = WrappedPeriod.locale()
    return runCatching {
        when (period) {
            Period.Week -> LocalDate.parse(key).dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
            Period.Month -> AppText.t("Sem. {0}").replace("{0}", key.removePrefix("S"))
            Period.Year, Period.All -> YearMonth.parse(key).month.getDisplayName(TextStyle.NARROW, locale)
        }
    }.getOrDefault(key)
}

private fun LazyListScope.statsSections(r: LiveStats.Result) {
    item(key = "hero") { Hero(r) }
    item(key = "chart") { BarsCard(r) }
    if (r.isEmpty) {
        item(key = "empty") {
            val th = LocalTheme.current
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp).testTag("stats-empty"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Rien d'écouté sur cette période pour l'instant.", color = th.onBackground, style = Ket27.Type.headline, textAlign = TextAlign.Center)
                Text("Lancez un titre : les chiffres bougent pendant l'écoute.", color = th.secondary, style = Ket27.Type.footnote,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
        return
    }
    item(key = "tiles") { Tiles(r) }
    if (r.artists.isNotEmpty()) item(key = "artists") { TopArtists(r.artists) }
    if (r.tracks.isNotEmpty()) item(key = "tracks") { TopTracks(r.tracks) }
    if (r.genres.isNotEmpty()) item(key = "genres") { TopGenres(r.genres) }
    if (r.hours.isNotEmpty()) item(key = "hours") { Hours(r.hours, r.peakHour) }
}

@Composable
private fun Hero(r: LiveStats.Result) {
    val th = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 4.dp)) {
        Text(AppText.t("Minutes d'écoute").uppercase(WrappedPeriod.locale()), localize = false, color = th.secondary,
            style = Ket27.Type.caption2, letterSpacing = 1.sp)
        Text(minutesText(r.ms), localize = false, color = th.onBackground, fontSize = 56.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-1.8).sp, lineHeight = 62.sp, modifier = Modifier.testTag("stats-minutes").asHeading())
        deltaText(r)?.let { delta ->
            Text(delta, localize = false, color = if ((r.deltaPercent ?: 0) >= 0) th.accent else th.secondary,
                style = Ket27.Type.subheadline, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("stats-delta"))
        }
        if (!r.isEmpty) Text(AppText.t("{0} écoutes de {1} titres.").replace("{0}", r.plays.toString()).replace("{1}", r.titles.toString()),
            localize = false, color = th.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun BarsCard(r: LiveStats.Result) {
    val th = LocalTheme.current
    val longest = r.bars.maxOfOrNull { it.ms } ?: 0L
    val quiet = th.onBackground.copy(alpha = if (th.isDark) .18f else .12f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(Ket27.Metrics.groupRadius))
        .background(th.card).padding(horizontal = 14.dp, vertical = 16.dp)) {
        val description = r.bars.joinToString { barLabel(r.period, it.key) + " " + AppText.t("{0} min").replace("{0}", (it.ms / 60_000L).toString()) }
        Canvas(Modifier.fillMaxWidth().height(116.dp).semantics { contentDescription = description }.testTag("stats-chart")) {
            if (r.bars.isEmpty()) return@Canvas
            val step = size.width / r.bars.size
            val barW = minOf(step * .52f, 28.dp.toPx())
            r.bars.forEachIndexed { i, bar ->
                val h = size.height * ListeningWeek.barShare(bar.ms, longest)
                drawRoundRect(if (bar.now) th.accent else quiet, Offset(i * step + (step - barW) / 2f, size.height - h), Size(barW, h),
                    CornerRadius(barW / 3f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            r.bars.forEach { bar ->
                Text(barLabel(r.period, bar.key), localize = false, color = if (bar.now) th.onBackground else th.secondary,
                    style = Ket27.Type.caption, fontWeight = if (bar.now) FontWeight.SemiBold else null, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tiles(r: LiveStats.Result) {
    // The three tiles keep one height, whichever label wraps.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("${r.streak}", if (r.streak == 1) "jour d'affilée" else "jours d'affilée", Modifier.weight(1f).fillMaxHeight().testTag("stats-streak"))
        Tile("${r.newArtists}", if (r.newArtists == 1) "nouvel artiste" else "nouveaux artistes", Modifier.weight(1f).fillMaxHeight().testTag("stats-new-artists"))
        if (r.mixes > 0) Tile("${r.mixes}", if (r.mixes == 1) "mix Ketmix" else "mixes Ketmix", Modifier.weight(1f).fillMaxHeight().testTag("stats-mixes"))
        else Tile("${r.titles}", "titres écoutés", Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    val th = LocalTheme.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(th.card).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(value, localize = false, color = th.onBackground, style = Ket27.Type.title2, maxLines = 1)
        Text(label, color = th.secondary, style = Ket27.Type.footnote, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TopArtists(artists: List<LiveStats.ArtistRow>) {
    val th = LocalTheme.current
    val layout = LocalAdaptiveLayout.current
    val large = com.ketvoice.app.ui.adaptive.LargeScreen.isLarge(layout.widthDp, layout.heightDp, com.ketvoice.app.BuildConfig.IS_QUEST)
    val side = if (large) 84.dp else 62.dp
    SectionHeader("Top artistes", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        artists.forEach { artist ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) {
                if (artist.catalogId != null) AppUi.openArtist(artist.catalogId, artist.name) else AppUi.openSearch(artist.name)
            }.padding(vertical = 4.dp).testTag("stats-artist"), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(artist.name, MediaOverrides.artistPhoto(artist.name, artist.cover), side, side / 2)
                Text(artist.name, localize = false, color = th.onBackground, style = Ket27.Type.caption, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp))
                Text(playsLabel(artist.plays), localize = false, color = th.secondary, style = Ket27.Type.caption2.copy(fontWeight = FontWeight.Normal),
                    maxLines = 1)
            }
        }
        repeat(LiveStats.TOP_ARTISTS - artists.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun TopTracks(rows: List<LiveStats.TrackRow>) {
    val th = LocalTheme.current
    val player by Player.state.collectAsState()
    val playable = remember(rows) { PersonalMixes.withLocalFiles(rows.map { it.track }, com.ketvoice.app.data.LocalMusic.tracks.value) }
    SectionHeader("Top titres", Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp))
    CollectionPlaybackActions(playable, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
    rows.forEachIndexed { index, row ->
        val track = playable.getOrElse(index) { row.track }
        SwipeTrackActions(track) {
            com.ketvoice.app.ui.common.TrackRow(track, ArtworkRepo.coverForTrack(row.track) ?: row.track.coverUrl, player.current?.id == row.track.id,
                player.isPlaying, { Player.playQueue(playable, index) }, modifier = Modifier.testTag("stats-track"), rank = index + 1) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(playsLabel(row.plays), localize = false, color = th.onBackground, style = Ket27.Type.footnote, maxLines = 1)
                    if (row.ms >= 60_000L) Text("≈ " + AppText.t("{0} min").replace("{0}", minutesText(row.ms)), localize = false,
                        color = th.secondary, style = Ket27.Type.caption, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TopGenres(genres: List<LiveStats.GenreRow>) {
    val th = LocalTheme.current
    SectionHeader("Top genres", Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(Ket27.Metrics.groupRadius)).background(th.card)
        .padding(horizontal = 16.dp, vertical = 6.dp).testTag("stats-genres")) {
        genres.forEach { g ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { AppUi.openSearch(AppText.t(g.label)) }
                .padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.label, color = th.onBackground, style = Ket27.Type.subheadline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${g.percent} %", localize = false, color = th.secondary, style = Ket27.Type.footnote)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(th.onBackground.copy(alpha = .08f))) {
                    Box(Modifier.fillMaxWidth((g.percent / 100f).coerceIn(.02f, 1f)).fillMaxHeight().clip(CircleShape).background(th.accent))
                }
            }
        }
    }
}

@Composable
private fun Hours(hours: List<Long>, peak: Int?) {
    val th = LocalTheme.current
    val quiet = th.onBackground.copy(alpha = if (th.isDark) .18f else .12f)
    SectionHeader("Écoute par heure", Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(Ket27.Metrics.groupRadius)).background(th.card)
        .padding(16.dp).testTag("stats-hours")) {
        if (peak != null) Text(AppText.t("Vous écoutez surtout {0}.").replace("{0}", AppText.t(Wrapped.dayPart(peak))), localize = false,
            color = th.onBackground, style = Ket27.Type.subheadline, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
        val longest = hours.maxOrNull() ?: 0L
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val step = size.width / 24f
            val barW = step * .6f
            hours.forEachIndexed { h, ms ->
                val height = size.height * ListeningWeek.barShare(ms, longest)
                drawRoundRect(if (h == peak) th.accent else quiet, Offset(h * step + (step - barW) / 2f, size.height - height), Size(barW, height),
                    CornerRadius(barW / 2.5f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf(0, 6, 12, 18).forEach { h ->
                Text("$h h", localize = false, color = th.secondary, style = Ket27.Type.caption, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The two statistics cards, previewed as posted, then the share sheet. */
@Composable
internal fun StatsShareSheet(result: LiveStats.Result, onDismiss: () -> Unit) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val top = result.tracks.take(5)
    var kind by remember { mutableIntStateOf(if (top.isEmpty()) 1 else 0) }
    val coverUrl = top.firstOrNull()?.track?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
    val accent = com.ketvoice.app.theme.rememberCoverAccent(coverUrl)?.first?.toArgb()
    val covers by produceState<List<android.graphics.Bitmap?>>(emptyList(), top) { value = top.map { ShareCard.loadCover(context, it.track) } }
    val heading = AppText.t("Mon top 5"); val unit = AppText.t("minutes d'écoute")
    val period = periodLabel(result)
    val delta = deltaText(result)
    val topLine = result.artists.firstOrNull()?.let { AppText.t("Artiste n°1 : {0}").replace("{0}", it.name) }
    val card by produceState<android.graphics.Bitmap?>(null, kind, covers, accent, result) {
        value = withContext(StatsWork.dispatcher) {
            runCatching {
                if (kind == 0) StatsShareCard.top5(heading, period, top.mapIndexed { i, row ->
                    StatsShareCard.Row(row.track.title, row.track.artist, covers.getOrNull(i)) }, accent)
                else StatsShareCard.minutes(period, minutesText(result.ms), unit, delta, result.bars.map { it.ms }.toLongArray(),
                    result.bars.indexOfFirst { it.now }, topLine, covers.firstOrNull(), accent)
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(Ket27.Metrics.alertRadius)
    com.ketvoice.app.ui.common.SpringSheet(onDismiss, insets = Modifier.statusBarsPadding().navigationBarsPadding(), startFull = true) { room ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(8.dp).heightIn(max = room - 16.dp).clip(shape)
                .background(theme.card).pointerInput(Unit) { detectTapGestures { } }.padding(bottom = 16.dp).testTag("stats-share-sheet"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 8.dp).size(36.dp, 5.dp).clip(CircleShape).background(theme.secondary.copy(alpha = .4f)))
                Text("Partager", color = theme.onBackground, style = Ket27.Type.title3, modifier = Modifier.padding(top = 12.dp))
                Text(period, localize = false, color = theme.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(top = 2.dp))
                Box(Modifier.padding(top = 14.dp).height(((room.value - 300f).coerceIn(220f, 440f)).dp).aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(16.dp)).background(theme.onBackground.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
                    val picture = card
                    if (picture != null) Image(picture.asImageBitmap(), AppText.t("Carte de partage"), Modifier.matchParentSize().testTag("stats-share-preview"))
                    else com.ketvoice.app.ui.common.ActivitySpinner(size = 20.dp)
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull(if (top.isNotEmpty()) 0 to "Mon top 5" else null, 1 to "Mes minutes").forEach { (k, label) ->
                        val on = kind == k
                        Text(label, color = if (on) Color.White else theme.onBackground, style = Ket27.Type.footnote, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) theme.accent else theme.onBackground.copy(alpha = .08f))
                                .clickable(role = Role.Tab) { kind = k }.heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically)
                                .padding(horizontal = 16.dp).testTag("stats-share-kind"))
                    }
                }
                com.ketvoice.app.ui.account.AccountButton("Partager l'image", Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp)
                    .testTag("stats-share-image"), primary = true, enabled = card != null) {
                    val picture = card ?: return@AccountButton
                    scope.launch {
                        runCatching { StatsShareCard.share(context, ShareCard.save(context, picture)) }
                        onDismiss()
                    }
                }
            }
        }
    }
}
