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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectTapGestures
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.glass.glassSource
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

@Composable
fun PlayPause(playing: Boolean, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp, glyph: androidx.compose.ui.unit.Dp, tint: Color = Color.Unspecified,
              filled: Boolean = false) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    val click by androidx.compose.runtime.rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    // A press sinks the button; the release bounces it back a little past its size (the bounce of a click).
    val scale by animateFloatAsState(when { pressed -> .86f; hovered && !Motion.reduced -> 1.06f; else -> 1f }, Motion.pressSpring(pressed))
    val ink = if (tint == Color.Unspecified) ket.label else tint
    Tooltip(if (playing) "Pause" else "Lecture") {
        Box(Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(androidx.compose.foundation.shape.CircleShape)
            .background(when { filled -> ink.copy(alpha = if (hovered) .22f else .14f); hovered -> ket.hover; else -> Color.Transparent })
            .hoverable(source)
            .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { click() }) }
            .keyboardControl(source, androidx.compose.foundation.shape.CircleShape) { click() }
            .testTag("play-pause"), contentAlignment = Alignment.Center) {
            ReplaceSymbol(if (playing) KetIcons.Pause else KetIcons.Play, AppText.t(if (playing) "Pause" else "Lecture"), ink, Modifier.size(glyph))
        }
    }
}

@Composable
internal fun LosslessMark(hiRes: Boolean, tint: Color = LocalKet.current.secondary, large: Boolean = false, tag: String = "lossless-badge") {
    val style = if (large) Ket27Desk.Type.caption else Ket27Desk.Type.caption2.copy(fontSize = Ket27Desk.Type.caption2.fontSize * .82f)
    Box(Modifier.padding(start = if (large) 0.dp else 6.dp).border(.8.dp, tint, RoundedCornerShape(if (large) 4.dp else 3.dp))
        .padding(horizontal = if (large) 5.dp else 3.dp, vertical = if (large) 1.dp else 0.dp).testTag(tag)) {
        KText(if (hiRes) "Hi-Res" else "Lossless", style, color = tint, translate = false, weight = FontWeight.SemiBold)
    }
}

/** Position, a thin bar, remaining time. [onDrag] hears when a scrub starts and ends (the island stays open meanwhile). */
@Composable
fun Seek(positionMs: Long, durationMs: Long, compact: Boolean, color: Color = Color.Unspecified, secondary: Color = Color.Unspecified,
         onDrag: (Boolean) -> Unit = {}) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownPos = scrub?.let { (it * durationMs).toLong() } ?: positionMs
    val c2 = if (secondary == Color.Unspecified) ket.secondary else secondary
    val style = (if (compact) Ket27Desk.Type.caption2.copy(fontWeight = FontWeight.Medium) else Ket27Desk.Type.caption)
        .copy(fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KText(formatTime(shownPos), style, Modifier.width(if (compact) 36.dp else 44.dp), color = c2, translate = false)
        ThinSlider(fraction, { if (scrub == null) onDrag(true); scrub = it }, Modifier.weight(1f),
            onDone = { player.seekTo((it * durationMs).toLong()); scrub = null; onDrag(false) }, tag = "seek",
            color = color, track = if (color == Color.Unspecified) Color.Unspecified else color.copy(alpha = .25f))
        KText("-" + formatTime((durationMs - shownPos).coerceAtLeast(0)), style, Modifier.width(if (compact) 40.dp else 48.dp).padding(start = 6.dp),
            color = c2, translate = false)
    }
}

