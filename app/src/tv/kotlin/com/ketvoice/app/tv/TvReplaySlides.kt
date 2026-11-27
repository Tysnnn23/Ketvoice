package com.ketvoice.app.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.sub.WrappedPeriod
import com.ketvoice.app.ui.sub.recapClipStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * « Replay » (a month) or « Wrapped » (the year) on the TV, full screen: big slides that move on by themselves, ◀ ▶ to
 * go back and forth, OK to hold a slide (on the last one, OK plays the titles), the play key for the soundtrack (the
 * best part of the top titles, outside the queue, as on the phone). Back leaves. No share card on a TV.
 */
@Composable
fun TvReplayPage(route: TvRoute.Replay) {
    val data by produceState<TvReplayData?>(null, route) {
        value = withContext(Dispatchers.Default) { runCatching { TvReplayPlan.dataForTest?.invoke(route.month) ?: tvReplayData(route.month) }.getOrNull() }
        value = value?.let { it.copy(photos = artistPhotos(it.recap)) }
    }
    val shown = data
    Box(Modifier.fillMaxSize().background(TvDesign.Ink.page).testTag("tv-replay")) {
        when {
            shown == null -> Unit
            shown.isEmpty -> ReplayEmpty()
            else -> ReplaySlides(shown)
        }
    }
}

@Composable
private fun ReplayEmpty() {
    Box(Modifier.fillMaxSize().pageItem("replay", "empty", first = true).focusable(), contentAlignment = Alignment.Center) {
        TvEmpty("Pas encore de Replay", "Vos titres les plus écoutés apparaîtront ici dès que vous aurez écouté de la musique.")
    }
}

/** The photos of the top artists (the catalogue's, four at most asked at a time); the recap's covers stand in for the others. */
private suspend fun artistPhotos(recap: Wrapped.Recap): Map<String, String> = kotlinx.coroutines.coroutineScope {
    recap.artists.take(TvReplayPlan.TOP).map { a ->
        async { runCatching { CatalogRepo.artistPicture(a.name, a.catalogId) }.getOrNull()?.let { a.name.lowercase() to it } }
    }.mapNotNull { it.await() }.toMap()
}

@Composable
private fun ReplaySlides(data: TvReplayData) {
    val slides = remember(data) { TvReplayPlan.slides(data) }
    val index = remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    val sound = rememberReplaySound(slides, data.recap)
    LaunchedEffect(index.intValue) { sound.show(index.intValue) }
    LaunchedEffect(index.intValue, held) {
        if (held || index.intValue >= slides.lastIndex) return@LaunchedEffect
        delay(TvReplayPlan.SLIDE_MS)
        index.intValue = TvReplayPlan.next(index.intValue, slides.size)
    }
    val actions = LocalTvActions.current
    val cover = data.recap.topTrack?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
    Box(Modifier.fillMaxSize().pageItem("replay", "slides", first = true).focusable()
        .onKeyEvent { replayKey(it, index, slides.size, onHold = { held = !held }, onSound = sound::toggle,
            onPlay = { actions.play(data.recap.tracks.map { t -> t.first }) }) }) {
        TvBackdrop(cover, Modifier.fillMaxSize(), veil = .35f)
        SlideTint(slides[index.intValue.coerceIn(slides.indices)])
        SlideContent(slides, index.intValue, data)
        ReplayTop(data.recap, slides.size, index.intValue, held)
        ReplayHint(index.intValue == slides.lastIndex, Modifier.align(Alignment.BottomCenter))
    }
}

private fun replayKey(event: androidx.compose.ui.input.key.KeyEvent, index: MutableIntState, size: Int, onHold: () -> Unit,
                      onSound: () -> Unit, onPlay: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val last = index.intValue >= size - 1
    when (event.key) {
        Key.DirectionRight -> index.intValue = TvReplayPlan.next(index.intValue, size)
        Key.DirectionLeft -> index.intValue = TvReplayPlan.previous(index.intValue)
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (last) onPlay() else onHold()
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> onSound()
        else -> return false
    }
    return true
}

