package com.ketvoice.app.ui.player

import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.combinedClickable
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ketvoice.app.theme.LocalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val LocalLyricsOffset = staticCompositionLocalOf { 0 }
/** The colour of the sung words (« Paroles à ton goût »; white, as in 8.3, unless the listener picked another). */
internal val LocalLyricsInk = androidx.compose.runtime.compositionLocalOf { Color.White }

/** The sung words' colour for [track]: white, the accent, its cover's colour or the listener's own ([com.ketvoice.app.theme.LyricsLook.sung]). */
@Composable
internal fun rememberSungInk(track: Track): Color {
    val mode = AppSettings.lyricsSung.value
    val cover = com.ketvoice.app.theme.rememberCoverAccent(
        if (mode == com.ketvoice.app.theme.LyricsLook.SUNG_COVER) com.ketvoice.app.data.ArtworkRepo.coverForTrack(track) else null)?.first
    return com.ketvoice.app.theme.LyricsLook.sung(mode, com.ketvoice.app.theme.LocalTheme.current.accent, cover, AppSettings.lyricsSungArgb.value)
}

@Composable
internal fun LyricsPane(track: Track, expanded: Boolean, onSource: () -> Unit,
                        modifier: Modifier = Modifier,
                        onEmptyTap: () -> Unit = {},
                        fontScale: Float = com.ketvoice.app.ui.quest.QuestLayout.lyricsScale(AppSettings.lyricsTextSize.value)) {
    val lyrics by LyricsRepo.state.collectAsState()
    // Lines held for a "Moment" card: a long-press starts the selection, a tap extends it.
    var moment by remember(track.id) { mutableStateOf(listOf<Int>()) }
    val momentHaptic = com.ketvoice.app.ui.motion.rememberHaptics()
    val offsets by LyricsOffsets.values.collectAsState()
    // The voice stage plays the sound a little later (6.0.0 Beta 4): the lyrics follow the sound heard.
    val stageDelay by com.ketvoice.app.stems.VoiceLevel.delayMs.collectAsState()
    val offsetMs = (offsets[remember(track.id) { LyricsOffsets.key(track.id) }] ?: 0) - stageDelay

    // The sung line breathes with the bass (5.9.0): read in the line's layer, never recomposing the list.
    val breathing = !Motion.reduced && !AppSettings.highPerformanceMode.value && Player.state.collectAsState().value.isPlaying
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, breathing) {
        fun update() = com.ketvoice.app.player.SonicMeter.observe("lyrics-breath",
            breathing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); com.ketvoice.app.player.SonicMeter.observe("lyrics-breath", false) }
    }
    val breath = remember { Animatable(0f) }
    LaunchedEffect(breathing) {
        if (!breathing) { breath.animateTo(0f, tween(240)); return@LaunchedEffect }
        androidx.compose.runtime.snapshotFlow { com.ketvoice.app.player.SonicMeter.levels.value.low }.collect { low ->
            breath.animateTo(LyricsWords.breath(low), tween(110))
        }
    }

    val position = Player.positionMs.collectAsState()
    val active by remember(lyrics, offsetMs) { derivedStateOf { lyrics.indexAt(LyricsOffsets.adjustedPosition(position.value, offsetMs)) } }
    val firstLineMs = remember(lyrics.lines) { lyrics.lines.firstOrNull { it.timeMs >= 0 }?.timeMs ?: 0L }
    val intro by remember(firstLineMs, lyrics.isTimed, offsetMs) { derivedStateOf {
        lyrics.isTimed && LyricsMotion.showIntro(LyricsOffsets.adjustedPosition(position.value, offsetMs), firstLineMs)
    } }
    val list = rememberLazyListState()
    var following by remember(track.id) { mutableStateOf(true) }
    // 8.3: the instrumental breaks of 4 s or more, each filled by the intro's dots under the line before it.
    val breaks = remember(lyrics.lines, lyrics.isTimed) { if (lyrics.isTimed) LyricsMotion.breaks(lyrics.lines) else emptyMap() }
    val blurSupported = remember { LyricsBlur.deviceSupported() }
    // « Paroles à ton goût » (2026-11-22): size on top of « Taille des paroles », alignment and the sung words' colour.
    val lyricScale = fontScale * com.ketvoice.app.theme.LyricsLook.sizeScale()
    val centred = com.ketvoice.app.theme.LyricsLook.centred
    val sungInk = rememberSungInk(track)
    val revealed = remember(track.id, lyrics.lines) { mutableSetOf<Int>() }
    val seekEpoch by Player.seekEpoch.collectAsState()
    val reduced = Motion.reduced
    var translationEnabled by remember(track.id) { mutableStateOf(false) }
    // Map granulaire : chaque ligne ne se recompose que lorsque SA traduction
    // arrive. Un Map immutable complet invaliderait toutes les lignes visibles
    // à chaque arrivée — c'était la principale source de lag du suivi traduit.
    val translated = remember(track.id, lyrics.lines) { mutableStateMapOf<Int, String>() }
    var translating by remember(track.id) { mutableStateOf(false) }
    val romanized by rememberRomanized(lyrics.lines, AppSettings.lyricsRomanization.value)
    // 8.0: lines of a featured singer go to the right, as in Apple Music (see LyricsDuet).
    val across = remember(lyrics.lines, track.title, track.artist) { LyricsDuet.sides(lyrics.lines, track.title, track.artist) }
    val target = TranslationLanguages.code(AppSettings.translateLanguage.value)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The lock screen line (6.1) shows the translation while this screen shows it.
    DisposableEffect(track.id) { onDispose { LyricsTranslation.showing(track.id, false) } }
    LaunchedEffect(translationEnabled, lyrics.lines, target) {
        translated.clear()
        LyricsTranslation.showing(track.id, translationEnabled && lyrics.lines.isNotEmpty())
        if (translationEnabled && lyrics.lines.isNotEmpty()) {
            translating = true
            try {
                LyricsTranslation.stream(lyrics.lines, target, active) { index, text ->
                    translated[index] = text
                    LyricsTranslation.translated(track.id, index, text)
                }
                if (translated.isEmpty()) Toast.makeText(context, AppText.t("Traduction temporairement indisponible"), Toast.LENGTH_SHORT).show()
            } finally { translating = false }
        }
    }
    // One coroutine owns initial placement and follow scrolling. Two concurrent
    // scrollToItem calls before first layout can race the lazy-list awaiters.
    LaunchedEffect(track.id) { following = true }
    LaunchedEffect(seekEpoch) { following = true }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    CompositionLocalProvider(LocalLyricsOffset provides offsetMs) {
    BoxWithConstraints(modifier) {
        val viewportPx = with(LocalDensity.current) { maxHeight.toPx() }
        LaunchedEffect(track.id, active, following, expanded, lyrics.lines, viewportPx, reduced, seekEpoch, translationEnabled) {
            if (!following || lyrics.lines.isEmpty()) return@LaunchedEffect
            val targetIndex = LyricsMotion.targetIndex(active, lyrics.isTimed)
            val anchor = -(viewportPx * .18f).toInt()
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (reduced || item == null || active < 0) list.scrollToItem(targetIndex, if (active < 0) 0 else anchor)
            // 8.2: the block follows the song on a spring (about half a second, no overshoot to speak of).
            else list.animateScrollBy((item.offset + anchor).toFloat(), LyricsCascade.SCROLL_SPRING)
        }
        Column(Modifier.fillMaxSize()) {
            if (lyrics.lines.isNotEmpty()) {
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()
                    // Fondu haut/bas : les lignes s'évanouissent dans le fond au lieu
                    // d'être coupées net par les bords de l'écran.
                    //
                    // Le masque DstIn exige un calque offscreen : sans
                    // CompositingStrategy.Offscreen, le GPU dessine le pinceau noir
                    // par-dessus le contenu au lieu de ne transmettre que son canal
                    // alpha, et une frange sombre apparaît le long des bords.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        if (!reduced) {
                            drawRect(brush = LyricsEdgeFade, blendMode = BlendMode.DstIn)
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope { while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Scroll) following = false
                        } }
                    }
                    .pointerInput(Unit) {
                        // Taps that no lyric line consumed: empty space below
                        // or around the text brings the transport back.
                        detectTapGestures(onTap = { onEmptyTap() })
                    },
                    contentPadding = PaddingValues(top = 22.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(25.dp)) {
                    if (lyrics.isTimed) item(key = "intro-${track.id}") {

                        Box(Modifier.height(if (firstLineMs > 0) 36.dp else 0.dp)) {
                            if (intro) IntroDots(firstLineMs)
                        }
                    }
                    run {
                        itemsIndexed(lyrics.lines, key = { i, l -> "$i:${l.timeMs}" }) { index, line ->
                            val current = index == active
                            val below = index > active
                            // 8.3 « Flou des paroles » (the owner lifts the 6.1 rule): the lines away from the sung one are
                            // softly blurred by distance, coming and going with their dimming. The radius is a layer
                            // property: once it has settled, the line's filtered picture is kept and not blurred again.
                            val blur = animateFloatAsState(LyricsBlur.radiusDp(if (active < 0) index + 1 else abs(index - active),
                                AppSettings.lyricsBlur.value, lyrics.isTimed, browsing = !following || moment.isNotEmpty(), reduced,
                                AppSettings.highPerformanceMode.value, blurSupported, com.ketvoice.app.theme.LyricsLook.blur()),
                                tween(Motion.duration(LyricsBlur.FADE_MS)), label = "lyricBlur")
                            val blurEffect = remember { LyricBlurEffect() }
                            val entry = remember(index, track.id, lyrics.lines) { Animatable(if (index in revealed || reduced) 1f else 0f) }
                            LaunchedEffect(index, reduced, revealed) {
                                if (reduced || !revealed.add(index)) entry.snapTo(1f)
                                else {
                                    // Entrée en cascade courte, puis fondu long en
                                    // ease-out : la ligne « se pose » au lieu
                                    // d'apparaître d'un coup.
                                    delay(index.coerceAtMost(3) * 30L)
                                    entry.animateTo(1f, tween(420, easing = CubicBezierEasing(.16f, .84f, .28f, 1f)))
                                }
                            }
                            // 8.2: what is still to come is dimmed and the lines already sung fade softly as they
                            // move up. Read in the line's layer: a fade never recomposes the line.
                            val past = lyrics.isTimed && LyricsCascade.sung(index, active)
                            val lineAlpha = animateFloatAsState(
                                targetValue = if (current || !lyrics.isTimed) 1f
                                    else if (past) LyricsCascade.pastAlpha(active - index) else LyricsCascade.INACTIVE_ALPHA,
                                animationSpec = tween(Motion.duration(if (past) LyricsCascade.PAST_FADE_MS else 320)))
                            val lineColor = if (current && lyrics.isTimed) sungInk else Color.White
                            // Cascade, the only lyrics style: the sung line alone stands forward; the others step
                            // back a little and catch up in a wave, each a few milliseconds after the one above.
                            // With reduced motion the lines keep their size and place: only their colour changes.
                            val lineScale = animateFloatAsState(
                                if (lyrics.isTimed && !current && !reduced) LyricsCascade.INACTIVE_SCALE else 1f,
                                if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 170f),
                                label = "lyricScale")
                            val wave = remember(index, track.id) { Animatable(0f) }
                            LaunchedEffect(active, reduced) {
                                if (reduced) { wave.snapTo(0f); return@LaunchedEffect }
                                if (active < 0 || index < active) { wave.animateTo(0f, LyricsCascade.WAVE_SPRING); return@LaunchedEffect }
                                // 8.2: the lines below hold back a little, then spring into place one after the other,
                                // settling with the faintest give (a dp at most), never a bounce.
                                wave.snapTo(maxOf(wave.value, LyricsCascade.waveStartDp(index - active)))
                                delay(LyricsCascade.waveDelayMs(index - active))
                                wave.animateTo(0f, LyricsCascade.WAVE_SPRING)
                            }
                            val held = index in moment
                            val otherSide = !centred && across.getOrElse(index) { false }
                            val lineModifier = Modifier.fillMaxWidth()
                                // The other singer's lines start a little further in, so that both voices read apart.
                                .padding(start = if (otherSide) LyricsCascade.DUET_INSET_DP.dp else 0.dp)
                                .then(if (held) Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = .14f)) else Modifier)
                                .momentClickable(onClick = {
                                    if (moment.isNotEmpty()) moment = com.ketvoice.app.util.MomentCard.toggle(moment, index)
                                    else if (lyrics.isTimed && line.timeMs >= 0) { Player.seekTo(LyricsOffsets.seekPosition(line.timeMs, offsetMs)); following = true }
                                }, onLongClick = {
                                    momentHaptic(com.ketvoice.app.ui.motion.Haptics.Kind.Tick)
                                    moment = com.ketvoice.app.util.MomentCard.toggle(moment, index)
                                })
                            Column(
                                 lineModifier.graphicsLayer {
                                    alpha = entry.value
                                    translationY = if (reduced) 0f else (1f - entry.value) * 16.dp.toPx() + wave.value.dp.toPx()
                                    val breathe = if (current) 1f + breath.value else 1f
                                    scaleX = lineScale.value * breathe
                                    scaleY = lineScale.value * breathe
                                    transformOrigin = TransformOrigin(if (otherSide) 1f else if (centred) .5f else 0f, .5f)
                                    renderEffect = blurEffect.at(blur.value.dp.toPx())
                                }
                            ) {
                                val nearActive = active < 0 || abs(index - active) <= 1
                                // The fade dims the flat drawing only: a line drawn word by word keeps its own pending ink.
                                androidx.compose.runtime.CompositionLocalProvider(LocalLyricsInk provides sungInk) {
                                    LyricVoices(line, current, lyrics.isTimed, lyricScale, lineColor, nearActive = nearActive, end = otherSide,
                                        fade = { lineAlpha.value })
                                }
                                romanized.getOrNull(index)?.let { Box(Modifier.graphicsLayer { alpha = lineAlpha.value }) {
                                    RomanizedLine(it, Color.White, lyricScale, end = otherSide) } }

                                AnimatedVisibility(
                                    visible = translationEnabled && translated[index] != null && (current || !lyrics.isTimed),
                                    enter = fadeIn(tween(Motion.duration(320))) + expandVertically(tween(Motion.duration(320)), expandFrom = Alignment.Top),
                                    exit = fadeOut(tween(Motion.duration(240))) + shrinkVertically(tween(Motion.duration(240)), shrinkTowards = Alignment.Top)
                                ) {
                                    translated[index]?.let { subtitle ->
                                        SyncedTranslation(
                                            line = line,
                                            text = subtitle,
                                            active = current && lyrics.isTimed,
                                            dimAlpha = if (current || !lyrics.isTimed) .82f else .43f,
                                            end = otherSide
                                        )
                                    }
                                }
                                breaks[index]?.let { pause -> BreakDots(pause, position, offsetMs) }
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lyrics.loading) com.ketvoice.app.ui.common.ActivitySpinner(size = 22.dp, color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text(if (lyrics.loading) "Recherche dans les sources de paroles…" else "Aucune parole disponible pour cette version",
                            color = Color.White.copy(alpha = .6f), style = com.ketvoice.app.theme.Ket27.Type.subheadline)
                        com.ketvoice.app.ui.common.KetTextButton("Choisir une source", onSource, Modifier.padding(top = 6.dp), primary = true, tint = Color.White)
                    }
                }
            }
            AnimatedVisibility(!expanded,
                enter = fadeIn(tween(Motion.duration(280))) + expandVertically(tween(Motion.duration(360))),
                exit = fadeOut(tween(Motion.duration(220))) + shrinkVertically(tween(Motion.duration(300)))) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallCircleButton(onSource, size = 36.dp) { Icon(Icons.Filled.Language, "Sources des paroles", tint = it, modifier = Modifier.size(21.dp)) }
                    Spacer(Modifier.weight(1f))
                    if (!following && lyrics.isTimed) FollowCapsule("Suivre la lecture") { following = true }
                    SmallCircleButton({
                        translationEnabled = !translationEnabled
                    }, active = translationEnabled, size = 36.dp) {
                        if (translating) com.ketvoice.app.ui.common.ActivitySpinner(size = 17.dp, color = it)
                        else Icon(Icons.Filled.Translate, "Traduire les paroles", tint = it, modifier = Modifier.size(21.dp))
                    }
                }
            }
            if (moment.isNotEmpty()) MomentBar(track, moment.mapNotNull { lyrics.lines.getOrNull(it) },
                lyrics.lines.getOrNull((moment.maxOrNull() ?: -1) + 1)?.timeMs ?: -1L, lyrics.isTimed) { moment = emptyList() }
            else if (expanded && !following && lyrics.isTimed) {
                FollowCapsule("Reprendre le suivi", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) { following = true }
            }
        }
        // 6.0.0 Beta 4: the title is separated whole as soon as its lyrics are shown, so that the slider answers at once.
        LaunchedEffect(track.id) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { if (com.ketvoice.app.stems.VoiceSeparation.modelReady) com.ketvoice.app.stems.VoiceSeparation.request(track.id) }
            }
        }
        // 6.0.0: the level of the voice, at the right above the translation button, while the controls are shown
        // (never in full-screen lyrics), on every title (6.0.0 Beta 3: a title is separated on its first touch).
        AnimatedVisibility(!expanded && moment.isEmpty(), Modifier.align(Alignment.BottomEnd).padding(bottom = 60.dp),
            enter = fadeIn(tween(Motion.duration(280))) + androidx.compose.animation.scaleIn(tween(Motion.duration(320)), initialScale = .85f),
            exit = fadeOut(tween(Motion.duration(200))) + androidx.compose.animation.scaleOut(tween(Motion.duration(240)), targetScale = .85f)) {
            VoiceSlider(track.id)
        }
    }
    }
}