internal fun Modifier.androidxPosition(onPos: (IntOffset) -> Unit) = onGloballyPositioned {
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
    val colors = rememberCoverColors(track.coverUrl)
    val ground = (colors?.first ?: Color(0xFF2C2C2E)).let { androidx.compose.ui.graphics.lerp(it, Color.Black, .45f) }
    val canvas = rememberPlayerCanvas(track, state.queue, state.index)
    var lyrics by FullPlayerLyrics.shown
    val actions = LocalTrackActions.current
    val backdrop = com.ketvoice.desktop.ui.glass.rememberGlassBackdrop()
    val scope = LocalPlayerScope.current
    Box(Modifier.fillMaxSize().unfold().testTag("full-player")) {
        // The cover's colours turning slowly into each other (the phone's ArtworkFlow), under a veil for the white text.
        Box(Modifier.fillMaxSize().background(ground).glassSource(backdrop)) {
            CoverFlowBackground(track.coverUrl, state.isPlaying)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .26f)))
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = .10f), Color.Transparent, Color.Black.copy(alpha = .30f)))))
        }
        Row(Modifier.fillMaxSize()) {
            // The artwork's place: the cover, or the Canvas edge to edge at the height of its format (8.3).
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (canvas != null) PlayerCanvasByFormat(canvas, state.isPlaying, Modifier.fillMaxSize())
                else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(start = 72.dp, end = 40.dp, top = 72.dp, bottom = 72.dp),
                    contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight, 520.dp)
                    Artwork(track.coverUrl, side, Modifier.shared("np:cover", scope), radius = Ket27Desk.Radius.card, shadow = 48.dp)
                }
            }
            Column((if (lyrics) Modifier.weight(1f) else Modifier.width(480.dp)).fillMaxHeight().padding(start = 28.dp, end = 64.dp, top = 64.dp, bottom = 48.dp),
                verticalArrangement = if (lyrics) Arrangement.Top else Arrangement.Center) {
                PlayerControlsBlock(track, compact = lyrics, backdrop = backdrop)
                // What other features show here (FullPlayerSlots).
                FullPlayerSlots.all.forEach { androidx.compose.runtime.key(it.id) { it.content(backdrop, lyrics) } }
                if (lyrics) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        CascadeLyrics(track, LyricsLook.Full, Modifier.weight(1f).fillMaxHeight())
                        Box(Modifier.fillMaxHeight().padding(start = 12.dp), contentAlignment = Alignment.Center) { DeskVoiceSlider(track, onDark = true) }
                    }
                }
            }
        }
        KetIconButton(DeskGlyphs.ChevronDown, "Fermer", onClose, Modifier.padding(18.dp).testTag("close-player"), size = 40.dp, glyph = 20.dp, tint = Color.White)
        Row(Modifier.align(Alignment.TopEnd).padding(18.dp).glass(backdrop, 22.dp, Color.White.copy(alpha = .06f),
            com.ketvoice.desktop.ui.glass.GlassStyle.Drop, rim = Color.White.copy(alpha = .28f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MoreButton(Color.White.copy(alpha = .75f)) { actions.menu(track) }
            MiniPlayerButton(Color.White.copy(alpha = .75f))
            KetIconButton(DeskGlyphs.EnterFullScreen, "Plein écran", { PlayerFullScreen.on.value = true }, Modifier.testTag("enter-fullscreen"),
                size = 36.dp, glyph = 18.dp, tint = Color.White.copy(alpha = .75f))
            KetIconButton(KetIcons.Lyrics, "Paroles", { lyrics = !lyrics }, Modifier.testTag("full-lyrics"),
                size = 36.dp, glyph = 18.dp, tint = if (lyrics) Color.White else Color.White.copy(alpha = .75f))
        }
    }
}

/**
 * The full player unfolding out of the island: its outline grows from the island's place and shape to the whole
 * window as the transition runs, and folds back into it on the way out (the shared cover, title and buttons travel
 * above it). Without a transition (reduced motion, tests) it is simply there.
 */
@Composable
private fun Modifier.unfold(): Modifier {
    val scope = LocalPlayerScope.current ?: return this
    val shell = LocalShell.current
    val progress = scope.transition.animateFloat({ androidx.compose.animation.core.spring(dampingRatio = .88f, stiffness = 300f) }, label = "unfold") {
        if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f
    }
    val path = remember { androidx.compose.ui.graphics.Path() }
    return drawWithContent {
        val p = progress.value
        val from = shell.islandBounds
        if (p >= .999f || from.isEmpty || Motion.reduced) { drawContent(); return@drawWithContent }
        val rect = Unfold.rect(from, androidx.compose.ui.geometry.Rect(Offset.Zero, size), p)
        val r = Unfold.radius(from.height / 2f, p)
        path.reset()
        path.addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(r)))
        clipPath(path) { this@drawWithContent.drawContent() }
    }
}

/** The arithmetic of [unfold]. */
internal object Unfold {
    fun rect(from: androidx.compose.ui.geometry.Rect, to: androidx.compose.ui.geometry.Rect, p: Float) = androidx.compose.ui.geometry.Rect(
        androidx.compose.ui.util.lerp(from.left, to.left, p), androidx.compose.ui.util.lerp(from.top, to.top, p),
        androidx.compose.ui.util.lerp(from.right, to.right, p), androidx.compose.ui.util.lerp(from.bottom, to.bottom, p))

