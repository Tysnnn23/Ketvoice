package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LiveStats
import com.ketvoice.app.data.LiveStats.Period
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** What the page computes from: the listening history on this computer (fixtures in tests). */
internal val LocalStatsInput = staticCompositionLocalOf<() -> LiveStats.Input> { ::historyInput }

internal fun historyInput(): LiveStats.Input = LiveStats.Input(LocalDate.now(), ListeningHistory.days.value, ListeningHistory.months.value,
    ListeningHistory.years.value, ListeningHistory.listenedMs.value, ListeningHistory.playCounts.value, ListeningHistory.knownTracks.value)

private val PERIODS = listOf(Period.Week to "Cette semaine", Period.Month to "Ce mois", Period.Year to "Cette année", Period.All to "Tout")

/**
 * « Statistiques »: the shared [LiveStats] over the history kept on this computer (no server tables),
 * live while music plays — minutes and their trend, the bars of the period, the listening hours, top
 * titles and artists. Same strings and figures as the phone's page.
 */
@Composable
fun StatsPage() {
    val ket = LocalKet.current
    val source = LocalStatsInput.current
    var period by rememberSaveable { mutableStateOf(Period.Week) }
    // Each published minute (every few seconds while playing) recomputes the page.
    val listened by ListeningHistory.listenedMs.collectAsState()
    val counts by ListeningHistory.playCounts.collectAsState()
    val result by produceState<LiveStats.Result?>(null, period, listened, counts) {
        value = withContext(Dispatchers.Default) {
            LiveStats.compute(period, source(), artistOf = { MediaOverrides.mainArtist(it.artist) })
        }
    }
    Page("Statistiques", "stats") {
        item("sub") {
            KText("Elles se mettent à jour en direct pendant que vous écoutez de la musique.", Ket27Desk.Type.subheadline,
                Modifier.padding(bottom = 16.dp), color = ket.secondary, maxLines = 2)
        }
        item("periods") { PeriodPicker(period) { period = it } }
        val r = result ?: return@Page
        item("hero") { Hero(r) }
        if (r.isEmpty) {
            item("empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp).testTag("stats-empty"), horizontalAlignment = Alignment.CenterHorizontally) {
                    KText("Rien d'écouté sur cette période pour l'instant.", Ket27Desk.Type.title3)
                    Spacer(Modifier.height(6.dp))
                    KText("Lancez un titre : les chiffres bougent pendant l'écoute.", Ket27Desk.Type.subheadline, color = ket.secondary)
                }
            }
            return@Page
        }
        item("charts") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card("Minutes d'écoute", Modifier.weight(1f)) { Bars(r.bars.map { it.ms }, r.bars.map { barLabel(period, it.key) }, r.bars.map { it.now }) }
                if (r.hours.any { it > 0L }) Card("Écoute par heure", Modifier.weight(1f)) {
                    Bars(r.hours, listOf("0 h", "6 h", "12 h", "18 h"), List(24) { it == r.peakHour }, labelSpan = 6)
                }
            }
        }
        item("tops") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card("Top titres", Modifier.weight(1f)) { TopTracks(r) }
                Card("Top artistes", Modifier.weight(1f)) { TopArtists(r) }
            }
        }
    }
}

