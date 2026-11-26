package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.ServerClock
import com.ketvoice.app.data.AppText
import com.ketvoice.app.remote.RemoteControl
import com.ketvoice.app.sync.DeviceState
import com.ketvoice.app.sync.RemoteSession
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.connect.ConnectPrefs
import com.ketvoice.desktop.connect.LiveSync
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.glass.glassSource
import com.ketvoice.desktop.ui.glass.rememberGlassBackdrop
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.launch

/* « Appareils connectés » and « Reprendre là où tu en étais » (live sync between the account's devices). */

/** How long ago, for a device's « vu il y a … ». */
internal fun seenAgo(at: Long, now: Long): String {
    val minutes = ((now - at).coerceAtLeast(0) / 60_000L)
    return when {
        at <= 0 -> AppText.t("Jamais vu")
        minutes < 1 -> AppText.t("À l'instant")
        minutes < 60 -> AppText.t("Vu il y a") + " $minutes min"
        minutes < 48 * 60 -> AppText.t("Vu il y a") + " ${minutes / 60} h"
        else -> AppText.t("Vu il y a") + " ${minutes / (24 * 60)} " + AppText.t("jours")
    }
}

internal fun kindIcon(kind: String): ImageVector = if (kind == "pc" || kind == "web") DeskGlyphs.Computer else DeskGlyphs.Phone

@Composable
internal fun DevicesCard(signedIn: Boolean) {
    val ket = LocalKet.current
    val devices by LiveSync.devices.collectAsState()
    val live by RemoteControl.devices.collectAsState()
    var confirm by remember { mutableStateOf<DeviceState?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(signedIn) { if (signedIn) LiveSync.refreshSoon() }
    SettingsCard {
        ToggleRow("Synchronisation en direct", ConnectPrefs.liveSync.value, "live-sync") { ConnectPrefs.set(ConnectPrefs.liveSync, "liveSync", it) }
        Note("Ce que joue ce PC (titre, position, file d'attente) part sur votre compte pendant que vous êtes connecté, pour reprendre ailleurs.")
        if (!signedIn) { Hairline(); Note("Connectez-vous pour voir vos appareils."); return@SettingsCard }
        val now = ServerClock.now()
        devices.forEach { d ->
            Hairline()
            DeviceRowView(d, online = live.any { it.id == d.deviceId }, here = d.deviceId == LiveSync.myId, now = now) { confirm = d }
        }
        if (devices.isEmpty()) { Hairline(); Note("Aucun autre appareil pour le moment.") }
    }
    confirm?.let { d ->
        ConfirmDialog(AppText.t("Déconnecter cet appareil ?"),
            AppText.t("Sa session est révoquée sur le serveur : il devra se reconnecter. Sa bibliothèque locale reste sur lui."),
            "Déconnecter", onConfirm = { scope.launch { LiveSync.signOutDevice(d.deviceId) } }, onDismiss = { confirm = null })
    }
}

@Composable
private fun DeviceRowView(d: DeviceState, online: Boolean, here: Boolean, now: Long, onSignOut: () -> Unit) {
    val ket = LocalKet.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("device:${d.deviceId}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(ket.fill), contentAlignment = Alignment.Center) {
            Icon(kindIcon(d.kind), null, Modifier.size(18.dp), tint = ket.label)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            KText(d.name + if (here) " · " + AppText.t("Cet appareil") else "", Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
            val playing = d.session?.let { s -> (if (s.playing && s.live(now)) "▶ " else "") + s.track.title + " · " + s.track.artist }
            val status = when {
                d.revoked -> AppText.t("Déconnecté")
                online || here -> AppText.t("En ligne")
                else -> seenAgo(d.lastSeen, now)
            }
            KText(listOfNotNull(status, playing).joinToString(" · "), Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
        }
        if (!here && !d.revoked) Row(horizontalArrangement = ButtonGap, verticalAlignment = Alignment.CenterVertically) {
            if (d.session != null || online) TextAction("Transférer la lecture ici", "device-take:${d.deviceId}") { LiveSync.transferHere(d) }
            if (online) TextAction("Lire sur cet appareil", "device-give:${d.deviceId}") { LiveSync.playThere(d.deviceId) }
            TextAction("Déconnecter", "device-out:${d.deviceId}", destructive = true, onClick = onSignOut)
        }
    }
}

@Composable
private fun TextAction(label: String, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val ket = LocalKet.current
    Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).testTag(tag)) {
        KText(label, Ket27Desk.Type.footnote, color = if (destructive) destructiveRed else ket.accent, weight = FontWeight.Medium)
    }
}

/**
 * « Reprendre là où tu en étais » on Home: what another device of the account played last, on clear glass over its
 * cover's colours, with « Reprendre » (here, where it is now) and « Plus tard ».
 */
@Composable
internal fun ResumeElsewhereCard() {
    val offer by LiveSync.offer.collectAsState()
    LaunchedEffect(Unit) { LiveSync.refreshSoon() }
    val session = offer ?: return
    ResumeCard(session)
}

@Composable
internal fun ResumeCard(session: RemoteSession) {
    val ket = LocalKet.current
    val picture = rememberGlassBackdrop()
    val colours = rememberCoverColors(CoverSources.track(session.track))
    Box(Modifier.then(PageEdges.sides()).padding(top = 20.dp).widthIn(max = 760.dp).testTag("resume-elsewhere")) {
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(Ket27Desk.Radius.card)).glassSource(picture)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colours?.first ?: ket.accent, (colours?.second ?: ket.card).copy(alpha = .7f)))))
        Row(Modifier.fillMaxWidth().glass(picture, Ket27Desk.Radius.card, Color.Black.copy(alpha = .18f), GlassStyle.Clear, rim = Color.White.copy(alpha = .28f))
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(CoverSources.track(session.track), 64.dp, radius = 10.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                KText(AppText.t("Reprendre là où tu en étais").uppercase(), Ket27Desk.Type.eyebrow, color = Color.White.copy(alpha = .8f), translate = false)
                KText(session.track.title, Ket27Desk.Type.headline, color = Color.White, translate = false)
                KText(session.track.artist + " · " + AppText.t("sur") + " " + session.deviceName, Ket27Desk.Type.footnote,
                    color = Color.White.copy(alpha = .82f), translate = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CapsuleButton("Reprendre", KetIcons.Play, { LiveSync.resume(session) }, filled = true, accent = ket.accent,
                    modifier = Modifier.testTag("resume-elsewhere-play"))
                KetIconButton(KetIcons.Close, "Plus tard", { LiveSync.dismiss() }, tint = Color.White, modifier = Modifier.testTag("resume-elsewhere-dismiss"))
            }
        }
    }
}