/**
 * Back to following the song, as a small capsule of glass over the lyrics (Ket 27): its label in the
 * footnote style (larger on Quest), with a 44 dp target.
 */
@Composable
private fun FollowCapsule(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).testTag("lyrics-follow"),
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = if (com.ketvoice.app.BuildConfig.IS_QUEST) com.ketvoice.app.theme.Ket27.Type.subheadline else com.ketvoice.app.theme.Ket27.Type.footnote,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = .16f)).padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

/** 8.3: the intro's dots, under a line followed by an instrumental break, for as long as the break lasts. */
@Composable
private fun BreakDots(pause: LongRange, position: State<Long>, offsetMs: Int) {
    val resting by remember(pause, offsetMs) { derivedStateOf { LyricsOffsets.adjustedPosition(position.value, offsetMs) in pause } }
    AnimatedVisibility(resting,
        enter = fadeIn(tween(Motion.duration(260))) + expandVertically(tween(Motion.duration(320)), expandFrom = Alignment.Top),
        exit = fadeOut(tween(Motion.duration(200))) + shrinkVertically(tween(Motion.duration(280)), shrinkTowards = Alignment.Top)) {
        Box(Modifier.padding(top = 14.dp).height(36.dp)) { IntroDots(pause.last + 1, pause.first) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.momentClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = com.ketvoice.app.data.AppText.t("Partager ce moment"))

/**
 * The selected lines (up to four) become a picture or, since 5.9.2, a video for a story: the Canvas
 * blurred behind, the words lit in time in a frosted card. Both are shared with a link to the title.
 */
@Composable
private fun MomentBar(track: Track, lines: List<MusicApi.LyricLine>, nextMs: Long, timed: Boolean, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var logo by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(com.ketvoice.app.data.AppSettings.lyricVideoSound.value) }
    val cover = com.ketvoice.app.data.ArtworkRepo.coverForTrack(track) ?: track.coverUrl
    val accent = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first
    val canvas = com.ketvoice.app.data.CanvasRepo.paths.collectAsState().value[track.id]
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("moment-bar")) {
        if (progress != null || failed != null) Text(failed?.let { com.ketvoice.app.data.AppText.t(it) }
            ?: "${com.ketvoice.app.data.AppText.t("Création de la vidéo…")} ${((progress ?: 0f) * 100).toInt()} %", localize = false,
            color = Color.White.copy(alpha = .82f), fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.ketvoice.app.ui.common.GlassCapsuleButton("Image", com.ketvoice.app.ui.common.KetIcons.Share, {
                if (!busy) { busy = true; failed = null
                    scope.launch {
                        try {
                            val file = com.ketvoice.app.util.MomentCard.render(context, track, lines.map { it.text }, accent?.toArgb())
                            com.ketvoice.app.util.MomentCard.share(context, file, track)
                            onClose()
                        } catch (_: Exception) { } finally { busy = false }
                    }
                }
            }, Modifier.weight(1f), prominent = !timed, enabled = !busy, tag = "moment-share", minHeight = 48.dp)
            // The video needs times: lyrics without them only make a picture.
            if (timed) com.ketvoice.app.ui.common.GlassCapsuleButton("Vidéo", com.ketvoice.app.ui.common.KetIcons.Play, {
                if (!busy) { busy = true; failed = null; progress = 0f
                    scope.launch {
                        try {
                            val file = com.ketvoice.app.util.LyricVideo.render(context, track, lines, nextMs, canvas, accent?.toArgb(), logo, sound) { p ->
                                scope.launch(kotlinx.coroutines.Dispatchers.Main) { progress = p }
                            }
                            com.ketvoice.app.util.LyricVideo.share(context, file, track)
                            onClose()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                        } catch (e: Exception) { failed = "Vidéo indisponible sur cet appareil"
                        } finally { busy = false; progress = null }
                    }
                }
            }, Modifier.weight(1f), prominent = true, enabled = !busy, tag = "moment-video", minHeight = 48.dp)
            com.ketvoice.app.ui.common.GlassCapsuleButton("Annuler", null, onClose, Modifier.weight(1f), minHeight = 48.dp, tag = "moment-cancel")
        }
        if (timed) Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Logo Ketvoice dans la vidéo", color = Color.White.copy(alpha = .78f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            com.ketvoice.app.ui.common.LiquidGlassSwitch(logo, { logo = it })
        }
        if (timed) Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Son du morceau dans la vidéo", color = Color.White.copy(alpha = .78f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            com.ketvoice.app.ui.common.LiquidGlassSwitch(sound, {
                sound = it; com.ketvoice.app.data.AppSettings.lyricVideoSound.value = it; com.ketvoice.app.data.SettingsStore.save()
            })
        }
    }
}