@Composable
private fun PeriodPicker(selected: Period, onPick: (Period) -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(ket.fill).padding(2.dp).testTag("stats-periods")) {
        PERIODS.forEach { (p, label) ->
            val on = p == selected
            Box(Modifier.height(28.dp).clip(RoundedCornerShape(7.dp)).background(if (!on) androidx.compose.ui.graphics.Color.Transparent else if (ket.dark) androidx.compose.ui.graphics.Color(0xFF636366) else ket.raised)
                .clickable { onPick(p) }.padding(horizontal = 14.dp).testTag("period:${p.name}"), contentAlignment = Alignment.Center) {
                KText(label, Ket27Desk.Type.footnote, weight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Hero(r: LiveStats.Result) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().padding(top = 20.dp).testTag("stats-hero"), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            KText("Minutes d'écoute", Ket27Desk.Type.footnote, color = ket.secondary, weight = FontWeight.SemiBold)
            KText(minutes(r.ms), Ket27Desk.Type.largeTitle.copy(fontSize = Ket27Desk.Type.largeTitle.fontSize * 1.4f), translate = false)
            deltaText(r)?.let { KText(it, Ket27Desk.Type.subheadline, color = ket.secondary, translate = false) }
        }
        r.artists.firstOrNull()?.let {
            KText(AppText.t("Vous écoutez surtout {0}.").replace("{0}", it.name), Ket27Desk.Type.subheadline,
                Modifier.padding(bottom = 4.dp), color = ket.secondary, translate = false)
        }
    }
}

@Composable
private fun Card(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    val ket = LocalKet.current
    Column(modifier.clip(RoundedCornerShape(Ket27Desk.Radius.menu)).background(ket.card).padding(16.dp)) {
        KText(title, Ket27Desk.Type.headline, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** Plain bars, the current one in the accent: no gradient, no glow. */
@Composable
private fun Bars(values: List<Long>, labels: List<String>, highlight: List<Boolean>, labelSpan: Int = 1) {
    val ket = LocalKet.current
    val top = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column {
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            values.forEachIndexed { i, v ->
                Box(Modifier.weight(1f).fillMaxHeight((v.toFloat() / top).coerceIn(.02f, 1f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (highlight.getOrElse(i) { false }) ket.accent else ket.fill))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            labels.forEach { KText(it, Ket27Desk.Type.caption2, Modifier.weight(labelSpan.toFloat()), color = ket.secondary, translate = false) }
        }
    }
}

@Composable
private fun TopTracks(r: LiveStats.Result) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val tracks = r.tracks.map { it.track }
    r.tracks.forEachIndexed { i, row ->
        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).clickable { player.playQueue(tracks, i) }
            .padding(horizontal = 4.dp).testTag("stats-track:$i"), verticalAlignment = Alignment.CenterVertically) {
            KText("${i + 1}", Ket27Desk.Type.subheadline, Modifier.width(24.dp), color = ket.secondary, translate = false)
            Artwork(CoverSources.track(row.track), 36.dp, radius = 6.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                KText(row.track.title, Ket27Desk.Type.subheadline, weight = FontWeight.Medium, translate = false)
                KText(row.track.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
            }
            Plays(row.plays)
        }
    }
}

@Composable
private fun TopArtists(r: LiveStats.Result) {
    val ket = LocalKet.current
    val nav = LocalNavigator.current
    r.artists.forEachIndexed { i, a ->
        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).clickable { nav.push(Route.Artist(ArtistRef(a.catalogId, a.name, a.cover))) }
            .padding(horizontal = 4.dp).testTag("stats-artist:$i"), verticalAlignment = Alignment.CenterVertically) {
            KText("${i + 1}", Ket27Desk.Type.subheadline, Modifier.width(24.dp), color = ket.secondary, translate = false)
            Artwork(CoverSources.artistPhoto(a.name, a.cover), 36.dp, circle = true)
            Spacer(Modifier.width(10.dp))
            KText(a.name, Ket27Desk.Type.subheadline, Modifier.weight(1f), weight = FontWeight.Medium, translate = false)
            Plays(a.plays)
        }
    }
}

@Composable
private fun RowScope.Plays(n: Int) {
    KText(AppText.t("{0} écoutes").replace("{0}", n.toString()), Ket27Desk.Type.footnote, color = LocalKet.current.secondary, translate = false)
}

private fun locale(): Locale = AppLanguage.all.firstOrNull { it.label == AppSettings.language.value }?.let { Locale.forLanguageTag(it.code) } ?: Locale.getDefault()

private fun minutes(ms: Long): String = java.text.NumberFormat.getIntegerInstance(locale()).format(ms / 60_000L)

/** « ▲ 12 % vs la semaine dernière », as on the phone; none for « Tout ». */
internal fun deltaText(r: LiveStats.Result): String? {
    val d = r.deltaPercent ?: return null
    val vs = when (r.period) {
        Period.Week -> "{0} % vs la semaine dernière"
        Period.Month -> "{0} % vs le mois dernier"
        Period.Year -> "{0} % vs l'an dernier"
        Period.All -> return null
    }
    return (if (d >= 0) "▲ " else "▼ ") + AppText.t(vs).replace("{0}", abs(d).toString())
}

private fun barLabel(period: Period, key: String): String = runCatching {
    when (period) {
        Period.Week -> LocalDates.weekday(LocalDate.parse(key).dayOfWeek)
        Period.Month -> AppText.t("Sem. {0}").replace("{0}", key.removePrefix("S"))
        Period.Year, Period.All -> LocalDates.month(YearMonth.parse(key).monthValue)
    }
}.getOrDefault(key)
