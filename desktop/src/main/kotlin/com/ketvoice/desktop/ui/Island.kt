package com.ketvoice.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.glass.GlassBackdrop
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.delay

/**
 * The player as a floating glass island at the bottom centre. At rest: the cover, the title, the transport and the
 * buttons of Settings › Disposition; under the pointer it grows on a spring and shows the seek bar, shuffle, repeat,
 * the volume and Ketmix. A click on the cover or the title unfolds it into the full player (their shared parts travel).
 */
@Composable
internal fun Island(backdrop: GlassBackdrop?, room: Dp, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val ket = LocalKet.current
    val shell = LocalShell.current
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val (source, hovered) = rememberHover()
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, dragging) {
        if (hovered || dragging) shell.islandOpen = true
        else { delay(IslandMotion.LINGER_MS); shell.islandOpen = false }
    }
    val open = shell.islandOpen && state.current != null
    val spec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring<Dp>(dampingRatio = .74f, stiffness = 420f)
    val width by animateDpAsState(ShellMetrics.island(room, open), spec)
    val height by animateDpAsState(if (open) ShellMetrics.ISLAND_OPEN_HEIGHT else ShellMetrics.ISLAND_HEIGHT, spec)
    val radius = if (open) 30.dp else ShellMetrics.ISLAND_HEIGHT / 2
    val scope = LocalPlayerScope.current
    Box(modifier.width(width).height(height).onGloballyPositioned { shell.islandBounds = it.boundsInRoot() }
        .shared("np:surface", scope).glassShadow(radius, 22.dp)
        .glass(backdrop, radius, ket.glassTint, GlassStyle.Island, rim = ket.glassRim)
        .hoverable(source).testTag("bar")) {
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            // The second row opens above the first, so what is under the pointer stays where it was.
            AnimatedVisibility(open, enter = fadeIn(Motion.Springs.SMOOTH.motion()) + expandVertically(Motion.Springs.SMOOTH.motion(), expandFrom = Alignment.Bottom),
                exit = fadeOut(Motion.Springs.DISMISS.motion()) + shrinkVertically(Motion.Springs.DISMISS.motion(), shrinkTowards = Alignment.Bottom)) {
                IslandSecondRow(onDrag = { dragging = it })
            }
            IslandTop(state.current, onOpenPlayer, width)
        }
        if (!open && state.current != null) IslandProgress(state.positionMs, state.durationMs.takeIf { it > 0 } ?: state.current!!.durationMs, Modifier.align(Alignment.BottomCenter))
    }
}

/** How many of the island's right-hand buttons fit its width; the queue always stays (it opens the panel). */
internal object IslandFit {
    /** Room for every button and the compact player's. */
    val FULL = 560.dp

    fun buttons(ids: List<String>, width: Dp): List<String> {
        val room = ((width - 300.dp) / 32.dp).toInt().coerceAtLeast(1)
        if (ids.size <= room) return ids
        val keep = ids.take(room).toMutableList()
        if ("queue" in ids && "queue" !in keep) keep[keep.lastIndex] = "queue"
        return keep
    }
}

internal object IslandMotion {
    /** How long the island stays open after the pointer left it. */
    const val LINGER_MS = 420L
}

/** Cover, title and artist (the « LCD » of 1.x), the transport, the buttons. */
@Composable
private fun IslandTop(track: Track?, onOpenPlayer: () -> Unit, width: Dp) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val loading by player.loading.collectAsState()
    val format by player.format.collectAsState()
    val actions = LocalTrackActions.current
    val menu = LocalMenu.current
    val scope = LocalPlayerScope.current
    Row(Modifier.fillMaxWidth().height(ShellMetrics.ISLAND_HEIGHT).padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clickable(enabled = track != null, onClick = onOpenPlayer).testTag("lcd-artwork")) {
            Artwork(track?.coverUrl, 48.dp, Modifier.shared("np:cover", scope), radius = 12.dp)
        }
        Spacer(Modifier.width(12.dp))
        // The « LCD » of 1.x (its tag wraps the clickable part, whose words merge into it).
        Box(Modifier.weight(1f).testTag("lcd")) {
        Column(Modifier.fillMaxWidth().then(if (track != null) Modifier.contextMenu(menu) { actions.menu(track) }.tapToOpen(onOpenPlayer) else Modifier)) {
            if (track == null) { KText("Aucune lecture en cours", Ket27Desk.Type.subheadline, color = ket.secondary); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically) {
                KText(track.title, Ket27Desk.Type.subheadline, Modifier.weight(1f, fill = false).shared("np:title", scope, text = true),
                    weight = FontWeight.SemiBold, translate = false)
                if (format?.lossless == true) LosslessMark(format!!.hiRes)
                MixMark()
                if (loading) Spinner(Modifier.padding(start = 6.dp).size(14.dp))
            }
            KText(track.artist, Ket27Desk.Type.footnote, Modifier.shared("np:artist", scope, text = true), color = ket.secondary, translate = false)
        }
        }
        Spacer(Modifier.width(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            KetIconButton(DeskGlyphs.Previous, "Précédent", { player.previous() }, Modifier.shared("np:prev", scope).magnetic().testTag("previous"),
                size = 36.dp, glyph = 19.dp, enabled = track != null)
            Box(Modifier.shared("np:play", scope).magnetic()) { PlayPause(state.isPlaying, { player.toggle() }, 44.dp, 24.dp) }
            KetIconButton(DeskGlyphs.Next, "Suivant", { player.next() }, Modifier.shared("np:next", scope).magnetic().testTag("next"),
                size = 36.dp, glyph = 19.dp, enabled = track != null)
        }
        Spacer(Modifier.width(10.dp))
        // Settings › Disposition: the buttons in the listener's order (`layoutPlayer`, as the phone's player row).
        Row(verticalAlignment = Alignment.CenterVertically) {
            IslandFit.buttons(DeskLayout.barButtons(), width).forEach { BarButton(it, track) }
            if (width >= IslandFit.FULL) MiniPlayerButton(ket.label.copy(alpha = .86f), size = 32.dp)
        }
    }
}

