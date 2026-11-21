package com.ketvoice.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ketvoice.app.remote.RemoteControl
import com.ketvoice.app.remote.RemoteDevice
import com.ketvoice.app.remote.RemoteOp
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.delay

/** What the devices popover shows; [devices] are the account's others, [here] what this PC plays. */
data class DevicesView(val signedIn: Boolean, val online: Boolean, val devices: List<RemoteDevice>, val hereTitle: String?, val herePlaying: Boolean)

/** The bar's devices button (« Contrôle du téléphone »): accent while another device of the account plays. */
@Composable
fun DevicesButton() {
    val devices by RemoteControl.devices.collectAsState()
    val online by RemoteControl.online.collectAsState()
    val account by com.ketvoice.app.account.AccountSession.state.collectAsState()
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntOffset.Zero) }
    val remotePlays = devices.any { it.now?.playing == true && it.now?.track != null }
    Box(Modifier.onGloballyPositioned { val p = it.positionInWindow(); anchor = IntOffset(p.x.toInt() + it.size.width, p.y.toInt()) }) {
        KetIconButton(DeskGlyphs.Devices, "Contrôle du téléphone", { open = !open }, glyph = 17.dp, active = remotePlays || open,
            modifier = Modifier.testTag("devices"))
    }
    if (!open) return
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset((anchor.x - popupContentSize.width).coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)),
                    (anchor.y - popupContentSize.height - 8).coerceAtLeast(8))
        },
        onDismissRequest = { open = false },
        properties = PopupProperties(focusable = true),
    ) {
        val view = DevicesView(account.signedIn, online, devices, state.current?.let { "${it.title} · ${it.artist}" }, state.isPlaying)
        DevicesPanel(view, System::currentTimeMillis,
            onOp = { id, op -> RemoteControl.control(id, op) },
            onTake = { id -> RemoteControl.takeFrom(id); open = false },
            onSend = { id -> RemoteControl.sendTo(id); open = false })
    }
}

/** Stateless, so the shell tests can render it. The device that plays is opened at first. */
@Composable
fun DevicesPanel(view: DevicesView, clock: () -> Long, onOp: (String, RemoteOp) -> Unit, onTake: (String) -> Unit, onSend: (String) -> Unit) {
    val ket = LocalKet.current
    val grow = remember { Animatable(if (Motion.reduced) 1f else .94f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.Springs.GROW.motion()) }
    val shape = RoundedCornerShape(Ket27Desk.Radius.menu)
    Column(Modifier.graphicsLayer { scaleX = grow.value; scaleY = grow.value; transformOrigin = TransformOrigin(1f, 1f) }
        .width(340.dp).heightIn(max = 560.dp)
        .shadow(16.dp, shape, ambientColor = Color.Black.copy(.3f), spotColor = Color.Black.copy(.3f))
        .clip(shape).background(ket.raised).border(.5.dp, ket.hairline, shape)
        .verticalScroll(rememberScrollState()).padding(vertical = 10.dp).testTag("devices-panel")) {
        KText("Contrôle du téléphone", Ket27Desk.Type.headline, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        DeviceRow(DeskGlyphs.Computer, "Cet ordinateur", view.hereTitle, playing = view.herePlaying && view.hereTitle != null,
            translateName = true, onClick = null)
        when {
            !view.signedIn -> Hint("Connectez-vous à votre compte Ketvoice pour voir et contrôler votre téléphone d'ici.")
            view.devices.isEmpty() -> Hint(if (view.online) "Ouvrez Ketvoice sur votre téléphone, connecté au même compte, pour le contrôler d'ici."
                else "Connexion à vos appareils…")
            else -> {
                var opened by remember { mutableStateOf(view.devices.firstOrNull { it.now?.playing == true }?.id) }
                view.devices.forEach { d ->
                    val track = d.now?.track
                    DeviceRow(if (d.kind == "pc") DeskGlyphs.Computer else DeskGlyphs.Phone, d.name,
                        track?.let { "${it.title} · ${it.artist}" }, playing = d.now?.playing == true && track != null, translateName = false,
                        onClick = { opened = if (opened == d.id) null else d.id })
                    if (opened == d.id) DeviceControls(d, clock, { onOp(d.id, it) }, { onTake(d.id) }, { onSend(d.id) })
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    KText(text, Ket27Desk.Type.footnote, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = LocalKet.current.secondary, maxLines = 3)
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, sub: String?, playing: Boolean, translateName: Boolean, onClick: (() -> Unit)?) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp))
        .background(if (hovered && onClick != null) ket.hover else Color.Transparent).hoverable(source)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 10.dp, vertical = 8.dp).testTag("device-row"), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (playing) ket.accent else ket.label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            KText(name, Ket27Desk.Type.subheadline, weight = FontWeight.Medium, translate = translateName)
            if (sub != null) KText(sub, Ket27Desk.Type.caption, color = if (playing) ket.accent else ket.secondary, translate = false)
            else KText("Rien en lecture", Ket27Desk.Type.caption, color = ket.secondary)
        }
        if (playing) Icon(KetIcons.Check, null, Modifier.size(15.dp).testTag("device-playing"), tint = ket.accent)
    }
}