@Composable
internal fun LyricVoices(line: MusicApi.LyricLine, active: Boolean, timed: Boolean, fontScale: Float = 1f,
                         color: Color = Color.White, nearActive: Boolean = true, end: Boolean = false,
                         fade: (() -> Float)? = null) {
    val align = com.ketvoice.app.theme.LyricsLook.align(end)
    val voices = remember(line) { LyricsPresentation.split(line) }
    // Only the active line and its immediate neighbours need the transport clock.
    // This keeps the other visible lines cheap while still letting an unfinished
    // line or back finish after the next one starts.
    val clock = remember(line, timed, nearActive) {
        if (timed && nearActive) Player.positionMs else kotlinx.coroutines.flow.flowOf(Long.MIN_VALUE)
    }
    val rawPosition by clock.collectAsState(Long.MIN_VALUE)
    val offset = LocalLyricsOffset.current
    val position = if (rawPosition == Long.MIN_VALUE) rawPosition else LyricsOffsets.adjustedPosition(rawPosition, offset)
    val leadEnd = voices.lead.words.maxOfOrNull { it.endMs } ?: line.timeMs
    val leadSinging = timed && nearActive && position != Long.MIN_VALUE && position < leadEnd + LyricsPresentation.LEAD_TAIL_MS
    Column(Modifier.fillMaxWidth()) {
        if (voices.lead.text.isNotBlank()) {
            if ((active || leadSinging) && voices.lead.words.isNotEmpty())
                KaraokeLine(voices.lead, Modifier.fillMaxWidth(), fontScale, dim = !active, end = end)
            else Text(voices.lead.text, localize = false, color = color,
                fontSize = (33f * fontScale).sp, lineHeight = (41f * fontScale).sp,
                letterSpacing = (-.3).sp, fontWeight = FontWeight.Black, textAlign = align, fontFamily = com.ketvoice.app.theme.LyricsLook.font(),
                modifier = Modifier.fillMaxWidth().then(if (fade != null) Modifier.graphicsLayer { alpha = fade() } else Modifier))
        }
        val leadStart = voices.lead.words.minOfOrNull { it.startMs } ?: line.timeMs
        voices.backs.forEach { back ->
            BackingVocal(back, active, timed, fontScale, nearActive, position, leadStart, leadEnd, end)
        }
    }
}

