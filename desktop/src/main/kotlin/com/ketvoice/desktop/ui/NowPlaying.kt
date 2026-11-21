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
        Column(Modifier.width(228.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KetIconButton(KetIcons.Shuffle, "Lecture aléatoire", { player.setShuffle(!state.shuffle) }, active = state.shuffle, glyph = 16.dp,
                modifier = Modifier.testTag("shuffle").symbolBounce(state.shuffle))
            KetIconButton(DeskGlyphs.Previous, "Précédent", { player.previous() }, size = 36.dp, glyph = 20.dp, modifier = Modifier.testTag("previous"))
            PlayPause(state.isPlaying, { player.toggle() }, 40.dp, 24.dp)
            KetIconButton(DeskGlyphs.Next, "Suivant", { player.next() }, size = 36.dp, glyph = 20.dp, modifier = Modifier.testTag("next"))
            KetIconButton(repeatIcon, "Répéter", {
                when { state.repeatOne -> player.setRepeat(false, false); state.repeatAll -> player.setRepeat(true, false); else -> player.setRepeat(false, true) }
            }, active = state.repeatAll || state.repeatOne, glyph = 16.dp, modifier = Modifier.testTag("repeat").symbolBounce(state.repeatOne))
        }
            KetmixSwitch()
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
                MixMark()
            }
            KText(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" — "), Ket27Desk.Type.caption, color = ket.secondary, translate = false)
            Seek(state.positionMs, state.durationMs.takeIf { it > 0 } ?: track.durationMs, compact = true)
        }
        if (loading) Spinner(Modifier.padding(end = 10.dp).size(16.dp))
    }
}

