package com.ketvoice.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LyricsDuet
import com.ketvoice.app.data.LyricsOffsets
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.LyricsTranslation
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.TranslationLanguages
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.theme.CoverLegibility
import com.ketvoice.app.ui.player.LyricBlurEffect
import com.ketvoice.app.ui.player.LyricsBlur
import com.ketvoice.app.ui.player.LyricsCascade
import com.ketvoice.app.ui.player.LyricsMotion
import com.ketvoice.app.ui.player.LyricsPresentation
import com.ketvoice.app.ui.player.LyricsWords
import com.ketvoice.app.ui.player.drawFlatWord
import com.ketvoice.app.ui.player.drawHeldGlow
import com.ketvoice.app.ui.player.drawLiftedWord
import com.ketvoice.app.ui.player.heldNow
import com.ketvoice.app.ui.player.karaokeInk
import com.ketvoice.app.ui.player.karaokeMasks
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Where lyrics come from: the shared `LyricsRepo` (sources, cache, downloads, the listener's files); tests give fixtures. */
interface LyricsFeed {
    val state: StateFlow<LyricsRepo.State>
    fun load(track: Track, refresh: Boolean = false)
}

object RepoLyricsFeed : LyricsFeed {
    override val state: StateFlow<LyricsRepo.State> get() = LyricsRepo.state
    override fun load(track: Track, refresh: Boolean) {
        runCatching { LyricsOffsets.init(android.content.Context.app) }
        runCatching { com.ketvoice.app.data.LocalLyrics.init(android.content.Context.app) }
        LyricsRepo.load(track, force = refresh, refresh = refresh)
    }
}

val LocalLyricsFeed = staticCompositionLocalOf<LyricsFeed> { RepoLyricsFeed }

/**
 * The colour of the sung words (« Couleur des mots chantés »). Over the full player's dark field it is the app's
 * [com.ketvoice.app.theme.LyricsLook.sung]; in the panel, which follows the theme, « Blanc » stays the theme's ink and
 * the other colours are made readable (AA) on the panel's ground and cards.
 */
@Composable
internal fun rememberDeskSungInk(track: Track, full: Boolean): Color {
    val ket = LocalKet.current
    val mode = AppSettings.lyricsSung.value
    val cover = if (mode == com.ketvoice.app.theme.LyricsLook.SUNG_COVER) rememberCoverColors(CoverSources.track(track))?.second else null
    return deskSungInk(mode, full, ket.label, ket.accent, ket.ground, ket.card, cover, AppSettings.lyricsSungArgb.value)
}

internal fun deskSungInk(mode: Int, full: Boolean, label: Color, accent: Color, ground: Color, card: Color, cover: Color?, custom: Long): Color {
    if (full) return com.ketvoice.app.theme.LyricsLook.sung(mode, accent, cover, custom)
    val picked = when (mode) {
        com.ketvoice.app.theme.LyricsLook.SUNG_ACCENT -> accent
        com.ketvoice.app.theme.LyricsLook.SUNG_COVER -> cover
        com.ketvoice.app.theme.LyricsLook.SUNG_CUSTOM -> Color(custom.toInt())
        else -> null
    } ?: return label
    return CoverLegibility.readableAccent(picked, ground.copy(alpha = 1f), card)
}

/** The two looks of Cascade: the right panel (follows the theme) and the full player (white on the cover's light). */
enum class LyricsLook(val size: Int, val lineHeight: Int, val gap: Int) { Panel(22, 29, 16), Full(34, 43, 24) }

/** The time the lyrics follow: the player's position, carried on frame by frame between its ticks while it plays. */
@Composable
internal fun rememberLyricsClock(): androidx.compose.runtime.State<Long> {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val clock = remember { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(state.positionMs, state.isPlaying, state.current?.id) {
        val base = state.positionMs
        clock.longValue = base
        if (!state.isPlaying) return@LaunchedEffect
        val start = withFrameNanos { it }
        // The next tick comes within a quarter second; never run on more than a second past the last one.
        while (true) withFrameNanos { clock.longValue = base + ((it - start) / 1_000_000).coerceAtMost(1_000) }
    }
    return clock
}

/** The right panel: Cascade for the title playing, or a quiet word without one. */
@Composable
fun LyricsPanel() {
    val ket = LocalKet.current
    val state by LocalPlayer.current.state.collectAsState()
    val track = state.current
    Box(Modifier.fillMaxSize().testTag("panel:lyrics")) {
        if (track == null) Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(60.dp))
            Icon(KetIcons.Lyrics, null, Modifier.size(34.dp), tint = ket.tertiary)
            Spacer(Modifier.height(14.dp))
            KText("Paroles", Ket27Desk.Type.headline)
        } else androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            CascadeLyrics(track, LyricsLook.Panel, Modifier.weight(1f).fillMaxHeight())
            // The voice level at the right of the lyrics, as on the phone.
            Box(Modifier.fillMaxHeight().padding(end = 12.dp), contentAlignment = Alignment.Center) { DeskVoiceSlider(track, onDark = ket.dark) }
        }
    }
}