    /** The capsule's corners at the start, square at the end. */
    fun radius(start: Float, p: Float) = start * (1f - p).coerceIn(0f, 1f)
}

/** Title, artist, the seek bar, the transport on a glass capsule and the volume; smaller over the lyrics. */
@Composable
internal fun PlayerControlsBlock(track: com.ketvoice.app.data.Track, compact: Boolean, backdrop: com.ketvoice.desktop.ui.glass.GlassBackdrop? = null) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    val scope = LocalPlayerScope.current
    KText(track.title, if (compact) Ket27Desk.Type.title1 else Ket27Desk.Type.display, Modifier.shared("np:title", scope, text = true),
        color = Color.White, maxLines = if (compact) 1 else 2, translate = false)
    Spacer(Modifier.height(4.dp))
    KText(track.artist, if (compact) Ket27Desk.Type.headline else Ket27Desk.Type.title3, Modifier.shared("np:artist", scope, text = true),
        color = Color.White.copy(alpha = .72f), translate = false, weight = FontWeight.Medium)
    val format by player.format.collectAsState()
    format?.takeIf { it.lossless && !compact }?.let { f ->
        Spacer(Modifier.height(12.dp))
        LosslessMark(f.hiRes, Color.White.copy(alpha = .7f), large = true, tag = "lossless-badge-full")
    }
    Spacer(Modifier.height(if (compact) 14.dp else 32.dp))
    Seek(state.positionMs, state.durationMs.takeIf { it > 0 } ?: track.durationMs, compact = compact, color = Color.White, secondary = Color.White.copy(alpha = .6f))
    Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
    val big = if (compact) 40.dp else 52.dp
    Row(Modifier.fillMaxWidth().glass(backdrop, if (compact) 34.dp else 42.dp, Color.White.copy(alpha = .07f),
        com.ketvoice.desktop.ui.glass.GlassStyle.Island, rim = Color.White.copy(alpha = .26f)).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val shufflePop = rememberPop(state.shuffle)
        KetIconButton(KetIcons.Shuffle, "Lecture aléatoire", { player.setShuffle(!state.shuffle) }, active = state.shuffle, tint = Color.White.copy(alpha = .8f),
            modifier = Modifier.graphicsLayer { scaleX = shufflePop.value; scaleY = shufflePop.value })
        KetIconButton(DeskGlyphs.Previous, "Précédent", { player.previous() }, Modifier.shared("np:prev", scope).magnetic(), size = big, glyph = big * .58f, tint = Color.White)
        Box(Modifier.shared("np:play", scope).magnetic()) {
            PlayPause(state.isPlaying, { player.toggle() }, if (compact) 52.dp else 68.dp, if (compact) 30.dp else 40.dp, tint = Color.White, filled = true)
        }
        KetIconButton(DeskGlyphs.Next, "Suivant", { player.next() }, Modifier.shared("np:next", scope).magnetic(), size = big, glyph = big * .58f, tint = Color.White)
        val repeatOn = state.repeatAll || state.repeatOne
        val repeatPop = rememberPop(repeatOn)
        KetIconButton(if (state.repeatOne) DeskGlyphs.RepeatOne else DeskGlyphs.Repeat, "Répéter", {
            when { state.repeatOne -> player.setRepeat(false, false); state.repeatAll -> player.setRepeat(true, false); else -> player.setRepeat(false, true) }
        }, active = repeatOn, tint = Color.White.copy(alpha = .8f), modifier = Modifier.graphicsLayer { scaleX = repeatPop.value; scaleY = repeatPop.value })
    }
    if (compact) return
    Spacer(Modifier.height(22.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(DeskGlyphs.Mute, null, Modifier.size(15.dp), tint = Color.White.copy(alpha = .6f))
        ThinSlider(volume, { player.setVolume(it) }, Modifier.weight(1f).padding(horizontal = 10.dp), tag = "full-volume",
            color = Color.White, track = Color.White.copy(alpha = .25f))
        Icon(DeskGlyphs.Volume, null, Modifier.size(15.dp), tint = Color.White.copy(alpha = .6f))
    }
}
