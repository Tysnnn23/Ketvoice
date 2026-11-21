package com.ketvoice.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.ReplaceSymbol
import com.ketvoice.app.ui.motion.symbolBounce
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlin.math.roundToInt

enum class Panel { NONE, QUEUE, LYRICS }

/** The bar at the top: transport on the left, the « LCD » in the centre, volume and panels on the right. */
@Composable
fun NowPlayingBar(panel: Panel, onPanel: (Panel) -> Unit, onOpenPlayer: () -> Unit) {
    val player = LocalPlayer.current
    val ket = LocalKet.current
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    val repeatIcon = if (state.repeatOne) DeskGlyphs.RepeatOne else DeskGlyphs.Repeat
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp).testTag("bar"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(228.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KetIconButton(KetIcons.Shuffle, "Lecture aléatoire", { player.setShuffle(!state.shuffle) }, active = state.shuffle, glyph = 16.dp,
                modifier = Modifier.testTag("shuffle").symbolBounce(state.shuffle))
            KetIconButton(DeskGlyphs.Previous, "Précédent", { player.previous() }, size = 36.dp, glyph = 20.dp, modifier = Modifier.testTag("previous"))
            PlayPause(state.isPlaying, { player.toggle() }, 40.dp, 24.dp)
            KetIconButton(DeskGlyphs.Next, "Suivant", { player.next() }, size = 36.dp, glyph = 20.dp, modifier = Modifier.testTag("next"))
            KetIconButton(repeatIcon, "Répéter", {
                when { state.repeatOne -> player.setRepeat(false, false); state.repeatAll -> player.setRepeat(true, false); else -> player.setRepeat(false, true) }
            }, active = state.repeatAll || state.repeatOne, glyph = 16.dp, modifier = Modifier.testTag("repeat").symbolBounce(state.repeatOne))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Lcd(onOpenPlayer) }
        Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            KetIconButton(if (volume <= 0f) DeskGlyphs.Mute else DeskGlyphs.Volume, "Volume", { player.setVolume(if (volume > 0f) 0f else 1f) }, glyph = 17.dp)
            ThinSlider(volume, { player.setVolume(it) }, Modifier.width(92.dp), tag = "volume")
            Spacer(Modifier.width(10.dp))
            KetIconButton(KetIcons.Lyrics, "Paroles", { onPanel(if (panel == Panel.LYRICS) Panel.NONE else Panel.LYRICS) },
                active = panel == Panel.LYRICS, glyph = 17.dp, modifier = Modifier.testTag("toggle-lyrics"))
            KetIconButton(DeskGlyphs.Queue, "À suivre", { onPanel(if (panel == Panel.QUEUE) Panel.NONE else Panel.QUEUE) },
                active = panel == Panel.QUEUE, glyph = 17.dp, modifier = Modifier.testTag("toggle-queue"))
            KetIconButton(DeskGlyphs.Ketmix, "Ketmix", { AppSettings.automix.value = !AppSettings.automix.value },
                active = AppSettings.automix.value, glyph = 17.dp, modifier = Modifier.testTag("ketmix"))
            OutputButton()
        }
    }
}

@Composable
fun PlayPause(playing: Boolean, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp, glyph: androidx.compose.ui.unit.Dp, tint: Color = Color.Unspecified) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    Tooltip(if (playing) "Pause" else "Lecture") {
        Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(if (hovered) ket.hover else Color.Transparent)
            .hoverable(source).clickable(onClick = onClick).testTag("play-pause"), contentAlignment = Alignment.Center) {
            ReplaceSymbol(if (playing) KetIcons.Pause else KetIcons.Play, AppText.t(if (playing) "Pause" else "Lecture"),
                if (tint == Color.Unspecified) ket.label else tint, Modifier.size(glyph))
        }
    }
}

