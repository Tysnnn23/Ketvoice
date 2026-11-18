package com.ketvoice.app.ui.sub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.ArtworkFlowBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The screens a Wrapped story can hold; a page appears only when there is something true to show on it. */
internal enum class WrappedPage { Intro, Minutes, Clock, Artist, Tracks, Albums, Mood, Discoveries, Ketmix, Summary }

/** The pages of the story, in order. */
internal object WrappedPages {
    /** Pages every recap has: intro, minutes, artist, titles, summary. */
    const val COUNT = 5
    const val PAGE_MS = 6_500L

    fun of(recap: Wrapped.Recap): List<WrappedPage> = buildList {
        add(WrappedPage.Intro); add(WrappedPage.Minutes)
        if (recap.peakHour != null) add(WrappedPage.Clock)
        add(WrappedPage.Artist); add(WrappedPage.Tracks)
        if (recap.albums.size >= 2) add(WrappedPage.Albums)
        if (recap.mood != null) add(WrappedPage.Mood)
        if (recap.newArtists.isNotEmpty()) add(WrappedPage.Discoveries)
        if (recap.ketmixMixes > 0) add(WrappedPage.Ketmix)
        add(WrappedPage.Summary)
    }

    /** A tap on the left third goes back, anywhere else forward; past the last page closes. */
    fun next(page: Int, x: Float, width: Float, count: Int = COUNT): Int =
        if (width > 0f && x < width / 3f) (page - 1).coerceAtLeast(0) else (page + 1).coerceAtMost(count)

    /** Each page on its own colours when no cover lights the story. */
    fun palette(page: WrappedPage): Pair<Long, Long> = when (page) {
        WrappedPage.Intro -> 0xFF1F1147 to 0xFFB0306A
        WrappedPage.Minutes -> 0xFF0E3B43 to 0xFF1E8C7E
        WrappedPage.Clock -> 0xFF101A4A to 0xFF4B3BB8
        WrappedPage.Artist -> 0xFF3A1240 to 0xFFC2412D
        WrappedPage.Tracks -> 0xFF14213D to 0xFF2F6BD3
        WrappedPage.Albums -> 0xFF2B1B10 to 0xFFB86E1F
        WrappedPage.Mood -> 0xFF0D3320 to 0xFF3FA34D
        WrappedPage.Discoveries -> 0xFF371B58 to 0xFFE0559B
        WrappedPage.Ketmix -> 0xFF0A0A1F to 0xFF0F7CA8
        WrappedPage.Summary -> 0xFF1C1C24 to 0xFF5A2D82
    }
}

/** The period a recap covers, as its pages name it: « Novembre 2026 », « 2026 », or all time. */
internal object WrappedPeriod {
    fun locale(): Locale = Locale.forLanguageTag(AppText.language().code)

    fun monthName(key: String, locale: Locale = locale()): String = runCatching {
        YearMonth.parse(key).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }
    }.getOrDefault(key)

    fun brand(recap: Wrapped.Recap): String = if (recap.month != null) "KETVOICE REPLAY" else "KETVOICE WRAPPED"

    fun title(recap: Wrapped.Recap): String = when {
        recap.month != null -> monthName(recap.month)
        recap.year != null -> AppText.t("Votre année {0}").replace("{0}", recap.year)
        else -> AppText.t("Votre Ketvoice")
    }
}

/**
 * The year's recap, with what the months of the year counted (hours, discoveries, Ketmix, mood). Worked out when
 * Wrapped opens (6.6): kept up to date in the Replay page, it was recomputed while the page was only looked at.
 */
internal fun wrappedRecapNow(): Wrapped.Recap {
    val cover = { t: Track -> ArtworkRepo.coverForTrack(t) ?: t.coverUrl }
    val months = ListeningHistory.months.value
    return Wrapped.withMonths(Wrapped.recap(java.time.LocalDate.now().year.toString(), ListeningHistory.years.value,
        ListeningHistory.playCounts.value, ListeningHistory.listenedMs.value, ListeningHistory.knownTracks.value, coverOf = cover),
        months, coverOf = cover, bpmOf = ::measuredBpm)
}