/**
 * Cascade, as on the phone: the line sung is lit word by word (a held note glows), the others step back, the block
 * follows in a wave, the other singer of a duet sits on the right, the pronunciation and the translation go under
 * the lines. A click on a line plays from it; scrolling stops the following until « Suivre la lecture » or a few
 * seconds of rest.
 */
@Composable
fun CascadeLyrics(track: Track, look: LyricsLook, modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val feed = LocalLyricsFeed.current
    val full = look == LyricsLook.Full
    LaunchedEffect(track.id) { feed.load(track) }
    val repo by feed.state.collectAsState()
    val lyrics = if (repo.trackId == track.id) repo else LyricsRepo.State(loading = true, trackId = track.id)
    val offsets by LyricsOffsets.values.collectAsState()
    val offset = offsets[LyricsOffsets.key(track.id)] ?: 0
    val clock = rememberLyricsClock()
    val now = LyricsOffsets.adjustedPosition(clock.value, offset)
    val active = lyrics.indexAt(now)
    val across = remember(lyrics.lines, track.title, track.artist) { LyricsDuet.sides(lyrics.lines, track.title, track.artist) }
    val romanizationOn = AppSettings.lyricsRomanization.value
    val romanized by produceState<List<String?>>(emptyList(), lyrics.lines, romanizationOn) {
        value = if (!romanizationOn || lyrics.lines.isEmpty()) emptyList() else withContext(Dispatchers.Default) { RomanCache.lines(lyrics.lines.map { it.text }) }
    }
    var translating by remember(track.id) { mutableStateOf(false) }
    val translated = remember(track.id, lyrics.lines) { mutableStateMapOf<Int, String>() }
    var noTranslation by remember(track.id) { mutableStateOf(false) }
    val target = TranslationLanguages.code(AppSettings.translateLanguage.value)
    LaunchedEffect(translating, lyrics.lines, target) {
        translated.clear(); noTranslation = false
        LyricsTranslation.showing(track.id, translating && lyrics.lines.isNotEmpty())
        if (!translating || lyrics.lines.isEmpty()) return@LaunchedEffect
        LyricsTranslation.stream(lyrics.lines, target, active) { i, text -> translated[i] = text; LyricsTranslation.translated(track.id, i, text) }
        noTranslation = translated.isEmpty()
    }
    val label = if (full) Color.White else ket.label
    val list = rememberLazyListState()
    var following by remember(track.id) { mutableStateOf(true) }
    var lastScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) { following = false; lastScroll = System.nanoTime() } } }
    // Following comes back by itself once the listener has left the lyrics alone for a while.
    LaunchedEffect(following, lastScroll) { if (!following) { delay(4_000); following = true } }
    val reduced = Motion.reduced
    val sung = rememberDeskSungInk(track, full)

    Column(modifier.testTag("lyrics:${look.name.lowercase()}")) {
        LyricsHeader(track, lyrics, translating, { translating = !translating }, full)
        // Windows 2.0: translation, translation under every line, romanisation, floating lyrics, the AI's word timing.
        LyricsToggles(track, lyrics, translating, { translating = !translating }, full)
        if (lyrics.lines.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.TopCenter) {
                KText(if (lyrics.loading) "Recherche des paroles…" else "Aucune parole pour ce titre",
                    if (full) Ket27Desk.Type.title3 else Ket27Desk.Type.subheadline, color = label.copy(alpha = .6f))
            }
            return@Column
        }
        if (noTranslation) KText("Traduction temporairement indisponible", Ket27Desk.Type.caption,
            Modifier.padding(horizontal = 20.dp), color = label.copy(alpha = .6f))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val viewport = with(LocalDensity.current) { maxHeight.toPx() }
            LaunchedEffect(active, following, lyrics.lines, viewport) {
                if (!following) return@LaunchedEffect
                val index = LyricsMotion.targetIndex(active, lyrics.isTimed)
                val anchor = -(viewport * .22f).toInt()
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (reduced || item == null || active < 0) list.scrollToItem(index, if (active < 0) 0 else anchor)
                else list.animateScrollBy((item.offset + anchor).toFloat(), LyricsCascade.SCROLL_SPRING)
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("lyrics-list")
                // The lines fade into the ground at the top and bottom instead of being cut by the edges (as on the phone).
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent { drawContent(); drawRect(LyricsEdgeFade, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn) }
                .pointerInput(Unit) {
                awaitPointerEventScope { while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Scroll) { following = false; lastScroll = System.nanoTime() }
                } }
            }, contentPadding = PaddingValues(start = if (full) 4.dp else 20.dp, end = if (full) 4.dp else 20.dp, top = 12.dp, bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(look.gap.dp)) {
                if (lyrics.isTimed) item("intro") {
                    val first = lyrics.lines.firstOrNull { it.timeMs >= 0 }?.timeMs ?: 0L
                    IntroDots(now, first, label, full)
                }
                itemsIndexed(lyrics.lines, key = { i, l -> "$i-${l.timeMs}" }) { index, line ->
                    val current = index == active || !lyrics.isTimed
                    val other = across.getOrNull(index) == true
                    LyricLineView(line, index, active, current, lyrics.isTimed, now, look, LineInk(label, sung), other, !following,
                        romanized.getOrNull(index), translated[index]?.takeIf { translating && (current || !lyrics.isTimed || com.ketvoice.desktop.lyrics.LyricsPcSettings.translateEvery.value) }) {
                        if (line.timeMs >= 0) { player.seekTo(LyricsOffsets.seekPosition(line.timeMs, offset)); following = true }
                    }
                }
            }
            if (!following && lyrics.isTimed) FollowCapsule(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), full) { following = true }
        }
    }
}

