package com.ketvoice.app.ui.player

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Content only. The player owns the header, actions, sheets and transport. */
@Composable
internal fun LyricsPane(track: Track, expanded: Boolean, onSource: () -> Unit,
                        modifier: Modifier = Modifier) {
    val lyrics by LyricsRepo.state.collectAsState()
    // A StateFlow must be collected. Reading flow.value inside derivedStateOf
    // does not subscribe and was leaving both highlight and scroll frozen.
    val position by Player.positionMs.collectAsState()
    val active = lyrics.indexAt(position)
    val list = rememberLazyListState()
    var following by remember(track.id) { mutableStateOf(true) }
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
                if (translated.isEmpty()) Toast.makeText(context, "Traduction temporairement indisponible", Toast.LENGTH_SHORT).show()
            } finally { translating = false }
        }
    }
    LaunchedEffect(track.id) { list.scrollToItem(0); following = true }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    BoxWithConstraints(modifier) {
        val viewportPx = with(LocalDensity.current) { maxHeight.toPx() }
        LaunchedEffect(active, following, expanded, lyrics.lines) {
            if (following && active >= 0) list.animateScrollToItem(active, -(viewportPx * .17f).toInt())
            else if (following && active < 0) list.scrollToItem(0)
        }
        Column(Modifier.fillMaxSize()) {
            if (lyrics.lines.isNotEmpty()) {
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 22.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(25.dp)) {
                    if (active < 0 && lyrics.isTimed) item(key = "intro") { IntroDots() }
                    itemsIndexed(lyrics.lines, key = { i, l -> "$i:${l.timeMs}" }) { index, line ->
                        val current = index == active
                        val distance = if (active < 0) 1 else abs(index - active)
                        val blur = if (!AppSettings.blurInactiveLyrics.value || current || !lyrics.isTimed) 0.dp
                            else if (distance <= 1) 1.dp else 2.dp
                        val lineModifier = Modifier.fillMaxWidth().clickable {
                            if (lyrics.isTimed && line.timeMs >= 0) { Player.seekTo(line.timeMs); following = true }
                        }
                        Column(lineModifier) {
                            if (current && line.words.isNotEmpty()) KaraokeLine(line, Modifier.fillMaxWidth())
                            else Text(line.text, color = if (current || !lyrics.isTimed) Color.White else Color(0xFF74747C),
                                fontSize = 33.sp, lineHeight = 41.sp, letterSpacing = (-.3).sp,
                                fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth().blur(blur))
                            if (translationEnabled) translated[index]?.let { subtitle ->
                                Text(subtitle, color = Color.White.copy(alpha = if (current || !lyrics.isTimed) .82f else .43f),
                                    fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp))
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
                    if (!following && lyrics.isTimed) Text("Suivre la lecture", color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.clickable { following = true }.padding(8.dp))
                    SmallCircleButton({
                        translationEnabled = !translationEnabled
                    }, active = translationEnabled, size = 36.dp) {
                        if (translating) CircularProgressIndicator(Modifier.size(17.dp), color = it, strokeWidth = 1.6.dp)
                        else Icon(Icons.Filled.Translate, "Traduire les paroles", tint = it, modifier = Modifier.size(21.dp))
                    }
                }
            } else if (!following && lyrics.isTimed) {
                Text("Reprendre le suivi", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable { following = true }.padding(12.dp))
            }
        }
    }
}

@Composable
private fun KaraokeLine(line: MusicApi.LyricLine, modifier: Modifier) {
    var now by remember(line) { mutableLongStateOf(Player.currentPositionMs()) }
    LaunchedEffect(line) {
        while (true) { now = Player.currentPositionMs(); delay(16) }
    }
    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier) {
        Text(line.text, color = Color(0xFF9A99A0), fontSize = 33.sp, lineHeight = 41.sp, letterSpacing = (-.3).sp,
            fontWeight = FontWeight.Black, onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth())
        Text(line.text, color = Color.White, fontSize = 33.sp, lineHeight = 41.sp, letterSpacing = (-.3).sp,
            fontWeight = FontWeight.Black,
            style = androidx.compose.ui.text.TextStyle(shadow = Shadow(Color.White.copy(alpha = .28f), Offset.Zero, 5f)),
            modifier = Modifier.fillMaxWidth().drawWithContent {
                val measured = layout ?: return@drawWithContent
                var offset = 0
                line.words.forEach { word ->
                    val start = offset
                    val end = (start + word.text.length).coerceAtMost(line.text.length)
                    offset = end
                    if (start >= end) return@forEach
                    val progress = segmentProgress(now, word.startMs, word.endMs)
                    if (progress <= 0f) return@forEach
                    val ranges = (0 until measured.lineCount).mapNotNull { row ->
                        val from = maxOf(start, measured.getLineStart(row))
                        val to = minOf(end, measured.getLineEnd(row))
                        if (from < to) Triple(from, to, measured.getPathForRange(from, to)) else null
                    }
                    var remaining = ranges.sumOf { it.third.getBounds().width.toDouble() }.toFloat() * progress
                    ranges.forEach { (from, _, path) ->
                        val bounds = path.getBounds()
                        val fill = remaining.coerceIn(0f, bounds.width)
                        remaining -= bounds.width
                        if (fill > 0f) clipPath(path) {
                            if (measured.getBidiRunDirection(from) == ResolvedTextDirection.Rtl) {
                                clipRect(bounds.right - fill, bounds.top, bounds.right, bounds.bottom) { this@drawWithContent.drawContent() }
                            } else clipRect(bounds.left, bounds.top, bounds.left + fill, bounds.bottom) { this@drawWithContent.drawContent() }
                        }
                    }
                }
            })
    }
}

internal fun segmentProgress(now: Long, start: Long, end: Long): Float = when {
    now < start -> 0f
    end <= start -> 1f // Source supplied a start only: do not invent a duration.
    else -> ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

@Composable
private fun IntroDots() {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { tick = android.os.SystemClock.uptimeMillis(); delay(40) } }
    Canvas(Modifier.size(44.dp, 23.dp)) {
        repeat(3) { index ->
            val phase = ((tick + index * 220) % 1100) / 1100f
            val alpha = .3f + .7f * ((kotlin.math.sin(phase * Math.PI * 2) + 1) / 2).toFloat()
            drawCircle(Color.White.copy(alpha = alpha), 4.dp.toPx(), Offset((7 + index * 12).dp.toPx(), size.height / 2))
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
            Text("Syllabes natives → mots natifs → lignes → texte", color = Color(0xFF9A9BA4), fontSize = 12.sp,
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
                        Text(description, color = Color(0xFF92949E), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) Icon(Icons.Filled.Check, "Sélectionnée", tint = LocalTheme.current.accent, modifier = Modifier.size(20.dp))
                }
            }
            Text("Relancer les sources en ligne", color = Color.White, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { LyricsRepo.load(track, force = true, refresh = true); onClose() }.padding(12.dp))
            Text("Paroles fournies notamment par lrc.red, BetterLyrics et LiriQo. Les accès peuvent varier selon le titre.",
                color = Color(0xFF92949E), fontSize = 10.sp, modifier = Modifier.padding(12.dp))
        }
    }
}