/** The centre « LCD »: artwork, title, artist — album, a thin seek bar with times, the Lossless badge. */
@Composable
private fun Lcd(onOpenPlayer: () -> Unit) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val format by player.format.collectAsState()
    val loading by player.loading.collectAsState()
    val track = state.current
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().height(50.dp).clip(shape)
        .background(if (ket.dark) Color.White.copy(alpha = .06f) else Color.Black.copy(alpha = .035f))
        .border(.5.dp, ket.hairline, shape).testTag("lcd"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).clickable(enabled = track != null, onClick = onOpenPlayer).testTag("lcd-artwork")) {
            Artwork(track?.coverUrl, 50.dp, radius = 0.dp)
        }
        if (track == null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Icon(KetIcons.Music, null, Modifier.size(20.dp), tint = ket.tertiary) }
            return@Row
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KText(track.title, Ket27Desk.Type.footnote, weight = FontWeight.SemiBold, translate = false)
                if (format?.lossless == true) LosslessMark(format!!.hiRes)
            }
            KText(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" — "), Ket27Desk.Type.caption, color = ket.secondary, translate = false)
            Seek(state.positionMs, state.durationMs.takeIf { it > 0 } ?: track.durationMs, compact = true)
        }
        if (loading) Spinner(Modifier.padding(end = 10.dp).size(16.dp))
    }
}

@Composable
private fun LosslessMark(hiRes: Boolean, tint: Color = LocalKet.current.secondary, large: Boolean = false, tag: String = "lossless-badge") {
    val style = if (large) Ket27Desk.Type.caption else Ket27Desk.Type.caption2.copy(fontSize = Ket27Desk.Type.caption2.fontSize * .82f)
    Box(Modifier.padding(start = if (large) 0.dp else 6.dp).border(.8.dp, tint, RoundedCornerShape(if (large) 4.dp else 3.dp))
        .padding(horizontal = if (large) 5.dp else 3.dp, vertical = if (large) 1.dp else 0.dp).testTag(tag)) {
        KText(if (hiRes) "Hi-Res" else "Lossless", style, color = tint, translate = false, weight = FontWeight.SemiBold)
    }
}

/** Position, a thin bar, remaining time. */
@Composable
fun Seek(positionMs: Long, durationMs: Long, compact: Boolean, color: Color = Color.Unspecified, secondary: Color = Color.Unspecified) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownPos = scrub?.let { (it * durationMs).toLong() } ?: positionMs
    val c2 = if (secondary == Color.Unspecified) ket.secondary else secondary
    val style = if (compact) Ket27Desk.Type.caption2.copy(fontWeight = FontWeight.Medium) else Ket27Desk.Type.caption
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KText(formatTime(shownPos), style, Modifier.width(if (compact) 34.dp else 44.dp), color = c2, translate = false)
        ThinSlider(fraction, { scrub = it }, Modifier.weight(1f), onDone = { player.seekTo((it * durationMs).toLong()); scrub = null }, tag = "seek",
            color = color, track = if (color == Color.Unspecified) Color.Unspecified else color.copy(alpha = .25f))
        KText("-" + formatTime((durationMs - shownPos).coerceAtLeast(0)), style, Modifier.width(if (compact) 38.dp else 48.dp).padding(start = 6.dp), color = c2, translate = false)
    }
}

/** The output devices (Windows' playback devices through Java Sound). */
@Composable
private fun OutputButton() {
    val menu = LocalMenu.current
    var at by remember { mutableStateOf(IntOffset.Zero) }
    Box(Modifier.androidxPosition { at = it }) {
        KetIconButton(DeskGlyphs.Output, "Sortie audio", {
            val names = runCatching {
                javax.sound.sampled.AudioSystem.getMixerInfo().filter { info ->
                    javax.sound.sampled.AudioSystem.getMixer(info).sourceLineInfo.any { it.lineClass == javax.sound.sampled.SourceDataLine::class.java }
                }.map { it.name }.filterNot { it.startsWith("Port ") }.distinct()
            }.getOrDefault(emptyList())
            menu.open(listOf(MenuItem("Sortie par défaut du système", KetIcons.Check, "output-default")) +
                names.map { MenuItem(it, null, "playlist") }, IntOffset(at.x - 180, at.y + 34))
        }, glyph = 17.dp, modifier = Modifier.testTag("output"))
    }
}