/** « Source des paroles » with the romanization switch at the top of its menu, and the translation button. */
@Composable
private fun LyricsHeader(track: Track, lyrics: LyricsRepo.State, translating: Boolean, onTranslate: () -> Unit, full: Boolean) {
    val ket = LocalKet.current
    val menu = LocalMenu.current
    val feed = LocalLyricsFeed.current
    val tint = if (full) Color.White.copy(alpha = .78f) else ket.secondary
    var at by remember { mutableStateOf(IntOffset.Zero) }
    Row(Modifier.fillMaxWidth().padding(start = if (full) 0.dp else 14.dp, end = if (full) 0.dp else 10.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val source = lyrics.source.ifBlank { MusicApi.sourceName(AppSettings.lyricsSource.value) }
        val (hover, hovered) = rememberHover()
        Row(Modifier.clip(RoundedCornerShape(7.dp)).background(if (hovered) (if (full) Color.White.copy(.12f) else ket.hover) else Color.Transparent)
            .hoverable(hover).onGloballyPositioned { val p = it.positionInWindow(); at = IntOffset(p.x.toInt(), p.y.toInt() + it.size.height + 4) }
            .clickable {
                val romanization = AppSettings.lyricsRomanization
                val current = AppSettings.lyricsSource.value
                val sources = listOf(MusicApi.SRC_AUTO) + MusicApi.lyricSources + MusicApi.SRC_OFF
                menu.open(listOf(MenuItem("Romanisation", if (romanization.value) KetIcons.Check else null, "lyrics-romanization") {
                    romanization.value = !romanization.value
                }, MenuItem("Éditeur de paroles", KetIcons.Lyrics, "lyrics-open-editor") { LyricsEditorDesk.open(track) }, MenuItem("-")) +
                    perTitleItems(track) { LyricsRepo.load(track, force = true) } + sources.map { key ->
                    MenuItem(MusicApi.sourceName(key), if (key == current) KetIcons.Check else null, "lyrics-source:$key") {
                        AppSettings.lyricsSource.value = key; feed.load(track, refresh = true)
                    }
                }, at)
            }.padding(horizontal = 6.dp, vertical = 4.dp).testTag("lyrics-source"), verticalAlignment = Alignment.CenterVertically) {
            KText(source, Ket27Desk.Type.caption, color = tint, translate = true, weight = FontWeight.Medium)
            Icon(com.ketvoice.desktop.ui.theme.DeskGlyphs.ChevronDown, AppText.t("Source des paroles"), Modifier.padding(start = 4.dp).size(10.dp), tint = tint)
        }
        Spacer(Modifier.weight(1f))
        if (lyrics.lines.isNotEmpty()) KetIconButton(KetIcons.Globe, "Traduire les paroles", onTranslate, active = translating, size = 28.dp, glyph = 15.dp,
            tint = if (full && !translating) Color.White.copy(alpha = .78f) else Color.Unspecified, modifier = Modifier.testTag("lyrics-translate"))
    }
}

/** The ink of a line: [label] for the lines and the words still to sing, [sung] for the words sung. */
internal class LineInk(val label: Color, val sung: Color)

/** A line's text style: the look's size scaled by « Taille du texte des paroles », the lyrics' font and alignment. */
internal fun lyricStyle(look: LyricsLook, align: TextAlign): TextStyle {
    val k = com.ketvoice.app.theme.LyricsLook.sizeScale()
    return TextStyle(fontSize = (look.size * k).sp, lineHeight = (look.lineHeight * k).sp,
        fontWeight = if (look == LyricsLook.Full) FontWeight.Black else FontWeight.Bold,
        letterSpacing = (-.3).sp, textAlign = align, fontFamily = com.ketvoice.app.theme.LyricsLook.font())
}

/**
 * 8.3 « Flou des paroles »: the radius, in dp and animated with the line's dimming, of a line [distance] lines from the
 * sung one; nothing while the listener scrolls, under reduced motion or the performance mode, or at « Intensité du flou » 0.
 */
@Composable
private fun rememberLineBlur(distance: Int, timed: Boolean, browsing: Boolean, reduced: Boolean): androidx.compose.runtime.State<Float> =
    animateFloatAsState(LyricsBlur.radiusDp(distance, AppSettings.lyricsBlur.value, timed, browsing, reduced,
        AppSettings.highPerformanceMode.value, supported = true, level = com.ketvoice.app.theme.LyricsLook.blur()),
        if (reduced) snap() else tween(LyricsBlur.FADE_MS), label = "lyric-blur")

@Composable
private fun LyricLineView(line: MusicApi.LyricLine, index: Int, active: Int, current: Boolean, timed: Boolean, now: Long, look: LyricsLook,
                          ink: LineInk, other: Boolean, browsing: Boolean, romanized: String?, translation: String?, onClick: () -> Unit) {
    val label = ink.label
    val reduced = Motion.reduced
    val distance = if (active < 0) index + 1 else kotlin.math.abs(index - active)
    val alpha by animateFloatAsState(if (current) 1f else LyricsCascade.INACTIVE_ALPHA, if (reduced) snap() else tween(320))
    val scale by animateFloatAsState(if (current || !timed) 1f else LyricsCascade.INACTIVE_SCALE, if (reduced) snap() else Motion.Springs.SMOOTH.motion())
    val blur = rememberLineBlur(distance, timed, browsing, reduced)
    val blurEffect = remember { LyricBlurEffect() }
    // The wave: when the sung line changes, the lines below follow it one after the other.
    val wave = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (reduced || !timed || index < active) return@LaunchedEffect
        wave.snapTo(LyricsCascade.waveStartDp(distance))
        delay(LyricsCascade.waveDelayMs(distance))
        wave.animateTo(0f, LyricsCascade.WAVE_SPRING)
    }
    val (hover, hovered) = rememberHover()
    val centred = com.ketvoice.app.theme.LyricsLook.centred
    val align = com.ketvoice.app.theme.LyricsLook.align(other) ?: TextAlign.Start
    val inset = LyricsCascade.DUET_INSET_DP.dp
    Column(Modifier.fillMaxWidth().padding(start = if (other) inset else 0.dp, end = if (other) 0.dp else inset * .5f)
        .graphicsLayer {
            translationY = wave.value.dp.toPx()
            scaleX = scale; scaleY = scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (centred) .5f else if (other) 1f else 0f, .5f)
            renderEffect = blurEffect.at(blur.value.dp.toPx())
        }
        .clip(RoundedCornerShape(10.dp)).background(if (hovered && timed) label.copy(alpha = .06f) else Color.Transparent)
        .hoverable(hover).clickable(enabled = timed && line.timeMs >= 0, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 4.dp).testTag("lyric:$index"),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else if (other) Alignment.End else Alignment.Start) {
        val style = lyricStyle(look, align)
        if (current && timed && line.words.size >= 2) WordLine(line, now, style, ink)
        else androidx.compose.material3.Text(line.text, Modifier.fillMaxWidth(), color = label.copy(alpha = if (timed) alpha else .9f), style = style)
        val under = label.copy(alpha = (if (timed) alpha else .9f) * .56f)
        val k = com.ketvoice.app.theme.LyricsLook.sizeScale()
        if (romanized != null) androidx.compose.material3.Text(romanized, Modifier.fillMaxWidth().padding(top = 3.dp).testTag("romanized:$index"), color = under,
            style = style.copy(fontSize = (look.size * .55f * k).sp, lineHeight = (look.lineHeight * .55f * k).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.1).sp))
        if (translation != null) androidx.compose.material3.Text(translation, Modifier.fillMaxWidth().padding(top = 4.dp).testTag("translation:$index"), color = under,
            style = style.copy(fontSize = (look.size * .62f * k).sp, lineHeight = (look.lineHeight * .62f * k).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.1).sp))
    }
}

