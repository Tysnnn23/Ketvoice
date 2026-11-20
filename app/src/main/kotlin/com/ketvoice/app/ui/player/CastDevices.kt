package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.cast.CastFlavor
import com.ketvoice.app.cast.CastState
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.sheetInk
import kotlinx.coroutines.delay

/** The output button's glyph: the Cast sign where Cast works, filled in while a device plays. */
@Composable
internal fun castOutputIcon(): ImageVector {
    val supported by CastState.supported.collectAsState()
    val connected by CastState.connected.collectAsState()
    return when {
        connected != null -> Icons.Filled.CastConnected
        supported -> Icons.Filled.Cast
        else -> Icons.Filled.PhoneAndroid
    }
}

/**
 * The Cast devices on this Wi-Fi, in « Sortie audio » under « Ce téléphone ». Searching starts when the sheet
 * opens and stops when it closes. Nothing is shown where Cast cannot work (Quest, no Play services).
 */
@Composable
internal fun CastDevicesSection(onClose: () -> Unit) {
    val context = LocalContext.current
    val available = remember { CastFlavor.available(context) }
    if (!available) return
    DisposableEffect(Unit) {
        CastFlavor.scan(context, true)
        onDispose { CastFlavor.scan(context, false) }
    }
    val devices by CastState.devices.collectAsState()
    val connecting by CastState.connecting.collectAsState()
    val connected by CastState.connected.collectAsState()
    var searchedLong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(6_000); searchedLong = true }
    CastDevicesList(devices, connecting, connected, searchedLong,
        onSelect = { id -> CastFlavor.select(id) },
        onStop = { CastFlavor.disconnect(); onClose() })
}

/** Stateless, so the host tests can draw every case. */
@Composable
internal fun CastDevicesList(devices: List<CastState.Device>, connecting: String?, connected: String?, searchedLong: Boolean,
                             onSelect: (String) -> Unit, onStop: () -> Unit) {
    val ink = sheetInk()
    Text("Appareils Cast", color = ink.copy(alpha = .6f), style = Ket27.Type.footnote,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp))
    if (devices.isEmpty() && connected == null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!searchedLong) { ActivitySpinner(size = 16.dp, color = ink.copy(alpha = .7f)); Spacer(Modifier.width(12.dp)) }
            Text(if (searchedLong) "Aucun appareil Cast trouvé sur ce Wi-Fi" else "Recherche d'appareils Cast sur ce Wi-Fi…",
                color = ink.copy(alpha = .7f), style = Ket27.Type.subheadline)
        }
    }
    val playing = devices.firstOrNull { it.name == connected }
    devices.forEach { device ->
        val on = device == playing
        CastDeviceRow(device.name, if (on) "Diffusion en cours · toucher pour arrêter" else device.description,
            translateSub = on, on = on, busy = device.id == connecting) { if (on) onStop() else onSelect(device.id) }
    }
    // A session resumed before its device was listed again.
    if (connected != null && playing == null) CastDeviceRow(connected, "Diffusion en cours · toucher pour arrêter",
        translateSub = true, on = true, busy = false, onClick = onStop)
    if (connected != null) Text("Ketmix, le niveau de la voix et l'égaliseur ne s'appliquent pas pendant la diffusion.",
        color = ink.copy(alpha = .5f), style = Ket27.Type.footnote,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("cast-effects-note"))
}

@Composable
private fun CastDeviceRow(name: String, sub: String?, translateSub: Boolean, on: Boolean, busy: Boolean, onClick: () -> Unit) {
    val ink = sheetInk()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2C2C2E)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag("cast-device"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (on) Icons.Filled.CastConnected else Icons.Filled.Cast, null,
            tint = if (on) LocalTheme.current.accent else ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, localize = false, color = ink, style = Ket27.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.takeIf { it.isNotBlank() }?.let {
                Text(it, localize = translateSub, color = ink.copy(alpha = .6f), style = Ket27.Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> ActivitySpinner(size = 18.dp, color = ink)
                on -> Icon(Icons.Filled.Check, null, tint = LocalTheme.current.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Cast errors and losses, said once in the app's snackbar wherever the listener is. */
@Composable
internal fun CastMessages() {
    val message by CastState.message.collectAsState()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        CastState.message.value = null
        com.ketvoice.app.ui.common.TrackActionFeedback.snackbar.showSnackbar(com.ketvoice.app.data.AppText.t(text))
    }
}