private fun Modifier.androidxPosition(onPos: (IntOffset) -> Unit) = onGloballyPositioned {
    val p = it.positionInWindow(); onPos(IntOffset(p.x.toInt(), p.y.toInt()))
}

/* ── Right panels ── */

/** « À suivre » (drag the grip to reorder) and « Historique ». */
@Composable
fun QueuePanel() {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val history by LocalLibrary.current.history.collectAsState()
    val upcoming = state.queue.withIndex().drop(state.index + 1)
    LazyColumn(Modifier.fillMaxSize().testTag("panel:queue"), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        state.current?.let { cur ->
            item("now-h") { KText("En cours de lecture", Ket27Desk.Type.headline, Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp)) }
            item("now") { CompactTrackRow(cur, state.queue, state.index, Modifier.fillMaxWidth()) }
        }
        item("next-h") { KText("À suivre", Ket27Desk.Type.headline, Modifier.padding(start = 6.dp, top = 16.dp, bottom = 6.dp)) }
        if (upcoming.isEmpty()) item("next-empty") { KText("Rien à suivre", Ket27Desk.Type.footnote, Modifier.padding(6.dp), color = ket.secondary) }
        itemsIndexed(upcoming, key = { _, e -> state.queueEntryIds.getOrNull(e.index) ?: "${e.index}-${e.value.id}" }) { _, e ->
            ReorderableRow(e.value, e.index, state.queue, onMove = { from, to -> player.moveQueue(from, to) }, onRemove = { player.removeAt(e.index) },
                bounds = (state.index + 1)..state.queue.lastIndex)
        }
        if (history.isNotEmpty()) {
            item("hist-h") { KText("Historique", Ket27Desk.Type.headline, Modifier.padding(start = 6.dp, top = 20.dp, bottom = 6.dp)) }
            itemsIndexed(history.take(30), key = { i, t -> "h$i-${t.id}" }) { i, t -> CompactTrackRow(t, history, i, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun ReorderableRow(track: Track, index: Int, queue: List<Track>, onMove: (Int, Int) -> Unit, onRemove: () -> Unit, bounds: IntRange) {
    val ket = LocalKet.current
    var dy by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val lift by animateFloatAsState(if (dragging) 1.02f else 1f, Motion.Springs.SNAPPY.motion())
    val (source, hovered) = rememberHover()
    Row(Modifier.fillMaxWidth().graphicsLayer { translationY = dy; scaleX = lift; scaleY = lift; shadowElevation = if (dragging) 12f else 0f }
        .zIndexIf(dragging).clip(RoundedCornerShape(8.dp)).background(if (dragging) ket.raised else Color.Transparent)
        .hoverable(source).testTag("queue:${track.id}"), verticalAlignment = Alignment.CenterVertically) {
        CompactTrackRow(track, queue, index, Modifier.weight(1f))
        if (hovered) KetIconButton(KetIcons.Close, "Retirer de la file d'attente", onRemove, size = 26.dp, glyph = 12.dp)
        Box(Modifier.size(28.dp).pointerInput(index) {
            detectDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = {
                    val to = (index + (dy / rowPx).roundToInt()).coerceIn(bounds.first, bounds.last.coerceAtLeast(bounds.first))
                    dragging = false; dy = 0f
                    if (to != index) onMove(index, to)
                },
                onDragCancel = { dragging = false; dy = 0f },
            ) { change, amount -> change.consume(); dy += amount.y }
        }.testTag("grip:${track.id}"), contentAlignment = Alignment.Center) {
            Icon(DeskGlyphs.Grip, AppText.t("Réordonner"), Modifier.size(14.dp), tint = ket.tertiary)
        }
    }
}

private fun Modifier.zIndexIf(on: Boolean) = if (on) zIndex(1f) else this

/** Lyrics arrive with Cascade in Phase 2: a quiet pane that says so, with the title. */
@Composable
fun LyricsPanel() {
    val ket = LocalKet.current
    val state by LocalPlayer.current.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("panel:lyrics"), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp))
        Icon(KetIcons.Lyrics, null, Modifier.size(34.dp), tint = ket.tertiary)
        Spacer(Modifier.height(14.dp))
        KText(state.current?.title ?: "Paroles", Ket27Desk.Type.headline, translate = state.current == null)
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.Text(AppText.t("Les paroles synchronisées arrivent dans une prochaine version de Ketvoice pour Windows."),
            style = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.footnote), color = ket.secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/* ── Full-window player ── */

@Composable
fun FullPlayer(onClose: () -> Unit) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val track = state.current ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val image = rememberCover(track.coverUrl)
    val colors = rememberCoverColors(track.coverUrl)
    val ground = (colors?.first ?: Color(0xFF2C2C2E)).let { androidx.compose.ui.graphics.lerp(it, Color.Black, .45f) }
    val rise = remember { Animatable(if (Motion.reduced) 0f else 1f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, Motion.Springs.SHEET.motion()) }
    val volume by player.volume.collectAsState()
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = rise.value * size.height * .08f; alpha = 1f - rise.value }
        .background(ground).testTag("full-player")) {
        // The cover itself, blurred far, as the room's light (where Skia blurs; flat colour otherwise).
        if (image != null) Image(image, null, Modifier.fillMaxSize().blur(120.dp).graphicsLayer { alpha = .55f }, contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f)))
        KetIconButton(DeskGlyphs.ChevronDown, "Fermer", onClose, Modifier.padding(16.dp).testTag("close-player"), size = 36.dp, glyph = 20.dp, tint = Color.White)
        Row(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 60.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Artwork(track.coverUrl, 440.dp, radius = Ket27Desk.Radius.card, shadow = 40.dp)
            Spacer(Modifier.width(60.dp))
            Column(Modifier.width(420.dp)) {
                KText(track.title, Ket27Desk.Type.largeTitle, color = Color.White, maxLines = 2, translate = false)
                KText(track.artist, Ket27Desk.Type.title3, color = Color.White.copy(alpha = .7f), translate = false, weight = FontWeight.Medium)
                val format by player.format.collectAsState()
                format?.takeIf { it.lossless }?.let { f ->
                    Spacer(Modifier.height(10.dp))
                    LosslessMark(f.hiRes, Color.White.copy(alpha = .7f), large = true, tag = "lossless-badge-full")
                }
                Spacer(Modifier.height(28.dp))
                Seek(state.positionMs, state.durationMs.takeIf { it > 0 } ?: track.durationMs, compact = false, color = Color.White, secondary = Color.White.copy(alpha = .6f))
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    KetIconButton(KetIcons.Shuffle, "Lecture aléatoire", { player.setShuffle(!state.shuffle) }, active = state.shuffle, tint = Color.White.copy(alpha = .8f))
                    KetIconButton(DeskGlyphs.Previous, "Précédent", { player.previous() }, size = 52.dp, glyph = 30.dp, tint = Color.White)
                    PlayPause(state.isPlaying, { player.toggle() }, 64.dp, 40.dp, tint = Color.White)
                    KetIconButton(DeskGlyphs.Next, "Suivant", { player.next() }, size = 52.dp, glyph = 30.dp, tint = Color.White)
                    KetIconButton(if (state.repeatOne) DeskGlyphs.RepeatOne else DeskGlyphs.Repeat, "Répéter", {
                        when { state.repeatOne -> player.setRepeat(false, false); state.repeatAll -> player.setRepeat(true, false); else -> player.setRepeat(false, true) }
                    }, active = state.repeatAll || state.repeatOne, tint = Color.White.copy(alpha = .8f))
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(DeskGlyphs.Mute, null, Modifier.size(15.dp), tint = Color.White.copy(alpha = .6f))
                    ThinSlider(volume, { player.setVolume(it) }, Modifier.weight(1f).padding(horizontal = 10.dp), tag = "full-volume",
                        color = Color.White, track = Color.White.copy(alpha = .25f))
                    Icon(DeskGlyphs.Volume, null, Modifier.size(15.dp), tint = Color.White.copy(alpha = .6f))
                }
            }
        }
    }
}