/**
 * The line being sung, as the phone sings it (8.3, the shared `KaraokeDraw`): a feathered fill moving at one pace
 * through each word, the word lifting and growing a little (« Mouvement des mots »), a held note's letters swelling
 * one after another with a blurred glow behind them. Words that do not cover the line keep the flat fill.
 */
@Composable
internal fun WordLine(line: MusicApi.LyricLine, now: Long, style: TextStyle, ink: LineInk) {
    var layout by remember(line.text, style) { mutableStateOf<TextLayoutResult?>(null) }
    val words = remember(line) { LyricsPresentation.aligned(line) }
    val lifting = remember(line, words) { !Motion.reduced && LyricsWords.covers(line.text, words.map { it.text }) }
    val masks = remember(layout, line, words) { layout?.let { karaokeMasks(it, line.text, words) }.orEmpty() }
    val pending = ink.label.copy(alpha = .40f)
    val motion = com.ketvoice.app.theme.LyricsLook.motion()
    val glowLayer = lifting && !AppSettings.highPerformanceMode.value
    val glowEffect = remember { LyricBlurEffect() }
    Box(Modifier.fillMaxWidth().semantics { contentDescription = line.text }) {
        androidx.compose.material3.Text(line.text, Modifier.fillMaxWidth(), color = if (lifting) Color.Transparent else pending, style = style, onTextLayout = { layout = it })
        if (glowLayer && masks.any { it.letters.isNotEmpty() }) {
            val holding = heldNow(masks, now)
            Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = if (holding) glowEffect.at(LyricsWords.GLOW_RADIUS_DP.dp.toPx()) else null }) {
                val measured = layout ?: return@Canvas
                if (holding) drawHeldGlow(measured, masks, now, karaokeInk(ink.sung, null, false, motion))
            }
        }
        Canvas(Modifier.matchParentSize().testTag("word-line")) {
            val measured = layout ?: return@Canvas
            if (lifting) {
                val k = karaokeInk(ink.sung, pending, shadowGlow = !glowLayer, motion = motion)
                masks.forEach { drawLiftedWord(measured, it, now, k) }
            } else {
                val feather = LyricsWords.FEATHER_DP.dp.toPx()
                masks.forEach { drawFlatWord(measured, it, now, ink.sung, feather) }
            }
        }
    }
}