@Composable
private fun BackingVocal(
    line: MusicApi.LyricLine,
    active: Boolean,
    timed: Boolean,
    fontScale: Float,
    nearActive: Boolean,
    position: Long,
    parentStart: Long,
    parentEnd: Long,
    across: Boolean = false
) {
    // Word timings are optional on backs. Without them, follow the parent line
    // window instead of collapsing to a single instant (which made the row flash).
    val first = line.words.minOfOrNull { it.startMs }
    val last = line.words.maxOfOrNull { it.endMs }
    val start = first ?: parentStart
    val end = when {
        last == null -> maxOf(parentEnd, parentStart + 600L)
        last <= start -> start + 600L
        else -> last
    }
    // Reserve the extra row only 200 ms before the back starts, and keep it until
    // it has actually finished even if the next line already began.
    val visible = !timed || (nearActive && position != Long.MIN_VALUE &&
        position >= start - 200L && position <= end + 500L)
    // 8.3: the back opens under its line, rising a few dp into place and growing from 94 %.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.duration(220))) + expandVertically(tween(Motion.duration(240)), expandFrom = Alignment.Top) +
            androidx.compose.animation.slideInVertically(tween(Motion.duration(280))) { if (Motion.reduced) 0 else it / 4 } +
            androidx.compose.animation.scaleIn(tween(Motion.duration(280)), initialScale = if (Motion.reduced) 1f else .94f,
                transformOrigin = TransformOrigin(if (across) 1f else 0f, 0f)),
        exit = fadeOut(tween(Motion.duration(220))) + shrinkVertically(tween(Motion.duration(220)), shrinkTowards = Alignment.Top)
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 7.dp).testTag("backing-vocal")) {
            // A backing vocal stays a backing vocal; it only sits on the side of the line it belongs to.
            if (timed && line.words.isNotEmpty()) KaraokeLine(line, Modifier.fillMaxWidth(), fontScale, backing = true, end = across)
            else Text(line.text, localize = false, color = Color.White.copy(alpha = .80f),
                fontSize = (24f * fontScale).sp, lineHeight = (31f * fontScale).sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-.2).sp, fontFamily = com.ketvoice.app.theme.LyricsLook.font(),
                textAlign = com.ketvoice.app.theme.LyricsLook.align(across), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun KaraokeLine(line: MusicApi.LyricLine, modifier: Modifier, fontScale: Float = 1f,
                         backing: Boolean = false, dim: Boolean = false, end: Boolean = false) {
    val offset = LocalLyricsOffset.current
    val clock = rememberKaraokeClock(line, offset)
    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }

    // Providers do not always ship word text that concatenates back to the line
    // (extra spaces, CJK segmentation, translated syllables). Re-align it before
    // computing masks, otherwise the highlight drifts onto the wrong glyphs — the
    // "buggy foreign lyrics" symptom. If the mapping is impossible, highlight the
    // whole line instead of lighting random characters.
    val alignedWords = remember(line) { LyricsPresentation.aligned(line) }
    val masks = remember(layout, line, alignedWords) { layout?.let { karaokeMasks(it, line.text, alignedWords) }.orEmpty() }
    // Apple-style words (5.9.0): each word rises a little as it is sung and a held note glows. The
    // words are then drawn by the canvas, dim and lit parts moving together; when the word timing
    // does not cover the line's letters, the line keeps the flat drawing so that nothing disappears.
    val pendingAlpha = if (backing) .36f else if (dim) .30f else .40f
    val lifting = remember(line, alignedWords) { !Motion.reduced && LyricsWords.covers(line.text, alignedWords.map { it.text }) }
    // 8.3: a held note's glow is a blurred copy of its letters (Android 12 and later, outside the performance mode);
    // the layer is blurred only while a held word is in its hold, and holds nothing otherwise.
    val blurGlow = lifting && !backing && android.os.Build.VERSION.SDK_INT >= LyricsBlur.MIN_API && !AppSettings.highPerformanceMode.value
    // « Paroles à ton goût »: the sung words' colour and how much they lift and grow (1 = the 8.3 lyrics).
    val sung = LocalLyricsInk.current
    val motion = com.ketvoice.app.theme.LyricsLook.motion()
    val holding = remember(masks, offset) { derivedStateOf { masks.isNotEmpty() && heldNow(masks, LyricsOffsets.adjustedPosition(clock.longValue, offset)) } }
    Box(modifier) {
        // One shaped layout for both the pending glyphs and the sung overlay.
        // No remeasurement, shifted word copy, scaling or disappearing held glyph.
        Text(line.text, localize = false, color = if (lifting) Color.Transparent else Color.White.copy(
            alpha = pendingAlpha),
            fontSize = ((if (backing) 24 else 33) * fontScale).sp,
            lineHeight = ((if (backing) 31 else 41) * fontScale).sp,
            letterSpacing = (-.3).sp, fontWeight = if (backing) FontWeight.Bold else FontWeight.Black,
            fontFamily = com.ketvoice.app.theme.LyricsLook.font(), textAlign = com.ketvoice.app.theme.LyricsLook.align(end),
            onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth())
        if (blurGlow && masks.any { it.letters.isNotEmpty() }) HeldGlow(layout, masks, clock, offset, holding, sung, motion)
        Canvas(Modifier.matchParentSize()) {
            val measured = layout ?: return@Canvas
            val now = LyricsOffsets.adjustedPosition(clock.longValue, offset)
            val ink = sung.copy(alpha = if (dim) .82f else 1f)
            if (lifting) {
                val k = karaokeInk(ink, Color.White.copy(alpha = pendingAlpha), shadowGlow = !blurGlow, motion = motion)
                masks.forEach { drawLiftedWord(measured, it, now, k) }
            } else {
                val feather = LyricsWords.FEATHER_DP.dp.toPx()
                masks.forEach { drawFlatWord(measured, it, now, ink, feather) }
            }
        }
    }
}