/** The soundtrack of the slides: the phone's [com.ketvoice.app.ui.sub.RecapSoundtrack], its switch kept in `wrappedSound`. */
private class ReplaySound(private val track: com.ketvoice.app.ui.sub.RecapSoundtrack) {
    fun open(muted: Boolean) = track.open(0, muted)
    fun close() = track.close()
    fun show(index: Int) = track.show(index)
    fun toggle() {
        val muted = !track.muted
        AppSettings.wrappedSound.value = !muted
        SettingsStore.save()
        track.mute(muted)
    }
}

@Composable
private fun rememberReplaySound(slides: List<TvSlide>, recap: Wrapped.Recap): ReplaySound {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val sound = remember(recap) {
        ReplaySound(com.ketvoice.app.ui.sub.RecapSoundtrack(scope, TvReplayPlan.clips(slides, recap), com.ketvoice.app.ui.sub.ListenerPlayer,
            { com.ketvoice.app.ui.sub.ExoClipDeck(context, scope) }, ::recapClipStart))
    }
    DisposableEffect(sound) {
        sound.open(!AppSettings.wrappedSound.value)
        onDispose { sound.close() }
    }
    return sound
}

@Composable
private fun SlideTint(slide: TvSlide) {
    val (top, bottom) = TvReplayPlan.palette(slide)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(top).copy(alpha = .78f), Color(bottom).copy(alpha = .50f)))))
}

@Composable
private fun SlideContent(slides: List<TvSlide>, index: Int, data: TvReplayData) {
    val tier = tvTier()
    val still = Motion.reduced
    AnimatedContent(index.coerceIn(slides.indices), Modifier.fillMaxSize(), transitionSpec = {
        val forward = targetState > initialState
        val enter = if (still) fadeIn(tween(0)) else if (tier == TvTier.Light) fadeIn(tween(350))
            else fadeIn(tween(520)) + slideInHorizontally(tween(620)) { w -> (if (forward) w else -w) / 12 }
        enter togetherWith fadeOut(tween(if (still) 0 else 260))
    }, label = "tv-replay-slide") { shown ->
        Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 84.dp), verticalArrangement = Arrangement.Center) {
            SlideBody(slides[shown], data)
        }
    }
}

@Composable
private fun ColumnScope.SlideBody(slide: TvSlide, data: TvReplayData) {
    when (slide) {
        TvSlide.Intro -> IntroSlide(data.recap)
        TvSlide.Minutes -> MinutesSlide(data.recap)
        TvSlide.Artists -> ArtistsSlide(data)
        TvSlide.Titles -> TitlesSlide(data.recap)
        TvSlide.Genres -> GenresSlide(data)
        TvSlide.Hours -> HoursSlide(data.recap)
        TvSlide.Summary -> SummarySlide(data.recap)
    }
}

private object SlideType {
    val giant get() = TextStyle(fontFamily = Ket27.font, fontWeight = FontWeight.Bold, fontSize = 112.sp, lineHeight = 116.sp, letterSpacing = (-3).sp)
    val headline get() = TextStyle(fontFamily = Ket27.font, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-1).sp)
    val kicker get() = TextStyle(fontFamily = Ket27.font, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 2.4.sp)
}

private fun number(n: Long): String = java.text.NumberFormat.getIntegerInstance(WrappedPeriod.locale()).format(n)

@Composable
private fun Kicker(text: String, localize: Boolean = true) {
    Text(if (localize) AppText.t(text).uppercase() else text, localize = false, style = SlideType.kicker, color = Color.White.copy(alpha = .75f))
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun IntroSlide(recap: Wrapped.Recap) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Kicker(if (recap.month != null) "Votre Replay" else "Votre récap")
            Text(WrappedPeriod.title(recap), localize = false, style = SlideType.headline, color = Color.White, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("replay-title"))
            Spacer(Modifier.height(16.dp))
            Text(if (recap.month != null) "Votre mois en musique, écran par écran." else "Ce que vous avez écouté cette année, écran par écran.",
                style = TvDesign.Type.body, color = Color.White.copy(alpha = .8f))
        }
        Spacer(Modifier.width(40.dp))
        CoverMosaic(recap.tracks.map { it.first }.take(4))
    }
}

@Composable
private fun CoverMosaic(tracks: List<Track>) {
    val covers = tracks.map { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        covers.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { TvCover(it, 150.dp, RoundedCornerShape(TvDesign.Radius.card)) }
            }
        }
    }
}