/** The wait before the first line: three dots that fill one after the other, then leave as the line arrives. */
@Composable
private fun IntroDots(now: Long, first: Long, label: Color, full: Boolean) {
    if (!LyricsMotion.showIntro(now, first)) { Spacer(Modifier.height(1.dp)); return }
    val exit = LyricsMotion.dotExit(now, first)
    val dot = if (full) 12.dp else 9.dp
    Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp).graphicsLayer {
        val s = LyricsMotion.exitScale(exit); scaleX = s; scaleY = s; alpha = LyricsMotion.exitAlpha(exit)
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
    }.testTag("lyrics-intro"), horizontalArrangement = Arrangement.spacedBy(dot * .7f)) {
        repeat(3) { i ->
            val fill = LyricsMotion.dotFill(i, now, first)
            Box(Modifier.size(dot).clip(CircleShape).background(label.copy(alpha = .28f + .72f * fill)))
        }
    }
}

@Composable
private fun FollowCapsule(modifier: Modifier, full: Boolean, onClick: () -> Unit) {
    val ket = LocalKet.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val ground = if (full) Color.White.copy(alpha = if (hovered) .28f else .2f) else if (hovered) ket.accent else ket.raised
    Box(modifier.clip(RoundedCornerShape(50)).background(ground).hoverable(source).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 7.dp).testTag("lyrics-follow")) {
        KText("Suivre la lecture", Ket27Desk.Type.footnote, color = if (full || hovered) Color.White else ket.label, weight = FontWeight.SemiBold)
    }
}

/** The lyrics' top and bottom fade (the app's), made once. */
private val LyricsEdgeFade = androidx.compose.ui.graphics.Brush.verticalGradient(
    0f to Color.Transparent, .07f to Color.Black, .86f to Color.Black, 1f to Color.Transparent)