/** The blurred copy of a held note's letters, behind the line; a plain layer holding nothing outside a hold. */
@Composable
private fun BoxScope.HeldGlow(layout: TextLayoutResult?, masks: List<LyricWordMask>, clock: androidx.compose.runtime.LongState,
                              offset: Int, holding: State<Boolean>, sung: Color = Color.White, motion: Float = 1f) {
    val effects = remember { LyricBlurEffect() }
    Canvas(Modifier.matchParentSize().graphicsLayer {
        renderEffect = if (holding.value) effects.at(LyricsWords.GLOW_RADIUS_DP.dp.toPx()) else null
    }) {
        if (!holding.value) return@Canvas
        val measured = layout ?: return@Canvas
        drawHeldGlow(measured, masks, LyricsOffsets.adjustedPosition(clock.longValue, offset), karaokeInk(sung, null, false, motion))
    }
}

/**
 * The transport position a karaoke line draws from, updated on every frame while playing — but only while the
 * line is inside its own time (from just before its first word to [LyricsWords.REST_MS] after its last): before
 * and after, its drawing does not change, so the clock is left alone and the line (and any blur over it) is not
 * drawn again. Crossing from one side to the other without passing through still updates it once.
 */
@Composable
private fun rememberKaraokeClock(line: MusicApi.LyricLine, offset: Int): androidx.compose.runtime.LongState {
    val clock = remember(line) { mutableLongStateOf(Player.currentPositionMs()) }
    val playing by remember { Player.state.map { it.isPlaying }.distinctUntilChanged() }.collectAsState(false)
    val window = remember(line) { LyricsWords.window(line) }
    LaunchedEffect(line, playing, offset) {
        var side = Int.MIN_VALUE
        fun take(raw: Long) {
            val now = LyricsWords.side(LyricsOffsets.adjustedPosition(raw, offset), window)
            if (now == 0 || now != side) clock.longValue = raw
            side = now
        }
        take(Player.currentPositionMs())
        if (playing) while (true) { withFrameNanos { take(Player.currentPositionMs()) } }
        else Player.positionMs.collect { take(it) }
    }
    return clock
}