/** Ketmix on or off, under the transport: the whole little row toggles, a small switch shows it. */
@Composable
private fun KetmixSwitch() {
    val ket = LocalKet.current
    val on = AppSettings.automix.value
    val knob by androidx.compose.animation.core.animateDpAsState(if (on) 11.dp else 1.dp,
        if (Motion.reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = .8f, stiffness = 700f))
    val (source, hovered) = rememberHover()
    Tooltip(if (on) "Ketmix actif" else "Ketmix désactivé") {
        Row(Modifier.height(18.dp).clip(RoundedCornerShape(9.dp)).background(if (hovered) ket.hover else Color.Transparent)
            .hoverable(source).clickable { AppSettings.automix.value = !on }.padding(horizontal = 6.dp).testTag("ketmix"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(DeskGlyphs.Ketmix, null, Modifier.size(11.dp), tint = if (on) ket.accent else ket.secondary)
            KText("Ketmix", Ket27Desk.Type.caption2, color = if (on) ket.label else ket.secondary, translate = false, weight = FontWeight.SemiBold)
            Box(Modifier.size(22.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(if (on) ket.accent else ket.hairline).testTag("ketmix-switch")) {
                Box(Modifier.padding(start = knob, top = 1.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
            }
        }
    }
}

/** « Mix en cours » beside the title while Ketmix plays two titles together. */
@Composable
private fun MixMark() {
    val ui by com.ketvoice.desktop.ketmix.DesktopAutomix.ui.collectAsState()
    if (ui.phase != com.ketvoice.desktop.ketmix.DesktopAutomix.Phase.Mixing) return
    val ket = LocalKet.current
    Row(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(3.dp)).background(ket.accent.copy(alpha = .14f))
        .padding(horizontal = 4.dp).testTag("mix-badge"), verticalAlignment = Alignment.CenterVertically) {
        Icon(DeskGlyphs.Ketmix, null, Modifier.size(9.dp), tint = ket.accent)
        KText("Mix en cours", Ket27Desk.Type.caption2.copy(fontSize = Ket27Desk.Type.caption2.fontSize * .82f), Modifier.padding(start = 3.dp),
            color = ket.accent, weight = FontWeight.SemiBold)
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

/* ── Full-window player ── */

/** The full player's lyrics switch, kept while the app runs. */
object FullPlayerLyrics { val shown = mutableStateOf(false) }

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
    val canvas = rememberPlayerCanvas(track, state.queue, state.index)
    var lyrics by FullPlayerLyrics.shown
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = rise.value * size.height * .08f; alpha = 1f - rise.value }
        .background(ground).testTag("full-player")) {
        // The cover itself, blurred far, as the room's light (where Skia blurs; flat colour otherwise).
        if (image != null) Image(image, null, Modifier.fillMaxSize().blur(120.dp).graphicsLayer { alpha = .55f }, contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f)))
        Row(Modifier.fillMaxSize()) {
            // The artwork's place: the cover, or the Canvas placed as CanvasFit places it, its colours around it.
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (canvas != null) FittedCanvas(canvas, state.isPlaying, ground, Modifier.fillMaxSize(), shade = .18f)
                else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(start = 64.dp, end = 32.dp, top = 64.dp, bottom = 64.dp),
                    contentAlignment = Alignment.Center) {
                    Artwork(track.coverUrl, minOf(maxWidth, maxHeight, 460.dp), radius = Ket27Desk.Radius.card, shadow = 40.dp)
                }
            }
            Column((if (lyrics) Modifier.weight(1f) else Modifier.width(460.dp)).fillMaxHeight().padding(start = 28.dp, end = 56.dp, top = 56.dp, bottom = 40.dp),
                verticalArrangement = if (lyrics) Arrangement.Top else Arrangement.Center) {
                PlayerControlsBlock(track, compact = lyrics)
                if (lyrics) {
                    Spacer(Modifier.height(10.dp))
                    CascadeLyrics(track, LyricsLook.Full, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
        KetIconButton(DeskGlyphs.ChevronDown, "Fermer", onClose, Modifier.padding(16.dp).testTag("close-player"), size = 36.dp, glyph = 20.dp, tint = Color.White)
        Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            KetIconButton(KetIcons.Lyrics, "Paroles", { lyrics = !lyrics }, Modifier.testTag("full-lyrics"),
                size = 36.dp, glyph = 18.dp, tint = if (lyrics) Color.White else Color.White.copy(alpha = .6f))
        }
    }
}

/** Title, artist, the seek bar, the transport and the volume; smaller over the lyrics. */
@Composable
private fun PlayerControlsBlock(track: com.ketvoice.app.data.Track, compact: Boolean) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    KText(track.title, if (compact) Ket27Desk.Type.title2 else Ket27Desk.Type.largeTitle, color = Color.White, maxLines = if (compact) 1 else 2, translate = false)
    KText(track.artist, if (compact) Ket27Desk.Type.headline else Ket27Desk.Type.title3, color = Color.White.copy(alpha = .7f), translate = false, weight = FontWeight.Medium)
    val format by player.format.collectAsState()
    format?.takeIf { it.lossless && !compact }?.let { f ->
        Spacer(Modifier.height(10.dp))
        LosslessMark(f.hiRes, Color.White.copy(alpha = .7f), large = true, tag = "lossless-badge-full")
    }
    Spacer(Modifier.height(if (compact) 14.dp else 28.dp))
    Seek(state.positionMs, state.durationMs.takeIf { it > 0 } ?: track.durationMs, compact = compact, color = Color.White, secondary = Color.White.copy(alpha = .6f))
    Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
    val big = if (compact) 40.dp else 52.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        KetIconButton(KetIcons.Shuffle, "Lecture aléatoire", { player.setShuffle(!state.shuffle) }, active = state.shuffle, tint = Color.White.copy(alpha = .8f))
        KetIconButton(DeskGlyphs.Previous, "Précédent", { player.previous() }, size = big, glyph = big * .58f, tint = Color.White)
        PlayPause(state.isPlaying, { player.toggle() }, if (compact) 50.dp else 64.dp, if (compact) 30.dp else 40.dp, tint = Color.White)
        KetIconButton(DeskGlyphs.Next, "Suivant", { player.next() }, size = big, glyph = big * .58f, tint = Color.White)
        KetIconButton(if (state.repeatOne) DeskGlyphs.RepeatOne else DeskGlyphs.Repeat, "Répéter", {
            when { state.repeatOne -> player.setRepeat(false, false); state.repeatAll -> player.setRepeat(true, false); else -> player.setRepeat(false, true) }
        }, active = state.repeatAll || state.repeatOne, tint = Color.White.copy(alpha = .8f))
    }
    if (compact) return
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(DeskGlyphs.Mute, null, Modifier.size(15.dp), tint = Color.White.copy(alpha = .6f))
        ThinSlider(volume, { player.setVolume(it) }, Modifier.weight(1f).padding(horizontal = 10.dp), tag = "full-volume",
            color = Color.White, track = Color.White.copy(alpha = .25f))
        Icon(DeskGlyphs.Volume, null, Modifier.size(15.dp), tint = Color.White.copy(alpha = .6f))
    }
}