/** The tempo Ketmix measured for a title, when it did (its ending first, then its intro). */
internal fun measuredBpm(track: Track): Float? = runCatching {
    val store = com.ketvoice.app.automix.AnalysisStore
    (store.outro(track.id, track.durationMs) ?: store.intro(track.id, track.durationMs))
        ?.takeIf { it.bpm > 0f && it.confidence >= .3f }?.bpm
}.getOrNull()

/** Ketvoice Wrapped, as stories: the year — or a month — in pages over the flowing colours of the most played cover. */
@Composable
internal fun WrappedStory(recap: Wrapped.Recap, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        WrappedStoryContent(recap, onClose)
    }
}

@Composable
internal fun WrappedStoryContent(recap: Wrapped.Recap, onClose: () -> Unit, startPage: Int = 0, autoAdvance: Boolean = true) {
    val pages = remember(recap) { WrappedPages.of(recap) }
    var page by remember { mutableIntStateOf(startPage.coerceIn(0, pages.lastIndex)) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(page) {
        if (page >= pages.size) { onClose(); return@LaunchedEffect }
        progress.snapTo(0f)
        // The last page waits for the listener: it holds the share button.
        if (page == pages.lastIndex || !autoAdvance) { progress.snapTo(if (page == pages.lastIndex) 1f else 0f); return@LaunchedEffect }
        progress.animateTo(1f, tween(WrappedPages.PAGE_MS.toInt(), easing = LinearEasing))
        page++
    }
    val shownPage = pages[page.coerceIn(0, pages.lastIndex)]
    val (top, bottom) = WrappedPages.palette(shownPage)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(top), Color(bottom)))).testTag("wrapped-story")) {
        ArtworkFlowBackground(recap.topTrack?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }, playing = true)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(top).copy(alpha = .55f), Color.Black.copy(alpha = .45f)))))
        Box(Modifier.fillMaxSize().pointerInput(pages.size) {
            detectTapGestures { offset -> page = WrappedPages.next(page, offset.x, size.width.toFloat(), pages.size) }
        }) {
            AnimatedContent(page.coerceAtMost(pages.lastIndex), transitionSpec = {
                (fadeIn(tween(Motion.duration(420))) + scaleIn(tween(Motion.duration(420)), initialScale = .94f))
                    .togetherWith(fadeOut(tween(Motion.duration(220))))
            }, label = "wrapped-page") { shown ->
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(start = 28.dp, end = 28.dp, top = 84.dp, bottom = 28.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                        WrappedPageBody(pages[shown], recap)
                    }
                    if (pages[shown] != WrappedPage.Summary) WrappedShareButton(recap, pages[shown], Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
        // Story bars and the close button above everything.
        Column(Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(pages.size) { index ->
                    // The running bar is drawn from the progress, never composed from it: reading it here recomposed
                    // the whole story, its page and its flowing background, every frame of every page (6.6).
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .30f))
                        .drawBehind {
                            val fill = when { index < page -> 1f; index == page -> progress.value; else -> 0f }
                            if (fill > 0f) drawRect(Color.White, size = size.copy(width = size.width * fill.coerceIn(0f, 1f)))
                        })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(WrappedPeriod.brand(recap), localize = false, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                // Ket 27: the round 44 dp button of an iOS story.
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f))
                    .clickable(role = Role.Button, onClick = onClose).testTag("wrapped-close"), contentAlignment = Alignment.Center) {
                    Icon(KetIcons.Close, "Fermer", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.WrappedPageBody(page: WrappedPage, recap: Wrapped.Recap) {
    when (page) {
        WrappedPage.Intro -> WrappedIntro(recap)
        WrappedPage.Minutes -> WrappedMinutes(recap)
        WrappedPage.Clock -> WrappedClock(recap)
        WrappedPage.Artist -> WrappedArtist(recap)
        WrappedPage.Tracks -> WrappedTracks(recap)
        WrappedPage.Albums -> WrappedAlbums(recap)
        WrappedPage.Mood -> WrappedMood(recap)
        WrappedPage.Discoveries -> WrappedDiscoveries(recap)
        WrappedPage.Ketmix -> WrappedKetmix(recap)
        WrappedPage.Summary -> WrappedSummary(recap)
    }
}

/** Every page can leave as a story picture (9:16), through the same share sheet as the recap. */
@Composable
private fun WrappedShareButton(recap: Wrapped.Recap, page: WrappedPage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Row(modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = .20f))
        .clickable(role = Role.Button, enabled = !sharing) {
            sharing = true
            scope.launch {
                runCatching { WrappedCard.share(context, WrappedCard.render(context, recap, page)) }
                sharing = false
            }
        }.heightIn(min = 44.dp).padding(horizontal = 18.dp, vertical = 10.dp).testTag("wrapped-share-page"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.Share, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(if (sharing) "…" else AppText.t("Partager en story"), localize = false, color = Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Headline(text: String, size: Int = 40, modifier: Modifier = Modifier) {
    // Ket 27: a display title as iOS sets one — bold rather than black, with the tracking that suits Inter.
    Text(text, localize = false, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-size * .018f).sp, lineHeight = (size + 4).sp, modifier = modifier)
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, localize = false, color = Color.White.copy(alpha = .82f),
        style = com.ketvoice.app.theme.Ket27.Type.body, modifier = modifier.padding(top = 10.dp))
}

@Composable
private fun Kicker(text: String) {
    Text(text.uppercase(), localize = false, color = Color.White.copy(alpha = .78f), fontSize = 13.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp, modifier = Modifier.padding(bottom = 10.dp))
}

/** A value that appears with the page: counts up and slides in, or is simply there under reduced motion. */
@Composable
private fun rememberAppear(delayMs: Long = 0L): Float {
    val appear = remember { Animatable(if (Motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!Motion.reduced) { delay(delayMs); appear.animateTo(1f, tween(Motion.duration(480))) } }
    return appear.value
}

internal fun playsLabel(n: Int): String = AppText.t(if (n == 1) "1 écoute" else "{0} écoutes").replace("{0}", n.toString())

@Composable
private fun WrappedIntro(recap: Wrapped.Recap) {
    if (recap.month != null) {
        Kicker(AppText.t("Votre Replay"))
        Headline(WrappedPeriod.title(recap), 50)
        Caption(AppText.t("Votre mois en musique, écran par écran."))
    } else {
        Headline(WrappedPeriod.title(recap), 46)
        Caption(if (recap.year != null) AppText.t("Ce que vous avez écouté cette année, écran par écran.")
            else AppText.t("Tout ce que Ketvoice a compté depuis vos débuts, écran par écran."))
    }
}

@Composable
private fun WrappedMinutes(recap: Wrapped.Recap) {
    val shown = remember { Animatable(if (Motion.reduced) recap.minutes.toFloat() else 0f) }
    LaunchedEffect(recap.minutes) {
        if (Motion.reduced) shown.snapTo(recap.minutes.toFloat())
        else shown.animateTo(recap.minutes.toFloat(), tween(1_800, easing = FastOutSlowInEasing))
    }
    Caption(AppText.t("Vous avez écouté"))
    Text("%,d".format(shown.value.toLong()).replace(',', ' '), localize = false, color = Color.White, fontSize = 72.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-2.5).sp, modifier = Modifier.testTag("wrapped-minutes"))
    Headline(AppText.t("minutes de musique"), 30)
    Caption(AppText.t("{0} écoutes de {1} titres.").replace("{0}", recap.plays.toString()).replace("{1}", recap.titles.toString()))
    val hours = recap.minutes / 60
    if (hours >= 2) Caption(AppText.t("Soit {0} heures sans couper le son.").replace("{0}", hours.toString()))
}

/** The listening clock: twenty-four rays, the longest at the hour listened to most. */
@Composable
private fun WrappedClock(recap: Wrapped.Recap) {
    val peak = recap.peakHour ?: return
    val grow = rememberAppear()
    Kicker(AppText.t("Votre horloge d'écoute"))
    Headline(AppText.t("Vous écoutez surtout {0}").replace("{0}", AppText.t(Wrapped.dayPart(peak))), 34)
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val longest = recap.clock.max().coerceAtLeast(1L)
        androidx.compose.foundation.Canvas(Modifier.size(230.dp).testTag("wrapped-clock")) {
            val center = Offset(size.width / 2, size.height / 2)
            val inner = size.minDimension * .24f
            val outer = size.minDimension * .5f
            drawCircle(Color.White.copy(alpha = .10f), radius = inner - 6.dp.toPx(), center = center)
            recap.clock.forEachIndexed { hour, ms ->
                val angle = Math.toRadians(hour * 15.0 - 90.0)
                val length = (outer - inner) * (.08f + .92f * ms.toFloat() / longest) * grow
                val dir = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                drawLine(if (hour == peak) Color.White else Color.White.copy(alpha = .42f), center + dir * inner,
                    center + dir * (inner + length), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text(AppText.t("{0} h").replace("{0}", peak.toString()), localize = false, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(16.dp))
    val parts = Wrapped.dayParts(recap.clock)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Matin", "Après-midi", "Soir", "Nuit").forEachIndexed { i, label ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = if (i == partIndex(peak)) .26f else .12f))
                .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(parts[i] * 100).toInt()} %", localize = false, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(label, color = Color.White.copy(alpha = .8f), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

private fun partIndex(hour: Int) = when (hour.mod(24)) { in 5..11 -> 0; in 12..17 -> 1; in 18..22 -> 2; else -> 3 }

@Composable
private fun WrappedArtist(recap: Wrapped.Recap) {
    val artist = recap.topArtist
    if (artist == null) { Headline(AppText.t("Votre artiste vous attend encore")); return }
    // The artist's own photo when the catalogue has one; the cover of their most played title otherwise.
    val photo by produceState(MediaOverrides.artistPhoto(artist.name, null), artist.name) {
        if (value == null) value = runCatching { com.ketvoice.app.data.CatalogRepo.artistPicture(artist.name, artist.catalogId) }.getOrNull()
    }
    val appear = rememberAppear()
    Caption(AppText.t("Votre artiste numéro 1"))
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth().graphicsLayer { scaleX = .9f + .1f * appear; scaleY = scaleX; alpha = appear }, contentAlignment = Alignment.Center) {
        Cover(artist.name, photo ?: artist.cover, 240.dp, 120.dp, modifier = Modifier.testTag("wrapped-artist-photo"))
    }
    Spacer(Modifier.height(22.dp))
    Text(artist.name, localize = false, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    val share = if (recap.plays > 0) artist.plays * 100 / recap.plays else 0
    Text(playsLabel(artist.plays) + if (share > 0) " · " + AppText.t("{0} % de vos écoutes").replace("{0}", share.toString()) else "",
        localize = false, color = Color.White.copy(alpha = .82f), fontSize = 17.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

@Composable
private fun RankedRow(index: Int, title: String, subtitle: String, cover: String?) {
    val appear = rememberAppear(index * 140L)
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).graphicsLayer { alpha = appear; translationY = (1f - appear) * 24f },
        verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", localize = false, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
        Cover(title, cover, 54.dp, 10.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, localize = false, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, localize = false, color = Color.White.copy(alpha = .72f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WrappedTracks(recap: Wrapped.Recap) {
    Headline(AppText.t("Vos titres en boucle"), 34)
    Spacer(Modifier.height(18.dp))
    recap.tracks.take(5).forEachIndexed { index, (track, n) ->
        RankedRow(index, track.title, "${track.artist} · ${playsLabel(n)}", ArtworkRepo.coverForTrack(track) ?: track.coverUrl)
    }
}

@Composable
private fun WrappedAlbums(recap: Wrapped.Recap) {
    Headline(AppText.t("Vos albums"), 34)
    Spacer(Modifier.height(18.dp))
    recap.albums.take(5).forEachIndexed { index, album ->
        RankedRow(index, album.title, "${album.artist} · ${playsLabel(album.plays)}", album.cover)
    }
}

/** The mood, read from the tempo Ketmix measured: a disc that beats at that tempo (still under reduced motion). */
@Composable
private fun WrappedMood(recap: Wrapped.Recap) {
    val mood = recap.mood ?: return
    Kicker(AppText.t("Votre humeur"))
    Headline(AppText.t(mood.label), 52, Modifier.testTag("wrapped-mood"))
    Spacer(Modifier.height(22.dp))
    val beat = remember { Animatable(0f) }
    LaunchedEffect(mood.bpm) {
        if (Motion.reduced) return@LaunchedEffect
        val period = (60_000 / mood.bpm.coerceIn(60, 180)).toLong()
        while (true) {
            beat.snapTo(1f); beat.animateTo(0f, tween((period * .8f).toInt(), easing = FastOutSlowInEasing))
            delay((period * .2f).toLong())
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(150.dp).graphicsLayer { val s = 1f + .08f * beat.value; scaleX = s; scaleY = s }
            .clip(CircleShape).background(Brush.radialGradient(listOf(Color.White.copy(alpha = .55f), Color.White.copy(alpha = .08f)))),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${mood.bpm}", localize = false, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("BPM", localize = false, color = Color.White.copy(alpha = .8f), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
        }
    }
    Caption(AppText.t("Le tempo moyen de vos titres les plus écoutés, mesuré par Ketmix sur {0} titres.").replace("{0}", mood.measured.toString()),
        Modifier.padding(top = 12.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrappedDiscoveries(recap: Wrapped.Recap) {
    val n = recap.newArtists.size
    Kicker(AppText.t("Vos découvertes"))
    Headline(if (n == 1) AppText.t("1 nouvel artiste") else AppText.t("{0} nouveaux artistes").replace("{0}", n.toString()), 40)
    Caption(if (recap.month != null) AppText.t("Écoutés pour la première fois ce mois-ci.") else AppText.t("Écoutés pour la première fois cette année."))
    Spacer(Modifier.height(18.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        recap.newArtists.take(8).forEachIndexed { index, name ->
            val appear = rememberAppear(index * 90L)
            Text(name, localize = false, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer { alpha = appear }.clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = .18f)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

/** Ketmix's two rings, then how many transitions it mixed. */
@Composable
private fun WrappedKetmix(recap: Wrapped.Recap) {
    val appear = rememberAppear()
    Kicker("Ketmix (BETA)")
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 150.dp, height = 96.dp)) {
            val r = size.height * .42f
            val gap = r * .9f * appear
            drawCircle(Color.White.copy(alpha = .9f), r, Offset(size.width / 2 - gap / 2 - r * .3f, size.height / 2), style = Stroke(6.dp.toPx()))
            drawCircle(Color(0xFF7FE0FF), r, Offset(size.width / 2 + gap / 2 + r * .3f, size.height / 2), style = Stroke(6.dp.toPx()))
        }
    }
    Headline(if (recap.ketmixMixes == 1) AppText.t("1 transition mixée") else
        AppText.t("{0} transitions mixées").replace("{0}", "%,d".format(recap.ketmixMixes).replace(',', ' ')), 40)
    Caption(AppText.t("Ketmix a enchaîné vos titres sans un blanc."))
}

@Composable
private fun WrappedSummary(recap: Wrapped.Recap) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Headline(AppText.t("Votre récap"), 40)
    Spacer(Modifier.height(16.dp))
    Text("${recap.minutes} min · ${recap.plays} ${AppText.t("écoutes")}", localize = false, color = Color.White, fontSize = 20.sp,
        fontWeight = FontWeight.Bold)
    recap.topArtist?.let { Caption("${AppText.t("Artiste")} : ${it.name}") }
    recap.topTrack?.let { Caption("${AppText.t("Titre")} : ${it.title}") }
    recap.mood?.let { Caption("${AppText.t("Humeur")} : ${AppText.t(it.label)}") }
    if (recap.newArtists.isNotEmpty()) Caption(AppText.t("{0} nouveaux artistes").replace("{0}", recap.newArtists.size.toString()))
    Spacer(Modifier.height(28.dp))
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White)
        .clickable(role = Role.Button, enabled = !sharing) {
            sharing = true
            scope.launch {
                runCatching { WrappedCard.share(context, WrappedCard.render(context, recap, WrappedPage.Summary)) }
                sharing = false
            }
        }.padding(horizontal = 26.dp, vertical = 14.dp).testTag("wrapped-share")) {
        Text(if (sharing) "…" else AppText.t("Partager mon récap"), localize = false, color = Color.Black, fontSize = 16.sp,
            fontWeight = FontWeight.Bold)
    }
}

/** The shareable pictures of the recap, story-sized (9:16) like the lyrics Moment: one per page. */
internal object WrappedCard {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private const val LEFT = 90f

    private suspend fun load(context: Context, url: String?, size: Int = 900): Bitmap? = url?.let {
        runCatching {
            val request = coil.request.ImageRequest.Builder(context).data(it).allowHardware(false).size(size).build()
            (coil.Coil.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    suspend fun render(context: Context, recap: Wrapped.Recap, page: WrappedPage = WrappedPage.Summary): File {
        val cover = load(context, recap.topTrack?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl })
        val pictures = when (page) {
            WrappedPage.Artist -> listOf(load(context, recap.topArtist?.let { a ->
                MediaOverrides.artistPhoto(a.name, null) ?: runCatching { com.ketvoice.app.data.CatalogRepo.artistPicture(a.name, a.catalogId) }.getOrNull()
                    ?: a.cover }))
            WrappedPage.Tracks -> recap.tracks.take(5).map { load(context, ArtworkRepo.coverForTrack(it.first) ?: it.first.coverUrl, 300) }
            WrappedPage.Albums -> recap.albums.take(5).map { load(context, it.cover, 300) }
            else -> emptyList()
        }
        val bitmap = withContext(Dispatchers.Default) { draw(context, recap, page, cover, pictures) }
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        folder.listFiles { file -> file.name.startsWith("wrapped-") }?.forEach { it.delete() }
        val file = File(folder, "wrapped-${page.name.lowercase()}-${System.currentTimeMillis()}.png")
        withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bitmap.recycle()
        return file
    }

    private fun typeface(context: Context, weight: Int): Typeface {
        val inter = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, com.ketvoice.app.R.font.inter_variable) }.getOrNull()
        return Typeface.create(inter ?: Typeface.DEFAULT, weight, false)
    }

    /** One page as a picture: the page's colours (or the cover's), its words, its figures, the address. */
    internal fun draw(context: Context, recap: Wrapped.Recap, page: WrappedPage, cover: Bitmap?, pictures: List<Bitmap?>): Bitmap {
        val bitmap = androidx.core.graphics.createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        val (top, bottom) = WrappedPages.palette(page)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
        })
        // The most played cover, blurred to colour fields, as in the story.
        if (cover != null) canvas.drawBitmap(cover.scale(10, 18).scale(WIDTH, HEIGHT), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 })
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), 0x33000000, 0xB3000000.toInt(), Shader.TileMode.CLAMP)
        })
        val bold = typeface(context, 700)
        val regular = typeface(context, 400)
        fun paint(size: Float, alpha: Int = 255, face: Typeface = bold) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); this.alpha = alpha; textSize = size; typeface = face
        }
        /** Text set within the margins on up to [lines] lines; returns the y under it. */
        fun block(text: String, y: Float, p: TextPaint, lines: Int = 3, spacing: Float = 1.05f): Float {
            val width = (WIDTH - 2 * LEFT).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, spacing).setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            canvas.save(); canvas.translate(LEFT, y); layout.draw(canvas); canvas.restore()
            return y + layout.height
        }
        fun image(b: Bitmap?, left: Float, top: Float, side: Float, radius: Float) {
            b ?: return
            val clip = Path().apply { addRoundRect(RectF(left, top, left + side, top + side), radius, radius, Path.Direction.CW) }
            canvas.withClip(clip) { drawBitmap(b, null, RectF(left, top, left + side, top + side), Paint(Paint.FILTER_BITMAP_FLAG)) }
        }
        canvas.drawText(WrappedPeriod.brand(recap), LEFT, 170f, paint(38f, 220).apply { letterSpacing = .25f })
        var y = block(WrappedPeriod.title(recap), 210f, paint(64f, 235), lines = 1) + 90f
        when (page) {
            WrappedPage.Intro -> {
                image(cover, LEFT, y, WIDTH - 2 * LEFT, 48f)
                y += if (cover != null) WIDTH - 2 * LEFT + 70f else 0f
                y = block(if (recap.month != null) AppText.t("Votre mois en musique, écran par écran.")
                    else AppText.t("Ce que vous avez écouté cette année, écran par écran."), y, paint(52f, 230, regular), 3)
            }
            WrappedPage.Minutes, WrappedPage.Summary -> {
                y = block(AppText.t("Vous avez écouté"), y, paint(46f, 210, regular), 1) + 10f
                y = block("%,d".format(recap.minutes).replace(',', ' '), y, paint(220f), 1, 1f)
                y = block(AppText.t("minutes de musique"), y, paint(64f), 2) + 30f
                y = block(AppText.t("{0} écoutes de {1} titres.").replace("{0}", recap.plays.toString()).replace("{1}", recap.titles.toString()),
                    y, paint(44f, 210, regular), 2) + 80f
                if (page == WrappedPage.Summary) {
                    recap.topArtist?.let {
                        y = block(AppText.t("Artiste numéro 1"), y, paint(38f, 190, regular), 1) + 8f
                        y = block(it.name, y, paint(68f), 1) + 50f
                    }
                    y = block(AppText.t("Titres en boucle"), y, paint(38f, 190, regular), 1) + 12f
                    recap.tracks.take(5).forEachIndexed { index, (track, _) -> y = block("${index + 1}  ${track.title}", y, paint(48f), 1) + 12f }
                }
            }
            WrappedPage.Clock -> {
                val peak = recap.peakHour ?: 0
                y = block(AppText.t("Vous écoutez surtout {0}").replace("{0}", AppText.t(Wrapped.dayPart(peak))), y, paint(78f), 3) + 60f
                val cx = WIDTH / 2f; val inner = 170f; val outer = 420f; val cy = y + outer + 20f
                val longest = recap.clock.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 22f; strokeCap = Paint.Cap.ROUND }
                canvas.drawCircle(cx, cy, inner - 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF })
                // The four parts of the day under the clock, the one listened to most in full white.
                val parts = Wrapped.dayParts(recap.clock)
                val labels = listOf("Matin", "Après-midi", "Soir", "Nuit")
                val peakPart = when (peak) { in 5..11 -> 0; in 12..17 -> 1; in 18..22 -> 2; else -> 3 }
                val column = (WIDTH - 2 * LEFT) / 4
                labels.forEachIndexed { i, label ->
                    val x = LEFT + column * i + column / 2
                    val alpha = if (i == peakPart) 255 else 170
                    canvas.drawText("${(parts[i] * 100).toInt()} %", x, cy + outer + 130f, paint(64f, alpha).apply { textAlign = Paint.Align.CENTER })
                    canvas.drawText(AppText.t(label), x, cy + outer + 190f, paint(34f, alpha, regular).apply { textAlign = Paint.Align.CENTER })
                }
                recap.clock.forEachIndexed { hour, ms ->
                    val a = Math.toRadians(hour * 15.0 - 90.0)
                    val len = (outer - inner) * (.08f + .92f * ms.toFloat() / longest)
                    ray.color = if (hour == peak) 0xFFFFFFFF.toInt() else 0x6BFFFFFF
                    canvas.drawLine(cx + inner * kotlin.math.cos(a).toFloat(), cy + inner * kotlin.math.sin(a).toFloat(),
                        cx + (inner + len) * kotlin.math.cos(a).toFloat(), cy + (inner + len) * kotlin.math.sin(a).toFloat(), ray)
                }
                val label = AppText.t("{0} h").replace("{0}", peak.toString())
                val lp = paint(80f).apply { textAlign = Paint.Align.CENTER }
                canvas.drawText(label, cx, cy + 28f, lp)
            }
            WrappedPage.Artist -> {
                val artist = recap.topArtist
                y = block(AppText.t("Votre artiste numéro 1"), y, paint(46f, 210, regular), 1) + 40f
                val side = 720f
                val photo = pictures.firstOrNull()
                if (photo != null) {
                    val left = (WIDTH - side) / 2
                    val clip = Path().apply { addCircle(left + side / 2, y + side / 2, side / 2, Path.Direction.CW) }
                    canvas.withClip(clip) { drawBitmap(photo, null, RectF(left, y, left + side, y + side), Paint(Paint.FILTER_BITMAP_FLAG)) }
                    y += side + 60f
                }
                y = block(artist?.name ?: "—", y, paint(96f), 2) + 16f
                artist?.let { y = block(playsLabel(it.plays), y, paint(48f, 210, regular), 1) }
            }
            WrappedPage.Tracks, WrappedPage.Albums -> {
                y = block(AppText.t(if (page == WrappedPage.Tracks) "Vos titres en boucle" else "Vos albums"), y, paint(78f), 2) + 50f
                val rows = if (page == WrappedPage.Tracks) recap.tracks.take(5).map { (t, n) -> Triple(t.title, t.artist, n) }
                    else recap.albums.take(5).map { Triple(it.title, it.artist, it.plays) }
                rows.forEachIndexed { index, (title, artist, n) ->
                    canvas.drawText("${index + 1}", LEFT, y + 120f, paint(72f))
                    image(pictures.getOrNull(index), LEFT + 90f, y, 170f, 22f)
                    val textLeft = LEFT + 90f + (if (pictures.getOrNull(index) != null) 200f else 0f)
                    val width = WIDTH - textLeft - LEFT
                    val tp = paint(50f); val sp = paint(36f, 190, regular)
                    canvas.drawText(android.text.TextUtils.ellipsize(title, tp, width, android.text.TextUtils.TruncateAt.END).toString(), textLeft, y + 72f, tp)
                    canvas.drawText(android.text.TextUtils.ellipsize("$artist · ${playsLabel(n)}", sp, width, android.text.TextUtils.TruncateAt.END).toString(),
                        textLeft, y + 128f, sp)
                    y += 210f
                }
            }
            WrappedPage.Mood -> {
                val mood = recap.mood
                y = block(AppText.t("Votre humeur"), y, paint(46f, 210, regular), 1) + 10f
                y = block(AppText.t(mood?.label ?: "—"), y, paint(150f), 1) + 60f
                val cx = WIDTH / 2f; val cy = y + 260f
                canvas.drawCircle(cx, cy, 240f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF })
                canvas.drawText("${mood?.bpm ?: 0}", cx, cy + 40f, paint(140f).apply { textAlign = Paint.Align.CENTER })
                canvas.drawText("BPM", cx, cy + 110f, paint(40f, 210).apply { textAlign = Paint.Align.CENTER; letterSpacing = .2f })
            }
            WrappedPage.Discoveries -> {
                val n = recap.newArtists.size
                y = block(if (n == 1) AppText.t("1 nouvel artiste") else AppText.t("{0} nouveaux artistes").replace("{0}", n.toString()), y, paint(96f), 2) + 50f
                recap.newArtists.take(8).forEach { y = block(it, y, paint(56f, 230, regular), 1) + 18f }
            }
            WrappedPage.Ketmix -> {
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 18f; color = 0xE6FFFFFF.toInt() }
                canvas.drawCircle(WIDTH / 2f - 110f, y + 200f, 170f, ring)
                canvas.drawCircle(WIDTH / 2f + 110f, y + 200f, 170f, ring.apply { color = 0xFF7FE0FF.toInt() })
                y += 460f
                y = block(AppText.t("{0} transitions mixées").replace("{0}", "%,d".format(recap.ketmixMixes).replace(',', ' ')), y, paint(96f), 2) + 20f
                y = block(AppText.t("Ketmix a enchaîné vos titres sans un blanc."), y, paint(48f, 210, regular), 3)
            }
        }
        canvas.drawText("ketvoice.vercel.app", LEFT, HEIGHT - 110f, paint(36f, 200, regular))
        return bitmap
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, AppText.t("Mon Ketvoice Wrapped") + " · https://ketvoice.vercel.app")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, AppText.t("Partager mon récap")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