/** How far into a word, 0..1, at [now]: one constant pace from its start to its end (8.3: no easing inside a word). */
internal fun segmentProgress(now: Long, start: Long, end: Long): Float = when {
    now < start -> 0f
    end <= start -> 1f
    else -> ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

/**
 * The wait before the first line: three dots that fill one after the other, breathe while
 * they wait, then swell and vanish as the line arrives. Position and clock are read while
 * drawing only, so the dots move on every frame without recomposing the lyrics.
 * 8.3: the same dots fill every instrumental break, from [fromMs] (the end of the line before) to [firstLineMs].
 */
@Composable
internal fun IntroDots(firstLineMs: Long, fromMs: Long = 0L) {
    val offset = LocalLyricsOffset.current
    val reduced = Motion.reduced
    val shown = remember { mutableLongStateOf(LyricsOffsets.adjustedPosition(Player.positionMs.value, offset)) }
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(offset, reduced) {
        var anchor = Player.positionMs.value
        var anchorAt = -1L
        var origin = -1L
        while (true) withFrameMillis { now ->
            if (origin < 0) origin = now
            val position = Player.positionMs.value
            if (position != anchor || anchorAt < 0) { anchor = position; anchorAt = now }
            // Between two position reports the title keeps playing: the fill does not move in steps.
            val ahead = if (!reduced && Player.state.value.isPlaying) (now - anchorAt).coerceIn(0L, 500L) else 0L
            shown.longValue = LyricsOffsets.adjustedPosition(anchor + ahead, offset)
            clock.longValue = now - origin
        }
    }
    Canvas(Modifier.padding(start = 6.dp).size(width = 68.dp, height = 36.dp).testTag("intro-dots")) {
        val position = shown.longValue - fromMs
        val span = firstLineMs - fromMs
        val elapsed = clock.longValue
        val exit = if (reduced) 0f else LyricsMotion.dotExit(position, span)
        val enter = if (reduced) 1f else LyricsMotion.enter(elapsed)
        val group = if (reduced) 1f else LyricsMotion.exitScale(exit) * (.6f + .4f * enter)
        val fade = LyricsMotion.exitAlpha(exit) * enter
        val radius = 6.dp.toPx()
        val gap = 20.dp.toPx()
        val middle = Offset(radius * 1.3f + gap, size.height / 2f)
        for (index in 0 until 3) {
            val fill = if (reduced) (if (index < LyricsMotion.litDots(position, span)) 1f else 0f)
                else LyricsMotion.dotFill(index, position, span)
            val breath = if (reduced) 1f else LyricsMotion.breath(elapsed, index)
            drawCircle(Color.White.copy(alpha = ((.26f + .74f * fill) * fade).coerceIn(0f, 1f)),
                radius * group * breath * (.84f + .16f * fill),
                Offset(middle.x + (index - 1) * gap * group, middle.y))
        }
    }
}

@Composable
internal fun LyricsSourceSheet(track: Track, onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(context) { LyricsOffsets.init(context) }
    val offsets by LyricsOffsets.values.collectAsState()
    val offset = offsets[remember(track.id) { LyricsOffsets.key(track.id) }] ?: 0
    var results by remember(track.id) { mutableStateOf<Map<String, MusicApi.ProviderStatus>>(emptyMap()) }
    LaunchedEffect(track.id) {
        coroutineScope {
            MusicApi.lyricSources.forEach { source -> launch {
                val data = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { MusicApi.lyrics(track, source = source) }.getOrNull() }
                val lines = data?.second.orEmpty()
                results = results + (source to MusicApi.ProviderStatus(lines.isNotEmpty(), lines.any { it.timeMs >= 0 }, lines.size,
                    lines.any { it.words.isNotEmpty() }, lines.any { it.syllableTimed }, ExtraLyricsProviders.status[source]))
            } }
        }
    }
    // "Personnalisé" (5.9.1): the listener's own lyrics file for this title, kept and used from then on.
    LaunchedEffect(context) { com.ketvoice.app.data.CustomLyrics.init(context) }
    var custom by remember(track.id) { mutableStateOf(com.ketvoice.app.data.CustomLyrics.entry(track.id)) }
    var customError by remember(track.id) { mutableStateOf<String?>(null) }
    val pickScope = rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pickScope.launch {
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { com.ketvoice.app.data.CustomLyrics.choose(context, track.id, uri) }
            }
            result.onSuccess {
                custom = it; customError = null
                LyricsRepo.load(track, force = true)
                onClose()
            }.onFailure { customError = it.message ?: "Fichier illisible" }
        }
    }
    val radius = with(LocalDensity.current) { 26.dp.toPx() }
    GlassPanel(Modifier.fillMaxWidth().padding(10.dp), RoundedCornerShape(26.dp), GlassParams.sheet(radius, true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text("Sources des paroles", color = Color.White, style = com.ketvoice.app.theme.Ket27.Type.title3)
            Text("Syllabes natives → mots natifs → lignes → texte", color = Color.White.copy(alpha = .78f),
                style = if (com.ketvoice.app.BuildConfig.IS_QUEST) com.ketvoice.app.theme.Ket27.Type.subheadline else com.ketvoice.app.theme.Ket27.Type.footnote,
                modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
            // 7.5 (owner: « en haut tu mets un switch Romanisation »): first in the lyrics source menu.
            RomanizationOption()
            Spacer(Modifier.height(10.dp))
            Text("Décalage des paroles", color = Color.White, style = com.ketvoice.app.theme.Ket27.Type.headline)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.ketvoice.app.ui.common.KetTextButton("Retarder", { LyricsOffsets.set(track.id, offset - 100) }, Modifier.heightIn(min = 48.dp), tint = Color.White)
                Text("${if (offset > 0) "+" else ""}$offset ms", localize = false, color = Color.White, style = com.ketvoice.app.theme.Ket27.Type.body,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                com.ketvoice.app.ui.common.KetTextButton("Avancer", { LyricsOffsets.set(track.id, offset + 100) }, Modifier.heightIn(min = 48.dp), tint = Color.White)
            }
            com.ketvoice.app.ui.common.KetTextButton("Réinitialiser", { LyricsOffsets.set(track.id, 0) }, Modifier.heightIn(min = 48.dp), tint = Color.White.copy(alpha = .78f))
            run {
                val active = custom?.active == true
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (active) Color.White.copy(alpha = .09f) else Color.Transparent)
                    .clickable(role = Role.Button) {
                        val existing = custom
                        if (existing != null && !existing.active) {
                            com.ketvoice.app.data.CustomLyrics.setActive(track.id, true)
                            custom = existing.copy(active = true); LyricsRepo.load(track, force = true); onClose()
                        } else picker.launch(arrayOf("*/*"))
                    }.padding(horizontal = 12.dp, vertical = 11.dp).testTag("lyrics-custom"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Personnalisé", color = Color.White, style = com.ketvoice.app.theme.Ket27.Type.body)
                        Text(customError?.let { com.ketvoice.app.data.AppText.t(it) } ?: custom?.let { "${it.name} · ${it.format.uppercase()}" }
                            ?: com.ketvoice.app.data.AppText.t("Choisir un fichier : LRC, TTML, SRT, VTT, ASS, JSON, TXT…"),
                            localize = false, color = Color.White.copy(alpha = .78f), style = if (com.ketvoice.app.BuildConfig.IS_QUEST) com.ketvoice.app.theme.Ket27.Type.subheadline else com.ketvoice.app.theme.Ket27.Type.footnote,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (active) Icon(Icons.Filled.Check, "Sélectionnée", tint = LocalTheme.current.accent, modifier = Modifier.size(20.dp))
                }
                if (custom != null) Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                    com.ketvoice.app.ui.common.KetTextButton("Changer de fichier", { picker.launch(arrayOf("*/*")) }, tint = Color.White.copy(alpha = .78f))
                    com.ketvoice.app.ui.common.KetTextButton("Retirer", {
                        com.ketvoice.app.data.CustomLyrics.forget(track.id); custom = null; LyricsRepo.load(track, force = true)
                    }, destructive = true)
                }
            }
            (listOf(MusicApi.SRC_AUTO) + MusicApi.lyricSources + MusicApi.SRC_OFF).forEach { source ->
                val status = results[source]
                val selected = AppSettings.lyricsSource.value == source && custom?.active != true
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Color.White.copy(alpha = .09f) else Color.Transparent)
                    .clickable {
                        // Another source for this title: its own file steps aside (kept, ready to be chosen again).
                        if (custom?.active == true) com.ketvoice.app.data.CustomLyrics.setActive(track.id, false)
                        AppSettings.lyricsSource.value = source
                        AppSettings.lyricsMode.value = if (source == MusicApi.SRC_OFF) 2 else 0
                        SettingsStore.save()
                        LyricsRepo.load(track, force = true)
                        onClose()
                    }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(MusicApi.sourceName(source), color = Color.White, style = com.ketvoice.app.theme.Ket27.Type.body)
                        val description = when {
                            source == MusicApi.SRC_AUTO -> "Meilleure synchronisation disponible"
                            source == MusicApi.SRC_OFF -> "Ne pas afficher de paroles"
                            status == null -> "Recherche en cours…"
                            !status.available -> status.detail ?: "Aucune parole pour ce titre"
                            status.syllableTimed -> "Syllabe par syllabe · ${status.lines} lignes"
                            status.wordTimed -> "Mot par mot · ${status.lines} lignes"
                            status.timed -> "Ligne par ligne · ${status.lines} lignes"
                            else -> "Texte non synchronisé · ${status.lines} lignes"
                        }
                        Text(description, color = Color.White.copy(alpha = .78f), style = if (com.ketvoice.app.BuildConfig.IS_QUEST) com.ketvoice.app.theme.Ket27.Type.subheadline else com.ketvoice.app.theme.Ket27.Type.footnote,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) Icon(Icons.Filled.Check, "Sélectionnée", tint = LocalTheme.current.accent, modifier = Modifier.size(20.dp))
                }
            }
            com.ketvoice.app.ui.common.KetTextButton("Relancer les sources en ligne", { LyricsRepo.load(track, force = true, refresh = true); onClose() },
                Modifier.padding(top = 4.dp), tint = Color.White)
            Text("Paroles fournies notamment par lrc.red, BetterLyrics et LiriQo. Les accès peuvent varier selon le titre.",
                color = Color.White.copy(alpha = .78f), style = if (com.ketvoice.app.BuildConfig.IS_QUEST) com.ketvoice.app.theme.Ket27.Type.footnote else com.ketvoice.app.theme.Ket27.Type.caption, modifier = Modifier.padding(12.dp))
        }
    }
}

