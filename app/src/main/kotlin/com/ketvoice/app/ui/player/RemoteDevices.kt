package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.remote.RemoteControl
import com.ketvoice.app.remote.RemoteDevice
import com.ketvoice.app.remote.RemoteOp
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.sheetInk
import kotlinx.coroutines.delay

internal fun remoteIcon(kind: String): ImageVector = when (kind) {
    "pc" -> Icons.Filled.Computer
    "tablet" -> Icons.Filled.Tablet
    else -> Icons.Filled.Smartphone
}

/** « Vos appareils » in « Sortie audio »: the account's other devices (the PC…), shown only while signed in. */
@Composable
internal fun RemoteDevicesSection(onClose: () -> Unit) {
    val online by RemoteControl.online.collectAsState()
    val devices by RemoteControl.devices.collectAsState()
    if (!online && devices.isEmpty()) return
    RemoteDevicesList(devices, System::currentTimeMillis,
        onOp = { id, op -> RemoteControl.control(id, op) },
        onTake = { id -> RemoteControl.takeFrom(id); onClose() },
        onSend = { id -> RemoteControl.sendTo(id); onClose() })
}

/** Stateless, so the host tests can draw it. The device that plays is opened at first. */
@Composable
internal fun RemoteDevicesList(devices: List<RemoteDevice>, clock: () -> Long, onOp: (String, RemoteOp) -> Unit,
                               onTake: (String) -> Unit, onSend: (String) -> Unit) {
    val ink = sheetInk()
    Text("Vos appareils", color = ink.copy(alpha = .6f), style = Ket27.Type.footnote,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp))
    if (devices.isEmpty()) {
        Text("Ouvrez Ketvoice sur un autre appareil connecté à votre compte pour le contrôler d'ici.",
            color = ink.copy(alpha = .7f), style = Ket27.Type.subheadline,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        return
    }
    var open by remember { mutableStateOf(devices.firstOrNull { it.now?.playing == true }?.id) }
    devices.forEach { device ->
        val expanded = device.id == open
        RemoteDeviceCard(device, expanded, clock, { open = if (expanded) null else device.id },
            { onOp(device.id, it) }, { onTake(device.id) }, { onSend(device.id) })
    }
}

@Composable
private fun RemoteDeviceCard(device: RemoteDevice, expanded: Boolean, clock: () -> Long, onToggle: () -> Unit,
                             onOp: (RemoteOp) -> Unit, onTake: () -> Unit, onSend: () -> Unit) {
    val ink = sheetInk()
    val accent = LocalTheme.current.accent
    val now = device.now
    val track = now?.track
    val playing = now?.playing == true && track != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF2C2C2E)).testTag("remote-device")) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(remoteIcon(device.kind), null, tint = if (playing) accent else ink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, localize = false, color = ink, style = Ket27.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track != null) Text("${track.title} · ${track.artist}", localize = false, color = if (playing) accent else ink.copy(alpha = .6f),
                    style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text("Rien en lecture", color = ink.copy(alpha = .6f), style = Ket27.Type.footnote)
            }
        }
        if (!expanded) return@Column
        if (track != null) {
            var position by remember { mutableLongStateOf(now.positionAt(clock())) }
            LaunchedEffect(now) { while (true) { position = now.positionAt(clock()); delay(500) } }
            val duration = now.durationMs.takeIf { it > 0 } ?: track.durationMs
            Column(Modifier.padding(horizontal = 14.dp)) {
                VolumeRail(if (duration > 0) position.toFloat() / duration else 0f,
                    Modifier.fillMaxWidth().semantics { contentDescription = com.ketvoice.app.data.AppText.t("Position") }) {
                    if (duration > 0) onOp(RemoteOp.Seek((it * duration).toLong()))
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(formatRemote(position), localize = false, color = ink.copy(alpha = .6f), style = Ket27.Type.caption2)
                    Spacer(Modifier.weight(1f))
                    Text("-" + formatRemote((duration - position).coerceAtLeast(0)), localize = false, color = ink.copy(alpha = .6f), style = Ket27.Type.caption2)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RemoteButton(Icons.Filled.SkipPrevious, "Précédent", 30.dp) { onOp(RemoteOp.Previous) }
                Spacer(Modifier.width(18.dp))
                RemoteButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Lecture", 40.dp) { onOp(RemoteOp.Toggle) }
                Spacer(Modifier.width(18.dp))
                RemoteButton(Icons.Filled.SkipNext, "Suivant", 30.dp) { onOp(RemoteOp.Next) }
            }
            Row(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VolumeUp, null, tint = ink.copy(alpha = .6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                VolumeRail(now.volume, Modifier.weight(1f).semantics { contentDescription = com.ketvoice.app.data.AppText.t("Volume") }) {
                    onOp(RemoteOp.Volume(it))
                }
            }
            if (now.upcoming.isNotEmpty()) {
                Text("À suivre", color = ink.copy(alpha = .6f), style = Ket27.Type.footnote,
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
                now.upcoming.take(5).forEach { next ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOp(RemoteOp.Jump(next.index)) }
                        .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 6.dp).testTag("remote-next"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(next.title, localize = false, color = ink, style = Ket27.Type.subheadline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(next.artist, localize = false, color = ink.copy(alpha = .6f), style = Ket27.Type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            if (track != null) KetTextButton("Reprendre ici", onTake, Modifier.testTag("remote-take"))
            KetTextButton("Transférer la lecture", onSend, Modifier.testTag("remote-send"), primary = true)
        }
    }
}

@Composable
private fun RemoteButton(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val ink = sheetInk()
    Icon(icon, com.ketvoice.app.data.AppText.t(label), tint = ink,
        modifier = Modifier.size(size + 14.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(7.dp))
}

private fun formatRemote(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