@Composable
private fun DeviceControls(device: RemoteDevice, clock: () -> Long, onOp: (RemoteOp) -> Unit, onTake: () -> Unit, onSend: () -> Unit) {
    val ket = LocalKet.current
    val now = device.now
    val track = now?.track
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp).testTag("device-controls")) {
        if (now != null && track != null) {
            var position by remember(now) { mutableLongStateOf(now.positionAt(clock())) }
            LaunchedEffect(now) { while (true) { position = now.positionAt(clock()); delay(500) } }
            val duration = now.durationMs.takeIf { it > 0 } ?: track.durationMs
            var scrub by remember { mutableStateOf<Float?>(null) }
            val fraction = scrub ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                KText(formatTime(position), Ket27Desk.Type.caption2, Modifier.width(36.dp), color = ket.secondary, translate = false)
                ThinSlider(fraction, { scrub = it }, Modifier.weight(1f), tag = "device-seek",
                    onDone = { if (duration > 0) onOp(RemoteOp.Seek((it * duration).toLong())); scrub = null })
                KText("-" + formatTime((duration - position).coerceAtLeast(0)), Ket27Desk.Type.caption2, Modifier.padding(start = 6.dp).width(40.dp),
                    color = ket.secondary, translate = false)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                KetIconButton(DeskGlyphs.Previous, "Précédent", { onOp(RemoteOp.Previous) }, size = 34.dp, glyph = 18.dp)
                PlayPause(now.playing, { onOp(RemoteOp.Toggle) }, 38.dp, 22.dp)
                KetIconButton(DeskGlyphs.Next, "Suivant", { onOp(RemoteOp.Next) }, size = 34.dp, glyph = 18.dp)
            }
            var volume by remember(now.volume) { mutableStateOf(now.volume) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(DeskGlyphs.Volume, null, Modifier.size(15.dp), tint = ket.secondary)
                Spacer(Modifier.width(8.dp))
                ThinSlider(volume, { volume = it }, Modifier.weight(1f), tag = "device-volume", onDone = { onOp(RemoteOp.Volume(it)) })
            }
            if (now.upcoming.isNotEmpty()) {
                KText("À suivre", Ket27Desk.Type.caption, Modifier.padding(top = 10.dp, bottom = 2.dp), color = ket.secondary, weight = FontWeight.SemiBold)
                now.upcoming.take(6).forEach { next -> UpNextRow(next.title, next.artist, { onOp(RemoteOp.Jump(next.index)) }, { onOp(RemoteOp.Remove(next.index)) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
            if (track != null) KetTextLink("Reprendre ici", "device-take", onTake)
            KetTextLink("Transférer la lecture", "device-send", onSend)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun UpNextRow(title: String, artist: String, onPlay: () -> Unit, onRemove: () -> Unit) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (hovered) ket.hover else Color.Transparent).hoverable(source)
        .clickable(onClick = onPlay).padding(horizontal = 6.dp, vertical = 4.dp).testTag("device-next"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            KText(title, Ket27Desk.Type.footnote, translate = false)
            KText(artist, Ket27Desk.Type.caption2, color = ket.secondary, translate = false)
        }
        if (hovered) KetIconButton(KetIcons.Close, "Retirer de la file", onRemove, size = 24.dp, glyph = 12.dp)
    }
}