/** How sung words move (5.9.0), in the manner of Apple Music. */
internal object LyricsWords {
    /** How high a sung word rises, in dp. */
    const val LIFT_DP = 2.5f
    /** Notes held at least this long glow while they are sung. */
    const val GLOW_FROM_MS = 900L

    /** The sung line's breath for a bass level of 0..1: at most one and a half percent. */
    fun breath(low: Float): Float = if (!low.isFinite()) 0f else (((low - .2f) / .8f).coerceIn(0f, 1f).let { it * it }) * .015f

    /** 8.3: a very short word only nudges up by this part of [LIFT_DP]; one of [LIFT_FULL_MS] or more goes the whole way. */
    const val LIFT_FLOOR = .25f
    const val LIFT_QUICK_MS = 160L
    const val LIFT_FULL_MS = 900L
    /** The rise takes the word's own time, within these bounds; the word settles back over [FALL_MS] once sung. */
    const val RISE_MIN_MS = 250L
    const val RISE_MAX_MS = 700L
    const val FALL_MS = 450L

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** How high a word of [durationMs] goes at most, 0..1 of [LIFT_DP]: smooth between a quick word and a held one. */
    fun liftHeight(durationMs: Long): Float =
        LIFT_FLOOR + (1f - LIFT_FLOOR) * smooth((durationMs - LIFT_QUICK_MS).toFloat() / (LIFT_FULL_MS - LIFT_QUICK_MS))

    /**
     * 8.3: how far a word is lifted, 0..1 of [LIFT_DP], [sinceStartMs] after it starts: it rises over its own time
     * (kept between [RISE_MIN_MS] and [RISE_MAX_MS]) to [liftHeight], then settles back over [FALL_MS] after its end.
     */
    fun lift(sinceStartMs: Long, durationMs: Long): Float {
        if (sinceStartMs <= 0L) return 0f
        val duration = durationMs.coerceAtLeast(0L)
        val up = smooth(sinceStartMs.toFloat() / duration.coerceIn(RISE_MIN_MS, RISE_MAX_MS))
        val down = if (sinceStartMs <= duration) 0f else smooth((sinceStartMs - duration).toFloat() / FALL_MS)
        return liftHeight(duration) * up * (1f - down)
    }

    /** 8.3: the fill's leading edge is feathered over this many dp (a soft front instead of a cut). */
    const val FEATHER_DP = 30f
    /** Once a word is sung, the feather behind its edge finishes within these bounds (during the pause, if any). */
    const val TAIL_MIN_MS = 60L
    const val TAIL_MAX_MS = 300L

    /**
     * 8.3: where the fill's leading edge is, in px from the word's leading side, [sinceStartMs] into a word of
     * [durationMs] and [widthPx]: it moves at one constant pace through the word (no easing inside it). Past the
     * word's end it goes on for one feather ([featherPx]), so the word's last letters light fully: at the word's
     * own pace, but within the pause before the next word ([pauseMs], negative when unknown) and [TAIL_MIN_MS]..
     * [TAIL_MAX_MS]. 0 before the word; widthPx + featherPx once it is all lit.
     */
    fun edgePx(sinceStartMs: Long, durationMs: Long, widthPx: Float, featherPx: Float, pauseMs: Long = -1L): Float {
        if (sinceStartMs < 0L || !(widthPx > 0f)) return 0f
        val feather = featherPx.coerceAtLeast(0f)
        if (durationMs <= 0L) return widthPx + feather
        if (sinceStartMs <= durationMs) return widthPx * sinceStartMs / durationMs
        val tail = tailMs(durationMs, widthPx, feather, pauseMs)
        return widthPx + feather * ((sinceStartMs - durationMs).toFloat() / tail).coerceIn(0f, 1f)
    }