/** Shuffle, the seek bar with its times, repeat; the volume and Ketmix. */
@Composable
private fun IslandSecondRow(onDrag: (Boolean) -> Unit) {
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val volume by player.volume.collectAsState()
    val track = state.current ?: return
    Row(Modifier.fillMaxWidth().height(ShellMetrics.ISLAND_OPEN_HEIGHT - ShellMetrics.ISLAND_HEIGHT).padding(start = 16.dp, end = 18.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val shufflePop = rememberPop(state.shuffle)
        KetIconButton(KetIcons.Shuffle, "Lecture aléatoire", { player.setShuffle(!state.shuffle) }, active = state.shuffle, glyph = 15.dp,
            modifier = Modifier.graphicsLayer { scaleX = shufflePop.value; scaleY = shufflePop.value }.testTag("shuffle"))
        Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Seek(state.positionMs, state.durationMs.takeIf { it > 0 } ?: track.durationMs, compact = true, onDrag = onDrag)
        }
        val repeatOn = state.repeatAll || state.repeatOne
        val repeatPop = rememberPop(repeatOn)
        KetIconButton(if (state.repeatOne) DeskGlyphs.RepeatOne else DeskGlyphs.Repeat, "Répéter", {
            when { state.repeatOne -> player.setRepeat(false, false); state.repeatAll -> player.setRepeat(true, false); else -> player.setRepeat(false, true) }
        }, active = repeatOn, glyph = 15.dp, modifier = Modifier.graphicsLayer { scaleX = repeatPop.value; scaleY = repeatPop.value }.testTag("repeat"))
        Spacer(Modifier.width(14.dp))
        KetIconButton(if (volume <= 0f) DeskGlyphs.Mute else DeskGlyphs.Volume, "Volume", { player.setVolume(if (volume > 0f) 0f else 1f) }, glyph = 16.dp)
        ThinSlider(volume, { player.setVolume(it) }, Modifier.width(84.dp), tag = "volume")
        Spacer(Modifier.width(12.dp))
        KetmixSwitch()
    }
}