@Composable
private fun MinutesSlide(recap: Wrapped.Recap) {
    Kicker("Vous avez écouté")
    Text(number(recap.minutes), localize = false, style = SlideType.giant, color = Color.White, modifier = Modifier.testTag("replay-minutes"))
    Text("minutes de musique", style = SlideType.headline.copy(fontSize = 34.sp, lineHeight = 40.sp), color = Color.White)
    Spacer(Modifier.height(20.dp))
    val hours = TvReplayPlan.hours(recap.minutes)
    if (hours > 0) Text(AppText.t("Soit {0} heures sans couper le son.").replace("{0}", number(hours)), localize = false,
        style = TvDesign.Type.row, color = Color.White.copy(alpha = .85f))
    Text(AppText.t("{0} écoutes de {1} titres.").replace("{0}", number(recap.plays.toLong())).replace("{1}", number(recap.titles.toLong())),
        localize = false, style = TvDesign.Type.body, color = Color.White.copy(alpha = .7f))
}

@Composable
private fun ArtistsSlide(data: TvReplayData) {
    Kicker("Vos artistes")
    // Five columns share the width: the first artist's photo is the largest.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        data.recap.artists.take(TvReplayPlan.TOP).forEachIndexed { i, artist ->
            ArtistColumn(i, artist, data.photos[artist.name.lowercase()], Modifier.weight(1f))
        }
        repeat(TvReplayPlan.TOP - data.recap.artists.size.coerceAtMost(TvReplayPlan.TOP)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ArtistColumn(rank: Int, artist: Wrapped.Artist, photo: String?, modifier: Modifier) {
    // A fifth of the slide's 768 dp each: the largest photo still fits its column, so it stays round.
    val side = if (rank == 0) 132.dp else 104.dp
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TvCover(photo ?: artist.cover, side, CircleShape, placeholder = com.ketvoice.app.ui.common.KetIcons.Account)
        Spacer(Modifier.height(14.dp))
        Text("${rank + 1}", localize = false, style = TvDesign.Type.title, color = Color.White)
        Text(artist.name, localize = false, style = TvDesign.Type.row, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(plays(artist.plays), localize = false, style = TvDesign.Type.caption, color = Color.White.copy(alpha = .7f))
    }
}

private fun plays(n: Int): String = if (n == 1) AppText.t("1 écoute") else AppText.t("{0} écoutes").replace("{0}", number(n.toLong()))

@Composable
private fun TitlesSlide(recap: Wrapped.Recap) {
    val top = recap.tracks.take(TvReplayPlan.TOP)
    Kicker("Vos titres en boucle")
    Row(verticalAlignment = Alignment.CenterVertically) {
        top.firstOrNull()?.let { (t, _) -> TvCover(ArtworkRepo.coverForTrack(t) ?: t.coverUrl, 260.dp, RoundedCornerShape(TvDesign.Radius.panel)) }
        Spacer(Modifier.width(44.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { top.forEachIndexed { i, (t, n) -> TitleLine(i, t, n) } }
    }
}

@Composable
private fun TitleLine(rank: Int, track: Track, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${rank + 1}", localize = false, style = TvDesign.Type.title, color = Color.White, modifier = Modifier.width(44.dp))
        TvCover(ArtworkRepo.coverForTrack(track) ?: track.coverUrl, 52.dp, RoundedCornerShape(8.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.width(380.dp)) {
            Text(track.title, localize = false, style = TvDesign.Type.row, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist + " · " + plays(count), localize = false, style = TvDesign.Type.caption, color = Color.White.copy(alpha = .7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GenresSlide(data: TvReplayData) {
    Kicker("Vos genres")
    Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        data.genres.forEachIndexed { i, g -> GenreBar(g.label, g.percent, i == 0) }
    }
}

@Composable
private fun GenreBar(label: String, percent: Int, first: Boolean) {
    val still = Motion.reduced || tvTier() == TvTier.Light
    val grow = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (first) TvDesign.Type.title else TvDesign.Type.row, color = Color.White, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$percent %", localize = false, style = TvDesign.Type.row, color = Color.White.copy(alpha = .8f))
    }
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)).drawBehind {
        drawRoundRect(Color.White, size = Size(size.width * (percent / 100f).coerceIn(.02f, 1f) * grow.value, size.height),
            cornerRadius = CornerRadius(size.height / 2))
    })
}

@Composable
private fun HoursSlide(recap: Wrapped.Recap) {
    val peak = recap.peakHour ?: return
    Kicker("Votre horloge d'écoute")
    Text(AppText.t("Vous écoutez surtout {0}").replace("{0}", AppText.t(Wrapped.dayPart(peak))), localize = false,
        style = SlideType.headline, color = Color.White)
    Spacer(Modifier.height(10.dp))
    Text(AppText.t("Votre heure : {0} h").replace("{0}", peak.toString()), localize = false, style = TvDesign.Type.row,
        color = Color.White.copy(alpha = .8f))
    Spacer(Modifier.height(30.dp))
    ClockBars(TvReplayPlan.clockBars(recap.clock), peak)
}

@Composable
private fun ClockBars(bars: List<Float>, peak: Int) {
    Box(Modifier.fillMaxWidth().height(150.dp).drawBehind {
        val gap = 6.dp.toPx()
        val w = (size.width - gap * 23) / 24
        bars.forEachIndexed { h, f ->
            val bh = (size.height - 22.dp.toPx()) * f.coerceAtLeast(.03f)
            val color = if (h == peak) Color.White else Color.White.copy(alpha = .38f)
            drawRoundRect(color, Offset(h * (w + gap), size.height - 22.dp.toPx() - bh), Size(w, bh), CornerRadius(w / 3))
        }
    }) {
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { Text("$it h", localize = false, style = TvDesign.Type.caption, color = Color.White.copy(alpha = .6f)) }
        }
    }
}

@Composable
private fun SummarySlide(recap: Wrapped.Recap) {
    Kicker(WrappedPeriod.title(recap).uppercase(), localize = false)
    Text(if (recap.month != null) "Merci pour ce mois en musique" else "Merci pour cette année en musique",
        style = SlideType.headline, color = Color.White)
    Spacer(Modifier.height(30.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Figure("Minutes", number(recap.minutes), Modifier.weight(1f))
        Figure("Artiste", recap.topArtist?.name ?: "—", Modifier.weight(1f))
        Figure("Titre", recap.topTrack?.title ?: "—", Modifier.weight(1f))
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = TvDesign.Type.caption, color = Color.White.copy(alpha = .7f))
        Text(value, localize = false, style = TvDesign.Type.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The bars of the slides (the running one fills in the draw phase only), the period's name and a held mark. */
@Composable
private fun ReplayTop(recap: Wrapped.Recap, count: Int, index: Int, held: Boolean) {
    val progress = remember { Animatable(0f) }
    val running = !held && !Motion.reduced && tvTier() != TvTier.Light && index < count - 1
    LaunchedEffect(index, running) {
        progress.snapTo(0f)
        if (running) progress.animateTo(1f, tween(TvReplayPlan.SLIDE_MS.toInt(), easing = LinearEasing))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = TvDesign.Safe.horizontal, vertical = TvDesign.Safe.vertical)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .28f)).drawBehind {
                    val fill = when { i < index -> 1f; i == index -> if (running) progress.value else 1f; else -> 0f }
                    if (fill > 0f) drawRect(Color.White, size = size.copy(width = size.width * fill))
                })
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(WrappedPeriod.brand(recap), localize = false, style = SlideType.kicker, color = Color.White, modifier = Modifier.weight(1f))
            if (held) Text("En pause", style = TvDesign.Type.caption, color = Color.White.copy(alpha = .8f), modifier = Modifier.testTag("replay-held"))
        }
    }
}

@Composable
private fun ReplayHint(last: Boolean, modifier: Modifier) {
    Text(if (last) "OK : écouter ces titres · ◀ : revenir · ⏯ : son" else "◀ ▶ : diapositives · OK : pause · ⏯ : son",
        style = TvDesign.Type.caption, color = Color.White.copy(alpha = .72f),
        modifier = modifier.padding(bottom = TvDesign.Safe.vertical).background(Color.Black.copy(alpha = .3f), RoundedCornerShape(TvDesign.Radius.pill))
            .padding(horizontal = 18.dp, vertical = 8.dp).testTag("replay-hint"))
}