    /** How long a word's feather takes to finish after its end (see [edgePx]). */
    fun tailMs(durationMs: Long, widthPx: Float, featherPx: Float, pauseMs: Long = -1L): Long {
        val pace = if (widthPx > 0f) featherPx * durationMs / widthPx else 0f
        val within = if (pauseMs > 0L) minOf(pace, pauseMs.toFloat()) else pace
        return within.toLong().coerceIn(TAIL_MIN_MS, TAIL_MAX_MS)
    }

    /** The ink, 0..1, [d] px from the word's leading side when the edge is at [edgePx]: full a feather behind it. */
    fun ink(d: Float, edgePx: Float, featherPx: Float): Float =
        if (featherPx <= 0f) (if (d < edgePx) 1f else 0f) else ((edgePx - d) / featherPx).coerceIn(0f, 1f)

    /** 8.2: how much larger the word being sung is drawn, at most (owner: « très légèrement », ≤ 4 %). */
    const val GROW = .035f

    /**
     * The scale of a word [progress] (0..1) into its singing, [sinceEndMs] after its end (negative before): it grows
     * over the first third of the word and settles back over [SETTLE_MS] once sung. 1 for a word not reached yet.
     */
    fun grow(progress: Float, sinceEndMs: Long): Float {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (p <= 0f) return 1f
        val settle = if (sinceEndMs <= 0L) 1f else 1f - smooth(sinceEndMs / SETTLE_MS.toFloat())
        return 1f + GROW * smooth(p / .3f) * settle
    }
    const val SETTLE_MS = 320L

    /** The glow of a held note: strongest in the middle of the note, nothing for short words. */
    fun glow(progress: Float, durationMs: Long): Float {
        if (durationMs < GLOW_FROM_MS || !(progress > 0f && progress < 1f)) return 0f
        return kotlin.math.sin(progress * Math.PI).toFloat() * heldStrength(durationMs)
    }

    /** The colour of the lower-cost glow of a held note (below Android 12 and in performance mode). */
    fun glowColor(glow: Float): Color = Color.White.copy(alpha = .42f * glow.coerceIn(0f, 1f))

    /** 8.3: a held note's letters swell one after another; only short words held at least [GLOW_FROM_MS] do. */
    const val HELD_MAX_LETTERS = 7
    /** How much larger a letter is at the top of its swell, how far it leans out from the word's middle and up. */
    const val SWELL = .045f
    const val LEAN_DP = 1.4f
    const val LETTER_RISE_DP = 1.2f
    /** The last letters finish their swell this long after the word's end. */
    const val HELD_TAIL_MS = 200L
    /** Each letter starts at most this long after the one before it. */
    const val STAGGER_MAX_MS = 110L
    /** The glow of a held note: a blurred copy of its letters at about this alpha, blurred by this radius. */
    const val GLOW_ALPHA = .6f
    const val GLOW_RADIUS_DP = 9f
    /** In, hold and out of each letter's swell, as parts of its window. */
    private const val SWELL_IN = .32f
    private const val SWELL_HOLD = .58f
    /**
     * Everything a word does (fill tail, lift, growth, swell, glow) is over this long after its end, so a line is
     * drawn exactly the same from then on.
     */
    const val REST_MS = 600L

    /** How strong a held note's swell and glow are for its length: half at [GLOW_FROM_MS], all from two seconds. */
    fun heldStrength(durationMs: Long): Float = ((durationMs - GLOW_FROM_MS) / 1_100f + .5f).coerceIn(.5f, 1f)

    /**
     * 8.3: whether a word swells letter by letter: held [GLOW_FROM_MS] or longer, one to [HELD_MAX_LETTERS] letters,
     * and written in Latin, Greek or Cyrillic only (never Chinese, Japanese, Korean, Arabic, Hebrew or other scripts
     * whose letters join or stack: their glyphs cannot be drawn one by one).
     */
    fun holds(text: String, durationMs: Long): Boolean {
        if (durationMs < GLOW_FROM_MS) return false
        val letters = text.filterNot { it.isWhitespace() }
        if (letters.isEmpty() || letters.length > HELD_MAX_LETTERS || letters.none { it.isLetter() }) return false
        return letters.all(::separable)
    }

    private fun separable(c: Char): Boolean = !c.isSurrogate() && when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.COMMON -> true
        else -> false
    }

    /** While a held word's letters may still move: from its start to [HELD_TAIL_MS] after its end. */
    fun inHold(sinceStartMs: Long, durationMs: Long): Boolean = sinceStartMs >= 0L && sinceStartMs < durationMs + HELD_TAIL_MS

    /**
     * The swell of letter [index] of [count], 0..1, [sinceStartMs] into a held word of [durationMs]: each letter
     * starts a little after the one before, rises, holds, then falls back to 0 by [HELD_TAIL_MS] after the end.
     */
    fun swell(index: Int, count: Int, sinceStartMs: Long, durationMs: Long): Float {
        if (count <= 0 || durationMs <= 0L) return 0f
        val total = (durationMs + HELD_TAIL_MS).toFloat()
        val stagger = if (count > 1) minOf(STAGGER_MAX_MS.toFloat(), total * .35f / (count - 1)) else 0f
        val window = total - stagger * (count - 1)
        val p = (sinceStartMs - index.coerceIn(0, count - 1) * stagger) / window
        return when {
            !(p > 0f) || p >= 1f -> 0f
            p < SWELL_IN -> smooth(p / SWELL_IN)
            p < SWELL_HOLD -> 1f
            else -> 1f - smooth((p - SWELL_HOLD) / (1f - SWELL_HOLD))
        }
    }

    /** The time a karaoke line changes in: from just before its first word to [REST_MS] after its last. */
    fun window(line: MusicApi.LyricLine): LongRange {
        val first = line.words.minOfOrNull { it.startMs } ?: line.timeMs
        val last = maxOf(line.words.maxOfOrNull { maxOf(it.endMs, it.startMs) } ?: first, first)
        return (first - 50L)..(last + REST_MS)
    }

    /** -1 before [window], 0 inside it, 1 after it. */
    fun side(now: Long, window: LongRange): Int = when { now < window.first -> -1; now > window.last -> 1; else -> 0 }

    /** True when the words, joined, hold every visible letter of the line (so drawing words alone loses nothing). */
    fun covers(line: String, words: List<String>): Boolean {
        val letters = line.count { !it.isWhitespace() }
        if (letters == 0 || words.isEmpty()) return false
        return words.sumOf { w -> w.count { !it.isWhitespace() } } >= letters && words.joinToString("").length <= line.length
    }
}

/** The lyrics' top and bottom fade, made once (7.6): built inside the draw, it was a new gradient at every frame. */
private val LyricsEdgeFade = Brush.verticalGradient(
    0f to Color.Transparent,
    .09f to Color.Black,
    .84f to Color.Black,
    1f to Color.Transparent
)