/** At rest, how far the title has played: a thin line along the island's foot. */
@Composable
private fun IslandProgress(positionMs: Long, durationMs: Long, modifier: Modifier) {
    val ket = LocalKet.current
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().padding(horizontal = 34.dp).height(2.dp).drawBehind {
        drawLine(ket.label.copy(alpha = .12f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
        drawLine(ket.accent, Offset(0f, size.height / 2), Offset(size.width * fraction, size.height / 2), size.height, StrokeCap.Round)
    }.testTag("island-progress"))
}

/** One of the island's right-hand buttons ([DeskLayout.barButtons]). */
@Composable
private fun BarButton(id: String, track: Track?) {
    val shell = LocalShell.current
    when (id) {
        "lyrics" -> KetIconButton(KetIcons.Lyrics, "Paroles", { shell.toggle("lyrics") }, active = shell.panel == "lyrics", glyph = 17.dp,
            modifier = Modifier.magnetic().testTag("toggle-lyrics"))
        "queue" -> KetIconButton(DeskGlyphs.Queue, "À suivre", { shell.toggle("queue") }, active = shell.panel == "queue", glyph = 17.dp,
            modifier = Modifier.magnetic().testTag("toggle-queue"))
        "account" -> DevicesButton()
        "output" -> OutputButton()
        "like" -> LikeButton(track)
        "playlist" -> PlaylistButton(track)
        "download" -> DownloadButton(track)
    }
}

@Composable
private fun LikeButton(track: Track?) {
    val ket = LocalKet.current
    val library = LocalLibrary.current
    val liked by library.favourites.collectAsState()
    val on = track != null && liked.any { it.id == track.id }
    val pop = rememberPop(on)
    KetIconButton(if (on) com.ketvoice.app.ui.common.Ket27Glyphs.HeartFilled else com.ketvoice.app.ui.common.Ket27Glyphs.Heart,
        if (on) "Retirer des favoris" else "J'aime", { track?.let(library::toggleFavourite) }, active = on, glyph = 17.dp,
        modifier = Modifier.likeBurst(on, ket.accent).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.testTag("bar-like"))
}

@Composable
private fun PlaylistButton(track: Track?) {
    val menu = LocalMenu.current
    val actions = LocalTrackActions.current
    var at by remember { mutableStateOf(IntOffset.Zero) }
    Box(Modifier.androidxPosition { at = it }) {
        KetIconButton(KetIcons.Plus, "Ajouter à une playlist", {
            track?.let { t -> menu.open(actions.playlistChoices(t), IntOffset(at.x - 180, at.y - 8), from = IntOffset(at.x + 16, at.y)) }
        }, glyph = 17.dp, modifier = Modifier.testTag("bar-playlist"))
    }
}

@Composable
private fun DownloadButton(track: Track?) {
    val library = LocalLibrary.current
    val downloads by library.downloads.collectAsState()
    val done = track != null && downloads.any { it.track.id == track.id && it.done }
    KetIconButton(if (done) KetIcons.Check else KetIcons.Download, if (done) "Téléchargé" else "Télécharger",
        { if (track != null && downloads.none { it.track.id == track.id }) library.download(track) }, glyph = 17.dp,
        modifier = Modifier.testTag("bar-download"))
}

/** « Sortie audio »: Windows' playback devices through Java Sound; a pick moves the music there at once and is remembered. */
@Composable
private fun OutputButton() {
    val menu = LocalMenu.current
    var at by remember { mutableStateOf(IntOffset.Zero) }
    val chosen by com.ketvoice.desktop.audio.DesktopOutput.chosen.collectAsState()
    Box(Modifier.androidxPosition { at = it }) {
        KetIconButton(DeskGlyphs.Output, "Sortie audio", {
            val devices = com.ketvoice.desktop.audio.DesktopOutput.devices.value
            menu.open(HeadphoneEqLogic.outputItems(devices, chosen, com.ketvoice.desktop.audio.DesktopOutput::choose), IntOffset(at.x - 180, at.y - 8),
                from = IntOffset(at.x + 16, at.y))
        }, glyph = 17.dp, active = chosen != null, modifier = Modifier.testTag("output"))
    }
}

/** Ketmix on or off: the whole little capsule toggles, a small switch shows it; a right click opens Ketmix Studio. */
@Composable
internal fun KetmixSwitch() {
    val ket = LocalKet.current
    val on = AppSettings.automix.value
    val knob by animateDpAsState(if (on) 11.dp else 1.dp,
        if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = .6f, stiffness = 700f))
    val (source, hovered) = rememberHover()
    val menu = LocalMenu.current
    Tooltip(if (on) "Ketmix actif" else "Ketmix désactivé") {
        Row(Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(if (hovered) ket.hover else ket.fill.copy(alpha = ket.fill.alpha * .6f))
            .contextMenu(menu) { listOf(MenuItem("Ketmix Studio", DeskGlyphs.Ketmix, "open-studio", translate = false) { com.ketvoice.desktop.ketmix.KetmixStudioDesk.openNext() }) }
            .hoverable(source).clickable { AppSettings.automix.value = !on }.padding(horizontal = 8.dp).testTag("ketmix"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(DeskGlyphs.Ketmix, null, Modifier.size(12.dp), tint = if (on) ket.accent else ket.secondary)
            KText("Ketmix", Ket27Desk.Type.caption2, color = if (on) ket.label else ket.secondary, translate = false, weight = FontWeight.SemiBold)
            Box(Modifier.size(22.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(if (on) ket.accent else ket.hairline).testTag("ketmix-switch")) {
                Box(Modifier.padding(start = knob, top = 1.dp).size(10.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

/** « Mix en cours » beside the title while Ketmix plays two titles together. */
@Composable
internal fun MixMark() {
    val ui by com.ketvoice.desktop.ketmix.DesktopAutomix.ui.collectAsState()
    if (ui.phase != com.ketvoice.desktop.ketmix.DesktopAutomix.Phase.Mixing) return
    val ket = LocalKet.current
    Row(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(5.dp)).background(ket.accent.copy(alpha = .16f))
        .padding(horizontal = 5.dp, vertical = 1.dp).testTag("mix-badge"), verticalAlignment = Alignment.CenterVertically) {
        Icon(DeskGlyphs.Ketmix, null, Modifier.size(9.dp), tint = ket.accent)
        KText("Mix en cours", Ket27Desk.Type.caption2.copy(fontSize = Ket27Desk.Type.caption2.fontSize * .82f), Modifier.padding(start = 3.dp),
            color = ket.accent, weight = FontWeight.SemiBold)
    }
}

/** A tap that opens the full player, without merging the words below into one node (their badges keep their tags). */
private fun Modifier.tapToOpen(onOpen: () -> Unit): Modifier = composed {
    val open by androidx.compose.runtime.rememberUpdatedState(onOpen)
    pointerInput(Unit) { detectTapGestures(onTap = { open() }) }
}
