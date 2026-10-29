package com.ketvoice.app.ui.player

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.CircularProgressIndicator
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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

@Composable
internal fun LyricsPane(track: Track, expanded: Boolean, onSource: () -> Unit,
                        modifier: Modifier = Modifier,
                        onEmptyTap: () -> Unit = {},
                        fontScale: Float = com.ketvoice.app.ui.quest.QuestLayout.lyricsScale(AppSettings.lyricsTextSize.value)) {
    val lyrics by LyricsRepo.state.collectAsState()

    val position = Player.positionMs.collectAsState()
    val active by remember(lyrics) { derivedStateOf { lyrics.indexAt(position.value) } }
    val firstLineMs = remember(lyrics.lines) { lyrics.lines.firstOrNull { it.timeMs >= 0 }?.timeMs ?: 0L }
    val intro by remember(firstLineMs, lyrics.isTimed) { derivedStateOf {
        lyrics.isTimed && LyricsMotion.showIntro(position.value, firstLineMs)
    } }
    val list = rememberLazyListState()
    var following by remember(track.id) { mutableStateOf(true) }
    val revealed = remember(track.id, lyrics.lines) { mutableSetOf<Int>() }
    val seekEpoch by Player.seekEpoch.collectAsState()
    val reduced = Motion.reduced
    var translationEnabled by remember(track.id) { mutableStateOf(false) }
    var translated by remember(track.id, lyrics.lines) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var translating by remember(track.id) { mutableStateOf(false) }
    val target = TranslationLanguages.code(AppSettings.translateLanguage.value)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(translationEnabled, lyrics.lines, target) {
        translated = emptyMap()
        if (translationEnabled && lyrics.lines.isNotEmpty()) {
            translating = true
            try {
                LyricsTranslation.stream(lyrics.lines, target, active) { index, text -> translated = translated + (index to text) }
                if (translated.isEmpty()) Toast.makeText(context, AppText.t("Traduction temporairement indisponible"), Toast.LENGTH_SHORT).show()
            } finally { translating = false }
        }
    }
    LaunchedEffect(track.id) { list.scrollToItem(0); following = true }
    LaunchedEffect(seekEpoch) { following = true }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    BoxWithConstraints(modifier) {
        val viewportPx = with(LocalDensity.current) { maxHeight.toPx() }
        LaunchedEffect(active, following, expanded, lyrics.lines, viewportPx, reduced, seekEpoch, translationEnabled) {
            if (!following || lyrics.lines.isEmpty()) return@LaunchedEffect
            val targetIndex = LyricsMotion.targetIndex(active, lyrics.isTimed)
            val anchor = -(viewportPx * .18f).toInt()
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (reduced || item == null || active < 0) list.scrollToItem(targetIndex, if (active < 0) 0 else anchor)
            else list.animateScrollBy(
                (item.offset + anchor).toFloat(),
                tween(Motion.duration(540), easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
            )
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
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    .09f to Color.Black,
                                    .84f to Color.Black,
                                    1f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
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

                        Box(Modifier.height(if (firstLineMs > 0) 32.dp else 0.dp)) {
                            if (intro) IntroDots(firstLineMs)
                        }
                    }
                    run {
                        itemsIndexed(lyrics.lines, key = { i, l -> "$i:${l.timeMs}" }) { index, line ->
                            val current = index == active
                            val below = index > active
                            val distance = if (active < 0) 1 else abs(index - active)
                            // Progressive blur: sung lines stay slightly soft, upcoming
                            // lines sharpen to the active one, then soften again the
                            // further down they are.
                            val blur = if (reduced || !AppSettings.blurInactiveLyrics.value || current || !lyrics.isTimed) 0.dp
                                else if (below) when (distance) {
                                    1 -> 0.dp
                                    2 -> .5.dp
                                    3 -> 1.dp
                                    4 -> 1.5.dp
                                    else -> 2.dp
                                } else if (distance <= 1) .3.dp else .8.dp
                            val entry = remember(index, track.id) { Animatable(if (index in revealed || reduced) 1f else 0f) }
                            LaunchedEffect(index, reduced) {
                                if (reduced || !revealed.add(index)) entry.snapTo(1f)
                                else { delay(index.coerceAtMost(5) * 45L); entry.animateTo(1f, tween(Motion.FADE)) }
                            }
                            val lineColor by animateColorAsState(
                                targetValue = if (current || !lyrics.isTimed) Color.White else Color.White.copy(alpha = .54f),
                                animationSpec = tween(Motion.duration()),
                                label = "lyricColor"
                            )
                            val lineBlur by animateDpAsState(blur, tween(Motion.duration()), label = "lyricBlur")
                            // Toutes les lignes gardent exactement la même taille et la
                            // même graisse : seule la couleur distingue la ligne active.
                            // Aucun changement d'échelle, pour éviter l'effet « lyrics
                            // basiques » sur les lignes non sélectionnées.
                            val lineScale = 1f
                            val lineModifier = Modifier.fillMaxWidth().clickable {
                                if (lyrics.isTimed && line.timeMs >= 0) { Player.seekTo(line.timeMs); following = true }
                            }
                            Column(
                                 lineModifier.graphicsLayer {
                                    alpha = entry.value
                                    translationY = if (reduced) 0f else (1f - entry.value) * 8.dp.toPx()
                                    scaleX = lineScale
                                    scaleY = lineScale
                                    transformOrigin = TransformOrigin(0f, .5f)
                                }
                            ) {
                                if (current && line.words.isNotEmpty()) KaraokeLine(line, Modifier.fillMaxWidth(), fontScale)
                                else {
                                    // Une seule couche de texte par ligne : plus aucun halo
                                    // flou dupliqué au-dessus des paroles.
                                    // Le texte de base ne doit plus contenir les segments
                                    // entre parenthèses : ils sont rendus une seule fois,
                                    // par le bloc ad-libs animé juste en dessous. Les
                                    // laisser ici produisait le doublon visé.
                                    val baseText = remember(line.text) {
                                        Regex("\\([^)]*\\)").replace(line.text, "")
                                            .replace(Regex("\\s{2,}"), " ")
                                            .trim()
                                    }
                                    if (baseText.isNotBlank()) Text(baseText, localize = false, color = lineColor,
                                        fontSize = (33f * fontScale).sp,
                                        lineHeight = (41f * fontScale).sp,
                                        letterSpacing = (-.3).sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.fillMaxWidth().blur(lineBlur, BlurredEdgeTreatment.Unbounded))
                                }

                                // Backs / ad-libs : pour les paroles originales synchronisées (mot ou syllabe),
                                // les segments entre parenthèses apparaissent avec la même animation d'expansion/fondu
                                // que les traductions, et disparaissent immédiatement dès le passage à la ligne suivante.
                                if (line.words.isNotEmpty() && line.text.contains("(") && line.text.contains(")")) {
                                    val adlibs = remember(line.text) {
                                        Regex("\\(([^)]+)\\)").findAll(line.text).map { "(${it.groupValues[1]})" }.joinToString(" ")
                                    }
                                    if (adlibs.isNotBlank()) {
                                        // Les backs ne doivent vivre que le temps de leurs
                                        // propres syllabes. On retrouve leur fenêtre en
                                        // suivant la profondeur des parenthèses dans le
                                        // flux de mots synchronisés, puis on l'utilise
                                        // comme condition d'affichage.
                                        val adlibWindow = remember(line.words) {
                                            var depth = 0
                                            var start = -1L
                                            var end = -1L
                                            val sb = StringBuilder()
                                            line.words.forEach { w ->
                                                val from = sb.length
                                                sb.append(w.text)
                                                for (i in from until sb.length) {
                                                    when (sb[i]) {
                                                        '(' -> {
                                                            depth++
                                                            if (start < 0) start = w.startMs
                                                        }
                                                        ')' -> if (depth > 0) {
                                                            depth--
                                                            if (depth == 0) {
                                                                end = if (w.endMs > 0) w.endMs
                                                                else w.startMs + 900L
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            if (start >= 0 && end > start) start..end else null
                                        }
                                        AnimatedVisibility(
                                            visible = adlibWindow?.let { position.value in it } ?: current,
                                            enter = fadeIn(tween(Motion.duration(320))) + expandVertically(tween(Motion.duration(320)), expandFrom = Alignment.Top),
                                            exit = ExitTransition.None
                                        ) {
                                            Text(
                                                adlibs,
                                                localize = false,
                                                color = Color.White.copy(alpha = 0.72f),
                                                fontSize = (20f * fontScale).sp,
                                                lineHeight = (26f * fontScale).sp,
                                                letterSpacing = (-.2).sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }

                                AnimatedVisibility(
                                    visible = translationEnabled && translated[index] != null && (current || !lyrics.isTimed),
                                    enter = fadeIn(tween(Motion.duration(320))) + expandVertically(tween(Motion.duration(320)), expandFrom = Alignment.Top),
                                    exit = fadeOut(tween(Motion.duration(240))) + shrinkVertically(tween(Motion.duration(240)), shrinkTowards = Alignment.Top)
                                ) {
                                    translated[index]?.let { subtitle ->
                                        SyncedTranslation(
                                            line = line,
                                            text = subtitle,
                                            positionMs = position.value,
                                            active = current && lyrics.isTimed,
                                            dimAlpha = if (current || !lyrics.isTimed) .82f else .43f
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lyrics.loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.height(16.dp))
                        Text(if (lyrics.loading) "Recherche dans les sources de paroles…" else "Aucune parole disponible pour cette version",
                            color = Color(0xFFA0A1AA), fontSize = 15.sp)
                        Text("Choisir une source", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.clickable(onClick = onSource).padding(16.dp))
                    }
                }
            }
            if (!expanded) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallCircleButton(onSource, size = 36.dp) { Icon(Icons.Filled.Language, "Sources des paroles", tint = it, modifier = Modifier.size(21.dp)) }
                    Spacer(Modifier.weight(1f))
                    if (!following && lyrics.isTimed) Text("Suivre la lecture", color = Color.White, fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 11.sp,
                        modifier = Modifier.clickable { following = true }.padding(8.dp))
                    SmallCircleButton({
                        translationEnabled = !translationEnabled
                    }, active = translationEnabled, size = 36.dp) {
                        if (translating) CircularProgressIndicator(Modifier.size(17.dp), color = it, strokeWidth = 1.6.dp)
                        else Icon(Icons.Filled.Translate, "Traduire les paroles", tint = it, modifier = Modifier.size(21.dp))
                    }
                }
            } else if (!following && lyrics.isTimed) {
                Text("Reprendre le suivi", color = Color.White, fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable { following = true }.padding(12.dp))
            }
        }
    }
}

@Composable
private fun KaraokeLine(line: MusicApi.LyricLine, modifier: Modifier, fontScale: Float = 1f) {
    var now by remember(line) { mutableLongStateOf(Player.currentPositionMs()) }
    val playing by remember { Player.state.map { it.isPlaying }.distinctUntilChanged() }.collectAsState(false)
    val position by Player.positionMs.collectAsState()
    LaunchedEffect(line, playing) {
        now = Player.currentPositionMs()
        if (playing) while (true) { withFrameNanos { now = Player.currentPositionMs() } }
    }
    LaunchedEffect(position, playing) { if (!playing) now = position }
    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }

    val masks = remember(layout, line) { layout?.let { measured ->
        var offset = 0
        line.words.map { word ->
            val start = offset
            val end = (start + word.text.length).coerceAtMost(line.text.length)
            offset = end
            val slices = if (start >= end) emptyList() else (0 until measured.lineCount).mapNotNull { row ->
                val from = maxOf(start, measured.getLineStart(row))
                val to = minOf(end, measured.getLineEnd(row))
                if (from < to) {
                    val path = measured.getPathForRange(from, to)
                    LyricMaskSlice(path, path.getBounds(), measured.getBidiRunDirection(from) == ResolvedTextDirection.Rtl)
                } else null
            }
            LyricWordMask(word.startMs, word.endMs, slices, slices.sumOf { it.bounds.width.toDouble() }.toFloat())
        }
    }.orEmpty() }
    Box(modifier) {
        Text(line.text, localize = false, color = Color.White.copy(alpha = .62f), fontSize = (33 * fontScale).sp, lineHeight = (41 * fontScale).sp, letterSpacing = (-.3).sp,
            fontWeight = FontWeight.Black, onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth())
        Text(line.text, localize = false, color = Color.White, fontSize = (33 * fontScale).sp, lineHeight = (41 * fontScale).sp, letterSpacing = (-.3).sp,
            fontWeight = FontWeight.Black,
            style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color.White.copy(alpha = .14f), Offset.Zero, 2f)),
            modifier = Modifier.fillMaxWidth().drawWithContent {
                masks.forEach { word ->
                    val progress = segmentProgress(now, word.start, word.end)
                    if (progress <= 0f) return@forEach
                    var remaining = word.width * progress
                    word.slices.forEach { slice ->
                        val bounds = slice.bounds
                        val fill = remaining.coerceIn(0f, bounds.width)
                        remaining -= bounds.width
                        if (fill > 0f) clipPath(slice.path) {
                            if (slice.rtl) {
                                clipRect(bounds.right - fill, bounds.top, bounds.right, bounds.bottom) { this@drawWithContent.drawContent() }
                            } else clipRect(bounds.left, bounds.top, bounds.left + fill, bounds.bottom) { this@drawWithContent.drawContent() }
                        }
                    }
                }
            })
    }
}

private data class LyricMaskSlice(val path: androidx.compose.ui.graphics.Path, val bounds: androidx.compose.ui.geometry.Rect, val rtl: Boolean)
private data class LyricWordMask(val start: Long, val end: Long, val slices: List<LyricMaskSlice>, val width: Float)

internal fun segmentProgress(now: Long, start: Long, end: Long): Float = when {
    now < start -> 0f
    end <= start -> 1f
    else -> {
        val t = ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        // Courbe smoothstep fluide : élimine les à-coups du word-by-word
        // pour une glisse continue et naturelle, alignée sur le rendu syllabique.
        t * t * (3f - 2f * t)
    }
}

@Composable
internal fun IntroDots(firstLineMs: Long) {
    val position by Player.positionMs.collectAsState()
    val lit = LyricsMotion.litDots(position, firstLineMs)

    // Plain white dots: no halo, no ripple, no drift.
    // Anchored to the left edge of the screen, slightly tightened spacing.
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp, horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { index ->
                val alpha by animateFloatAsState(
                    targetValue = if (index < lit) 1f else .28f,
                    animationSpec = tween(Motion.duration(380)),
                    label = "intro-dot-$index"
                )
                Canvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(
                        color = Color.White.copy(alpha = alpha),
                        radius = 6.dp.toPx(),
                        center = Offset(size.width / 2f, size.height / 2f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun LyricsSourceSheet(track: Track, onClose: () -> Unit) {
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
    val radius = with(LocalDensity.current) { 26.dp.toPx() }
    GlassPanel(Modifier.fillMaxWidth().padding(10.dp), RoundedCornerShape(26.dp), GlassParams.sheet(radius, true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text("Sources des paroles", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Syllabes natives → mots natifs → lignes → texte", color = Color.White.copy(alpha = .78f), fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 12.sp,
                modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
            (listOf(MusicApi.SRC_AUTO) + MusicApi.lyricSources + MusicApi.SRC_OFF).forEach { source ->
                val status = results[source]
                val selected = AppSettings.lyricsSource.value == source
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Color.White.copy(alpha = .09f) else Color.Transparent)
                    .clickable {
                        AppSettings.lyricsSource.value = source
                        AppSettings.lyricsMode.value = if (source == MusicApi.SRC_OFF) 2 else 0
                        SettingsStore.save()
                        LyricsRepo.load(track, force = true)
                        onClose()
                    }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(MusicApi.sourceName(source), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                        Text(description, color = Color.White.copy(alpha = .78f), fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) Icon(Icons.Filled.Check, "Sélectionnée", tint = LocalTheme.current.accent, modifier = Modifier.size(20.dp))
                }
            }
            Text("Relancer les sources en ligne", color = Color.White, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { LyricsRepo.load(track, force = true, refresh = true); onClose() }.padding(12.dp))
            Text("Paroles fournies notamment par lrc.red, BetterLyrics et LiriQo. Les accès peuvent varier selon le titre.",
                color = Color.White.copy(alpha = .78f), fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 13.sp else 10.sp, modifier = Modifier.padding(12.dp))
        }
    }
}
